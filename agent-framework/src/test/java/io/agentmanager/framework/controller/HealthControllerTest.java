package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.AgentRuntimeService;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OafConfig oafConfig;

    @MockBean
    private AgentRuntimeService agentRuntime;

    @MockBean
    private AgentManagerProperties props;

    @Test
    void healthShouldReturnOk() throws Exception {
        var llm = new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120);
        when(props.llm()).thenReturn(llm);
        when(oafConfig.name()).thenReturn("test-agent");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.slug()).thenReturn("acme/test-agent");
        when(agentRuntime.tenantPrefix()).thenReturn("acme:test-agent");

        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("healthy"))
            .andExpect(jsonPath("$.agent").value("test-agent"))
            .andExpect(jsonPath("$.version").value("1.0.0"))
            .andExpect(jsonPath("$.slug").value("acme/test-agent"))
            .andExpect(jsonPath("$.engine").value("AgentScope Java 2.0"))
            .andExpect(jsonPath("$.llm_configured").value(true))
            .andExpect(jsonPath("$.tenant_prefix").value("acme:test-agent"));
    }

    @Test
    void healthShouldShowLlmNotConfigured() throws Exception {
        var llm = new AgentManagerProperties.LLMConfig("", "", "", "openai", 0.7, 4096, 120);
        when(props.llm()).thenReturn(llm);
        when(oafConfig.name()).thenReturn("test");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.slug()).thenReturn("test");
        when(agentRuntime.tenantPrefix()).thenReturn("test");

        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.llm_configured").value(false));
    }
}
