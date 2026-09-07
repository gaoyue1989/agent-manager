package io.agentmanager.framework.controller;

import org.junit.jupiter.api.Test;

import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.URLSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 序列化词条单测（UT-22）：ToolResultDataDeltaEvent 的 Base64Source/URLSource 两分支。
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
}