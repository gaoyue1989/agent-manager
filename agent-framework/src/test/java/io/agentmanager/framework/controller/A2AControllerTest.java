package io.agentmanager.framework.controller;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import io.agentmanager.framework.service.SessionUserStore;

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
    private SessionUserStore sessionUserStore;

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

        // SDK 异常由 GlobalExceptionHandler 兜底，返回 500 JSON
        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"8","method":"tasks/get",
                     "params":{"id":"s1"}}
                    """))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("internal_error"));
    }

    @Test
    void tasksCancelShouldForwardToSdk() throws Exception {
        mockWrapper();
        when(wrapper.handleRequest(anyString(), anyMap(), any())).thenReturn(
            java.util.Map.of("jsonrpc", "2.0", "id", "9",
                "result", java.util.Map.of("kind", "task", "id", "s9", "state", "canceled")));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"9","method":"tasks/cancel",
                     "params":{"id":"s9"}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.state").value("canceled"));

        org.mockito.ArgumentCaptor<String> bodyCap =
            org.mockito.ArgumentCaptor.forClass(String.class);
        verify(wrapper).handleRequest(bodyCap.capture(), anyMap(), anyMap());
        org.junit.jupiter.api.Assertions.assertTrue(
            bodyCap.getValue().contains("\"method\":\"tasks/cancel\""),
            "tasks/cancel 必须原样透传: " + bodyCap.getValue());
    }

    @Test
    void tasksResubscribeShouldForwardToSdk() throws Exception {
        mockWrapper();
        // resubscribe 返回 Flux（流式），controller 应转 SSE；
        // JSONRPCResponse 为密封类，用许可子类构造真实实例
        var task = new io.a2a.spec.Task("s10", "ctx-10",
            new io.a2a.spec.TaskStatus(io.a2a.spec.TaskState.SUBMITTED),
            java.util.List.of(), java.util.List.of(), java.util.Map.of());
        when(wrapper.handleRequest(anyString(), anyMap(), any()))
            .thenReturn(reactor.core.publisher.Flux.just(
                new io.a2a.spec.CancelTaskResponse("2.0", "10", task, null)));

        mockMvc.perform(post("/")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jsonrpc":"2.0","id":"10","method":"tasks/resubscribe",
                     "params":{"id":"s10"}}
                    """))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(
                org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE));

        org.mockito.ArgumentCaptor<String> bodyCap =
            org.mockito.ArgumentCaptor.forClass(String.class);
        verify(wrapper).handleRequest(bodyCap.capture(), anyMap(), anyMap());
        org.junit.jupiter.api.Assertions.assertTrue(
            bodyCap.getValue().contains("tasks/resubscribe"),
            "tasks/resubscribe 必须原样透传: " + bodyCap.getValue());
    }

    // ---------- 本轮 skills 列表（session-skill-list-design §6） ----------

    private java.util.Map<String, Object> forwardedRequest(String body) throws Exception {
        mockWrapper();
        when(wrapper.handleRequest(anyString(), anyMap(), any())).thenReturn(java.util.Map.of());
        mockMvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        // 同一用例内可多次调用：取最后一次转发的请求体
        verify(wrapper, org.mockito.Mockito.atLeastOnce()).handleRequest(captor.capture(), anyMap(), any());
        var forwarded = captor.getAllValues().get(captor.getAllValues().size() - 1);
        return (java.util.Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(forwarded, java.util.Map.class);
    }

    @Test
    void messageSendShouldTranslateSkillsIntoGroupedMetadata() throws Exception {
        // SDK 的 A2A→Msg 转换只认「按 msg id 分组」的 metadata：
        // metadata[msgId] 必须是 Map，part 以 _agentscope_msg_id 标组
        var req = forwardedRequest("""
            {"jsonrpc":"2.0","id":"20","method":"message/send",
             "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}],
               "metadata":{"sessionId":"a2a-sk1","userId":"alice","skills":["a","b"]}}}}
            """);
        var params = (java.util.Map<String, Object>) req.get("params");
        var message = (java.util.Map<String, Object>) params.get("message");
        var meta = (java.util.Map<String, Object>) message.get("metadata");
        var msgId = String.valueOf(message.get("messageId"));
        var grouped = (java.util.Map<String, Object>) meta.get(msgId);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("a", "b"), grouped.get("skills"),
            "skills 按 msg id 分组（SDK 读取约定）");
        org.junit.jupiter.api.Assertions.assertFalse(meta.containsKey("skills"),
            "顶层 skills 键被移除（避免与分组键混淆）");
        var parts = (java.util.List<?>) message.get("parts");
        var partMeta = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) parts.get(0)).get("metadata");
        org.junit.jupiter.api.Assertions.assertEquals(msgId, partMeta.get("_agentscope_msg_id"));
    }

    @Test
    void messageSendShouldNormalizeTopLevelAndEmptySkills() throws Exception {
        var req = forwardedRequest("""
            {"jsonrpc":"2.0","id":"21","method":"message/send",
             "params":{"sessionId":"a2a-sk2","userId":"alice","skills":["x"],
               "message":{"role":"user","parts":[{"kind":"text","text":"hi"}]}}}
            """);
        var params = (java.util.Map<String, Object>) req.get("params");
        var message = (java.util.Map<String, Object>) params.get("message");
        var meta = (java.util.Map<String, Object>) message.get("metadata");
        var grouped = (java.util.Map<String, Object>) meta.get(String.valueOf(message.get("messageId")));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("x"), grouped.get("skills"));

        // 显式空列表：同样分组（空数组 = 无技能）
        var emptyReq = forwardedRequest("""
            {"jsonrpc":"2.0","id":"22","method":"message/send",
             "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}],
               "metadata":{"sessionId":"a2a-sk3","userId":"alice","skills":[]}}}}
            """);
        var emptyParams = (java.util.Map<String, Object>) emptyReq.get("params");
        var emptyMessage = (java.util.Map<String, Object>) emptyParams.get("message");
        var emptyMeta = (java.util.Map<String, Object>) emptyMessage.get("metadata");
        var emptyGrouped = (java.util.Map<String, Object>) emptyMeta.get(
            String.valueOf(emptyMessage.get("messageId")));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), emptyGrouped.get("skills"));
    }

    @Test
    void messageSendShouldNormalizeLegacyMetadataSkills() throws Exception {
        // 旧格式：skills 藏在 params.metadata（与 thread_id 同层），须归一为
        // 「按 msg id 分组」形态 SDK 才消费（修复前顶层回填永不被读取 → 白名单静默失效）
        var req = forwardedRequest("""
            {"jsonrpc":"2.0","id":"23","method":"message/send",
             "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}]},
               "metadata":{"thread_id":"a2a-sk4","skills":["a","b"]}}}
            """);
        var params = (java.util.Map<String, Object>) req.get("params");
        var message = (java.util.Map<String, Object>) params.get("message");
        var meta = (java.util.Map<String, Object>) message.get("metadata");
        var msgId = String.valueOf(message.get("messageId"));
        var grouped = (java.util.Map<String, Object>) meta.get(msgId);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("a", "b"), grouped.get("skills"),
            "旧格式 params.metadata.skills 归一为分组形态");
        org.junit.jupiter.api.Assertions.assertFalse(meta.containsKey("skills"),
            "顶层不得残留 skills 键（SDK 不消费顶层键）");
        var parts = (java.util.List<?>) message.get("parts");
        var partMeta = (java.util.Map<String, Object>) ((java.util.Map<String, Object>) parts.get(0)).get("metadata");
        org.junit.jupiter.api.Assertions.assertEquals(msgId, partMeta.get("_agentscope_msg_id"));
        org.junit.jupiter.api.Assertions.assertEquals("a2a-sk4", meta.get("sessionId"),
            "第 6 步 thread_id 兼容仍生效");

        // 显式空列表同样归一（分组空数组 = 无技能）
        var emptyReq = forwardedRequest("""
            {"jsonrpc":"2.0","id":"24","method":"message/send",
             "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}]},
               "metadata":{"thread_id":"a2a-sk5","skills":[]}}}
            """);
        var emptyParams = (java.util.Map<String, Object>) emptyReq.get("params");
        var emptyMessage = (java.util.Map<String, Object>) emptyParams.get("message");
        var emptyMeta = (java.util.Map<String, Object>) emptyMessage.get("metadata");
        var emptyGrouped = (java.util.Map<String, Object>) emptyMeta.get(
            String.valueOf(emptyMessage.get("messageId")));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), emptyGrouped.get("skills"));
    }

    @Test
    void messageSendShouldNormalizeLegacySkillsEvenWhenSessionIdPresent() throws Exception {
        // 混用：message.metadata 已含 sessionId 时，旧顶层回填受第 6 步
        // 「!meta.containsKey(sessionId)」门控阻断（缺陷下 skills 完全读不到）；
        // 归一取值在 5.1 步完成，不再受该门控影响
        var req = forwardedRequest("""
            {"jsonrpc":"2.0","id":"25","method":"message/send",
             "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}],
               "metadata":{"sessionId":"a2a-sk6"}},
               "metadata":{"thread_id":"a2a-ignored","skills":["legacy-skill"]}}}
            """);
        var params = (java.util.Map<String, Object>) req.get("params");
        var message = (java.util.Map<String, Object>) params.get("message");
        var meta = (java.util.Map<String, Object>) message.get("metadata");
        var msgId = String.valueOf(message.get("messageId"));
        var grouped = (java.util.Map<String, Object>) meta.get(msgId);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("legacy-skill"), grouped.get("skills"));
        org.junit.jupiter.api.Assertions.assertEquals("a2a-sk6", meta.get("sessionId"),
            "sessionId 仍取 message.metadata，thread_id 不覆盖");
    }

    @Test
    void messageSendShouldPreferMessageMetadataSkillsOverLegacyParams() throws Exception {
        // 优先级：message.metadata.skills > params.metadata.skills（first-non-null-wins 链）
        var req = forwardedRequest("""
            {"jsonrpc":"2.0","id":"26","method":"message/send",
             "params":{"message":{"role":"user","parts":[{"kind":"text","text":"hi"}],
               "metadata":{"sessionId":"a2a-sk7","skills":["from-message"]}},
               "metadata":{"thread_id":"a2a-sk7","skills":["from-legacy"]}}}
            """);
        var params = (java.util.Map<String, Object>) req.get("params");
        var message = (java.util.Map<String, Object>) params.get("message");
        var meta = (java.util.Map<String, Object>) message.get("metadata");
        var msgId = String.valueOf(message.get("messageId"));
        var grouped = (java.util.Map<String, Object>) meta.get(msgId);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("from-message"), grouped.get("skills"),
            "message.metadata.skills 优先于旧格式 params.metadata.skills");
    }
}
