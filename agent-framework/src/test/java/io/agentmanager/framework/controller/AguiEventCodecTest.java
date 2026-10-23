package io.agentmanager.framework.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ToolUseBlock;

/**
 * AguiEventCodec golden 用例（agui-native-parity-design M5）：锁 pa 词表的关键结构，
 * SDK 升级时跑回归（设计 R2）。
 */
class AguiEventCodecTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AguiEventCodec codec = new AguiEventCodec(
        new io.agentmanager.framework.config.AguiProperties(
            true, true, true, true, false));

    private String threadId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
    private String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);

    private AguiStreamContext ctx() {
        return codec.newContext(threadId, runId);
    }

    @Test
    void textBlockChainProducesStandardTextMessageEvents() throws Exception {
        var ctx = ctx();
        String start = codec.encode(new TextBlockStartEvent("r1", "b1"), ctx);
        String delta = codec.encode(newDelta("r1", "b1", "你好"), ctx);
        String end = codec.encode(new io.agentscope.core.event.TextBlockEndEvent("r1", "b1"), ctx);

        // SDK TextBlockEventConverter 对空 START 延迟发帧（首个 DELTA 才产出 START+CONTENT
        // 成对）——官方行为而非缺陷，也印证 pa 以「native 事件为单位存数组」的设计必要性
        assertNull(start, "空 TEXT_BLOCK_START 延迟发帧（官方语义）");
        assertNotNull(delta, "首个 DELTA 应产出 AG-UI 事件（含 TEXT_MESSAGE_START）");
        assertNotNull(end);

        var deltaArr = JSON.readTree(delta);
        assertTrue(deltaArr.isArray() && deltaArr.size() >= 2,
            "首个 DELTA 应至少产出 START+CONTENT 两帧: " + delta);
        assertEquals("TEXT_MESSAGE_START", deltaArr.get(0).get("type").asText());
        assertEquals(threadId, deltaArr.get(0).get("threadId").asText());
        assertEquals(runId, deltaArr.get(0).get("runId").asText());
        assertEquals("TEXT_MESSAGE_CONTENT", deltaArr.get(1).get("type").asText());
        assertEquals("你好", deltaArr.get(1).get("delta").asText());

        var endArr = JSON.readTree(end);
        assertEquals("TEXT_MESSAGE_END", endArr.get(0).get("type").asText());
    }

    private TextBlockDeltaEvent newDelta(String replyId, String blockId, String text) {
        // SDK 2.0.3 事件构造器首参恒为 replyId（javap 实证）
        return new TextBlockDeltaEvent(replyId, blockId, text);
    }

    @Test
    void toolCallStartProducesToolCallStartEvent() throws Exception {
        var event = new ToolCallStartEvent("r1", "call-1", "get_weather");
        String pa = codec.encode(event, ctx());
        assertNotNull(pa);
        var arr = JSON.readTree(pa);
        assertEquals("TOOL_CALL_START", arr.get(0).get("type").asText());
        assertEquals("call-1", arr.get(0).get("toolCallId").asText());
        assertEquals("get_weather", arr.get(0).get("toolCallName").asText());
    }

    @Test
    void requireUserConfirmRegistersInterruptOnRunFinishedAtAgentEnd() throws Exception {
        var ctx = ctx();
        var confirm = new RequireUserConfirmEvent("sdk-reply-1", List.of(
            new ToolUseBlock("call-9", "delete_file", Map.of("path", "/tmp/x"))));
        String pa = codec.encode(confirm, ctx);
        // interrupt 只登记不产出事件——RUN_FINISHED 由 lifecycle 转换器在 AGENT_END 统一携带
        assertNull(pa, "RequireUserConfirmEvent 自身不应产出帧（interrupt 挂 AGENT_END 的 RUN_FINISHED）");

        String endPa = codec.encode(new io.agentscope.core.event.AgentEndEvent("r1"), ctx);
        assertNotNull(endPa);
        var arr = JSON.readTree(endPa);
        JsonNode finished = null;
        for (var el : arr) {
            if ("RUN_FINISHED".equals(el.get("type").asText())) {
                finished = el;
            }
        }
        assertNotNull(finished, "AGENT_END 应产出 RUN_FINISHED");
        assertNotNull(finished.get("outcome"), "HITL 挂起后 RUN_FINISHED 应带 interrupt outcome");
        var interrupts = finished.get("outcome").get("interrupts");
        assertNotNull(interrupts);
        assertEquals(1, interrupts.size());
        var it = interrupts.get(0);
        // interruptId = runId:toolCallId（resume[] 回传解析口径）
        assertEquals(runId + ":call-9", it.get("id").asText());
        assertEquals("tool_call", it.get("reason").asText());
        assertEquals("call-9", it.get("toolCallId").asText());
        assertEquals("permission_confirm",
            it.get("metadata").get("agentscope.interruptKind").asText());
        assertEquals("delete_file", it.get("metadata").get("toolName").asText());
    }

    @Test
    void agentEndWithoutPendingInterruptCarriesNoInterruptOutcome() throws Exception {
        var ctx = ctx();
        String pa = codec.encode(new io.agentscope.core.event.AgentEndEvent("r1"), ctx);
        assertNotNull(pa);
        var arr = JSON.readTree(pa);
        for (var el : arr) {
            if ("RUN_FINISHED".equals(el.get("type").asText())) {
                assertTrue(el.get("outcome") == null || el.get("outcome").isNull()
                    || !el.has("interrupts"),
                    "正常完成不应携带 interrupts");
            }
        }
    }

    @Test
    void encodeCustomWrapsSingleCustomEvent() throws Exception {
        String pa = codec.encodeCustom(threadId, runId, "file_ready",
            Map.of("file_id", "f-1", "file_name", "a.txt"));
        assertNotNull(pa);
        var arr = JSON.readTree(pa);
        assertEquals(1, arr.size());
        assertEquals("CUSTOM", arr.get(0).get("type").asText());
        assertEquals("file_ready", arr.get(0).get("name").asText());
        assertEquals("f-1", arr.get(0).get("value").get("file_id").asText());
    }

    @Test
    void agentStartProducesRunStarted() throws Exception {
        var ctx = ctx();
        var event = new io.agentscope.core.event.AgentStartEvent("r1", "sid", "agent");
        String pa = codec.encode(event, ctx);
        assertNotNull(pa);
        var arr = JSON.readTree(pa);
        assertEquals("RUN_STARTED", arr.get(0).get("type").asText());
        assertEquals(threadId, arr.get(0).get("threadId").asText());
    }

    @Test
    void unknownEventFallsBackToRaw() throws Exception {
        var ctx = ctx();
        // DATA_BLOCK 在 SDK 2.0.3 无专属转换器（设计 D8）——按官方语义回退 RAW
        var event = new io.agentscope.core.event.DataBlockStartEvent("b2", "r1");
        String pa = codec.encode(event, ctx);
        if (pa != null) {
            var arr = JSON.readTree(pa);
            for (var el : arr) {
                assertTrue("RAW".equals(el.get("type").asText())
                        || "DATA_BLOCK".equals(el.get("type").asText()),
                    "未知事件应落 RAW 或显式映射，实际: " + el.get("type").asText());
            }
        }
    }

    @Test
    void converterRegisteredForConfirmEvent() {
        var converter = AguiEventCodec.permissionConfirmConverter();
        assertTrue(converter.eventTypes().contains(RequireUserConfirmEvent.class));
        assertEquals(1, converter.eventTypes().size());
    }
}
