package io.agentmanager.framework.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.agentmanager.framework.service.UiContextStore;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UI 交互上下文端点测试（4.7：POST /mcp/ui-context 覆盖式持久化）。
 */
class UiContextControllerTest {

    private MockMvc mockMvc;
    private UiContextStore uiContextStore;

    @BeforeEach
    void setUp() {
        uiContextStore = mock(UiContextStore.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UiContextController(uiContextStore)).build();
    }

    @Test
    void shouldPersistContentAndStructuredContext() throws Exception {
        mockMvc.perform(post("/mcp/ui-context")
                .contentType("application/json")
                .content("{\"sessionId\":\"tenant:s1\",\"content\":\"clock: 12:00\","
                    + "\"structuredContent\":{\"time\":\"12:00\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(true))
            .andExpect(jsonPath("$.sessionId").value("tenant:s1"));

        verify(uiContextStore).upsert(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldPersistStructuredOnly() throws Exception {
        mockMvc.perform(post("/mcp/ui-context")
                .contentType("application/json")
                .content("{\"sessionId\":\"tenant:s2\",\"structuredContent\":{\"time\":\"12:00\"}}"))
            .andExpect(status().isOk());
    }

    @Test
    void shouldRejectMissingSessionId() throws Exception {
        mockMvc.perform(post("/mcp/ui-context")
                .contentType("application/json")
                .content("{\"content\":\"clock: 12:00\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(uiContextStore);
    }

    @Test
    void shouldRejectEmptyPayload() throws Exception {
        mockMvc.perform(post("/mcp/ui-context")
                .contentType("application/json")
                .content("{\"sessionId\":\"tenant:s3\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectInvalidSessionIdFormat() throws Exception {
        mockMvc.perform(post("/mcp/ui-context")
                .contentType("application/json")
                .content("{\"sessionId\":\"no-separator\",\"content\":\"x\"}"))
            .andExpect(status().isBadRequest());
    }
}