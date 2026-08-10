package io.agentmanager.framework.controller;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
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
    private JsonRpcTransportWrapper wrapper;

    private void mockWrapper() {
        when(a2aServer.getTransportWrapper(anyString(), any())).thenReturn(wrapper);
    }

    // ---------- 全量透传 ----------

    @Test
    void tasksGetShouldForwardToSdk() throws Exception {
        mockWrapper();
        when(wrapper.handleRequest(anyString(), anyMap(), any())).thenReturn(
            java.util.Map.of("jsonrpc", "2.0", "id", "1",
                "result", java.util.Map.of("kind", "task", "id", "s1", "history", java.util.List.of())));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"1","method":"tasks/get",
                     "params":{"id":"s1","historyLength":5}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.id").value("s1"));

        // 验证原样透传（不修改 body）
        verify(wrapper).handleRequest(anyString(), anyMap(), anyMap());
    }

    @Test
    void messageSendShouldForwardToSdk() throws Exception {
        mockWrapper();
        when(wrapper.handleRequest(anyString(), anyMap(), any())).thenReturn(
            java.util.Map.of("jsonrpc", "2.0", "id", "2",
                "result", java.util.Map.of("kind", "message", "role", "agent",
                    "parts", java.util.List.of(java.util.Map.of("kind", "text", "text", "welcome")))));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"2","method":"message/send",
                     "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hello"}]}}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.parts[0].text").value("welcome"));
    }

    // ---------- message/send 兼容转换 ----------

    @Test
    void messageSendShouldAddKindAndMessageIdAndPartKind() throws Exception {
        mockWrapper();
        when(wrapper.handleRequest(anyString(), anyMap(), any())).thenReturn(java.util.Map.of());

        // parts 无 kind、message 无 kind/messageId，应由 Controller 补全
        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"3","method":"message/send",
                     "params":{"message":{"role":"user","parts":[{"text":"hi"}]}}}
                    """))
            .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(wrapper).handleRequest(captor.capture(), anyMap(), any());
        @SuppressWarnings("unchecked")
        var req = (java.util.Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(captor.getValue(), java.util.Map.class);
        @SuppressWarnings("unchecked")
        var params = (java.util.Map<String, Object>) req.get("params");
        @SuppressWarnings("unchecked")
        var message = (java.util.Map<String, Object>) params.get("message");
        org.junit.jupiter.api.Assertions.assertEquals("message", message.get("kind"));
        org.junit.jupiter.api.Assertions.assertNotNull(message.get("messageId"));
        @SuppressWarnings("unchecked")
        var parts = (java.util.List<java.util.Map<String, Object>>) message.get("parts");
        org.junit.jupiter.api.Assertions.assertEquals("text", parts.get(0).get("kind"));
    }

    @Test
    void messageSendShouldPutUserIdIntoMessageMetadata() throws Exception {
        mockWrapper();
        when(wrapper.handleRequest(anyString(), anyMap(), any())).thenReturn(java.util.Map.of());

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"4","method":"message/send",
                     "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}]},
                               "userId":"alice"}}
                    """))
            .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(wrapper).handleRequest(captor.capture(), anyMap(), any());
        @SuppressWarnings("unchecked")
        var req = (java.util.Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(captor.getValue(), java.util.Map.class);
        @SuppressWarnings("unchecked")
        var params = (java.util.Map<String, Object>) req.get("params");
        @SuppressWarnings("unchecked")
        var message = (java.util.Map<String, Object>) params.get("message");
        @SuppressWarnings("unchecked")
        var metadata = (java.util.Map<String, Object>) message.get("metadata");
        org.junit.jupiter.api.Assertions.assertEquals("alice", metadata.get("userId"));
    }

    @Test
    void messageSendShouldSetBlockingTrue() throws Exception {
        mockWrapper();
        when(wrapper.handleRequest(anyString(), anyMap(), any())).thenReturn(java.util.Map.of());

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"5","method":"message/send",
                     "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}]}}}
                    """))
            .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(wrapper).handleRequest(captor.capture(), anyMap(), any());
        @SuppressWarnings("unchecked")
        var req = (java.util.Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(captor.getValue(), java.util.Map.class);
        @SuppressWarnings("unchecked")
        var params = (java.util.Map<String, Object>) req.get("params");
        @SuppressWarnings("unchecked")
        var config = (java.util.Map<String, Object>) params.get("configuration");
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, config.get("blocking"));
    }

    @Test
    void messageStreamShouldAlsoBeNormalized() throws Exception {
        mockWrapper();
        when(wrapper.handleRequest(anyString(), anyMap(), any())).thenReturn(
            reactor.core.publisher.Flux.empty());

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {"jsonrpc":"2.0","id":"6","method":"message/stream",
                     "params":{"message":{"role":"user","parts":[{"text":"hi"}]}}}
                    """))
            .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(wrapper).handleRequest(captor.capture(), anyMap(), any());
        org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().contains("\"kind\":\"message\""));
        org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().contains("\"kind\":\"text\""));
    }

    // ---------- 错误处理 ----------

    @Test
    void unknownMethodShouldBeForwardedToSdk() throws Exception {
        mockWrapper();
        when(wrapper.handleRequest(anyString(), anyMap(), any())).thenReturn(
            java.util.Map.of("jsonrpc", "2.0", "id", "7",
                "error", java.util.Map.of("code", -32601, "message", "Method not found")));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"7","method":"unknown/method","params":{}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void sdkErrorShouldPropagate() throws Exception {
        mockWrapper();
        when(wrapper.handleRequest(anyString(), anyMap(), any()))
            .thenThrow(new RuntimeException("sdk down"));

        // SDK 异常向上抛出（官方实现不捕获，由容器处理）
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
            mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"8","method":"tasks/get",
                     "params":{"id":"s1"}}
                    """)));
    }
}
