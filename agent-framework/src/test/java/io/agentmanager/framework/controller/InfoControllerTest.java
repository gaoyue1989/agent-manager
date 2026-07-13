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

    @Test
    void rootShouldReturnServiceInfo() throws Exception {
        when(oafConfig.name()).thenReturn("test-agent");
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
    void systemPromptShouldReturnPrompts() throws Exception {
        when(agentRuntime.buildSystemPrompt()).thenReturn("Full system prompt with skills");
        when(oafConfig.systemPrompt()).thenReturn("Base prompt");

        mockMvc.perform(get("/system-prompt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.system_prompt").value("Full system prompt with skills"))
            .andExpect(jsonPath("$.base_prompt").value("Base prompt"));
    }
}
