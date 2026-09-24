package io.agentmanager.framework.controller;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.agentmanager.framework.service.SessionEventStore;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.URLSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 序列化词条单测（UT-22）：ToolResultDataDeltaEvent 的 Base64Source/URLSource 两分支；
 * A1 扩充：withReplyId 注入算法的字节一致性 oracle + toSseFrame 帧构造。
 */
class AgentEventSseSerializerTest {

    @Test
    void toolResultDataDeltaShouldSerializeBase64Source() {
        var dataBlock = DataBlock.builder()
            .source(Base64Source.builder()
                .mediaType("image/png")
                .data("aGVsbG8=")
                .build())
            .build();
        var ev = new ToolResultDataDeltaEvent("reply-1", "call-1", "gen_chart", dataBlock);

        String json = AgentEventSseSerializer.payload(ev);
        assertTrue(json.contains("\"TOOL_RESULT_DATA_DELTA\""), "type 词条（大写，Channel 词表）: " + json);
        assertTrue(json.contains("\"tool_call_id\":\"call-1\""), "tool_call_id: " + json);
        assertTrue(json.contains("\"tool_call_name\":\"gen_chart\""), "tool_call_name: " + json);
        assertTrue(json.contains("\"media_type\":\"image/png\""), "media_type: " + json);
        assertTrue(json.contains("\"data\":\"aGVsbG8=\""), "base64 data 内联: " + json);
    }

    @Test
    void toolResultDataDeltaShouldSerializeUrlSource() {
        var dataBlock = DataBlock.builder()
            .source(URLSource.builder()
                .mimeType("application/pdf")
                .url("http://localhost:8100/files/x")
                .build())
            .build();
        var ev = new ToolResultDataDeltaEvent("reply-2", "call-2", "gen_pdf", dataBlock);

        String json = AgentEventSseSerializer.payload(ev);
        assertTrue(json.contains("\"media_type\":\"application/pdf\""), "media_type: " + json);
        assertTrue(json.contains("\"url\":\"http://localhost:8100/files/x\""), "url 引用: " + json);
        assertTrue(!json.contains("\"data\""), "URLSource 不应输出 data: " + json);
    }

    // ===== withReplyId：与收口前读端算法的字节一致性 oracle（设计 §3.4-1） =====

    /**
     * 参照实现：收口前 SessionEventBus / SessionEventTailer 私有 toSSE 的注入算法原样拷贝。
     * withReplyId 与它在任意输入上必须逐字节相等——这是 A1 合并前唯一的「不可回滚点」。
     */
    private static String legacyInject(String payload, String replyId) {
        if (replyId == null || replyId.isBlank()) {
            return payload;
        }
        var mapper = new ObjectMapper();
        try {
            var node = mapper.readTree(payload);
            if (node != null && node.isObject() && !node.has("replyId")) {
                ((ObjectNode) node).put("replyId", replyId);
                return mapper.writeValueAsString(node);
            }
            return payload;
        } catch (Exception e) {
            return payload;
        }
    }

    @Test
    void withReplyIdInjectsAtEndLikeLegacyReader() {
        String out = AgentEventSseSerializer.withReplyId(
            "{\"type\":\"AGENT_END\"}", "rid-1");
        assertEquals("{\"type\":\"AGENT_END\",\"replyId\":\"rid-1\"}", out,
            "注入追加在 JSON 末尾（ObjectNode 保持插入序）");
        assertEquals(legacyInject("{\"type\":\"AGENT_END\"}", "rid-1"), out);
    }

    @Test
    void withReplyIdShortCircuitsWhenPayloadAlreadyHasReplyId() {
        String payload = "{\"type\":\"TEXT_BLOCK_DELTA\",\"replyId\":\"rid-2\",\"delta\":\"hi\"}";
        // 原引用返回（短路、零重序列化）——ui 元数据覆写路径依赖这一点防二次注入
        assertSame(payload, AgentEventSseSerializer.withReplyId(payload, "rid-x"));
    }

    @Test
    void withReplyIdReturnsOriginalWhenReplyIdNullOrBlank() {
        assertEquals("{\"type\":\"AGENT_END\"}",
            AgentEventSseSerializer.withReplyId("{\"type\":\"AGENT_END\"}", null));
        assertEquals("{\"type\":\"AGENT_END\"}",
            AgentEventSseSerializer.withReplyId("{\"type\":\"AGENT_END\"}", "  "));
    }

    @Test
    void withReplyIdReturnsOriginalForNonObjectPayload() {
        assertEquals("\"plain text\"",
            AgentEventSseSerializer.withReplyId("\"plain text\"", "rid-3"));
        assertEquals("[1,2]",
            AgentEventSseSerializer.withReplyId("[1,2]", "rid-3"));
    }

    @Test
    void withReplyIdReturnsOriginalForMalformedJson() {
        assertEquals("not-json{",
            AgentEventSseSerializer.withReplyId("not-json{", "rid-4"));
    }

    @Test
    void withReplyIdMustInjectEvenWhenLiteralReplyIdAppearsInValues() {
        // 顶层无 replyId、但字符串值里出现字面量 "replyId"（工具入参写 JSON 源码是常态）：
        // 必须仍注入——这正是不能做 contains() 子串捷径的反例（设计 §3.3-4）
        String payload = "{\"type\":\"TOOL_CALL_DELTA\",\"delta\":\"{\\\"replyId\\\":\\\"fake\\\"}\"}";
        assertFalse(payload.contains("\"replyId\":\"rid-5\""));
        String out = AgentEventSseSerializer.withReplyId(payload, "rid-5");
        assertTrue(out.endsWith("\"replyId\":\"rid-5\"}"),
            "顶层键判定（非子串）应允许注入: " + out);
        assertEquals(legacyInject(payload, "rid-5"), out);
    }

    @Test
    void withReplyIdKeepsSnakeCaseReplyIdUntouched() {
        // HITL 帧：permission_ask 只带 snake_case reply_id，读端会再注入 camelCase replyId
        // ——双字段现状必须原样保留（设计 §3.3-6）
        String payload = "{\"type\":\"permission_ask\",\"reply_id\":\"rid-6\"}";
        String out = AgentEventSseSerializer.withReplyId(payload, "rid-6");
        assertEquals("{\"type\":\"permission_ask\",\"reply_id\":\"rid-6\",\"replyId\":\"rid-6\"}", out);
        assertEquals(legacyInject(payload, "rid-6"), out);
    }

    // ===== toSseFrame：EnvelopedEvent → SSE 帧 =====

    @Test
    void toSseFramePassesThroughNewRowsAndSetsSeqAsId() {
        // 新形态行：写路径已注入 replyId → data 原串透传（零重序列化）
        var row = new SessionEventStore.EnvelopedEvent(9, "AGENT_END",
            "{\"type\":\"AGENT_END\",\"replyId\":\"rid-7\"}", "rid-7");
        var frame = AgentEventSseSerializer.toSseFrame(row);
        assertEquals("9", frame.id());
        assertEquals("{\"type\":\"AGENT_END\",\"replyId\":\"rid-7\"}", frame.data());
    }

    @Test
    void toSseFrameInjectsReplyIdForLegacyRows() {
        // 存量形态行（升级前落库、p 内无 replyId）：读端兜底注入，输出与收口前读端逐字节一致
        var row = new SessionEventStore.EnvelopedEvent(3, "file_ready",
            "{\"type\":\"file_ready\",\"file_id\":\"f-1\"}", "rid-8");
        var frame = AgentEventSseSerializer.toSseFrame(row);
        assertEquals("3", frame.id());
        assertEquals(legacyInject(row.payload(), row.replyId()), frame.data());
        assertTrue(frame.data().endsWith("\"replyId\":\"rid-8\"}"));
    }
}