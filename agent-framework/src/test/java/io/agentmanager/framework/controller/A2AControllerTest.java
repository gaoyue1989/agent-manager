package io.agentmanager.framework.controller;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(A2AController.class)
class A2AControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentScopeA2aServer a2aServer;

    @MockBean
    private JsonRpcTransportWrapper transportWrapper;

    @BeforeEach
    void setUp() {
        when(a2aServer.getTransportWrapper(eq("JSONRPC"), eq(JsonRpcTransportWrapper.class)))
            .thenReturn(transportWrapper);
    }

    @Test
    void a2aRequestShouldDelegateToTransportWrapper() throws Exception {
        when(transportWrapper.handleRequest(any(), any(), any()))
            .thenReturn(Map.of("jsonrpc", "2.0", "result", "ok", "id", "1"));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"method\":\"message/send\",\"params\":{},\"id\":\"1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jsonrpc").value("2.0"))
            .andExpect(jsonPath("$.result").value("ok"));
    }

    @Test
    void a2aRequestShouldHandleError() throws Exception {
        when(transportWrapper.handleRequest(any(), any(), any()))
            .thenThrow(new RuntimeException("Internal error"));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"method\":\"message/send\",\"params\":{},\"id\":\"1\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.jsonrpc").value("2.0"))
            .andExpect(jsonPath("$.error.code").value(-32603));
    }
}
