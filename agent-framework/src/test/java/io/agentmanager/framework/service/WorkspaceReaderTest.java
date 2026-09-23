package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;

/**
 * WorkspaceReader 测试：使用真实 InMemoryStore + 框架 RemoteFilesystem，
 * 验证 KV 读写与注入路径（与生产 JdbcStore 同接口）。
 */
class WorkspaceReaderTest {

    private static final String USER = "user-alice";

    private InMemoryStore store() {
        return new InMemoryStore();
    }


    private static io.agentscope.harness.agent.DistributedStore distributedStore(
            io.agentscope.harness.agent.filesystem.remote.store.BaseStore store) {
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        return ds;
    }

    /** 用框架 RemoteFilesystem 预置 KV 数据（模拟记忆系统写入） */
    private void seedMemory(InMemoryStore store, String user, String memoryContent) {
        var fs = new RemoteFilesystem(store, java.util.List.of(user));
        var ctx = io.agentscope.core.agent.RuntimeContext.builder().userId(user).build();
        fs.write(ctx, "MEMORY.md", memoryContent);
        fs.write(ctx, "memory/2026-08-12.md", "## 2026-08-12\n- fact one");
    }

    @Test
    void readRuntimeFilesShouldReturnMemoryAndDailyLogs() {
        var store = store();
        seedMemory(store, USER, "# MEMORY\n- long term");
        var reader = new WorkspaceReader(distributedStore(store));

        var files = reader.readRuntimeFiles(USER);

        assertTrue(files.containsKey("MEMORY.md"));
        assertEquals("# MEMORY\n- long term",
            new String(files.get("MEMORY.md"), StandardCharsets.UTF_8));
        assertTrue(files.containsKey("memory/2026-08-12.md"));
        assertTrue(new String(files.get("memory/2026-08-12.md"), StandardCharsets.UTF_8)
            .contains("fact one"));
    }

    @Test
    void readRuntimeFilesShouldReturnEmptyForNewUser() {
        var reader = new WorkspaceReader(distributedStore(store()));

        var files = reader.readRuntimeFiles("brand-new-user");

        assertTrue(files.isEmpty());
    }

    @Test
    void readRuntimeFilesShouldIsolateByUser() {
        var store = store();
        seedMemory(store, USER, "# alice memory");
        var reader = new WorkspaceReader(distributedStore(store));

        var bobFiles = reader.readRuntimeFiles("user-bob");

        assertTrue(bobFiles.isEmpty());
    }

    @Test
    void injectToSandboxShouldWriteAllFiles() {
        var files = Map.of(
            "MEMORY.md", "m1".getBytes(StandardCharsets.UTF_8),
            "memory/2026-08-12.md", "m2".getBytes(StandardCharsets.UTF_8));
        var osb = org.mockito.Mockito.mock(com.alibaba.opensandbox.sandbox.Sandbox.class);
        var filesSvc = org.mockito.Mockito.mock(
            com.alibaba.opensandbox.sandbox.domain.services.Filesystem.class);
        org.mockito.Mockito.when(osb.files()).thenReturn(filesSvc);

        new WorkspaceReader(distributedStore(store())).injectToSandbox(osb, files);

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.Mockito.verify(filesSvc).write(captor.capture());
        @SuppressWarnings("unchecked")
        java.util.List<com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry> entries =
            (java.util.List<com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry>) captor.getValue();
        assertEquals(2, entries.size());
        // Map.of 不保证顺序，按内容断言
        var paths = entries.stream().map(e -> e.getPath()).toList();
        assertTrue(paths.containsAll(java.util.List.of(
            "/workspace/MEMORY.md", "/workspace/memory/2026-08-12.md")));
    }

    @Test
    void injectToSandboxShouldSkipEmptyFiles() {
        var osb = org.mockito.Mockito.mock(com.alibaba.opensandbox.sandbox.Sandbox.class);

        new WorkspaceReader(distributedStore(store())).injectToSandbox(osb, Map.of());

        org.mockito.Mockito.verify(osb, org.mockito.Mockito.never()).files();
    }

    // ===== writeWorkspaceFile 测试 =====

    @Test
    void writeWorkspaceFileShouldPersistAndBeReadable() {
        var store = store();
        var reader = new WorkspaceReader(distributedStore(store));

        boolean ok = reader.writeWorkspaceFile(USER, "outputs/report.txt", "hello world");
        assertTrue(ok, "写入 KV 应成功");

        // 用 readWorkspaceFile 读回验证
        var bytes = reader.readWorkspaceFile(USER, "outputs/report.txt");
        assertNotNull(bytes, "读回应非 null");
        assertEquals("hello world", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void writeWorkspaceFileShouldOverwriteExisting() {
        var store = store();
        var reader = new WorkspaceReader(distributedStore(store));

        reader.writeWorkspaceFile(USER, "memo.txt", "v1");
        reader.writeWorkspaceFile(USER, "memo.txt", "v2-overwrite");

        var bytes = reader.readWorkspaceFile(USER, "memo.txt");
        assertNotNull(bytes);
        assertEquals("v2-overwrite", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void writeWorkspaceFileShouldRejectNullOrBlank() {
        var reader = new WorkspaceReader(distributedStore(store()));

        assertFalse(reader.writeWorkspaceFile(null, "a.txt", "c"));
        assertFalse(reader.writeWorkspaceFile(USER, null, "c"));
        assertFalse(reader.writeWorkspaceFile(USER, "a.txt", null));
        assertFalse(reader.writeWorkspaceFile("  ", "a.txt", "c"));
        assertFalse(reader.writeWorkspaceFile(USER, "  ", "c"));
    }

    @Test
    void writeWorkspaceFileShouldIsolateByUser() {
        var store = store();
        var reader = new WorkspaceReader(distributedStore(store));

        reader.writeWorkspaceFile(USER, "secret.txt", "alice-data");

        var bobBytes = reader.readWorkspaceFile("user-bob", "secret.txt");
        assertNull(bobBytes, "Bob 不应能读到 Alice 的数据");
    }

    @Test
    void writeWorkspaceFileThenPresentFileCanRead() {
        // 模拟完整链路：write_file → KV 同步 → present_file 从 KV 读
        var store = store();
        var reader = new WorkspaceReader(distributedStore(store));

        // 1. write_file 同步写 KV
        reader.writeWorkspaceFile(USER, "outputs/result.md", "# AI Report\nContent here");

        // 2. present_file 通过 readWorkspaceFile 从 KV 读
        var bytes = reader.readWorkspaceFile(USER, "outputs/result.md");
        assertNotNull(bytes, "present_file 应从 KV 读到 write_file 写入的内容");
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("AI Report"));
    }

    // ===== 用户技能（L4）KV 读写 =====

    /** agent 名：L4 命名空间首段（与 SDK workspace-writable 仓库一致） */
    private static final String AGENT = "Skill Dyn E2E";

    /** L4 技能命名空间：agents/{agent}/users/{uid}/skills */
    private static java.util.List<String> skillNs(String user) {
        return java.util.List.of("agents", AGENT, "users", user, "skills");
    }

    private WorkspaceReader readerWithAgent(
            io.agentscope.harness.agent.filesystem.remote.store.BaseStore store) {
        return new WorkspaceReader(distributedStore(store), AGENT);
    }

    /**
     * 命名空间与 key 形态是硬约束（SDK write 原样落 key、read 精确 key 无回退）：
     * 必须是 agents/{agent}/users/{uid}/skills + /{name}/SKILL.md（前导斜杠）。
     */
    @Test
    void writeUserSkillFileShouldUseAgentNamespaceAndLeadingSlashKey() {
        var store = store();
        var reader = readerWithAgent(store);

        assertTrue(reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "# demo-a"));

        assertNotNull(store.get(skillNs(USER), "/demo-a/SKILL.md"), "key 必须带前导斜杠");
        assertNull(store.get(skillNs(USER), "demo-a/SKILL.md"), "不带前导斜杠的 key 不应存在");
        assertNull(store.get(java.util.List.of(USER), "/demo-a/SKILL.md"), "不得写进裸 userId 命名空间");
        assertNull(store.get(java.util.List.of("agents", AGENT, "users", USER), "/demo-a/SKILL.md"),
            "不得写进 users 层（少了 skills 段）");
    }

    @Test
    void userSkillFileShouldRoundTripAndVersionIncrement() {
        var store = store();
        var reader = readerWithAgent(store);

        assertTrue(reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "v1"));
        assertEquals("v1", reader.readUserSkillFile(USER, "demo-a", "SKILL.md"));
        var v1 = reader.userSkillFileVersion(USER, "demo-a", "SKILL.md");
        assertTrue(v1 > 0, "写入后版本号应 > 0");

        assertTrue(reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "v2-updated"));
        assertEquals("v2-updated", reader.readUserSkillFile(USER, "demo-a", "SKILL.md"));
        assertTrue(reader.userSkillFileVersion(USER, "demo-a", "SKILL.md") > v1, "覆盖写入后版本号应自增");

        // 相同内容重复写：短路成功（防 SDK edit 空 needle 死循环）
        assertTrue(reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "v2-updated"));
    }

    /**
     * KV 已有空内容 → 再写非空内容必须真正落库：修前该分支走短路直接返回 true，
     * 调用方（PUT /skills、sync-from-package、沙箱回写）按成功上报，KV 里却仍是空内容
     * （容器换代后数据永久丢失）。空内容行可由下发的 0 字节文件产生。
     */
    @Test
    void writeUserSkillFileShouldReplaceEmptyContentInsteadOfShortCircuiting() {
        var store = store();
        var reader = readerWithAgent(store);

        assertTrue(reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", ""), "空内容建行本身允许");
        assertEquals("", reader.readUserSkillFile(USER, "demo-a", "SKILL.md"));

        assertTrue(reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "# real content"),
            "已有空内容 → 写非空必须真实落库（不能假成功）");

        assertEquals("# real content", reader.readUserSkillFile(USER, "demo-a", "SKILL.md"));
        assertEquals("# real content",
            store.get(skillNs(USER), "/demo-a/SKILL.md").value().get("content"),
            "KV 行内容必须被真实替换，而不是仍为空");
        assertTrue(reader.userSkillFileVersion(USER, "demo-a", "SKILL.md") > 0);
    }

    @Test
    void listUserSkillsShouldReturnFilesAndSkipMetadataDirs() {
        var store = store();
        var reader = readerWithAgent(store);
        reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "# a");
        reader.writeUserSkillFile(USER, "demo-a", "scripts/hello.sh", "echo hi");
        // 归档（软删）与草稿目录：SDK 侧同样跳过，不能当成技能
        reader.writeUserSkillFile(USER, ".archive/demo-x-1", "SKILL.md", "# archived");
        reader.writeUserSkillFile(USER, "_drafts/demo-y", "SKILL.md", "# draft");
        // 无 SKILL.md 的目录不算技能
        reader.writeUserSkillFile(USER, "not-a-skill", "notes.txt", "x");

        var skills = reader.listUserSkills(USER);

        assertEquals(java.util.Set.of("demo-a"), skills.keySet());
        assertEquals(java.util.List.of("SKILL.md", "scripts/hello.sh"), skills.get("demo-a"));
        assertEquals("echo hi", reader.readUserSkillFile(USER, "demo-a", "scripts/hello.sh"));
    }

    @Test
    void listUserSkillsShouldReturnEmptyForNewUser() {
        var reader = readerWithAgent(store());

        assertTrue(reader.listUserSkills("brand-new-user").isEmpty(), "空命名空间不得抛错");
        assertNull(reader.readUserSkillFile("brand-new-user", "demo-a", "SKILL.md"));
    }

    @Test
    void deleteUserSkillShouldRemoveAllFilesAndReportCount() {
        var store = store();
        var reader = readerWithAgent(store);
        reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "# a");
        reader.writeUserSkillFile(USER, "demo-a", "scripts/hello.sh", "echo hi");
        reader.writeUserSkillFile(USER, "demo-b", "SKILL.md", "# b");

        assertEquals(2, reader.deleteUserSkill(USER, "demo-a"));

        assertNull(reader.readUserSkillFile(USER, "demo-a", "SKILL.md"), "删除后 SKILL.md 不应可读");
        assertNull(store.get(skillNs(USER), "/demo-a/SKILL.md"), "删除后 KV 行必须消失");
        assertEquals(java.util.Set.of("demo-b"), reader.listUserSkills(USER).keySet(), "仅 demo-a 被删除");
        // 其他技能不受影响；重复删除返回 0（调用方映射 404）
        assertNotNull(store.get(skillNs(USER), "/demo-b/SKILL.md"));
        assertEquals(0, reader.deleteUserSkill(USER, "demo-a"));
    }

    @Test
    void hasUserSkillShouldReflectL4Presence() {
        var store = store();
        var reader = readerWithAgent(store);
        assertFalse(reader.hasUserSkill(USER, "demo-a"));

        reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "# a");

        assertTrue(reader.hasUserSkill(USER, "demo-a"));
    }

    /**
     * 删除失败必须返回 -1（调用方据此区分 404 与 500）：曾把 -1 回退成 0 时，
     * 真实删除失败会被当成“无覆盖”静默返回 404。
     */
    @Test
    void deleteUserSkillShouldReturnMinusOneWhenStoreDeleteFails() {
        var store = new DeleteFailingStore();
        var reader = readerWithAgent(store);
        assertTrue(reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "# a"));

        assertEquals(-1, reader.deleteUserSkill(USER, "demo-a"));
        // 文件仍在（删除确实失败，不能按“已删除”上报）
        assertEquals("# a", reader.readUserSkillFile(USER, "demo-a", "SKILL.md"));
    }

    /**
     * 文件枚举失败（glob 不可信）时不得降级成“该技能只有 SKILL.md”：
     * listUserSkills 必须显式抛错、deleteUserSkill 必须返回 -1，而不是给出漏文件的列表/假装删干净。
     */
    @Test
    void listUserSkillsAndDeleteShouldFailExplicitlyWhenEnumerationFails() {
        // 第 1 次 search 让 ls 成功，第 2 次起（glob）抛异常，做出“枚举失败但技能存在”的场景
        var store = new SearchFailingStore(2);
        var reader = readerWithAgent(store);
        assertTrue(reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "# a"));
        assertTrue(reader.writeUserSkillFile(USER, "demo-a", "scripts/hello.sh", "echo hi"));

        assertThrows(IllegalStateException.class, () -> reader.listUserSkills(USER));
        assertEquals(-1, reader.deleteUserSkill(USER, "demo-a"));
        assertEquals("echo hi", reader.readUserSkillFile(USER, "demo-a", "scripts/hello.sh"),
            "枚举失败时不得删掉部分文件后按成功上报");
    }

    /** 显式删除会写入删除标记（tombstone）：沙箱回写据此跳过同名技能，避免删除被静默还原 */
    @Test
    void deleteUserSkillShouldWriteDeletionMarkerAndRecreateShouldClearIt() {
        var store = store();
        var reader = readerWithAgent(store);
        reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "# a");

        assertEquals(1, reader.deleteUserSkill(USER, "demo-a"));

        assertTrue(reader.isUserSkillDeleted(USER, "demo-a"), "删除后必须留删除标记");
        assertNotNull(store.get(skillNs(USER), "/demo-a/.deleted"));
        assertFalse(reader.hasUserSkill(USER, "demo-a"));
        assertTrue(reader.listUserSkills(USER).isEmpty(), "只剩删除标记的目录不算技能");

        // 用户重新创建 → 清除标记，否则沙箱回写侧会一直跳过该技能
        assertTrue(reader.clearUserSkillDeletion(USER, "demo-a"));
        assertFalse(reader.isUserSkillDeleted(USER, "demo-a"));
        assertNull(store.get(skillNs(USER), "/demo-a/.deleted"));
        assertTrue(reader.clearUserSkillDeletion(USER, "demo-a"), "本无标记时清除视为成功");
    }

    /**
     * 管理面写入栅栏（admin-override）：与 tombstone 对称的写侧仲裁——管理面 PUT/下发后，
     * 沙箱回写据此跳过同名技能，避免同代容器内旧副本把管理面内容改回容器版本。
     * 删除该技能时栅栏一并清除（此后由删除标记接管）。
     */
    @Test
    void adminOverrideFenceShouldBeWrittenReadAndClearedByDelete() {
        var store = store();
        var reader = readerWithAgent(store);
        reader.writeUserSkillFile(USER, "demo-a", "SKILL.md", "# a");

        assertFalse(reader.isUserSkillAdminOverride(USER, "demo-a"), "未标记时无栅栏");
        assertTrue(reader.markUserSkillAdminOverride(USER, "demo-a"));

        assertTrue(reader.isUserSkillAdminOverride(USER, "demo-a"));
        assertNotNull(store.get(skillNs(USER), "/demo-a/.admin-override"));
        // 栅栏是元数据段：不算技能文件，也不进技能文件清单
        assertEquals(List.of("SKILL.md"), reader.listUserSkills(USER).get("demo-a"));

        assertEquals(1, reader.deleteUserSkill(USER, "demo-a"));
        assertFalse(reader.isUserSkillAdminOverride(USER, "demo-a"), "删除必须清除写侧栅栏");
        assertTrue(reader.clearUserSkillAdminOverride(USER, "demo-a"), "本无栅栏时清除视为成功");
    }

    /**
     * 删除标记可见性：删除后技能不在 L4 列表里（只剩元数据键），必须能单独枚举出来——
     * 否则运维看不到「已删除但标记仍在」，也无从解释「同代容器内重建了却不落库」。
     */
    @Test
    void listUserSkillTombstonesShouldExposeDeletedNamesWithTimestamp() {
        var store = store();
        var reader = readerWithAgent(store);
        reader.writeUserSkillFile(USER, "gone", "SKILL.md", "# gone");
        reader.writeUserSkillFile(USER, "kept", "SKILL.md", "# kept");
        assertTrue(reader.listUserSkillTombstones(USER).isEmpty(), "未删除时无标记");

        assertEquals(1, reader.deleteUserSkill(USER, "gone"));

        var tombstones = reader.listUserSkillTombstones(USER);
        assertEquals(1, tombstones.size());
        assertEquals("gone", tombstones.get(0).name());
        assertTrue(tombstones.get(0).deletedAt() > 0, "标记时间应为毫秒时间戳");
        // 只剩标记的目录不得被当成技能；正常技能不受影响
        assertEquals(List.of("kept"), new java.util.ArrayList<>(reader.listUserSkills(USER).keySet()));
        assertTrue(reader.listUserSkillTombstones("brand-new-user").isEmpty(), "新用户无标记且不抛错");
    }

    /** delete 委托真实 InMemoryStore，但 delete 抛异常（store 故障） */
    private static final class DeleteFailingStore
            implements io.agentscope.harness.agent.filesystem.remote.store.BaseStore {
        private final InMemoryStore delegate = new InMemoryStore();

        @Override
        public io.agentscope.harness.agent.filesystem.remote.store.StoreItem get(
                java.util.List<String> namespace, String key) {
            return delegate.get(namespace, key);
        }

        @Override
        public void put(java.util.List<String> namespace, String key, java.util.Map<String, Object> value) {
            delegate.put(namespace, key, value);
        }

        @Override
        public boolean putIfVersion(java.util.List<String> namespace, String key,
                                    java.util.Map<String, Object> value, long version) {
            return delegate.putIfVersion(namespace, key, value, version);
        }

        @Override
        public java.util.List<io.agentscope.harness.agent.filesystem.remote.store.StoreItem> search(
                java.util.List<String> namespace, int limit, int offset) {
            return delegate.search(namespace, limit, offset);
        }

        @Override
        public void delete(java.util.List<String> namespace, String key) {
            throw new IllegalStateException("store delete down");
        }
    }

    /**
     * 委托真实 InMemoryStore，但从第 failFromSearchCall 次 search 调用起抛异常。
     * ls 与 glob 都走 search（实测 limit=100/offset=0），用计数把“glob 失败”单独做出来
     * （putIfVersion 必须一并委托，否则框架 write 会直接返回失败）。
     */
    private static final class SearchFailingStore
            implements io.agentscope.harness.agent.filesystem.remote.store.BaseStore {
        private final InMemoryStore delegate = new InMemoryStore();
        private final int failFromSearchCall;
        private int searchCalls = 0;

        SearchFailingStore(int failFromSearchCall) {
            this.failFromSearchCall = failFromSearchCall;
        }

        @Override
        public io.agentscope.harness.agent.filesystem.remote.store.StoreItem get(
                java.util.List<String> namespace, String key) {
            return delegate.get(namespace, key);
        }

        @Override
        public void put(java.util.List<String> namespace, String key, java.util.Map<String, Object> value) {
            delegate.put(namespace, key, value);
        }

        @Override
        public boolean putIfVersion(java.util.List<String> namespace, String key,
                                    java.util.Map<String, Object> value, long version) {
            return delegate.putIfVersion(namespace, key, value, version);
        }

        @Override
        public java.util.List<io.agentscope.harness.agent.filesystem.remote.store.StoreItem> search(
                java.util.List<String> namespace, int limit, int offset) {
            if (++searchCalls >= failFromSearchCall) {
                throw new IllegalStateException("store search down (call " + searchCalls + ")");
            }
            return delegate.search(namespace, limit, offset);
        }

        @Override
        public void delete(java.util.List<String> namespace, String key) {
            delegate.delete(namespace, key);
        }
    }
}
