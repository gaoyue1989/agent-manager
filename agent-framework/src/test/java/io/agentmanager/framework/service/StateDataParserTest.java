package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StateDataParserTest {

    private static final String STATE_DATA = """
        {"session_id":"s1","context":[
           {"id":"m1","role":"USER","content":[{"type":"text","text":"hello"}]},
           {"id":"m2","role":"ASSISTANT",
            "content":[{"type":"thinking","thinking":"internal"},{"type":"text","text":"hi"}]},
           {"id":"m3","role":"TOOL","content":[{"type":"tool_result","content":"result"}]}
         ]}
        """;

    @Test
    void findMessagesArrayShouldFindContext() {
        var arr = StateDataParser.findMessagesArray(STATE_DATA);
        assertNotNull(arr);
        assertTrue(arr.isArray());
        assertEquals(3, arr.size());
    }

    @Test
    void findMessagesArrayShouldReturnNullForBlank() {
        assertNull(StateDataParser.findMessagesArray(null));
        assertNull(StateDataParser.findMessagesArray("  "));
    }

    @Test
    void findMessagesArrayShouldSupportLegacyMessagesField() {
        var arr = StateDataParser.findMessagesArray("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}");
        assertNotNull(arr);
        assertEquals(1, arr.size());
    }

    @Test
    void extractContentTextShouldJoinTextBlocksAndSkipThinking() {
        var arr = StateDataParser.findMessagesArray(STATE_DATA);
        var assistant = arr.get(1);
        assertEquals("hi", StateDataParser.extractContentText(assistant));
    }

    @Test
    void extractContentTextShouldHandlePlainString() {
        var arr = StateDataParser.findMessagesArray("{\"context\":[{\"role\":\"user\",\"content\":\"plain\"}]}");
        assertEquals("plain", StateDataParser.extractContentText(arr.get(0)));
    }

    @Test
    void extractContentTextShouldHandlePartsFallback() {
        var arr = StateDataParser.findMessagesArray("{\"context\":[{\"role\":\"user\",\"parts\":[{\"text\":\"p1\"}]}]}");
        assertTrue(StateDataParser.extractContentText(arr.get(0)).contains("p1"));
    }

    @Test
    void extractRoleShouldLowercase() {
        var arr = StateDataParser.findMessagesArray(STATE_DATA);
        assertEquals("user", StateDataParser.extractRole(arr.get(0)));
        assertEquals("assistant", StateDataParser.extractRole(arr.get(1)));
    }

    @Test
    void toRoleContentListShouldSkipToolMessages() {
        var arr = StateDataParser.findMessagesArray(STATE_DATA);
        var list = StateDataParser.toRoleContentList(arr);
        assertEquals(2, list.size());
        assertEquals(Map.of("role", "user", "content", "hello"), list.get(0));
        assertEquals(Map.of("role", "assistant", "content", "hi"), list.get(1));
    }

    @Test
    void toRoleContentListShouldExtractToolCalls() {
        var stateData = """
            {"context":[{"role":"assistant","content":[
                {"type":"text","text":"let me run it"},
                {"type":"tool_use","id":"call_abc","name":"execute",
                 "input":{"command":"python3 -c \\"print(6*7)\\"","timeout":5}}
            ]}]}
            """;
        var arr = StateDataParser.findMessagesArray(stateData);
        var list = StateDataParser.toRoleContentList(arr);

        assertEquals(1, list.size());
        @SuppressWarnings("unchecked")
        var calls = (java.util.List<Map<String, Object>>) list.get(0).get("tool_calls");
        assertNotNull(calls);
        assertEquals(1, calls.size());
        assertEquals("call_abc", calls.get(0).get("id"));
        assertEquals("execute", calls.get(0).get("name"));
        assertTrue(calls.get(0).get("input").toString().contains("python3"));
    }

    @Test
    void toRoleContentListShouldHandleNull() {
        assertTrue(StateDataParser.toRoleContentList(null).isEmpty());
    }

    @Test
    void toRoleContentListShouldHandleEmpty() {
        assertTrue(StateDataParser.toRoleContentList(
            StateDataParser.findMessagesArray("{\"context\":[]}")).isEmpty());
    }

    // ========== 工具执行状态与结果输出（history 权威来源 = AgentState） ==========

    /** 含 tool_use + 配对 tool_result 的会话（形态取自线上 agent_state 实测） */
    private static final String TOOL_STATE_DATA = """
        {"session_id":"gw-3f20f08c5499","user_id":"webui-s1","context":[
           {"role":"USER","content":[{"type":"text","text":"发布服务"}]},
           {"role":"ASSISTANT","content":[
             {"type":"text","text":"开始执行"},
             {"type":"tool_use","id":"call_a","name":"list_images","input":{"x":1},"state":"allowed"}]},
           {"role":"TOOL","content":[
             {"type":"tool_result","id":"call_a","name":"list_images",
              "output":[{"type":"text","text":"images: [img1]"}],"state":"success"}]},
           {"role":"ASSISTANT","content":[
             {"type":"tool_use","id":"call_b","name":"publish_service","input":{"packageId":3},"state":"asking"}]}
         ]}
        """;

    @Test
    void toRoleContentListShouldMergeToolResultStateAndOutput() {
        var list = StateDataParser.toRoleContentList(
            StateDataParser.findMessagesArray(TOOL_STATE_DATA));

        @SuppressWarnings("unchecked")
        var call = ((java.util.List<Map<String, Object>>) list.get(1).get("tool_calls")).get(0);
        assertEquals("call_a", call.get("id"));
        // 结果状态优先于 tool_use 自身状态：allowed（已放行）→ success（已成功）
        assertEquals("success", call.get("state"));
        assertEquals("images: [img1]", call.get("output"));
        assertFalse(call.containsKey("output_truncated"), "未超限不应标记截断");
    }

    @Test
    void toolUseStateIsKeptWhenNoResultExists() {
        // 被 HITL 拦住的调用没有 tool_result：状态取 tool_use 自身的 asking
        var list = StateDataParser.toRoleContentList(
            StateDataParser.findMessagesArray(TOOL_STATE_DATA));

        @SuppressWarnings("unchecked")
        var call = ((java.util.List<Map<String, Object>>) list.get(2).get("tool_calls")).get(0);
        assertEquals("asking", call.get("state"));
        assertNull(call.get("output"));
    }

    private static String singleToolStateData(String output) {
        return """
            {"context":[
               {"role":"ASSISTANT","content":[{"type":"tool_use","id":"c1","name":"read_file","input":{}}]},
               {"role":"TOOL","content":[{"type":"tool_result","id":"c1","name":"read_file",
                 "output":[{"type":"text","text":"%s"}],"state":"success"}]}
             ]}
            """.formatted(output);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstToolCall(String stateData, int limit) {
        var list = StateDataParser.toRoleContentList(
            StateDataParser.findMessagesArray(stateData), limit);
        return ((java.util.List<Map<String, Object>>) list.get(0).get("tool_calls")).get(0);
    }

    @Test
    void toolOutputIsTruncatedAtConfiguredLimit() {
        var call = firstToolCall(singleToolStateData("x".repeat(50)), 10);

        assertEquals("x".repeat(10), call.get("output"));
        assertEquals(true, call.get("output_truncated"));
        assertEquals(50, call.get("output_full_length"), "完整长度供前端提示");
    }

    @Test
    void nonPositiveLimitDisablesTruncation() {
        var longOutput = "y".repeat(20);
        var call = firstToolCall(singleToolStateData(longOutput), 0);

        assertEquals(longOutput, call.get("output"));
        assertFalse(call.containsKey("output_truncated"));
    }

    @Test
    void extractOutputTextHandlesStringAndNonTextBlocks() {
        var arr = StateDataParser.findMessagesArray(singleToolStateData("zzzzz"));
        var result = arr.get(1).path("content").get(0);
        var resultOutput = result.get("output");

        // ContentBlock 数组：拼接 text 块
        assertEquals("zzzzz", StateDataParser.extractOutputText(resultOutput));
        // 纯字符串输出
        assertEquals("raw", StateDataParser.extractOutputText(
            com.fasterxml.jackson.databind.node.TextNode.valueOf("raw")));
        // null / 缺失 → 空串
        assertEquals("", StateDataParser.extractOutputText(null));
        // 无 text 块的数组：回落为紧凑 JSON，避免完全丢失结果形状
        var imageBlocks = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode()
            .add(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("type", "image").put("source", "s3://x"));
        assertTrue(StateDataParser.extractOutputText(imageBlocks).contains("image"));
    }

    @Test
    void extractAskingToolCallsShouldReadLastAssistantOnly() {
        var asking = StateDataParser.extractAskingToolCalls(
            StateDataParser.findMessagesArray(TOOL_STATE_DATA));

        assertEquals(1, asking.size(), "只有最后一条 assistant 的 asking 工具算挂起");
        assertEquals("call_b", asking.get(0).get("tool_call_id"));
        assertEquals("publish_service", asking.get(0).get("name"));
    }

    @Test
    void extractAskingToolCallsShouldCarryReplyIdFromMetadata() {
        var stateData = """
            {"context":[
               {"role":"ASSISTANT","content":[{"type":"tool_use","id":"c9","name":"delete_service","input":{},
                 "state":"asking"}],
                "metadata":{"agentscope_confirm_request_reply_id":"reply-42"}}
             ]}
            """;
        var asking = StateDataParser.extractAskingToolCalls(StateDataParser.findMessagesArray(stateData));

        assertEquals(1, asking.size());
        assertEquals("reply-42", asking.get(0).get("reply_id"), "恢复所需的 replyId 必须一并带出");
    }

    @Test
    void extractAskingToolCallsShouldBeEmptyAfterApproval() {
        // 批准后 tool_use 提升为 allowed 且出现 tool_result → 不再有挂起项
        var stateData = """
            {"context":[
               {"role":"ASSISTANT","content":[{"type":"tool_use","id":"c1","name":"publish_service","input":{},
                 "state":"allowed"}]},
               {"role":"TOOL","content":[{"type":"tool_result","id":"c1","name":"publish_service",
                 "output":[{"type":"text","text":"ok"}],"state":"success"}]}
             ]}
            """;
        assertTrue(StateDataParser.extractAskingToolCalls(
            StateDataParser.findMessagesArray(stateData)).isEmpty());
    }

    @Test
    void extractRuntimeIdentityShouldReturnGatewayKeys() {
        var identity = StateDataParser.extractRuntimeIdentity(TOOL_STATE_DATA);
        assertEquals("gw-3f20f08c5499", identity.get("session_id"));
        assertEquals("webui-s1", identity.get("user_id"), "userId 即 peer，恢复 RuntimeContext 需要它");
    }

    // ========== HITL 恢复 content 回填（fix: argument "content" is null） ==========

    /**
     * 提取出的 asking 条目必须是普通 Map（而非 JsonNode）：AgentStateReader 重建
     * ToolUseBlock 时用 instanceof Map 判断，JsonNode 会被丢成空 Map——恢复执行参数丢失。
     * 同时透传块上的 content 字符串，供重建块回填（ToolValidator 校验用）。
     */
    @Test
    void extractAskingToolCallsShouldReturnMapInputAndCarryContent() {
        var stateData = """
            {"context":[
               {"role":"ASSISTANT","content":[{"type":"tool_use","id":"c1","name":"publish_service",
                 "input":{"packageId":166,"name":"demo"},"content":"{\\"packageId\\":166,\\"name\\":\\"demo\\"}",
                 "state":"asking"}],
                "metadata":{"agentscope_confirm_request_reply_id":"reply-1"}}
             ]}
            """;
        var asking = StateDataParser.extractAskingToolCalls(
            StateDataParser.findMessagesArray(stateData));

        assertEquals(1, asking.size());
        var call = asking.get(0);
        assertTrue(call.get("input") instanceof Map<?, ?>, "input 必须是 Map 而非 JsonNode");
        @SuppressWarnings("unchecked")
        var input = (Map<String, Object>) call.get("input");
        assertEquals(166, input.get("packageId"), "重建块需要完好参数");
        assertEquals("demo", input.get("name"));
        assertEquals("{\"packageId\":166,\"name\":\"demo\"}", call.get("content"),
            "块上的 content 必须透传，供重建 ToolUseBlock 回填");
        assertEquals("reply-1", call.get("reply_id"));
    }

    /** content 缺失/null（老数据形态）时不含 content 键，重建侧回落 input 序列化 */
    @Test
    void extractAskingToolCallsShouldOmitContentWhenAbsent() {
        var stateData = """
            {"context":[
               {"role":"ASSISTANT","content":[{"type":"tool_use","id":"c1","name":"publish_service",
                 "input":{"packageId":166},"state":"asking"}]}
             ]}
            """;
        var asking = StateDataParser.extractAskingToolCalls(
            StateDataParser.findMessagesArray(stateData));

        assertEquals(1, asking.size());
        assertFalse(asking.get(0).containsKey("content"));
        assertTrue(asking.get(0).get("input") instanceof Map<?, ?>);
        assertEquals(166, ((Map<?, ?>) asking.get(0).get("input")).get("packageId"));
    }

    /** toJsonString：Map → JSON 字符串；null → null（fail-soft 契约） */
    @Test
    void toJsonStringShouldSerializeMapOrNull() {
        assertEquals("{\"packageId\":166}",
            StateDataParser.toJsonString(Map.of("packageId", 166)));
        assertNull(StateDataParser.toJsonString(null));
    }

    @Test
    void extractRuntimeIdentityShouldBeEmptyForBlankInput() {
        assertEquals("", StateDataParser.extractRuntimeIdentity(null).get("session_id"));
        assertEquals("", StateDataParser.extractRuntimeIdentity("").get("user_id"));
    }

    // ========== 敏感值遮掩（P1-1：output 落 history 前的文本级遮掩） ==========

    @Test
    void maskShouldRedactSensitiveKeyValuePairs() {
        // 实测线上真实形态：get_service_status 的 output 里 LLM_API_KEY 明文
        assertEquals("LLM_API_KEY: " + StateDataParser.MASKED,
            StateDataParser.maskSensitiveText("LLM_API_KEY: tp-c9dgn7tl95bl9d2lptfdwq4qsozazbrfit3vehndv0mmyw13"));
        assertEquals("\"CHECKPOINT_PASSWORD\": " + StateDataParser.MASKED,
            StateDataParser.maskSensitiveText("\"CHECKPOINT_PASSWORD\": \"OafPlatform2026\""));
        assertEquals("api_key = " + StateDataParser.MASKED,
            StateDataParser.maskSensitiveText("api_key = sk-abc123XYZdef456"));
        // 值后的标点不属于凭据字符集，允许残留（不含敏感信息）
        assertEquals("数据库密码： " + StateDataParser.MASKED + "!",
            StateDataParser.maskSensitiveText("数据库密码： MyS3cret!"));
    }

    @Test
    void maskShouldRedactPlatformTokensAndBearer() {
        // 平台 token 形态（无键名提示也要遮）
        assertEquals(StateDataParser.MASKED,
            StateDataParser.maskSensitiveText("tp-c9dgn7tl95bl9d2lptfdwq4qsozazbrfit3vehndv0mmyw13"));
        // Bearer 凭据保留前缀
        assertEquals("Bearer " + StateDataParser.MASKED,
            StateDataParser.maskSensitiveText("Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"));
    }

    @Test
    void maskShouldKeepUsageNumbersAndNormalText() {
        // 纯数字（token 用量统计）不遮：避免破坏 usage 类正常输出
        var usage = "totalTokens: 21666, inputTokens: 21443";
        assertEquals(usage, StateDataParser.maskSensitiveText(usage));
        // 普通文本与短值不动
        assertEquals("images: [img1]", StateDataParser.maskSensitiveText("images: [img1]"));
        assertEquals("k8sName: oaf-demo", StateDataParser.maskSensitiveText("k8sName: oaf-demo"));
        assertEquals("", StateDataParser.maskSensitiveText(""));
        assertNull(StateDataParser.maskSensitiveText(null));
    }

    @Test
    void toolOutputIsMaskedBeforeTruncation() {
        // 遮掩先于截断：即使截断窗口恰好覆盖敏感片段，落库内容也是遮掩后的
        var secret = "LLM_API_KEY: tp-abcdefghijklmnop1234567890abcdef";
        var stateData = """
            {"context":[
               {"role":"ASSISTANT","content":[{"type":"tool_use","id":"c1","name":"get_service_status","input":{}}]},
               {"role":"TOOL","content":[{"type":"tool_result","id":"c1","name":"get_service_status",
                 "output":[{"type":"text","text":"%s ...tail"}],"state":"success"}]}
             ]}
            """.formatted(secret);

        var call = firstToolCall(stateData, 30);

        var out = (String) call.get("output");
        assertFalse(out.contains("tp-abcdefghijklmnop"), "截断后的内容不得含明文 token");
        assertTrue(out.contains(StateDataParser.MASKED));
    }
}
