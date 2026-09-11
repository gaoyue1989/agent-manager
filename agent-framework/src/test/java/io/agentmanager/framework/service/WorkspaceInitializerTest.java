package io.agentmanager.framework.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceInitializerTest {

    @TempDir
    Path tempDir;

    private WorkspaceInitializer initializer;

    @BeforeEach
    void setUp() {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            tempDir.toString(),
            "",
            new AgentManagerProperties.            CleanupConfig(60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*,text/plain,text/markdown,text/csv,application/pdf", 5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files")
        );
        initializer = new WorkspaceInitializer(props);
    }

    private OafConfig config(List<String> tools, List<String> deniedTools,
                             List<OafConfig.SkillConfig> skills,
                             List<OafConfig.McpServerConfig> mcps,
                             List<OafConfig.SubAgentConfig> subAgents) {
        return new OafConfig(
            "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
            "Test agent", "@acme", "MIT",
            List.of("test"), "You are a test agent.",
            skills, mcps, subAgents, tools, deniedTools,
            new OafConfig.ModelConfig("openai", "gpt-4", ""),
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            new OafConfig.MemoryConfig("editable", Map.of()),
            Map.of()
        );
    }

    @Test
    void shouldCreateWorkspaceStructure() throws Exception {
        var ws = initializer.initialize(tempDir, config(List.of(), List.of(), List.of(), List.of(), List.of()));

        assertTrue(Files.exists(ws.resolve("AGENTS.md")));
        assertTrue(Files.exists(ws.resolve("tools.json")));
    }

    @Test
    void shouldGenerateAgentsMdWithFrontmatter() throws Exception {
        var ws = initializer.initialize(tempDir, config(List.of(), List.of(), List.of(), List.of(), List.of()));

        var content = Files.readString(ws.resolve("AGENTS.md"));
        assertTrue(content.contains("name: test-agent"));
        assertTrue(content.contains("provider: openai"));
        assertTrue(content.contains("temperature: 0.7"));
        assertTrue(content.contains("You are a test agent."));
    }

    @Test
    void shouldGenerateEmptyToolsJsonWhenNoDeniedTools() throws Exception {
        var ws = initializer.initialize(tempDir, config(List.of("Read", "Bash"), List.of(), List.of(), List.of(), List.of()));

        var node = new ObjectMapper().readTree(Files.readString(ws.resolve("tools.json")));
        // 不写 allow → 保留全部内置工具；无 deny → 空对象
        assertFalse(node.has("allow"));
        assertFalse(node.has("deny"));
    }

    @Test
    void shouldGenerateDenyListInToolsJson() throws Exception {
        var ws = initializer.initialize(tempDir,
            config(List.of(), List.of("write_file", "session_history"), List.of(), List.of(), List.of()));

        var node = new ObjectMapper().readTree(Files.readString(ws.resolve("tools.json")));
        assertTrue(node.has("deny"));
        assertEquals(2, node.get("deny").size());
        assertFalse(node.has("allow"));
    }

    @Test
    void shouldNotCopySkillsToWorkspace() throws Exception {
        // skills 动态加载改造后：OAF 包内技能不再复制到 workspace，
        // 由 AgentScopeConfig 注册 FileSystemSkillRepository（L2 市场层）动态扫描
        var skillDir = tempDir.resolve("skills").resolve("bash-tool");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: bash-tool\n---\n# Bash Tool\n");

        var skill = new OafConfig.SkillConfig("bash-tool", "local", "1.0.0", true, "Bash tool", List.of(),
            "", "", java.util.Map.of());
        var ws = initializer.initialize(tempDir, config(List.of(), List.of(), List.of(skill), List.of(), List.of()));

        // workspace 不再产出 skills 目录（L3 由框架按目录存在性自动跳过）
        assertFalse(Files.exists(ws.resolve("skills")));
    }

    @Test
    void shouldGenerateSubagents() throws Exception {
        var sub = new OafConfig.SubAgentConfig("openai", "researcher", "1.0.0", "researcher",
            List.of("research"), false, "");
        var ws = initializer.initialize(tempDir, config(List.of(), List.of(), List.of(), List.of(), List.of(sub)));

        var content = Files.readString(ws.resolve("subagents/researcher.md"));
        assertTrue(content.contains("description: researcher"));
        assertTrue(content.contains("delegations:"));
        assertTrue(content.contains("你是researcher。"));
    }

    @Test
    void shouldNotOverwriteExistingWorkspaceFiles() throws Exception {
        var ws = initializer.initialize(tempDir, config(List.of(), List.of(), List.of(), List.of(), List.of()));

        // 修改已生成的 AGENTS.md
        Files.writeString(ws.resolve("AGENTS.md"), "custom content");

        // 再次初始化不应覆盖
        var ws2 = initializer.initialize(tempDir, config(List.of(), List.of(), List.of(), List.of(), List.of()));
        assertEquals("custom content", Files.readString(ws2.resolve("AGENTS.md")));
    }
}
