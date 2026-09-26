package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;

/**
 * SkillInjectionService 测试：@Skill 引用解析、注入、边界情况。
 */
class SkillInjectionServiceTest {

    @TempDir
    Path tempDir;

    private Path skillsDir;
    private AgentManagerProperties props;

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120, true, 0, "", null),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            tempDir.toString(),
            "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*,text/plain,text/markdown,text/csv,application/pdf", 5, 15, 50, true, 7, "local", "", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults()
        );
    }

    private OafConfig oafConfig() {
        return new OafConfig(
            "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
            "Test agent", "@acme", "MIT",
            List.of("test"), "You are a test agent.",
            List.of(), List.of(), List.of(), List.of(), List.of(),
            new OafConfig.ModelConfig("openai", "gpt-4", ""),
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            new OafConfig.MemoryConfig("editable", Map.of()),
            Map.of()
        );
    }

    private SkillManageService manageService() {
        return new SkillManageService(props);
    }

    private UserSkillService userSkillService() {
        // L4 合并对现有用例为空（mock 默认返回空集合/Optional.empty）
        return org.mockito.Mockito.mock(UserSkillService.class);
    }

    private SkillCatalogService catalogService() {
        return new SkillCatalogService(oafConfig(), props, manageService(), userSkillService());
    }

    private SkillInjectionService injectionService() {
        return new SkillInjectionService(catalogService(), manageService(), userSkillService());
    }

    private void writeSkill(String name, String description, String body) throws IOException {
        var dir = skillsDir.resolve(name);
        Files.createDirectories(dir);
        var content = "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body + "\n";
        Files.writeString(dir.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
    }

    // ========== parseSkillReferences 测试 ==========

    @Test
    void parseNoAtReturnsEmpty() throws IOException {
        writeSkill("demo", "Demo skill", "# Demo");
        var service = injectionService();
        var refs = service.parseSkillReferences("hello world");
        assertTrue(refs.isEmpty());
    }

    @Test
    void parseSingleAtSkill() throws IOException {
        writeSkill("ppt生成大师", "PPT generation", "# PPT");
        var service = injectionService();
        var refs = service.parseSkillReferences("用@ppt生成大师 做个PPT");
        assertEquals(List.of("ppt生成大师"), refs);
    }

    @Test
    void parseMultipleAtSkills() throws IOException {
        writeSkill("pdf", "PDF tool", "# PDF");
        writeSkill("xlsx", "Excel tool", "# XLSX");
        var service = injectionService();
        var refs = service.parseSkillReferences("用@pdf 和 @xlsx 处理文件");
        assertEquals(List.of("pdf", "xlsx"), refs);
    }

    @Test
    void parseDuplicateAtOnlyOnce() throws IOException {
        writeSkill("pdf", "PDF tool", "# PDF");
        var service = injectionService();
        var refs = service.parseSkillReferences("@pdf and again @pdf");
        assertEquals(List.of("pdf"), refs);
    }

    @Test
    void parseNonExistentSkillIgnored() throws IOException {
        writeSkill("pdf", "PDF tool", "# PDF");
        var service = injectionService();
        var refs = service.parseSkillReferences("@pdf and @ghost-skill");
        assertEquals(List.of("pdf"), refs);
    }

    @Test
    void parseDisabledSkillIgnored() throws IOException {
        writeSkill("disabled-skill", "Disabled", "# Disabled");
        var manage = manageService();
        manage.toggleSkill("disabled-skill"); // disable
        var service = new SkillInjectionService(catalogService(), manage, userSkillService());
        var refs = service.parseSkillReferences("@disabled-skill help");
        assertTrue(refs.isEmpty());
    }

    @Test
    void parseDoesNotMatchEmail() throws IOException {
        writeSkill("example", "Example", "# Example");
        var service = injectionService();
        // user@example.com should NOT match @example
        var refs = service.parseSkillReferences("Email: user@example.com");
        assertTrue(refs.isEmpty(), "Email address should not be parsed as @Skill");
    }

    // ========== injectSkillReferences 测试 ==========

    @Test
    void injectNoAtReturnsOriginal() throws IOException {
        writeSkill("demo", "Demo", "# Demo");
        var service = injectionService();
        var result = service.injectSkillReferences("plain message");
        assertEquals("plain message", result);
    }

    @Test
    void injectSingleSkill() throws IOException {
        writeSkill("pdf", "PDF tool", "# PDF\nRead PDF files.");
        var service = injectionService();
        var result = service.injectSkillReferences("用@pdf 处理文件");
        assertTrue(result.contains("[@Skill:pdf]"), "@pdf should be replaced with reference marker");
        assertTrue(result.contains("## Referenced Skills"), "Should contain Referenced Skills section");
        assertTrue(result.contains("### Skill: pdf"), "Should contain Skill header");
        assertTrue(result.contains("# PDF\nRead PDF files."), "Should contain full SKILL.md content");
    }

    @Test
    void injectMultipleSkills() throws IOException {
        writeSkill("pdf", "PDF tool", "# PDF content");
        writeSkill("xlsx", "Excel tool", "# XLSX content");
        var service = injectionService();
        var result = service.injectSkillReferences("用@pdf 和 @xlsx 处理");
        assertTrue(result.contains("[@Skill:pdf]"));
        assertTrue(result.contains("[@Skill:xlsx]"));
        assertTrue(result.contains("### Skill: pdf"));
        assertTrue(result.contains("### Skill: xlsx"));
    }

    @Test
    void injectDuplicateOnlyOnce() throws IOException {
        writeSkill("pdf", "PDF tool", "# PDF content");
        var service = injectionService();
        var result = service.injectSkillReferences("@pdf and @pdf again");
        // Should only appear once in Referenced Skills
        var skillHeader = "### Skill: pdf";
        assertEquals(1, countOccurrences(result, skillHeader), "Duplicate @ should only inject once");
    }

    @Test
    void injectNonExistentKeptAsIs() throws IOException {
        writeSkill("pdf", "PDF tool", "# PDF");
        var service = injectionService();
        var result = service.injectSkillReferences("@pdf and @ghost-skill");
        assertTrue(result.contains("[@Skill:pdf]"), "Existing skill should be replaced");
        assertTrue(result.contains("@ghost-skill"), "Non-existent skill should remain as-is");
    }

    @Test
    void injectNullMessageReturnsNull() {
        var service = injectionService();
        assertEquals(null, service.injectSkillReferences(null));
    }

    @Test
    void injectBlankMessageReturnsBlank() {
        var service = injectionService();
        assertEquals("", service.injectSkillReferences(""));
    }

    @Test
    void injectDisabledSkillKeptAsIs() throws IOException {
        writeSkill("old-skill", "Old skill", "# Old");
        var manage = manageService();
        manage.toggleSkill("old-skill"); // disable
        var service = new SkillInjectionService(catalogService(), manage, userSkillService());
        var result = service.injectSkillReferences("用@old-skill 处理");
        assertTrue(result.contains("@old-skill"), "Disabled skill @ should remain as-is");
        assertFalse(result.contains("## Referenced Skills"), "No skills should be injected");
    }

    @Test
    void injectDoesNotCorruptEmail() throws IOException {
        writeSkill("example", "Example", "# Example");
        var service = injectionService();
        var result = service.injectSkillReferences("Contact: user@example.com and use @example");
        // user@example.com should not be touched
        assertTrue(result.contains("user@example.com"), "Email should remain untouched");
        // @example at sentence start should be replaced
        assertTrue(result.contains("[@Skill:example]"), "Standalone @example should be replaced");
    }

    // ========== availableSkills 测试 ==========

    @Test
    void availableSkillsReturnsEnabledOnly() throws IOException {
        writeSkill("pdf", "PDF tool", "# PDF");
        writeSkill("xlsx", "Excel tool", "# XLSX");
        var manage = manageService();
        manage.toggleSkill("xlsx"); // disable xlsx
        var catalog = new SkillCatalogService(oafConfig(), props, manage, userSkillService());
        var available = catalog.availableSkills();
        assertEquals(1, available.size());
        assertEquals("pdf", available.get(0).get("name"));
        assertEquals("PDF tool", available.get(0).get("description"));
    }

    @Test
    void availableSkillsReturnsEmptyWhenAllDisabled() throws IOException {
        writeSkill("a", "A", "# A");
        var manage = manageService();
        manage.toggleSkill("a"); // disable
        var catalog = new SkillCatalogService(oafConfig(), props, manage, userSkillService());
        var available = catalog.availableSkills();
        assertTrue(available.isEmpty());
    }

    @Test
    void injectShouldUseUserL4SkillWhenUserIdProvided() {
        var userService = org.mockito.Mockito.mock(UserSkillService.class);
        org.mockito.Mockito.when(userService.listSkills("u1")).thenReturn(List.of(
            new UserSkillService.UserSkillSummary("personal-a", List.of("SKILL.md"), 20, 1, false, false)));
        org.mockito.Mockito.when(userService.readSkill("u1", "personal-a", null))
            .thenReturn(java.util.Optional.of(new UserSkillService.UserSkillContent(
                "u1", "personal-a", "---\nname: personal-a\n---\nPersonal body",
                "user", true, 1, List.of("SKILL.md"), true)));
        var service = new SkillInjectionService(catalogService(), manageService(), userService);

        var injected = service.injectSkillReferences("@personal-a 请处理", "u1");
        assertTrue(injected.contains("Personal body"), injected);
        assertEquals(List.of("personal-a"), service.parseSkillReferences("@personal-a", "u1"));

        // 无 userId：L4 不参与，@ 引用保留原样（不注入个人技能内容）
        var noUser = service.injectSkillReferences("@personal-a 请处理");
        assertFalse(noUser.contains("Personal body"), noUser);
    }

    private static int countOccurrences(String text, String substr) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substr, idx)) >= 0) {
            count++;
            idx += substr.length();
        }
        return count;
    }
}
