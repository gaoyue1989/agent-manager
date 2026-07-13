package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.A2uiService;
import io.agentmanager.framework.service.AgentRuntimeService;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentCardController.class)
class AgentCardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OafConfig oafConfig;

    @MockBean
    private A2uiService a2uiService;

    @MockBean
    private AgentRuntimeService agentRuntime;

    @Test
    void agentCardShouldReturnCard() throws Exception {
        when(oafConfig.name()).thenReturn("test-agent");
        when(oafConfig.description()).thenReturn("A test agent");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.vendorKey()).thenReturn("acme");
        when(oafConfig.skills()).thenReturn(List.of());
        when(oafConfig.tags()).thenReturn(List.of("test"));
        when(a2uiService.getExtensionDeclaration())
            .thenReturn(Map.of("uri", "https://a2ui.org/a2a-extension/a2ui/v0.8", "params", Map.of()));

        mockMvc.perform(get("/.well-known/agent-card.json"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("test-agent"))
            .andExpect(jsonPath("$.description").value("A test agent"))
            .andExpect(jsonPath("$.version").value("1.0.0"))
            .andExpect(jsonPath("$.provider.organization").value("acme"))
            .andExpect(jsonPath("$.capabilities.streaming").value(true))
            .andExpect(jsonPath("$.defaultInputModes[0]").value("text"))
            .andExpect(jsonPath("$.defaultOutputModes[0]").value("text"))
            .andExpect(jsonPath("$.securitySchemes.bearer").exists());
    }
}
