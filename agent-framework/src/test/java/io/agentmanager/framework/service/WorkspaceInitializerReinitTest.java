package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;

/**
 * WorkspaceInitializer reinitialize（reload 强制重写）单测：
 * 覆盖旧文件、清理陈旧 subagent、不触碰非生成文件。
 */
class WorkspaceInitializerReinitTest {

    @TempDir
    Path tempDir;

    private WorkspaceInitializer initializer;

    @BeforeEach
    void setUp() {
        var props = new AgentManagerProperties(
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
        initializer = new WorkspaceInitializer(props);
    }

    private OafConfig config(String prompt, List<OafConfig.SubAgentConfig> subAgents) {
        return new OafConfig(
            "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
            "Test agent", "@acme", "MIT",
            List.of(), prompt,
            List.of(), List.of(), subAgents, List.of(), List.of(),
            null,
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            null, java.util.Map.of());
    }

    @Test
    void reinitializeShouldOverwriteExistingAgentsMd() throws Exception {
        var v1 = config("You are v1.", List.of());
        initializer.initialize(tempDir, v1);

        var v2 = config("You are v2.", List.of());
        initializer.reinitialize(tempDir, v2);

        var agentsMd = tempDir.resolve(".agentscope/workspace/AGENTS.md");
        var content = Files.readString(agentsMd);
        assertTrue(content.contains("You are v2."), "reinitialize 应覆盖旧 AGENTS.md");
        assertFalse(content.contains("You are v1."));
    }

    @Test
    void initializeShouldSkipExistingButReinitializeShouldNot() throws Exception {
        var v1 = config("You are v1.", List.of());
        initializer.initialize(tempDir, v1);

        // 启动语义不变：存在即跳过
        var v2 = config("You are v2.", List.of());
        initializer.initialize(tempDir, v2);
        assertTrue(Files.readString(tempDir.resolve(".agentscope/workspace/AGENTS.md")).contains("You are v1."));

        // reload 语义：强制覆盖
        initializer.reinitialize(tempDir, v2);
        assertTrue(Files.readString(tempDir.resolve(".agentscope/workspace/AGENTS.md")).contains("You are v2."));
    }

    @Test
    void reinitializeShouldRemoveStaleSubagents() throws Exception {
        var v1 = config("prompt", List.of(
            new OafConfig.SubAgentConfig("v", "alpha", "1.0", "role-a", List.of(), false, ""),
            new OafConfig.SubAgentConfig("v", "beta", "1.0", "role-b", List.of(), false, "")));
        initializer.initialize(tempDir, v1);
        var subagentsDir = tempDir.resolve(".agentscope/workspace/subagents");
        assertTrue(Files.exists(subagentsDir.resolve("alpha.md")));
        assertTrue(Files.exists(subagentsDir.resolve("beta.md")));

        // 新声明只含 alpha：beta 应被清理
        var v2 = config("prompt", List.of(
            new OafConfig.SubAgentConfig("v", "alpha", "1.0", "role-a-new", List.of(), false, "")));
        initializer.reinitialize(tempDir, v2);
        assertTrue(Files.exists(subagentsDir.resolve("alpha.md")));
        assertFalse(Files.exists(subagentsDir.resolve("beta.md")), "陈旧 subagent 应被删除");
        assertTrue(Files.readString(subagentsDir.resolve("alpha.md")).contains("role-a-new"));
    }

    @Test
    void reinitializeShouldNotTouchUserRuntimeFiles() throws Exception {
        initializer.initialize(tempDir, config("v1", List.of()));
        // 模拟用户运行时文件（L4/会话产物，不在生成清单内）
        var userFile = tempDir.resolve(".agentscope/workspace/notes/user-notes.md");
        Files.createDirectories(userFile.getParent());
        Files.writeString(userFile, "keep me");

        initializer.reinitialize(tempDir, config("v2", List.of()));

        assertTrue(Files.exists(userFile), "非生成文件不应被触碰");
        assertEquals("keep me", Files.readString(userFile));
    }
}
