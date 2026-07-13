package io.agentmanager.framework.config;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OafConfigLoaderTest {

    private OafConfigLoader loader;

    @BeforeEach
    void setUp() {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass"),
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
}
