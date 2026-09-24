package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;

/**
 * TurnToolSummaryTracker 测试：delta 累积 → END 事件合成人可读摘要帧。
 *
 * <p>SDK 的两处「坑」在这里被钉住：
 * <ol>
 *   <li>真实工具名只在 {@link ToolCallStartEvent} 上，delta 帧的名字是 {@code __fragment__}
 *       （e2e-ci-plan §11.3 D3）——故必须先登记再查表。</li>
 *   <li>参数/结果都切成 delta 下发——故必须到 END 才能合成摘要。</li>
 * </ol>
 */
class TurnToolSummaryTrackerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Emitted(String type, String payload) {
    }

    private final List<Emitted> emitted = new ArrayList<>();

    private TurnToolSummaryTracker tracker() {
        return new TurnToolSummaryTracker("sid-1", "reply-1",
            (type, payload) -> emitted.add(new Emitted(type, payload)));
    }

    private static String field(String payload, String key) throws Exception {
        var node = JSON.readTree(payload);
        return node.get(key).asText();
    }

    // ===== 核心链路 =====

    @Test
    void shouldAccumulateDeltasAndEmitSummaryOnCallEnd() throws Exception {
        var t = tracker();
        t.onToolCallStart(new ToolCallStartEvent("reply-1", "tc-1", "write_file"));
        // 参数分片到达，且 delta 帧的工具名是占位符（真实场景）
        t.onToolCallDelta(new ToolCallDeltaEvent("reply-1", "tc-1", "__fragment__", "{\"path\":\"out"));
        t.onToolCallDelta(new ToolCallDeltaEvent("reply-1", "tc-1", "__fragment__", "put/a.js\",\"content\":\"a\\nb\"}"));
        t.onToolCallEnd(new ToolCallEndEvent("reply-1", "tc-1", "write_file"));

        assertEquals(1, emitted.size());
        var e = emitted.get(0);
        assertEquals("tool_call_summary", e.type());
        assertEquals("创建 output/a.js 2行", field(e.payload(), "summary"));
        assertEquals("write_file", field(e.payload(), "toolName"));
        assertEquals("tc-1", field(e.payload(), "toolCallId"));
        assertEquals("reply-1", field(e.payload(), "replyId"));
    }

    @Test
    void shouldUseRegisteredNameNotDeltaPlaceholder() throws Exception {
        // 关键回归：delta 帧名为 __fragment__，若直接读事件名就会得出「创建 ? 1行」
        var t = tracker();
        t.onToolCallStart(new ToolCallStartEvent("reply-1", "tc-1", "execute"));
        t.onToolCallDelta(new ToolCallDeltaEvent("reply-1", "tc-1", "__fragment__", "{\"command\":\"npm install\"}"));
        t.onToolCallEnd(new ToolCallEndEvent("reply-1", "tc-1", "execute"));

        assertEquals("执行 npm install", field(emitted.get(0).payload(), "summary"));
    }

    @Test
    void shouldEmitResultPreviewOnResultEnd() throws Exception {
        var t = tracker();
        t.onToolCallStart(new ToolCallStartEvent("reply-1", "tc-1", "execute"));
        t.onToolResultDelta(new ToolResultTextDeltaEvent("reply-1", "tc-1", "execute", "PPT 生成成功。\n"));
        t.onToolResultDelta(new ToolResultTextDeltaEvent("reply-1", "tc-1", "execute", "后续明细"));
        t.onToolResultEnd(new ToolResultEndEvent("reply-1", "tc-1", "execute", ToolResultState.SUCCESS));

        assertEquals(1, emitted.size());
        assertEquals("tool_result_preview", emitted.get(0).type());
        assertEquals("PPT 生成成功。", field(emitted.get(0).payload(), "preview"));
    }

    @Test
    void failedResultShouldEmitTerminalStatePreview() throws Exception {
        var t = tracker();
        t.onToolResultEnd(new ToolResultEndEvent("reply-1", "tc-9", "execute", ToolResultState.ERROR));

        assertEquals(1, emitted.size());
        assertEquals("❌ 执行失败", field(emitted.get(0).payload(), "preview"));
    }

    @Test
    void emptySuccessResultShouldEmitNothing() {
        var t = tracker();
        t.onToolResultDelta(new ToolResultTextDeltaEvent("reply-1", "tc-1", "write_file", "  \n "));
        t.onToolResultEnd(new ToolResultEndEvent("reply-1", "tc-1", "write_file", ToolResultState.SUCCESS));

        assertTrue(emitted.isEmpty(), "空结果不该产生噪音帧");
    }

    // ===== 工具名判定与缓冲复用 =====

    @Test
    void isToolShouldResolveByRegisteredName() {
        var t = tracker();
        t.onToolCallStart(new ToolCallStartEvent("reply-1", "tc-1", "write_file"));
        assertTrue(t.isTool("tc-1", "write_file", null));
        assertFalse(t.isTool("tc-1", "read_file", null));
        // 未登记时回落到事件自带名字（ToolResult* 系列自带真实名）
        assertTrue(t.isTool("tc-unknown", "present_file", "present_file"));
        assertFalse(t.isTool(null, "write_file", null));
    }

    @Test
    void peekArgsShouldNotConsumeBuffer() {
        // write_file 的 KV 同步要在摘要合成之前读到参数，故 peek 不能移除缓冲
        var t = tracker();
        t.onToolCallStart(new ToolCallStartEvent("reply-1", "tc-1", "write_file"));
        t.onToolCallDelta(new ToolCallDeltaEvent("reply-1", "tc-1", "__fragment__", "{\"path\":\"a\",\"content\":\"x\"}"));

        assertEquals("{\"path\":\"a\",\"content\":\"x\"}", t.peekArgs("tc-1"));
        assertEquals("{\"path\":\"a\",\"content\":\"x\"}", t.peekArgs("tc-1"));
        assertEquals(null, t.peekArgs("missing"));
    }

    // ===== 健壮性 =====

    @Test
    void malformedArgsShouldStillEmitSummary() throws Exception {
        // 参数拼坏了也要发帧（至少显示工具名），不能整帧丢失
        var t = tracker();
        t.onToolCallStart(new ToolCallStartEvent("reply-1", "tc-1", "read_file"));
        t.onToolCallDelta(new ToolCallDeltaEvent("reply-1", "tc-1", "__fragment__", "{broken"));
        t.onToolCallEnd(new ToolCallEndEvent("reply-1", "tc-1", "read_file"));

        assertEquals(1, emitted.size());
        assertEquals("读取 ?", field(emitted.get(0).payload(), "summary"));
    }

    @Test
    void endWithoutStartShouldUseEventName() throws Exception {
        // 恢复执行段可能缺少 START 事件（跨段重放），回落到事件自带名字
        var t = tracker();
        t.onToolCallEnd(new ToolCallEndEvent("reply-1", "tc-2", "execute"));

        assertEquals(1, emitted.size());
        assertEquals("execute", field(emitted.get(0).payload(), "toolName"));
    }

    @Test
    void clearShouldDropAllBuffers() {
        var t = tracker();
        t.onToolCallStart(new ToolCallStartEvent("reply-1", "tc-1", "write_file"));
        t.onToolCallDelta(new ToolCallDeltaEvent("reply-1", "tc-1", "__fragment__", "{\"path\":\"a\"}"));
        t.clear();

        assertEquals(null, t.peekArgs("tc-1"));
        assertFalse(t.isTool("tc-1", "write_file", null));
    }

    @Test
    void resultBufferOverflowShouldNotThrow() throws Exception {
        var t = tracker();
        t.onToolResultDelta(new ToolResultTextDeltaEvent("reply-1", "tc-1", "execute", "x".repeat(70000)));
        t.onToolResultEnd(new ToolResultEndEvent("reply-1", "tc-1", "execute", ToolResultState.SUCCESS));

        assertFalse(emitted.isEmpty());
        assertTrue(field(emitted.get(0).payload(), "preview").endsWith("…"));
    }
}
