package io.agentmanager.framework.controller;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.agentmanager.framework.service.AgentRuntimeService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(A2AController.class)
class A2AControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentRuntimeService agentRuntime;

    @Test
    void messageSendShouldReturnResponse() throws Exception {
        when(agentRuntime.invoke(any(), any(), any()))
            .thenReturn(Map.of("response", "welcome", "thread_id", "t1"));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"1","method":"message/send",
                     "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hello"}]}}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jsonrpc").value("2.0"))
            .andExpect(jsonPath("$.result.result.message.parts[0].text").value("welcome"));
    }

    @Test
    void unknownMethodShouldReturnError() throws Exception {
        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"2","method":"unknown/method","params":{}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void missingMethodShouldReturnError() throws Exception {
        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":\"3\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error.code").value(-32600));
    }

    @Test
    void messageSendShouldPassMetadataUserIdToRuntime() throws Exception {
        when(agentRuntime.invoke(any(), any(), any()))
            .thenReturn(Map.of("response", "ok", "thread_id", "t1"));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"4","method":"message/send",
                     "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}]},
                               "metadata":{"thread_id":"t1","userId":"alice"}}}
                    """))
            .andExpect(status().isOk());

        org.mockito.Mockito.verify(agentRuntime).invoke(
            org.mockito.ArgumentMatchers.eq("hi"),
            org.mockito.ArgumentMatchers.eq("t1"),
            org.mockito.ArgumentMatchers.eq("alice"));
    }

    @Test
    void messageSendShouldUseTaskIdWhenNoMetadataThreadId() throws Exception {
        when(agentRuntime.invoke(any(), any(), any()))
            .thenReturn(Map.of("response", "ok", "thread_id", "task-1"));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"5","method":"message/send",
                     "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}],
                                          "taskId":"task-1"}}}
                    """))
            .andExpect(status().isOk());

        org.mockito.Mockito.verify(agentRuntime).invoke(
            org.mockito.ArgumentMatchers.eq("hi"),
            org.mockito.ArgumentMatchers.eq("task-1"),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void messageSendShouldUseContextIdWhenPresent() throws Exception {
        when(agentRuntime.invoke(any(), any(), any()))
            .thenReturn(Map.of("response", "ok", "thread_id", "ctx-9"));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"6","method":"message/send",
                     "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}]},
                               "metadata":{"contextId":"ctx-9"}}}
                    """))
            .andExpect(status().isOk());

        org.mockito.Mockito.verify(agentRuntime).invoke(
            org.mockito.ArgumentMatchers.eq("hi"),
            org.mockito.ArgumentMatchers.eq("ctx-9"),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void messageSendShouldPassNullUserIdWhenNotProvided() throws Exception {
        when(agentRuntime.invoke(any(), any(), any()))
            .thenReturn(Map.of("response", "ok", "thread_id", "t1"));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"7","method":"message/send",
                     "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}]}}}
                    """))
            .andExpect(status().isOk());

        org.mockito.Mockito.verify(agentRuntime).invoke(
            org.mockito.ArgumentMatchers.eq("hi"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void missingParamsShouldReturnInvalidParamsError() throws Exception {
        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"8","method":"message/send","params":{}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void missingMessageInParamsShouldReturnInvalidParamsError() throws Exception {
        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"9","method":"message/send",
                     "params":{"message":null}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error.code").value(-32602));
    }
}
