package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpManager;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InfoController.class)
class InfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OafConfig oafConfig;

    @MockBean
    private AgentRuntimeService agentRuntime;

    @MockBean
    private McpManager mcpManager;

    @MockBean
    private List<Map<String, Object>> mcpConfigs;

    @Test
    void rootShouldReturnServiceInfo() throws Exception {
        when(oafConfig.name()).thenReturn("test-agent");
        when(oafConfig.slug()).thenReturn("acme/test-agent");
        when(oafConfig.description()).thenReturn("A test agent");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.tools()).thenReturn(List.of("Read", "Bash"));
        when(oafConfig.skills()).thenReturn(List.of());
        when(oafConfig.mcpServers()).thenReturn(List.of());
        when(oafConfig.subAgents()).thenReturn(List.of());

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agent").value("test-agent"))
            .andExpect(jsonPath("$.description").value("A test agent"))
            .andExpect(jsonPath("$.version").value("1.0.0"))
            .andExpect(jsonPath("$.protocols.a2a").value("1.0.0"))
            .andExpect(jsonPath("$.protocols.a2ui").value("v0.8"))
            .andExpect(jsonPath("$.protocols.oaf").value("v0.8.0"))
            .andExpect(jsonPath("$.engine").value("AgentScope Java 2.0"))
            .andExpect(jsonPath("$.endpoints.agent_card").value("/.well-known/agent-card.json"))
            .andExpect(jsonPath("$.endpoints.jsonrpc").value("/"))
            .andExpect(jsonPath("$.endpoints.health").value("/health"))
            .andExpect(jsonPath("$.endpoints.debug").value("/debug"));
    }

    @Test
    void metadataShouldReturnFullAgentInfo() throws Exception {
        when(oafConfig.name()).thenReturn("test-agent");
        when(oafConfig.slug()).thenReturn("acme/test-agent");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.description()).thenReturn("A test agent");
        when(oafConfig.skills()).thenReturn(List.of(
            new OafConfig.SkillConfig("code-review", "local", "1.0.0", false,
                "Code review skill", List.of("bash", "python"),
                "", "", java.util.Map.of())
        ));
        when(mcpManager.getMcpSummaries(mcpConfigs))
            .thenReturn(List.of(Map.of("server", "weather-service", "tool_count", 3)));

        mockMvc.perform(get("/metadata"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("test-agent"))
            .andExpect(jsonPath("$.slug").value("acme/test-agent"))
            .andExpect(jsonPath("$.skills[0].name").value("code-review"))
            .andExpect(jsonPath("$.skills[0].description").value("Code review skill"))
            .andExpect(jsonPath("$.mcp[0].tool_count").value(3));
    }

    @Test
    void systemPromptShouldReturnPrompts() throws Exception {
        when(agentRuntime.buildSystemPrompt()).thenReturn("Full system prompt with skills");
        when(oafConfig.systemPrompt()).thenReturn("Base prompt");

        mockMvc.perform(get("/system-prompt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.system_prompt").value("Full system prompt with skills"))
            .andExpect(jsonPath("$.base_prompt").value("Base prompt"));
    }
}
