package io.agentmanager.framework.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class OafConfigLoaderTest {

    private OafConfigLoader loader;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            "src/test/resources/fixtures/test-agent"
        );
        loader = new OafConfigLoader(props);
    }

    @Test
    void shouldLoadOafConfigFromAgentsMd() {
        var config = loader.load();

        assertEquals("test-agent", config.name());
        assertEquals("acme", config.vendorKey());
        assertEquals("test-agent", config.agentKey());
        assertEquals("1.0.0", config.version());
        assertEquals("acme-test-agent", config.slug());
        assertEquals("A test agent for OAF config loader tests", config.description());
        assertEquals("Agent Manager Team", config.author());
        assertEquals("MIT", config.license());
    }

    @Test
    void shouldParseTags() {
        var config = loader.load();
        assertTrue(config.tags().containsAll(java.util.List.of("test", "oaf", "fixture")));
    }

    @Test
    void shouldParseSkills() {
        var config = loader.load();
        assertEquals(1, config.skills().size());

        var skill = config.skills().get(0);
        assertEquals("bash-tool", skill.name());
        assertEquals("local", skill.source());
        assertEquals("1.0.0", skill.version());
        assertTrue(skill.required());
    }

    @Test
    void shouldParseMcpServers() {
        var config = loader.load();
        assertEquals(1, config.mcpServers().size());

        var mcp = config.mcpServers().get(0);
        assertEquals("weather", mcp.vendor());
        assertEquals("weather-service", mcp.server());
        assertEquals("1.0.0", mcp.version());
        assertEquals("mcp-configs/weather", mcp.configDir());
        assertTrue(mcp.required());
    }

    @Test
    void shouldParseTools() {
        var config = loader.load();
        assertTrue(config.tools().containsAll(java.util.List.of("Read", "Bash", "Edit")));
    }

    @Test
    void shouldParseSystemPrompt() {
        var config = loader.load();
        assertTrue(config.systemPrompt().contains("This is a test agent used for OAF config loader validation"));
    }

    @Test
    void shouldParseModelConfig() {
        var config = loader.load();
        assertEquals("openai", config.model().provider());
        assertEquals("gpt-4", config.model().name());
    }

    @Test
    void shouldParseRuntimeConfig() {
        var config = loader.load();
        assertEquals(0.7, config.runtimeConfig().temperature());
        assertEquals(4096, config.runtimeConfig().maxTokens());
    }

    @Test
    void shouldParseMemoryConfig() {
        var config = loader.load();
        assertEquals("editable", config.memory().type());
    }

    @Test
    void shouldParseDeniedTools() throws Exception {
        writeAgentsMd("""
            ---
            name: deny-test
            vendorKey: acme
            agentKey: deny-test
            version: 1.0.0
            deniedTools:
              - write_file
              - session_history
            ---
            # Deny Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        assertTrue(config.hasDeniedTools());
        assertTrue(config.deniedTools().containsAll(java.util.List.of("write_file", "session_history")));
        assertEquals(2, config.deniedTools().size());
    }

    @Test
    void shouldDefaultDeniedToolsToEmpty() throws Exception {
        writeAgentsMd("""
            ---
            name: no-deny
            vendorKey: acme
            agentKey: no-deny
            version: 1.0.0
            ---
            # No Deny
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        assertFalse(config.hasDeniedTools());
        assertTrue(config.deniedTools().isEmpty());
    }

    @Test
    void shouldPreserveToolsFieldWhenDeniedToolsAbsent() throws Exception {
        writeAgentsMd("""
            ---
            name: tools-test
            vendorKey: acme
            agentKey: tools-test
            version: 1.0.0
            tools:
              - Read
              - Bash
            ---
            # Tools Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        assertTrue(config.tools().containsAll(java.util.List.of("Read", "Bash")));
        assertFalse(config.hasDeniedTools());
    }

    private AgentManagerProperties props(Path dir) {
        return new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            dir.toString()
        );
    }

    @Test
    void shouldPreferFrontmatterDescriptionOverBody() throws Exception {
        var skillDir = tempDir.resolve("skills").resolve("pdf-processing");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: pdf-processing
            description: 从 PDF 中提取文本和表格
            ---
            # PDF 处理
            ## 使用场景
            更详细的说明...
            """);

        writeAgentsMd("""
            ---
            name: desc-test
            vendorKey: acme
            agentKey: desc-test
            version: 1.0.0
            skills:
              - name: pdf-processing
                source: local
                version: "1.0.0"
            ---
            # Desc Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals("从 PDF 中提取文本和表格", config.skills().get(0).description());
    }

    @Test
    void shouldFallbackToBodyWhenNoFrontmatterDescription() throws Exception {
        var skillDir = tempDir.resolve("skills").resolve("no-desc");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: no-desc
            ---
            # No Description Skill
            This is the body content.
            """);

        writeAgentsMd("""
            ---
            name: desc-fallback-test
            vendorKey: acme
            agentKey: desc-fallback-test
            version: 1.0.0
            skills:
              - name: no-desc
                source: local
                version: "1.0.0"
            ---
            # Desc Fallback Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        var desc = config.skills().get(0).description();
        assertTrue(desc.contains("# No Description Skill"));
        assertTrue(desc.contains("This is the body content."));
    }

    @Test
    void shouldLoadMinimalSkillFormat() throws Exception {
        var skillDir = tempDir.resolve("skills").resolve("minimal-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: minimal-skill
            description: 最小格式的 Skill
            ---
            """);

        writeAgentsMd("""
            ---
            name: minimal-test
            vendorKey: acme
            agentKey: minimal-test
            version: 1.0.0
            skills:
              - name: minimal-skill
                source: local
            ---
            # Minimal Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        var skill = config.skills().get(0);
        assertEquals("minimal-skill", skill.name());
        assertEquals("最小格式的 Skill", skill.description());
        assertEquals("local", skill.source());
        assertEquals("1.0.0", skill.version());
        assertFalse(skill.required());
    }

    @Test
    void shouldLoadSkillWithOptionalFields() throws Exception {
        var skillDir = tempDir.resolve("skills").resolve("full-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: full-skill
            description: 包含所有可选字段的 Skill
            license: Apache-2.0
            compatibility: Requires Python 3.14+ and uv
            metadata:
              author: test-org
              version: "2.0"
            ---
            # Full Skill
            """);

        writeAgentsMd("""
            ---
            name: full-test
            vendorKey: acme
            agentKey: full-test
            version: 1.0.0
            skills:
              - name: full-skill
                source: local
                version: "2.0.0"
                required: true
                allowed-tools: "Bash(git:*) Read Write"
                license: Apache-2.0
                compatibility: Requires Python 3.14+ and uv
                metadata:
                  author: test-org
                  version: "2.0"
            ---
            # Full Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        var skill = config.skills().get(0);
        assertEquals("full-skill", skill.name());
        assertEquals("包含所有可选字段的 Skill", skill.description());
        assertEquals("2.0.0", skill.version());
        assertTrue(skill.required());
        assertTrue(skill.allowedTools().containsAll(java.util.List.of("Bash(git:*)", "Read", "Write")));
        assertEquals("Apache-2.0", skill.license());
        assertEquals("Requires Python 3.14+ and uv", skill.compatibility());
        assertEquals("test-org", skill.metadata().get("author"));
        assertEquals("2.0", skill.metadata().get("version"));
    }

    @Test
    void shouldParseMultipleSkills() throws Exception {
        // 创建多个 skill
        for (var name : java.util.List.of("skill-a", "skill-b", "skill-c")) {
            var dir = tempDir.resolve("skills").resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: Description for %s
                ---
                """.formatted(name, name));
        }

        writeAgentsMd("""
            ---
            name: multi-skill-test
            vendorKey: acme
            agentKey: multi-skill-test
            version: 1.0.0
            skills:
              - name: skill-a
                source: local
              - name: skill-b
                source: local
                required: true
              - name: skill-c
                source: local
            ---
            # Multi Skill Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals(3, config.skills().size());
        assertEquals("skill-a", config.skills().get(0).name());
        assertEquals("skill-b", config.skills().get(1).name());
        assertTrue(config.skills().get(1).required());
        assertEquals("skill-c", config.skills().get(2).name());
    }

    @Test
    void shouldHandleNoFrontmatter() throws Exception {
        var skillDir = tempDir.resolve("skills").resolve("no-frontmatter");
        Files.createDirectories(skillDir);
        // 没有 frontmatter（不以 --- 开头）
        Files.writeString(skillDir.resolve("SKILL.md"), """
            # No Frontmatter Skill
            This is plain markdown without frontmatter.
            """);

        writeAgentsMd("""
            ---
            name: no-frontmatter-test
            vendorKey: acme
            agentKey: no-frontmatter-test
            version: 1.0.0
            skills:
              - name: no-frontmatter
                source: local
            ---
            # No Frontmatter Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        // 没有 frontmatter，整个内容作为 body
        var desc = config.skills().get(0).description();
        assertTrue(desc.contains("# No Frontmatter Skill"));
        assertTrue(desc.contains("This is plain markdown without frontmatter."));
    }

    @Test
    void shouldHandleEmptyDescriptionField() throws Exception {
        var skillDir = tempDir.resolve("skills").resolve("empty-desc");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: empty-desc
            description:
            ---
            # Fallback Body
            This should be used as description.
            """);

        writeAgentsMd("""
            ---
            name: empty-desc-test
            vendorKey: acme
            agentKey: empty-desc-test
            version: 1.0.0
            skills:
              - name: empty-desc
                source: local
            ---
            # Empty Desc Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        var desc = config.skills().get(0).description();
        // description 为空，fallback 到 body
        assertTrue(desc.contains("# Fallback Body"));
        assertTrue(desc.contains("This should be used as description."));
    }

    @Test
    void shouldLoadRemoteSkillWithoutCopying() throws Exception {
        writeAgentsMd("""
            ---
            name: remote-skill-test
            vendorKey: acme
            agentKey: remote-skill-test
            version: 1.0.0
            skills:
              - name: web-search
                source: https://example.com/skills/web-search
                version: "1.0.0"
            ---
            # Remote Skill Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals(1, config.skills().size());
        var skill = config.skills().get(0);
        assertEquals("web-search", skill.name());
        assertEquals("https://example.com/skills/web-search", skill.source());
        // 远程 skill 没有本地 SKILL.md，description 为空
        assertEquals("", skill.description());
    }

    @Test
    void shouldLoadSkillWithSubdirectories() throws Exception {
        var skillDir = tempDir.resolve("skills").resolve("complex-skill");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: complex-skill
            description: 带子目录的复杂 Skill
            ---
            # Complex Skill
            See scripts/validate.py and references/rules.md
            """);
        Files.writeString(skillDir.resolve("scripts").resolve("validate.py"), "print('validate')");
        Files.writeString(skillDir.resolve("references").resolve("rules.md"), "# Rules");

        writeAgentsMd("""
            ---
            name: complex-test
            vendorKey: acme
            agentKey: complex-test
            version: 1.0.0
            skills:
              - name: complex-skill
                source: local
            ---
            # Complex Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals("带子目录的复杂 Skill", config.skills().get(0).description());
    }

    @Test
    void shouldReturnEmptyAllowedToolsWhenNotSpecified() throws Exception {
        var skillDir = tempDir.resolve("skills").resolve("no-tools");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: no-tools
            description: No allowed-tools specified
            ---
            """);

        writeAgentsMd("""
            ---
            name: no-tools-test
            vendorKey: acme
            agentKey: no-tools-test
            version: 1.0.0
            skills:
              - name: no-tools
                source: local
            ---
            # No Tools Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        var skill = config.skills().get(0);
        assertTrue(skill.allowedTools().isEmpty());
    }

    @Test
    void shouldParseAllowedToolsAsSpaceSeparatedString() throws Exception {
        var skillDir = tempDir.resolve("skills").resolve("allowed-tools");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: allowed-tools
            description: Test allowed-tools field
            ---
            """);

        writeAgentsMd("""
            ---
            name: allowed-tools-test
            vendorKey: acme
            agentKey: allowed-tools-test
            version: 1.0.0
            skills:
              - name: allowed-tools
                source: local
                allowed-tools: "Bash(git:*) Bash(jq:*) Read Write"
            ---
            # Allowed Tools Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        var skill = config.skills().get(0);
        assertEquals(4, skill.allowedTools().size());
        assertTrue(skill.allowedTools().containsAll(java.util.List.of(
            "Bash(git:*)", "Bash(jq:*)", "Read", "Write")));
    }

    @Test
    void shouldLoadSkillFromTemplate() throws Exception {
        // 模拟官方规范中的标准模板
        var skillDir = tempDir.resolve("skills").resolve("pdf-processing");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: pdf-processing
            description: Extract PDF text, fill forms, merge files. Use when handling PDFs.
            license: Apache-2.0
            metadata:
              author: example-org
              version: "1.0"
            ---

            # PDF 处理

            ## 使用场景
            当需要对 PDF 文件进行操作时使用，例如：

            - 提取 PDF 文本或表格数据
            - 填写 PDF 表单
            - 合并多个 PDF 文件

            ## 提取文本
            - 使用 `pdfplumber` 提取文本型 PDF 内容
            - 扫描版 PDF 需配合 OCR 工具

            ## 填写表单
            - 读取 PDF 表单字段
            - 按输入数据填充并生成新文件
            """);

        writeAgentsMd("""
            ---
            name: template-test
            vendorKey: acme
            agentKey: template-test
            version: 1.0.0
            skills:
              - name: pdf-processing
                source: local
                version: "1.0.0"
                required: true
            ---
            # Template Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        var skill = config.skills().get(0);
        assertEquals("pdf-processing", skill.name());
        assertEquals("Extract PDF text, fill forms, merge files. Use when handling PDFs.", skill.description());
        assertTrue(skill.required());
    }

    // ========== 降级加载测试：不符合规范也能加载成功 ==========

    @Test
    void shouldDegradeGracefullyWithLongName() throws Exception {
        var longName = "a".repeat(65); // 超过 64 字符
        var skillDir = tempDir.resolve("skills").resolve(longName);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: %s
            description: Test with long name
            ---
            """.formatted(longName));

        writeAgentsMd("""
            ---
            name: name-length-test
            vendorKey: acme
            agentKey: name-length-test
            version: 1.0.0
            skills:
              - name: %s
                source: local
            ---
            # Test
            """.formatted(longName));

        // 降级加载：name 超长时警告但继续加载
        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals(1, config.skills().size());
        assertEquals(longName, config.skills().get(0).name());
    }

    @Test
    void shouldDegradeGracefullyWithUppercaseName() throws Exception {
        // 创建 skill 目录（使用原始名称）
        var skillDir = tempDir.resolve("skills").resolve("PDF-Processing");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: PDF-Processing
            description: Test with uppercase name
            ---
            """);

        writeAgentsMd("""
            ---
            name: uppercase-test
            vendorKey: acme
            agentKey: uppercase-test
            version: 1.0.0
            skills:
              - name: PDF-Processing
                source: local
            ---
            # Test
            """);

        // 降级加载：name 含大写时警告但继续加载
        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals(1, config.skills().size());
        assertEquals("PDF-Processing", config.skills().get(0).name());
    }

    @Test
    void shouldDegradeGracefullyWithHyphenPrefix() throws Exception {
        writeAgentsMd("""
            ---
            name: start-hyphen-test
            vendorKey: acme
            agentKey: start-hyphen-test
            version: 1.0.0
            skills:
              - name: -pdf-processing
                source: remote
            ---
            # Test
            """);

        // 降级加载：name 以连字符开头时警告但继续加载
        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals(1, config.skills().size());
        assertEquals("-pdf-processing", config.skills().get(0).name());
    }

    @Test
    void shouldDegradeGracefullyWithHyphenSuffix() throws Exception {
        writeAgentsMd("""
            ---
            name: end-hyphen-test
            vendorKey: acme
            agentKey: end-hyphen-test
            version: 1.0.0
            skills:
              - name: pdf-processing-
                source: remote
            ---
            # Test
            """);

        // 降级加载：name 以连字符结尾时警告但继续加载
        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals(1, config.skills().size());
        assertEquals("pdf-processing-", config.skills().get(0).name());
    }

    @Test
    void shouldDegradeGracefullyWithConsecutiveHyphens() throws Exception {
        writeAgentsMd("""
            ---
            name: consecutive-hyphen-test
            vendorKey: acme
            agentKey: consecutive-hyphen-test
            version: 1.0.0
            skills:
              - name: pdf--processing
                source: remote
            ---
            # Test
            """);

        // 降级加载：name 含连续连字符时警告但继续加载
        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals(1, config.skills().size());
        assertEquals("pdf--processing", config.skills().get(0).name());
    }

    @Test
    void shouldDegradeGracefullyWithEmptyDescription() throws Exception {
        var skillDir = tempDir.resolve("skills").resolve("empty-desc-test");
        Files.createDirectories(skillDir);
        // description 为空且没有 body 内容
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: empty-desc-test
            description:
            ---
            """);

        writeAgentsMd("""
            ---
            name: empty-desc-validation
            vendorKey: acme
            agentKey: empty-desc-validation
            version: 1.0.0
            skills:
              - name: empty-desc-test
                source: local
            ---
            # Test
            """);

        // 降级加载：description 为空时警告但继续加载
        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals(1, config.skills().size());
        assertEquals("empty-desc-test", config.skills().get(0).name());
        assertEquals("", config.skills().get(0).description());
    }

    @Test
    void shouldDegradeGracefullyWithMissingSkillMd() throws Exception {
        // 不创建 skill 目录
        writeAgentsMd("""
            ---
            name: missing-skill-test
            vendorKey: acme
            agentKey: missing-skill-test
            version: 1.0.0
            skills:
              - name: non-existent-skill
                source: local
            ---
            # Missing Skill Test
            """);

        // 降级加载：SKILL.md 不存在时警告但继续加载
        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals(1, config.skills().size());
        assertEquals("non-existent-skill", config.skills().get(0).name());
        assertEquals("", config.skills().get(0).description());
    }

    // ========== 官方规范 allowed-tools 测试 ==========

    @Test
    void shouldLoadOfficialMinimalFormat() throws Exception {
        // 官方最小格式示例
        var skillDir = tempDir.resolve("skills").resolve("skill-name");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: skill-name
            description: A description of what this skill does and when to use it.
            ---
            """);

        writeAgentsMd("""
            ---
            name: official-minimal-test
            vendorKey: acme
            agentKey: official-minimal-test
            version: 1.0.0
            skills:
              - name: skill-name
                source: local
            ---
            # Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        var skill = config.skills().get(0);
        assertEquals("skill-name", skill.name());
        assertEquals("A description of what this skill does and when to use it.", skill.description());
        assertEquals("local", skill.source());
        assertTrue(skill.allowedTools().isEmpty());
        assertEquals("", skill.license());
        assertEquals("", skill.compatibility());
        assertTrue(skill.metadata().isEmpty());
    }

    @Test
    void shouldLoadOfficialOptionalFieldsFormat() throws Exception {
        // 官方可选字段示例
        var skillDir = tempDir.resolve("skills").resolve("pdf-processing");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: pdf-processing
            description: Extract PDF text, fill forms, merge files. Use when handling PDFs.
            license: Apache-2.0
            metadata:
              author: example-org
              version: "1.0"
            ---
            """);

        writeAgentsMd("""
            ---
            name: official-optional-test
            vendorKey: acme
            agentKey: official-optional-test
            version: 1.0.0
            skills:
              - name: pdf-processing
                source: local
                license: Apache-2.0
                metadata:
                  author: example-org
                  version: "1.0"
            ---
            # Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        var skill = config.skills().get(0);
        assertEquals("pdf-processing", skill.name());
        assertEquals("Extract PDF text, fill forms, merge files. Use when handling PDFs.", skill.description());
        assertEquals("Apache-2.0", skill.license());
        assertEquals("example-org", skill.metadata().get("author"));
        assertEquals("1.0", skill.metadata().get("version"));
    }

    @Test
    void shouldLoadCompatibilityField() throws Exception {
        var skillDir = tempDir.resolve("skills").resolve("compat-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: compat-skill
            description: Skill with compatibility field
            compatibility: Requires git, docker, jq, and access to the internet
            ---
            """);

        writeAgentsMd("""
            ---
            name: compat-test
            vendorKey: acme
            agentKey: compat-test
            version: 1.0.0
            skills:
              - name: compat-skill
                source: local
                compatibility: Requires git, docker, jq, and access to the internet
            ---
            # Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        var skill = config.skills().get(0);
        assertEquals("Requires git, docker, jq, and access to the internet", skill.compatibility());
    }

    private void writeAgentsMd(String content) throws Exception {
        Files.writeString(tempDir.resolve("AGENTS.md"), content);
    }

    @Test
    void shouldParsePermissionMode() throws Exception {
        writeAgentsMd("""
            ---
            name: perm-test
            config:
              permission:
                mode: dont_ask
            ---
            # Perm Test
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals("dont_ask", config.runtimeConfig().permissionMode());
    }

    @Test
    void shouldDefaultPermissionModeWhenAbsent() throws Exception {
        writeAgentsMd("""
            ---
            name: perm-default
            ---
            # Perm Default
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        assertEquals("default", config.runtimeConfig().permissionMode());
    }

    @Test
    void shouldParseRequireConfirmationWithPermissionMode() throws Exception {
        writeAgentsMd("""
            ---
            name: perm-compat
            config:
              require_confirmation: true
              permission:
                mode: accept_edits
            ---
            # Perm Compat
            """);

        var config = new OafConfigLoader(props(tempDir)).load();
        assertTrue(config.runtimeConfig().requireConfirmation());
        assertEquals("accept_edits", config.runtimeConfig().permissionMode());
    }
}
