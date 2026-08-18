package io.agentmanager.framework.service;

import java.time.Duration;
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
import static org.mockito.Mockito.*;

/**
 * AgentRuntimeService HITL 测试：permission_ask 事件转发 + 确认缓存 + resumeWithConfirm 恢复。
 * 覆盖 hitl-permission-plan.md 6.2.1 / 6.3.1 / 12.5。
 */
class AgentRuntimeServiceHitlTest {

    private HarnessAgent agent;
    private AgentRuntimeService service;
    private SessionEventBus eventBus;

    private static final String SID = "t1";

    @BeforeEach
    void setUp() {
        agent = mock(HarnessAgent.class);
        eventBus = new SessionEventBus();
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
        service = new AgentRuntimeService(config, agent, List.of(), new LLMLogger(), eventBus);
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

    // ---------- 6.3.1 确认缓存 ----------

    @Test
    void confirmCacheShouldBePopulatedByAsk() {
        emitAskThenCollect();
        // 缓存已写入 → 恢复可用（不抛异常即通过；内部 makeThreadId 补全前缀）
        service.checkConfirmAvailable(SID);
    }

    @Test
    void checkConfirmAvailableShouldFailWhenNoCache() {
        assertThrows(AgentRuntimeService.ConfirmContextNotFoundException.class,
            () -> service.checkConfirmAvailable(SID));
    }

    @Test
    void consumeConfirmContextShouldBeCasConsuming() {
        emitAskThenCollect();
        var sessionId = "acme-test-agent:" + SID;
        var first = service.consumeConfirmContext(sessionId);
        assertEquals(1, first.toolCalls().size());
        assertThrows(AgentRuntimeService.ConfirmAlreadyConsumedException.class,
            () -> service.consumeConfirmContext(sessionId));
    }

    @Test
    void consumeConfirmContextShouldExpireAfterTtl() {
        emitAskThenCollect();
        var sessionId = "acme-test-agent:" + SID;
        // 直接以过期时间戳验证 TTL 判断逻辑：构造已过期上下文
        var expired = new AgentRuntimeService.ConfirmContext(
            Map.of("call-1", toolUseBlock("call-1")), "reply-1",
            java.time.Instant.now().minus(Duration.ofMinutes(31)), new java.util.concurrent.atomic.AtomicBoolean(false));
        try {
            var f = AgentRuntimeService.class.getDeclaredField("confirmCache");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cache = (java.util.concurrent.ConcurrentHashMap<String, AgentRuntimeService.ConfirmContext>) f.get(service);
            cache.put(sessionId, expired);
            assertThrows(AgentRuntimeService.ConfirmContextNotFoundException.class,
                () -> service.consumeConfirmContext(sessionId));
        } catch (Exception e) {
            throw new AssertionError("reflection failed", e);
        }
    }

    // ---------- 6.3 resumeWithConfirm（同步） ----------

    @Test
    void resumeWithConfirmShouldPassConfirmResultsAndReturnFinalReply() {
        emitAskThenCollect();
        var sessionId = "acme-test-agent:" + SID;

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
        emitAskThenCollect();
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
    void resumeWithConfirmShould404WhenNoAskCache() {
        when(agent.call(anyList(), any(RuntimeContext.class)))
            .thenReturn(Mono.just(mock(Msg.class)));
        assertThrows(AgentRuntimeService.ConfirmContextNotFoundException.class,
            () -> service.resumeWithConfirm(SID, null,
                List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true))));
    }

    @Test
    void resumeWithConfirmShould409OnDuplicateConfirm() {
        emitAskThenCollect();
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
        emitAskThenCollect();
        assertThrows(IllegalArgumentException.class,
            () -> service.resumeWithConfirm(SID, null,
                List.of(Map.<String, Object>of("tool_call_id", "call-nope", "confirmed", true))));
    }

    // ---------- 6.3 resumeWithConfirmStream（流式 + 总线扇出） ----------

    @Test
    void resumeWithConfirmStreamShouldEmitDoneAndFanOutToEventBus() {
        emitAskThenCollect();

        // 订阅事件总线（模拟长连接）；multicast 需先订阅再触发
        // 使用原始 SID（与 resumeWithConfirmStream emit key 一致）
        var busEvents = new java.util.concurrent.CopyOnWriteArrayList<String>();
        eventBus.sink(SID).asFlux().subscribe(e -> busEvents.add(e.getType().name()));
        try { Thread.sleep(50); } catch (InterruptedException ignored) { }

        var end = new io.agentscope.core.event.AgentEndEvent("reply-2");
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(end));

        var frames = service.resumeWithConfirmStream(SID, null,
            List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true))).collectList().block();

        assertNotNull(frames);
        assertTrue(frames.stream().anyMatch(f -> "done".equals(f.get("type"))));
        assertTrue(frames.stream().anyMatch(f -> "completed".equals(f.get("state"))));
        // 总线扇出：AGENT_END 事件原样到达订阅者（bus 不转换词表）
        assertTrue(busEvents.contains("AGENT_END"), "eventBus fan-out failed: " + busEvents);
    }

    @Test
    void resumeWithConfirmStreamShouldEmitErrorFrameWhenNoCache() {
        var frames = service.resumeWithConfirmStream(SID, null,
            List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true))).collectList().block();

        assertNotNull(frames);
        assertTrue(frames.stream().anyMatch(f ->
            "error".equals(f.get("type"))
                && String.valueOf(f.get("error")).contains("confirm_context_not_found")));
        assertTrue(frames.stream().anyMatch(f -> "done".equals(f.get("type"))));
    }
}
