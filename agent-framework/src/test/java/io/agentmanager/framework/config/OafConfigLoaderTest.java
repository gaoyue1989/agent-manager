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

    private void writeAgentsMd(String content) throws Exception {
        Files.writeString(tempDir.resolve("AGENTS.md"), content);
    }
}
