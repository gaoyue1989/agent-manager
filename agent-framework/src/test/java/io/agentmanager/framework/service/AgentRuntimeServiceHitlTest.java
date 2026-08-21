package io.agentmanager.framework.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AgentRuntimeService HITL 测试：permission_ask 事件转发 + 确认上下文落库 + resumeWithConfirm 恢复。
 * 覆盖 hitl-permission-plan.md 6.2.1 / 6.3.1 / 12.5（无状态架构：确认上下文经 ConfirmContextStore
 * 落库跨副本可见，不再有进程内缓存/事件总线）。
 */
class AgentRuntimeServiceHitlTest {

    private HarnessAgent agent;
    private AgentRuntimeService service;
    private ConfirmContextStore confirmContextStore;

    private static final String SID = "t1";

    @BeforeEach
    void setUp() {
        agent = mock(HarnessAgent.class);
        confirmContextStore = mock(ConfirmContextStore.class);
        var config = new OafConfig(
            "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
            "Test agent", "@acme", "MIT",
            List.of("test"), "you are a helper.",
            List.of(), List.of(), List.of(), List.of(), List.of(),
            new OafConfig.ModelConfig("openai", "gpt-4", ""),
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            new OafConfig.MemoryConfig("editable", Map.of()),
            Map.of()
        );
        service = new AgentRuntimeService(config, agent, List.of(), new LLMLogger(), confirmContextStore);
    }

    private ToolUseBlock toolUseBlock(String id) {
        return ToolUseBlock.builder().id(id).name("get_weather")
            .input(Map.of("city", "beijing")).build();
    }

    private RequireUserConfirmEvent askEvent(String replyId, ToolUseBlock... calls) {
        return new RequireUserConfirmEvent("evt-1", "source-1", replyId, List.of(calls));
    }

    /** 模拟 agent 发出 RequireUserConfirmEvent（invokeStream 链路），并收集事件帧 */
    private List<Map<String, Object>> emitAskThenCollect() {
        var ask = askEvent("reply-1", toolUseBlock("call-1"));
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(ask));
        return service.invokeStream("query weather", SID, "alice").collectList().block();
    }

    /** 构造 store 回读行（模拟 DB 已存该 session 的确认上下文） */
    private ConfirmContextStore.StoredRow stubStoreRow() {
        var row = new ConfirmContextStore.StoredRow(
            List.of(toolUseBlock("call-1")), "reply-1", Instant.now(), null, null);
        when(confirmContextStore.consume(anyString())).thenReturn(row);
        return row;
    }

    // ---------- 6.2.1 invokeStream permission_ask ----------

    @Test
    void invokeStreamShouldEmitPermissionAskWithToolCalls() {
        var events = emitAskThenCollect();

        assertNotNull(events);
        var ask = events.stream().filter(e -> "permission_ask".equals(e.get("type")))
            .findFirst().orElse(null);
        assertNotNull(ask, "should emit permission_ask frame");
        assertEquals("reply-1", ask.get("reply_id"));
        assertEquals("t1", ask.get("task_id"));
        @SuppressWarnings("unchecked")
        var calls = (List<Map<String, Object>>) ask.get("tool_calls");
        assertEquals(1, calls.size());
        assertEquals("call-1", calls.get(0).get("tool_call_id"));
        assertEquals("get_weather", calls.get(0).get("name"));
        assertNotNull(calls.get(0).get("input"));
    }

    // ---------- 6.3.1 确认上下文落库 ----------

    @Test
    void confirmContextShouldBePersistedByAsk() {
        emitAskThenCollect();
        // 落库校验：storeConfirmContext → confirmContextStore.put（session 带 tenant 前缀）
        verify(confirmContextStore).put(eq("acme-test-agent:t1"), anyList(), anyString(), any(), any());
    }

    @Test
    void checkConfirmAvailableShouldFailWhenNoStoredRow() {
        // 无存储行：store 抛 NotFound（mock 需显式 stub，因为 void 默认不抛）
        doThrow(new AgentRuntimeService.ConfirmContextNotFoundException(SID))
            .when(confirmContextStore).checkAvailable(anyString());
        assertThrows(AgentRuntimeService.ConfirmContextNotFoundException.class,
            () -> service.checkConfirmAvailable(SID));
    }

    @Test
    void consumeConfirmContextShouldBeCasConsuming() {
        stubStoreRow();
        var sessionId = "acme-test-agent:" + SID;
        var first = service.consumeConfirmContext(sessionId);
        assertEquals(1, first.toolCalls().size());
    }

    @Test
    void checkConfirmAvailableShouldFailWhenStoreRejects() {
        doThrow(new AgentRuntimeService.ConfirmContextNotFoundException(SID))
            .when(confirmContextStore).checkAvailable(anyString());
        assertThrows(AgentRuntimeService.ConfirmContextNotFoundException.class,
            () -> service.checkConfirmAvailable(SID));
    }

    // ---------- 6.3 resumeWithConfirm（同步） ----------

    @Test
    void resumeWithConfirmShouldPassConfirmResultsAndReturnFinalReply() {
        stubStoreRow();
        var msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn("done");
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(msg));

        var result = service.resumeWithConfirm(SID, null, List.of(Map.<String, Object>of(
            "tool_call_id", "call-1", "confirmed", true, "accept_rule", false)));

        assertEquals("done", result.get("response"));
        assertEquals(SID, result.get("thread_id"));

        // 校验传给 agent 的消息携带 confirm_results metadata（含原始 ToolUseBlock）
        verify(agent).call(argThat((java.util.List<Msg> msgs) -> {
            if (msgs.isEmpty()) return false;
            var m = msgs.get(0);
            Object raw = m.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
            if (!(raw instanceof List<?> list) || list.size() != 1) return false;
            var cr = (ConfirmResult) list.get(0);
            return cr.isConfirmed()
                && "call-1".equals(cr.getToolCall().getId())
                && "get_weather".equals(cr.getToolCall().getName());
        }), any(RuntimeContext.class));
    }

    @Test
    void resumeWithConfirmShouldSupportReject() {
        stubStoreRow();
        var msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn("rejected and continue");
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(msg));

        service.resumeWithConfirm(SID, null, List.of(Map.<String, Object>of(
            "tool_call_id", "call-1", "confirmed", false, "accept_rule", false)));

        verify(agent).call(argThat((java.util.List<Msg> msgs) -> {
            var raw = msgs.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
            if (!(raw instanceof List<?> list) || list.size() != 1) return false;
            var cr = (ConfirmResult) list.get(0);
            return !cr.isConfirmed() && "call-1".equals(cr.getToolCall().getId());
        }), any(RuntimeContext.class));
    }

    @Test
    void resumeWithConfirmShould404WhenNoStoredRow() {
        when(confirmContextStore.consume(anyString()))
            .thenThrow(new AgentRuntimeService.ConfirmContextNotFoundException(SID));
        when(agent.call(anyList(), any(RuntimeContext.class)))
            .thenReturn(Mono.just(mock(Msg.class)));
        assertThrows(AgentRuntimeService.ConfirmContextNotFoundException.class,
            () -> service.resumeWithConfirm(SID, null,
                List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true))));
    }

    @Test
    void resumeWithConfirmShould409OnDuplicateConfirm() {
        stubStoreRow();
        when(confirmContextStore.consume(anyString()))
            .thenReturn(new ConfirmContextStore.StoredRow(
                List.of(toolUseBlock("call-1")), "reply-1", Instant.now(), null, null))
            .thenThrow(new AgentRuntimeService.ConfirmAlreadyConsumedException(SID));
        var msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn("ok");
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(msg));
        var body = List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true));

        service.resumeWithConfirm(SID, null, body);
        assertThrows(AgentRuntimeService.ConfirmAlreadyConsumedException.class,
            () -> service.resumeWithConfirm(SID, null, body));
    }

    @Test
    void resumeWithConfirmShouldRejectUnknownToolCallId() {
        stubStoreRow();
        assertThrows(IllegalArgumentException.class,
            () -> service.resumeWithConfirm(SID, null,
                List.of(Map.<String, Object>of("tool_call_id", "call-nope", "confirmed", true))));
    }

    // ---------- 6.3 resumeWithConfirmStream（流式，无总线扇出） ----------

    @Test
    void resumeWithConfirmStreamShouldEmitDoneAndCompleted() {
        stubStoreRow();
        var end = new io.agentscope.core.event.AgentEndEvent("reply-2");
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(end));

        var frames = service.resumeWithConfirmStream(SID, null,
            List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true))).collectList().block();

        assertNotNull(frames);
        assertTrue(frames.stream().anyMatch(f -> "done".equals(f.get("type"))));
        assertTrue(frames.stream().anyMatch(f -> "completed".equals(f.get("state"))));
    }

    @Test
    void resumeWithConfirmStreamShouldEmitErrorFrameWhenNoStoredRow() {
        when(confirmContextStore.consume(anyString()))
            .thenThrow(new AgentRuntimeService.ConfirmContextNotFoundException(SID));
        var frames = service.resumeWithConfirmStream(SID, null,
            List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true))).collectList().block();

        assertNotNull(frames);
        assertTrue(frames.stream().anyMatch(f ->
            "error".equals(f.get("type"))
                && String.valueOf(f.get("error")).contains("confirm_context_not_found")));
        assertTrue(frames.stream().anyMatch(f -> "done".equals(f.get("type"))));
    }
}
