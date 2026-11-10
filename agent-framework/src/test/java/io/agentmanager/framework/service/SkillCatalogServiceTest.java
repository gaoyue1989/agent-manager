package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;

/**
 * SkillCatalogService 动态技能目录测试：
 * frontmatter 声明 ∪ /config/skills 目录事实的合并语义、冲突以目录为准、动态变化即时可见。
 */
class SkillCatalogServiceTest {

    @TempDir
    Path tempDir;

    private Path skillsDir;

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
    }

    private AgentManagerProperties props() {
        return new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120, true, 0, "", null),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            tempDir.toString(),
            "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*,text/plain,text/markdown,text/csv,application/pdf", 5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
                AgentManagerProperties.HarnessConfig.defaults()
        );
    }

    private OafConfig oafConfig(List<OafConfig.SkillConfig> skills) {
        return new OafConfig(
            "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
            "Test agent", "@acme", "MIT",
            List.of("test"), "You are a test agent.",
            skills, List.of(), List.of(), List.of(), List.of(),
            new OafConfig.ModelConfig("openai", "gpt-4", ""),
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            new OafConfig.MemoryConfig("editable", Map.of()),
            Map.of()
        );
    }

    private OafConfig.SkillConfig declared(String name, String description, boolean required) {
        return new OafConfig.SkillConfig(name, "local", "1.0.0", required, description,
            List.of(), "", "", Map.of());
    }

    private void writeSkill(String name, String description, String version) throws IOException {
        var dir = skillsDir.resolve(name);
        Files.createDirectories(dir);
        var fm = new StringBuilder("---\nname: ").append(name).append("\ndescription: ")
            .append(description).append('\n');
        if (version != null) {
            fm.append("version: ").append(version).append('\n');
        }
        fm.append("---\n\n# ").append(name).append('\n');
        Files.writeString(dir.resolve("SKILL.md"), fm.toString());
    }

    private SkillManageService manageService() {
        return new SkillManageService(props());
    }

    private UserSkillService userSkillService() {
        // L4 合并对现有用例为空（mock 默认返回空集合）
        return org.mockito.Mockito.mock(UserSkillService.class);
    }

    private Map<String, Object> byName(SkillCatalogService service, String name) {
        return service.list().stream()
            .filter(m -> name.equals(m.get("name")))
            .findFirst().orElse(null);
    }

    @Test
    void directoryOnlySkillShouldBeLocalDynamic() throws IOException {
        writeSkill("extra-skill", "Dynamic skill", "2.0.0");
        var service = new SkillCatalogService(oafConfig(List.of()), props(), manageService(), userSkillService());

        var entry = byName(service, "extra-skill");
        assertNotNull(entry, "extra-skill should be in list()");
        assertEquals(SkillCatalogService.DYNAMIC_SOURCE, entry.get("source"));
        assertEquals("Dynamic skill", entry.get("description"));
        assertEquals("2.0.0", entry.get("version"));
        assertEquals(true, entry.get("dynamic"));
        assertEquals(false, entry.get("declaredButMissing"));
    }

    @Test
    void declaredOnlySkillShouldBeMarkedMissing() {
        var service = new SkillCatalogService(
            oafConfig(List.of(declared("ghost-skill", "Only declared", true))), props(), manageService(), userSkillService());

        var entry = byName(service, "ghost-skill");
        assertEquals("Only declared", entry.get("description"));
        assertEquals("local", entry.get("source"));
        assertEquals(true, entry.get("declaredButMissing"));
        assertEquals(false, entry.get("dynamic"));
        assertEquals(true, entry.get("required"));
    }

    @Test
    void conflictShouldPreferDirectoryFacts() throws IOException {
        // 声明 description="Declared desc"，目录 SKILL.md description="Disk desc" → 以目录为准
        writeSkill("demo", "Disk desc", "1.2.0");
        var service = new SkillCatalogService(
            oafConfig(List.of(declared("demo", "Declared desc", true))), props(), manageService(), userSkillService());

        var entry = byName(service, "demo");
        assertEquals("Disk desc", entry.get("description"));
        assertEquals("1.2.0", entry.get("version"));
        // 声明侧语义保留：source/required 来自 frontmatter
        assertEquals("local", entry.get("source"));
        assertEquals(true, entry.get("required"));
        assertEquals(true, entry.get("dynamic"));
        assertEquals(false, entry.get("declaredButMissing"));
    }

    @Test
    void runtimeDirectoryChangeShouldBeVisibleImmediately() throws IOException {
        writeSkill("a", "Skill A", "1.0.0");
        var service = new SkillCatalogService(
            oafConfig(List.of(declared("b", "Skill B", false))), props(), manageService(), userSkillService());

        assertEquals(2, service.list().size());

        // 运行中新增目录（PVC 原位更新）→ 无需重建服务即时可见
        writeSkill("c", "Skill C", null);
        var names = service.list().stream().map(m -> m.get("name")).toList();
        assertTrue(names.contains("c"));
        assertTrue(service.dynamicSkillNames().containsAll(List.of("a", "c")));

        // 运行中删除目录 → 即时消失
        deleteRecursively(skillsDir.resolve("a"));
        var after = service.list().stream().map(m -> m.get("name")).toList();
        assertFalse(after.contains("a"));
    }

    @Test
    void modifiedSkillMdShouldBeReRead() throws IOException {
        writeSkill("a", "Old desc", "1.0.0");
        var service = new SkillCatalogService(oafConfig(List.of()), props(), manageService(), userSkillService());
        assertEquals("Old desc", byName(service, "a").get("description"));

        // 修改 SKILL.md（强制 mtime 变化，模拟 PVC 原位更新）
        var skillMd = skillsDir.resolve("a").resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: a\ndescription: New desc\n---\n# a\n");
        Files.setLastModifiedTime(skillMd, FileTime.fromMillis(System.currentTimeMillis() + 2000));

        assertEquals("New desc", byName(service, "a").get("description"));
    }

    @Test
    void emptyDirectoryAndNoDeclarationsShouldReturnEmptyList() {
        var service = new SkillCatalogService(oafConfig(List.of()), props(), manageService(), userSkillService());
        assertTrue(service.list().isEmpty());
    }

    @Test
    void missingSkillsDirShouldFallBackToDeclarations() {
        // 目录不存在（包未携带 skills）→ repository 关闭，仅声明侧可见
        var emptyProps = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120, true, 0, "", null),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            tempDir.resolve("nonexistent").toString(),
            "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*,text/plain,text/markdown,text/csv,application/pdf", 5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
                AgentManagerProperties.HarnessConfig.defaults()

        );
        var service = new SkillCatalogService(
            oafConfig(List.of(declared("ghost", "Only declared", false))), emptyProps, new SkillManageService(emptyProps),
            userSkillService());

        assertTrue(service.dynamicSkillNames().isEmpty());
        assertEquals(1, service.list().size());
        assertEquals("ghost", service.list().get(0).get("name"));
    }

    @Test
    void availableSkillsShouldMergeUserL4Overrides() {
        var userService = org.mockito.Mockito.mock(UserSkillService.class);
        org.mockito.Mockito.when(userService.listSkills("u1")).thenReturn(List.of(
            new UserSkillService.UserSkillSummary("personal-a", List.of("SKILL.md"), 20, 1, false, false, "", true, "")));
        org.mockito.Mockito.when(userService.readSkill("u1", "personal-a", null))
            .thenReturn(java.util.Optional.of(new UserSkillService.UserSkillContent(
                "u1", "personal-a",
                "---\nname: personal-a\ndescription: Personal skill\n---\nbody",
                "user", true, 1, List.of("SKILL.md"), true)));

        var service = new SkillCatalogService(
            oafConfig(List.of(declared("global-a", "Global skill", false))),
            props(), manageService(), userService);

        var merged = service.availableSkills("u1");
        var byName = merged.stream().collect(java.util.stream.Collectors.toMap(
            m -> m.get("name"), m -> m.get("description")));
        assertEquals("Global skill", byName.get("global-a"));
        assertEquals("Personal skill", byName.get("personal-a"));

        // 无 userId：不合并 L4（仅全局目录）
        assertTrue(service.availableSkills().stream().noneMatch(m -> "personal-a".equals(m.get("name"))));
    }

    @Test
    void availableSkillsShouldExcludeDisabledOverrideIncludingPackageBaseline() {
        var userService = org.mockito.Mockito.mock(UserSkillService.class);
        // personal-a 在全局目录存在（L2 基线），用户个人覆盖被禁用 → 该技能对用户整体不可用
        org.mockito.Mockito.when(userService.listSkills("u1")).thenReturn(List.of(
            new UserSkillService.UserSkillSummary("personal-a", List.of("SKILL.md"), 20, 1, false, false,
                "", false, "")));

        var service = new SkillCatalogService(
            oafConfig(List.of(declared("personal-a", "Global personal-a", false))),
            props(), manageService(), userService);

        assertTrue(service.availableSkills("u1").stream()
                .noneMatch(m -> "personal-a".equals(m.get("name"))), "禁用的个人技能不应出现（含包内同名基线）");
    }

    @Test
    void availableSkillsL2DisabledMustNotBeRestoredByL4EnabledOverride() throws IOException {
        // oa-1110-repro-fixes-design §2.1b：L2 包级禁用 + 同名 L4 enabled 覆盖 → 不得重新放回
        // （与 SkillDisabledFilterMiddleware 的名字并集禁用口径对齐）
        writeSkill("pkg-a", "pkg a", null);
        var manage = new SkillManageService(props());
        manage.toggleSkill("pkg-a"); // L2 禁用
        var userService = org.mockito.Mockito.mock(UserSkillService.class);
        org.mockito.Mockito.when(userService.listSkills("u1")).thenReturn(List.of(
            new UserSkillService.UserSkillSummary("pkg-a", List.of("SKILL.md"), 20, 1, false, false,
                "l4 desc", true, "")));

        var service = new SkillCatalogService(oafConfig(List.of()), props(), manage, userService);

        var names = service.availableSkills("u1").stream().map(m -> m.get("name")).toList();
        assertFalse(names.contains("pkg-a"), "L2 禁用不得被同名 L4 enabled 覆盖放回：" + names);
        // 白名单路径同样不得放回
        assertTrue(service.availableSkills("u1", List.of("pkg-a")).isEmpty(),
            "白名单路径也不得放回 L2 禁用技能");
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            var all = stream.sorted(java.util.Comparator.reverseOrder()).toList();
            for (var p : all) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ========== 本轮技能列表视图（session-skill-list-design §5） ==========

    @Test
    void listWithSkillListShouldFilterToWhitelist() throws IOException {
        writeSkill("a", "A", null);
        writeSkill("b", "B", null);
        writeSkill("c", "C", null);
        var service = new SkillCatalogService(oafConfig(List.of()), props(), manageService(), userSkillService());

        var filtered = service.list(List.of("a", "c", "ghost")).stream()
            .map(m -> (String) m.get("name")).toList();
        assertTrue(filtered.containsAll(List.of("a", "c")));
        assertEquals(2, filtered.size(), "未知名忽略、白名单外技能不出现");
        assertTrue(service.list(List.of()).isEmpty(), "显式空列表 = 无技能");
        assertEquals(3, service.list((List<String>) null).size(), "null = 不过滤（存量语义）");
    }

    @Test
    void disabledSkillShouldStayExcludedUnderSkillList() throws IOException {
        writeSkill("a", "A", null);
        writeSkill("b", "B", null);
        var manage = new SkillManageService(props());
        manage.toggleSkill("a"); // a 禁用
        var service = new SkillCatalogService(oafConfig(List.of()), props(), manage, userSkillService());
        var names = service.list(List.of("a", "b")).stream().map(m -> (String) m.get("name")).toList();
        assertEquals(List.of("b"), names, "禁用优先：禁用技能即使在本轮白名单内也不出现");
    }

    @Test
    void availableSkillsWithSkillListShouldIntersectL4() throws IOException {
        writeSkill("pkg-a", "pkg a", null);
        var userService = org.mockito.Mockito.mock(UserSkillService.class);
        org.mockito.Mockito.when(userService.listSkills("u1")).thenReturn(List.of(
            new UserSkillService.UserSkillSummary("pkg-a", List.of("SKILL.md"), 20, 1, false, false,
                "l4 desc", true, ""),
            new UserSkillService.UserSkillSummary("personal-extra", List.of("SKILL.md"), 21, 1, false, false,
                "extra", true, "")));
        var service = new SkillCatalogService(oafConfig(List.of()), props(), manageService(), userService);

        var names = service.availableSkills("u1", List.of("pkg-a")).stream()
            .map(m -> m.get("name")).toList();
        assertEquals(List.of("pkg-a"), names, "白名单外的 L4 独有技能不出现");
        assertTrue(service.availableSkills("u1", List.of()).isEmpty());
        assertEquals(2, service.availableSkills("u1", (List<String>) null).size());
    }
}
