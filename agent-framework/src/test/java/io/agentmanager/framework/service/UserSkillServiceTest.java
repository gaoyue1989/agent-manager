package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;

/**
 * UserSkillService 测试：真实 InMemoryStore + 框架 RemoteFilesystem（与生产 JdbcStore 同接口），
 * 验证命名空间/key 形态、写读回环、删除先判存在再删除、sync-from-package 复制资源文件。
 */
class UserSkillServiceTest {

    private static final String AGENT = "User Skill E2E";
    private static final String USER = "u-alice";
    private static final String PKG_SKILL_MD = "---\nname: demo-a\n---\n\npackage baseline marker\n";

    @TempDir
    Path tempDir;

    private InMemoryStore store;
    private SkillManageService skillManageService;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException {
        store = new InMemoryStore();
        Files.createDirectories(tempDir.resolve("skills"));
        skillManageService = new SkillManageService(props(tempDir));
        dataSource = mock(DataSource.class);
    }

    /** 与 SkillManageServiceTest 同款：显式构造属性（configDir = 临时目录） */
    private static AgentManagerProperties props(Path configDir) {
        return new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120, true, 0),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            configDir.toString(),
            "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*,text/plain,application/zip", 5, 15, 50, true, 7, "local", "", "", "", "", "agent-files"),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults()
        );
    }

    private UserSkillService service(String agentName) {
        return serviceWith(store, agentName);
    }

    private UserSkillService service() {
        return service(AGENT);
    }

    /** 指定 KV store 的 service（故障注入用） */
    private UserSkillService serviceWith(
            io.agentscope.harness.agent.filesystem.remote.store.BaseStore baseStore) {
        return serviceWith(baseStore, AGENT);
    }

    private UserSkillService serviceWith(
            io.agentscope.harness.agent.filesystem.remote.store.BaseStore baseStore, String agentName) {
        var ds = mock(io.agentscope.harness.agent.DistributedStore.class);
        when(ds.baseStore()).thenReturn(baseStore);
        var oafConfig = mock(OafConfig.class);
        when(oafConfig.name()).thenReturn(agentName);
        return new UserSkillService(new WorkspaceReader(ds, agentName), skillManageService, dataSource, oafConfig);
    }

    private static List<String> skillNs(String user) {
        return List.of("agents", AGENT, "users", user, "skills");
    }

    /** 在包内（L2）放置一个技能目录 */
    private void seedPackageSkill(String name, String skillMd, String scriptRel, String scriptContent)
            throws IOException {
        var dir = skillManageService.getSkillsDir().resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), skillMd, StandardCharsets.UTF_8);
        if (scriptRel != null) {
            var script = dir.resolve(scriptRel);
            Files.createDirectories(script.getParent());
            Files.writeString(script, scriptContent, StandardCharsets.UTF_8);
        }
    }

    // ========== 命名空间 / key 形态（硬约束） ==========

    @Test
    void writeSkillShouldUseAgentScopedSkillsNamespaceAndLeadingSlashKey() {
        var outcome = service().writeSkill(USER, "demo-a", "# user override");

        assertEquals("created", outcome.action());
        assertTrue(outcome.version() > 0, "写入后应返回 KV 版本号");
        assertNotNull(store.get(skillNs(USER), "/demo-a/SKILL.md"), "must be agents/{agent}/users/{uid}/skills + /{name}/SKILL.md");
        assertNull(store.get(skillNs(USER), "demo-a/SKILL.md"), "key 不带前导斜杠即 SDK 读不到");
        assertNull(store.get(List.of(USER), "/demo-a/SKILL.md"), "不得写进裸 userId 命名空间");
    }

    // ========== 写读回环 ==========

    @Test
    void writeThenReadShouldReturnUserOverrideAndBumpVersion() {
        var svc = service();

        var created = svc.writeSkill(USER, "demo-a", "v1-content");
        var read1 = svc.readSkill(USER, "demo-a", null).orElseThrow();
        assertEquals("v1-content", read1.content());
        assertEquals("user", read1.source());
        assertTrue(read1.hasUserOverride());
        assertEquals(created.version(), read1.version());
        assertEquals(List.of("SKILL.md"), read1.files());
        assertTrue(read1.userOverrideExists());

        var updated = svc.writeSkill(USER, "demo-a", "v2-content");
        assertEquals("updated", updated.action());
        assertTrue(updated.version() > created.version(), "覆盖写入版本号应自增");
        assertEquals("v2-content", svc.readSkill(USER, "demo-a", null).orElseThrow().content());
    }

    /**
     * L4 优先于包内基线：同一技能同时有 L4 覆盖与包内基线时必须返回 L4
     * （判定写反会让所有既有用例照过，故需要“两层同时存在”的输入）。
     */
    @Test
    void readSkillShouldPreferUserOverrideOverPackageBaseline() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, "scripts/hello.sh", "echo hi");
        var svc = service();
        svc.writeSkill(USER, "demo-a", "# user override wins");

        var view = svc.readSkill(USER, "demo-a", null).orElseThrow();

        assertEquals("# user override wins", view.content(), "L4 必须覆盖包内基线");
        assertEquals("user", view.source());
        assertTrue(view.hasUserOverride());
        // 个人覆盖只有 SKILL.md：请求包内才有的资源文件 → 回落包内层，但 files 与 source 同源
        var resource = svc.readSkill(USER, "demo-a", "scripts/hello.sh").orElseThrow();
        assertEquals("echo hi", resource.content());
        assertEquals("package", resource.source());
        assertFalse(resource.hasUserOverride(), "内容来自包内层 → hasUserOverride=false");
        assertEquals(0L, resource.version(), "包内层无 KV 版本号");
        assertEquals(List.of("SKILL.md", "scripts/hello.sh"), resource.files(),
            "files 必须与 source 同源（包内清单），不能给 L4 文件表");
        assertTrue(resource.userOverrideExists(), "该用户仍有个人覆盖，仅此文件来自包内");
    }

    @Test
    void writeSkillShouldRejectEmptyAndOversizedContent() {
        var svc = service();

        assertThrows(IllegalArgumentException.class, () -> svc.writeSkill(USER, "demo-a", "   "));
        assertThrows(IllegalArgumentException.class, () -> svc.writeSkill(USER, "demo-a", null));
        var oversized = "x".repeat(UserSkillService.MAX_CONTENT_BYTES + 1);
        assertThrows(UserSkillService.ContentTooLargeException.class,
            () -> svc.writeSkill(USER, "demo-a", oversized));
        // 恰好 100KB 允许
        assertEquals("created",
            svc.writeSkill(USER, "demo-a", "y".repeat(UserSkillService.MAX_CONTENT_BYTES)).action());
    }

    @Test
    void writeSkillShouldFailExplicitlyWhenStoreUnavailable() {
        // 注意：不能 mock DistributedStore.baseStore() 抛异常——WorkspaceReader 构造期就取
        // baseStore，异常会落在构造器而不是被测方法上；此处让 store 本身不可用。
        var ds = mock(io.agentscope.harness.agent.DistributedStore.class);
        when(ds.baseStore()).thenReturn(new UnavailableBaseStore());
        var svc = new UserSkillService(new WorkspaceReader(ds, AGENT), skillManageService, dataSource,
            oafConfig(AGENT));

        // 写失败必须显式抛错（不能静默返回成功）
        assertThrows(IllegalStateException.class, () -> svc.writeSkill(USER, "demo-a", "content"));
    }

    // ========== 列表 / 包内基线 ==========

    @Test
    void listSkillsShouldReportFilesBytesAndPackageBaseline() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, "scripts/hello.sh", "echo hi");
        var svc = service();
        svc.writeSkill(USER, "demo-a", "override-a");
        svc.writeSkill(USER, "demo-b", "override-b");

        var skills = svc.listSkills(USER);

        assertEquals(List.of("demo-a", "demo-b"), skills.stream().map(UserSkillService.UserSkillSummary::name).toList());
        var demoA = skills.get(0);
        assertEquals(List.of("SKILL.md"), demoA.files());
        assertTrue(demoA.bytes() > 0, "bytes 应为内容字节数");
        assertTrue(demoA.hasPackageBaseline(), "包内同名技能存在 → 删除后回落基线");
        assertFalse(skills.get(1).hasPackageBaseline(), "包内无 demo-b → 无基线");
        // 管理面写入过 → 带写侧栅栏（沙箱回写会跳过同名技能），列表必须暴露该状态
        assertTrue(demoA.adminOverride());
    }

    @Test
    void listSkillsShouldReturnEmptyForNewUser() {
        assertTrue(service().listSkills("brand-new-user").isEmpty(), "空命名空间不得抛错");
    }

    @Test
    void readSkillShouldFallBackToPackageBaseline() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, null, null);

        var view = service().readSkill(USER, "demo-a", null).orElseThrow();

        assertEquals("package", view.source());
        assertFalse(view.hasUserOverride());
        assertEquals(PKG_SKILL_MD, view.content());
        assertEquals(0L, view.version(), "基线无 KV 版本号");
        assertEquals(List.of("SKILL.md"), view.files());
        assertFalse(view.userOverrideExists(), "无个人覆盖 → false");
    }

    @Test
    void readSkillShouldReturnEmptyWhenNeitherSideExists() {
        assertTrue(service().readSkill(USER, "ghost", null).isEmpty());
    }

    @Test
    void readSkillShouldReadResourceFileFromPackageBaseline() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, "scripts/hello.sh", "echo from-package");

        var view = service().readSkill(USER, "demo-a", "scripts/hello.sh").orElseThrow();

        assertEquals("echo from-package", view.content());
        assertEquals("package", view.source());
    }

    // ========== 删除（回落语义） ==========

    @Test
    void deleteSkillShouldRemoveAllFilesThenFallBackToBaseline() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, "scripts/hello.sh", "echo hi");
        var svc = service();
        svc.syncFromPackage(USER, "demo-a"); // 个人版本 = 包内副本 + 资源文件

        // 改写成有区分度的个人版本
        svc.writeSkill(USER, "demo-a", "user override marker");

        var deleted = svc.deleteSkill(USER, "demo-a").orElseThrow();

        assertEquals(2, deleted, "SKILL.md + scripts/hello.sh 两行都删");
        assertNull(store.get(skillNs(USER), "/demo-a/SKILL.md"));
        assertNull(store.get(skillNs(USER), "/demo-a/scripts/hello.sh"));
        assertTrue(svc.listSkills(USER).isEmpty());
        // 回落包内基线
        var view = svc.readSkill(USER, "demo-a", null).orElseThrow();
        assertEquals("package", view.source());
        assertEquals(PKG_SKILL_MD, view.content());
        // 该用户已无个人覆盖 → 二次删除 404 语义
        assertTrue(svc.deleteSkill(USER, "demo-a").isEmpty());
    }

    /**
     * 删除写删除标记（tombstone）：沙箱回写（syncBack）据此跳过同名技能，
     * 否则同代容器内副本会在下次 call 结束时把覆盖写回（删除被静默还原）；
     * 重新写入/下发则清除标记。
     */
    @Test
    void deleteSkillShouldLeaveTombstoneUntilRecreated() {
        var svc = service();
        svc.writeSkill(USER, "demo-a", "# a");

        assertEquals(1, svc.deleteSkill(USER, "demo-a").orElseThrow());
        assertNotNull(store.get(skillNs(USER), "/demo-a/.deleted"), "删除后必须留删除标记");
        assertTrue(svc.listSkills(USER).isEmpty(), "只剩标记的目录不算技能");

        svc.writeSkill(USER, "demo-a", "# recreated");
        assertNull(store.get(skillNs(USER), "/demo-a/.deleted"), "重新创建必须清除删除标记");
    }

    /**
     * 写入写管理面栅栏（admin-override）：沙箱回写据此跳过同名技能——否则同代容器内旧副本
     * 会在下一次 call 结束时把管理面写入改回容器版本（DELETE 有 tombstone 防护，PUT 此前没有
     * 对称防护）。删除该技能时栅栏一并清除（此后由删除标记接管）。
     */
    @Test
    void writeSkillShouldMarkAdminOverrideAndDeleteShouldClearIt() {
        var svc = service();

        svc.writeSkill(USER, "demo-a", "# admin content");
        assertNotNull(store.get(skillNs(USER), "/demo-a/.admin-override"),
            "管理面写入后必须留写侧栅栏（回写侧据此跳过该技能）");
        assertTrue(svc.listSkills(USER).get(0).adminOverride(), "列表必须暴露栅栏状态");
        // 栅栏值是写入时间戳（供现场核对，不参与判定）
        var fence = store.get(skillNs(USER), "/demo-a/.admin-override").value().get("content");
        assertTrue(Long.parseLong(String.valueOf(fence)) > 0, "栅栏值应为毫秒时间戳: " + fence);

        assertEquals(1, svc.deleteSkill(USER, "demo-a").orElseThrow());
        assertNull(store.get(skillNs(USER), "/demo-a/.admin-override"), "删除必须清除写侧栅栏");
    }

    /** 从包内下发同样要置写侧栅栏（下发是管理面写入的另一种形态） */
    @Test
    void syncFromPackageShouldMarkAdminOverride() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, null, null);

        service().syncFromPackage(USER, "demo-a").orElseThrow();

        assertNotNull(store.get(skillNs(USER), "/demo-a/.admin-override"), "下发后必须留写侧栅栏");
    }

    /**
     * 删除标记可见性：删除后技能已不在 L4 列表（只剩元数据键），必须由 listTombstones 单独暴露，
     * 否则运维看不到「已删除但标记仍在」——该用户在同代容器内重建同名技能会被回写侧一直跳过。
     */
    @Test
    void listTombstonesShouldExposeDeletedSkillUntilRecreated() {
        var svc = service();
        svc.writeSkill(USER, "demo-a", "# a");
        svc.writeSkill(USER, "demo-b", "# b");
        assertTrue(svc.listTombstones(USER).isEmpty(), "未删除时无标记");

        svc.deleteSkill(USER, "demo-a").orElseThrow();

        var tombstones = svc.listTombstones(USER);
        assertEquals(1, tombstones.size());
        assertEquals("demo-a", tombstones.get(0).name());
        assertFalse(tombstones.get(0).deletedAt().isBlank(), "标记时间应可读（现场核对用）");
        assertEquals(1, svc.listSkills(USER).size(), "只有 demo-b 仍在 L4 列表里");

        // 管理面重新写入 → 清除标记，恢复落库
        svc.writeSkill(USER, "demo-a", "# recreated");
        assertTrue(svc.listTombstones(USER).isEmpty(), "重新写入必须清除删除标记");
    }

    // ========== sync-from-package ==========

    @Test
    void syncFromPackageShouldCopySkillMdAndResourcesVerbatim() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, "scripts/hello.sh", "echo hi");

        var outcome = service().syncFromPackage(USER, "demo-a").orElseThrow();

        assertEquals(List.of("SKILL.md", "scripts/hello.sh"), outcome.files());
        assertTrue(outcome.skipped().isEmpty());
        // SKILL.md 原样落库（不重新序列化 frontmatter）
        assertEquals(PKG_SKILL_MD, store.get(skillNs(USER), "/demo-a/SKILL.md")
            .value().get("content"));
        assertEquals("echo hi", store.get(skillNs(USER), "/demo-a/scripts/hello.sh")
            .value().get("content"));
    }

    /**
     * 以包内清单为准的全量替换：包内已不存在的旧文件必须差集清理，
     * 否则换版后个人版本 ≠ 包内目录（旧 scripts 仍被 L4 覆盖、对该用户会话可见），接口却报成功。
     */
    @Test
    void syncFromPackageShouldRemoveFilesNoLongerInPackage() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, "scripts/hello.sh", "echo hi");
        var svc = service();
        svc.syncFromPackage(USER, "demo-a");
        assertNotNull(store.get(skillNs(USER), "/demo-a/scripts/hello.sh"));

        // 包内换版：删掉 scripts/hello.sh，改为 scripts/bye.sh
        Files.delete(skillManageService.getSkillsDir().resolve("demo-a/scripts/hello.sh"));
        Files.writeString(skillManageService.getSkillsDir().resolve("demo-a/scripts/bye.sh"),
            "echo bye", StandardCharsets.UTF_8);

        var outcome = svc.syncFromPackage(USER, "demo-a").orElseThrow();

        assertEquals(List.of("SKILL.md", "scripts/bye.sh"), outcome.files());
        assertNull(store.get(skillNs(USER), "/demo-a/scripts/hello.sh"), "包内已删除的旧文件必须清理");
        assertNotNull(store.get(skillNs(USER), "/demo-a/scripts/bye.sh"));
        assertEquals(List.of("SKILL.md", "scripts/bye.sh"),
            svc.listSkills(USER).get(0).files(), "个人目录与包内目录严格一致");
    }

    /**
     * 非 UTF-8/二进制文件显式跳过（不静默替换成 U+FFFD）：KV 只存字符串，
     * 静默替换等于下发即损坏数据，且调用方看不到任何异常。
     */
    @Test
    void syncFromPackageShouldSkipNonUtf8FilesInsteadOfReplacing() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, "scripts/hello.sh", "echo hi");
        var dir = skillManageService.getSkillsDir().resolve("demo-a");
        // PNG 头（含 0x89 等非 UTF-8 字节）与 GBK 编码的中文脚本文本
        Files.write(dir.resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            (byte) 0xFF, (byte) 0xFE, 0x00});
        Files.write(dir.resolve("gbk.sh"), "echo 中文\n".getBytes(java.nio.charset.Charset.forName("GBK")));

        var outcome = service().syncFromPackage(USER, "demo-a").orElseThrow();

        assertEquals(List.of("SKILL.md", "scripts/hello.sh"), outcome.files());
        assertEquals(List.of("gbk.sh", "logo.png"), outcome.skipped(), "跳过清单必须显式返回");
        assertNull(store.get(skillNs(USER), "/demo-a/logo.png"), "二进制不得静默损坏后落库");
        assertNull(store.get(skillNs(USER), "/demo-a/gbk.sh"), "非 UTF-8 文本不得落库");

        // 主文件本身非 UTF-8 → 明确报错（不能留半份个人版本）
        var broken = skillManageService.getSkillsDir().resolve("broken-md");
        Files.createDirectories(broken);
        Files.write(broken.resolve("SKILL.md"), new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD});
        var e = assertThrows(IllegalArgumentException.class, () -> service().syncFromPackage(USER, "broken-md"));
        assertTrue(e.getMessage().contains("SKILL.md"), "应说明缺主文件: " + e.getMessage());
        assertNull(store.get(skillNs(USER), "/broken-md/SKILL.md"));
    }

    /**
     * 下发中途写失败必须回滚：不能留“有 SKILL.md 缺资源”的半份覆盖（改前是半份覆盖 + 500 无补偿）。
     * 已有旧覆盖时回滚要恢复旧内容，本次新建的键则删掉。
     */
    @Test
    void syncFromPackageShouldRollBackWhenResourceWriteFails() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, "scripts/hello.sh", "echo hi");
        // 资源文件写入一律失败（SKILL.md 正常），做出“主文件写成、资源文件写失败”的失败形态
        var failing = new KeyFailingStore("/scripts/");
        var svc = serviceWith(failing);

        assertThrows(IllegalStateException.class, () -> svc.syncFromPackage(USER, "demo-a"));

        assertNull(failing.get(skillNs(USER), "/demo-a/SKILL.md"), "本次新建的键必须回滚删掉");
        assertNull(failing.get(skillNs(USER), "/demo-a/scripts/hello.sh"));
    }

    /** 已有旧覆盖：回滚必须恢复旧内容（而不是删掉或留下半份新内容） */
    @Test
    void syncFromPackageShouldRestorePreviousOverrideWhenResourceWriteFails() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, "scripts/hello.sh", "echo hi");
        var failing = new KeyFailingStore("/scripts/");
        var svc = serviceWith(failing);
        svc.writeSkill(USER, "demo-a", "old override"); // 预置旧覆盖（SKILL.md 写入不由故障键拦截）

        assertThrows(IllegalStateException.class, () -> svc.syncFromPackage(USER, "demo-a"));

        assertEquals("old override", svc.readSkill(USER, "demo-a", null).orElseThrow().content(),
            "回滚必须恢复旧覆盖内容");
        assertNull(failing.get(skillNs(USER), "/demo-a/scripts/hello.sh"), "失败的资源文件不得残留");
    }

    /** 技能名 + 相对路径的组合长度超 KV key 上限（VARCHAR(255)）必须在入口 400，而不是落到 KV 写入 500 */
    @Test
    void syncFromPackageShouldRejectOverlongCombinedKey() throws IOException {
        var longName = "n".repeat(120);
        var longRel = "scripts/" + "x".repeat(200) + ".sh"; // 120 + 208 > 253
        var dir = skillManageService.getSkillsDir().resolve(longName);
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("SKILL.md"), PKG_SKILL_MD, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(longRel), "echo hi", StandardCharsets.UTF_8);

        var e = assertThrows(IllegalArgumentException.class,
            () -> service().syncFromPackage(USER, longName));
        assertTrue(e.getMessage().contains(String.valueOf(UserSkillService.MAX_SKILL_KEY_LENGTH)),
            "应说明组合长度超限: " + e.getMessage());
        assertFalse(UserSkillService.isValidSkillIdentity(longName, longRel));
        assertTrue(UserSkillService.isValidSkillIdentity("demo-a", "scripts/hello.sh"));
    }

    @Test
    void syncFromPackageShouldReturnEmptyWhenSourceMissing() {
        assertTrue(service().syncFromPackage(USER, "ghost").isEmpty(), "包内无此技能 → 404 语义");
    }

    @Test
    void syncFromPackageShouldRejectSkillWithoutSkillMd() throws IOException {
        var dir = skillManageService.getSkillsDir().resolve("broken");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("notes.txt"), "no skill md", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> service().syncFromPackage(USER, "broken"));
    }

    @Test
    void syncFromPackageShouldSkipMetadataFilesAndRejectOversizedFile() throws IOException {
        seedPackageSkill("demo-a", PKG_SKILL_MD, "scripts/hello.sh", "echo hi");
        // 元数据（.skill-states.json / _drafts）不参与下发
        Files.writeString(skillManageService.getSkillsDir().resolve("demo-a/.skill-states.json"),
            "{\"demo-a\":{\"enabled\":true}}", StandardCharsets.UTF_8);
        var drafts = skillManageService.getSkillsDir().resolve("demo-a/_drafts");
        Files.createDirectories(drafts);
        Files.writeString(drafts.resolve("draft.md"), "draft", StandardCharsets.UTF_8);

        var outcome = service().syncFromPackage(USER, "demo-a").orElseThrow();

        assertEquals(List.of("SKILL.md", "scripts/hello.sh"), outcome.files());
        assertNull(store.get(skillNs(USER), "/demo-a/.skill-states.json"), "元数据文件不得落 KV");

        // 单文件超限 → 413 语义（不允许半份写入）
        var big = skillManageService.getSkillsDir().resolve("demo-b");
        Files.createDirectories(big);
        Files.writeString(big.resolve("SKILL.md"), PKG_SKILL_MD, StandardCharsets.UTF_8);
        Files.writeString(big.resolve("big.txt"), "x".repeat(UserSkillService.MAX_CONTENT_BYTES + 1),
            StandardCharsets.UTF_8);
        assertThrows(UserSkillService.ContentTooLargeException.class,
            () -> service().syncFromPackage("u-big", "demo-b"));
        assertNull(store.get(skillNs("u-big"), "/demo-b/SKILL.md"), "超限必须整体失败，不留半份覆盖");
    }

    @Test
    void syncFromPackageShouldRejectTooManyFiles() throws IOException {
        var dir = skillManageService.getSkillsDir().resolve("many");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), PKG_SKILL_MD, StandardCharsets.UTF_8);
        for (var i = 0; i <= UserSkillService.MAX_SYNC_FILES; i++) {
            Files.writeString(dir.resolve("f" + i + ".txt"), "x", StandardCharsets.UTF_8);
        }

        var e = assertThrows(IllegalArgumentException.class,
            () -> service().syncFromPackage(USER, "many"));
        assertTrue(e.getMessage().contains("文件数"), "文件数超限应给出明确原因: " + e.getMessage());
    }

    // ========== 用户索引（agent_fs 聚合） ==========

    @Test
    void listUsersShouldAggregateSkillsAndFilterByAgent() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getString("namespace_path")).thenReturn(
            "agents\u001F" + AGENT + "\u001Fusers\u001F" + USER + "\u001Fskills\u001F",
            "agents\u001F" + AGENT + "\u001Fusers\u001F" + USER + "\u001Fskills\u001F",
            "agents\u001FOther Agent\u001Fusers\u001Fbob\u001Fskills\u001F");
        when(rs.getString("item_key")).thenReturn("/demo-a/SKILL.md", "/demo-b/SKILL.md", "/demo-x/SKILL.md");
        when(rs.getLong("updated_at")).thenReturn(1000L, 2000L, 3000L);

        var index = service().listUsers();
        var users = index.users();

        assertFalse(index.truncated(), "未触顶不应标截断");
        assertEquals(1, users.size(), "其他 agent 的命名空间必须被过滤");
        assertEquals(USER, users.get(0).userId());
        assertEquals(2, users.get(0).skillCount());
        // updatedAt 取最近一次写入时间：断言 epoch 毫秒数值（不依赖 JVM 默认时区，
        // 负数 UTC 偏移下 Timestamp.toString() 会渲染成前一天，按文本断言必挂）
        assertEquals(new java.sql.Timestamp(2000L).toString(), users.get(0).updatedAt());
        assertEquals(2000L, java.sql.Timestamp.valueOf(users.get(0).updatedAt()).getTime(),
            "updatedAt 必须是最近一次写入的时间戳");
        // LIKE 参数必须转义（agent 名中的 _ / % 不当通配符）
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ps).setString(org.mockito.ArgumentMatchers.eq(1), captor.capture());
        assertEquals("agents\u001F" + AGENT + "\u001Fusers\u001F%", captor.getValue());
    }

    /** 索引触顶（多取 1 个用户用于判定）必须显式标 truncated，并丢弃只取到一半的最后一个用户 */
    @Test
    void listUsersShouldMarkTruncatedAndDropPartialUserWhenLimitHit() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        // USER_INDEX_SCAN_LIMIT+1 = 5001 个用户 → 判定触顶
        var rows = 5001;
        var nexts = new Boolean[rows + 1];
        java.util.Arrays.fill(nexts, 0, rows, Boolean.TRUE);
        nexts[rows] = Boolean.FALSE;
        when(rs.next()).thenReturn(nexts[0], java.util.Arrays.copyOfRange(nexts, 1, nexts.length));
        var namespaces = new String[rows];
        var keys = new String[rows];
        for (var i = 0; i < rows; i++) {
            namespaces[i] = "agents\u001F" + AGENT + "\u001Fusers\u001Fu-" + i + "\u001Fskills\u001F";
            keys[i] = "/demo-a/SKILL.md";
        }
        when(rs.getString("namespace_path")).thenReturn(namespaces[0],
            java.util.Arrays.copyOfRange(namespaces, 1, namespaces.length));
        when(rs.getString("item_key")).thenReturn(keys[0], java.util.Arrays.copyOfRange(keys, 1, keys.length));

        var index = service().listUsers();

        assertTrue(index.truncated(), "触顶必须标 truncated（不静默返回子集）");
        assertEquals(5000, index.users().size(), "多取的那 1 个用户必须丢弃");
    }

    /**
     * 索引聚合下推到 SQL：按 namespace_path 分组取用户（上限按“用户”计，而不是把文件行数当上限），
     * 外层再按（namespace_path, item_key）稳定排序——否则文件多的用户会把后面的用户挤出索引。
     */
    @Test
    void listUsersShouldPushNamespaceLimitAndOrderingIntoSql() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        service().listUsers();

        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(conn).prepareStatement(sqlCaptor.capture());
        var sql = sqlCaptor.getValue();
        assertTrue(sql.contains("GROUP BY namespace_path"), "上限必须按用户（namespace_path）聚合: " + sql);
        assertTrue(sql.contains("ORDER BY namespace_path"), "取子集必须稳定（ORDER BY）: " + sql);
        assertTrue(sql.contains("ORDER BY f.namespace_path, f.item_key"), "外层需稳定排序: " + sql);
        var intCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(ps, org.mockito.Mockito.atLeastOnce())
            .setInt(org.mockito.ArgumentMatchers.anyInt(), intCaptor.capture());
        assertTrue(intCaptor.getAllValues().contains(5001), "用户数上限多取 1 个用于判定触顶");
    }

    @Test
    void listUsersShouldEscapeLikeWildcardsInAgentName() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        service("agent_1%2").listUsers();

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ps).setString(org.mockito.ArgumentMatchers.eq(1), captor.capture());
        assertEquals("agents\u001Fagent\\_1\\%2\u001Fusers\u001F%", captor.getValue(),
            "LIKE 通配符必须转义（ESCAPE '\\\\'）");
    }

    @Test
    void listUsersShouldReturnEmptyWhenAgentNameMissing() {
        assertTrue(service("  ").listUsers().users().isEmpty(), "无 agent 名时命名空间不成立");
    }

    @Test
    void listUsersShouldThrowOnQueryFailure() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("db down"));

        // 不降级成「200 + 空列表」：那会让 DB 抖动看起来像「没有任何用户有个人技能」
        var e = assertThrows(IllegalStateException.class, () -> service().listUsers());
        assertTrue(e.getMessage().contains("db down"), "异常应带上原始原因: " + e.getMessage());
    }

    // ========== 输入校验 ==========

    @Test
    void validationShouldRejectPathTraversal() {
        assertTrue(UserSkillService.isValidSkillName("demo-a"));
        assertFalse(UserSkillService.isValidSkillName("../etc"));
        assertFalse(UserSkillService.isValidSkillName("a/b"));
        assertFalse(UserSkillService.isValidSkillName("a\\b"));
        assertFalse(UserSkillService.isValidSkillName(".hidden"));
        assertFalse(UserSkillService.isValidSkillName("_drafts"), "元数据目录约定：_ 开头不算技能");
        assertFalse(UserSkillService.isValidSkillName("  "));

        assertTrue(UserSkillService.isValidUserId("e2e-s5-1788759354"));
        assertTrue(UserSkillService.isValidUserId("_default"), "框架匿名用户标识必须可用");
        assertFalse(UserSkillService.isValidUserId("../u"));
        assertFalse(UserSkillService.isValidUserId("u/../x"));
        assertFalse(UserSkillService.isValidUserId(".hidden"));
        assertFalse(UserSkillService.isValidUserId("x".repeat(65)));

        assertTrue(UserSkillService.isValidSkillFilePath("scripts/hello.sh"));
        assertFalse(UserSkillService.isValidSkillFilePath("../escape.sh"));
        assertFalse(UserSkillService.isValidSkillFilePath("/abs/path"));
        assertFalse(UserSkillService.isValidSkillFilePath("scripts/../../etc/passwd"));
        assertFalse(UserSkillService.isValidSkillFilePath("_drafts/x.md"),
            "元数据路径写进去也会被列表过滤，必须拒绝");
    }

    /**
     * 控制字符与“清洗漂移”的标识必须 400：前者会把 0x1F 带进 KV 命名空间段（JdbcStore 抛异常 →
     * 500），后者让 API 写入的命名空间与框架实际使用的 userId 不一致（写成功但 SDK 读不到）。
     */
    @Test
    void validationShouldRejectNamesThatWouldDriftFromKvNamespace() {
        assertFalse(UserSkillService.isValidUserId("u\u001Fx"), "0x1F 是 KV 命名空间分隔符");
        assertFalse(UserSkillService.isValidUserId("u\n1"));
        assertFalse(UserSkillService.isValidUserId("u:v"), "sanitize 会把 : 清洗成 _ → 命名空间漂移");
        assertFalse(UserSkillService.isValidUserId("u  v"), "连续空格会被 sanitize 折叠 → 命名空间漂移");
        assertFalse(UserSkillService.isValidSkillName("demo\u001Fa"));

        assertThrows(IllegalArgumentException.class,
            () -> service().writeSkill("u:v", "demo-a", "content"), "非法 userId 必须在服务层拦下");
        assertThrows(IllegalArgumentException.class,
            () -> service().writeSkill(USER, "../escape", "content"));
        assertThrows(IllegalArgumentException.class, () -> service().deleteSkill("u:v", "demo-a"));
        assertThrows(IllegalArgumentException.class, () -> service().syncFromPackage("u:v", "demo-a"));
    }

    private static OafConfig oafConfig(String name) {
        var cfg = mock(OafConfig.class);
        when(cfg.name()).thenReturn(name);
        return cfg;
    }

    /**
     * KV 后端整体不可用（DB 宕机）：读/写/搜/删一律抛异常，用于验证失败必须显式上报。
     *
     * <p>语义要求：readSkill/deleteSkill 在存储不可用时抛 IllegalStateException（控制器映射 500，
     * 不能把“读/删失败”静默报成“无此技能”404）；listUsers（SQL 聚合）同样抛 IllegalStateException
     * （不能把“索引不可用”报成“没有任何用户有个人技能”）；listSkills 在 KV 列举失败时降级为空列表
     * （与既有 /skills 视图一致：命名空间建不起来等价于该用户无 L4）。
     */
    @Test
    void readAndDeleteShouldFailExplicitlyWhenStoreUnavailable() {
        var ds = mock(io.agentscope.harness.agent.DistributedStore.class);
        when(ds.baseStore()).thenReturn(new UnavailableBaseStore());
        var svc = new UserSkillService(new WorkspaceReader(ds, AGENT), skillManageService, dataSource,
            oafConfig(AGENT));

        // 写：已由 writeSkillShouldFailExplicitlyWhenStoreUnavailable 覆盖
        assertThrows(IllegalStateException.class,
            () -> svc.readSkill(USER, "demo-a", null), "读失败不得当成“不存在”返回 404");
        assertThrows(IllegalStateException.class,
            () -> svc.deleteSkill(USER, "demo-a"), "删除失败不得当成“无覆盖”返回 404");
        // 用户索引（SQL）失败同样必须显式上报（索引查询不可用 ≠ 没有用户有覆盖）
        assertThrows(IllegalStateException.class, svc::listUsers, "索引失败不得降级成空列表");
        // 技能列表保持降级（命名空间不可用即无 L4），语义用测试固定下来
        assertTrue(svc.listSkills(USER).isEmpty(), "列表降级为空（与既有 /skills 视图一致）");
    }

    /**
     * 包内基线判定必须与同类其它判定同口径（目录内要有 SKILL.md）：
     * 只判目录存在会把“无 SKILL.md 的目录”标成有基线，删除后回落不到任何技能。
     */
    @Test
    void hasPackageBaselineShouldRequireSkillMd() throws IOException {
        var svc = service();
        var dir = skillManageService.getSkillsDir().resolve("no-md");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("notes.txt"), "no skill md", StandardCharsets.UTF_8);

        assertFalse(svc.hasPackageBaseline("no-md"), "无 SKILL.md 的目录不算基线");
        seedPackageSkill("demo-a", PKG_SKILL_MD, null, null);
        assertTrue(svc.hasPackageBaseline("demo-a"));
        assertFalse(svc.hasPackageBaseline("never-existed"));
        assertFalse(svc.hasPackageBaseline("../escape"), "非法技能名一律 false");
    }

    /** 指定 KV key 片段上的写入一律失败：用来制造“主文件写成、资源文件写失败”的失败形态 */
    private static final class KeyFailingStore
            implements io.agentscope.harness.agent.filesystem.remote.store.BaseStore {
        private final InMemoryStore delegate = new InMemoryStore();
        private final String forbiddenKeyPart;

        KeyFailingStore(String forbiddenKeyPart) {
            this.forbiddenKeyPart = forbiddenKeyPart;
        }

        private void check(String key) {
            if (key != null && key.contains(forbiddenKeyPart)) {
                throw new IllegalStateException("store put down for " + key);
            }
        }

        @Override
        public io.agentscope.harness.agent.filesystem.remote.store.StoreItem get(
                List<String> namespace, String key) {
            return delegate.get(namespace, key);
        }

        @Override
        public void put(List<String> namespace, String key, java.util.Map<String, Object> value) {
            check(key);
            delegate.put(namespace, key, value);
        }

        @Override
        public boolean putIfVersion(List<String> namespace, String key,
                                    java.util.Map<String, Object> value, long version) {
            check(key);
            return delegate.putIfVersion(namespace, key, value, version);
        }

        @Override
        public List<io.agentscope.harness.agent.filesystem.remote.store.StoreItem> search(
                List<String> namespace, int limit, int offset) {
            return delegate.search(namespace, limit, offset);
        }

        @Override
        public void delete(List<String> namespace, String key) {
            delegate.delete(namespace, key);
        }
    }

    /** KV 后端整体不可用（DB 宕机）：读/写/搜/删一律抛异常，用于验证失败必须显式上报 */
    private static final class UnavailableBaseStore
            implements io.agentscope.harness.agent.filesystem.remote.store.BaseStore {

        @Override
        public io.agentscope.harness.agent.filesystem.remote.store.StoreItem get(
                List<String> namespace, String key) {
            throw new IllegalStateException("store down");
        }

        @Override
        public void put(List<String> namespace, String key, java.util.Map<String, Object> value) {
            throw new IllegalStateException("store down");
        }

        @Override
        public List<io.agentscope.harness.agent.filesystem.remote.store.StoreItem> search(
                List<String> namespace, int limit, int offset) {
            throw new IllegalStateException("store down");
        }

        @Override
        public void delete(List<String> namespace, String key) {
            throw new IllegalStateException("store down");
        }
    }
}
