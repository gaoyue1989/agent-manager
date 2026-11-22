package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;

/**
 * ToolCallObservabilityMiddleware 测试：审计单一写点（TOOL_CALL_START +
 * TOOL_RESULT_END 行，含 result_state）、指标计数（service/tool/result 标签）、
 * 非成功终态按 error 计、事件透传、registry/resolver 缺省安全。
 */
class ToolCallObservabilityMiddlewareTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ToolAuditStore auditStore = mock(ToolAuditStore.class);
    private final SessionKeyResolver resolver = mock(SessionKeyResolver.class);

    private static ToolUseBlock toolCall(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).content("{}").build();
    }

    private static RuntimeContext ctx() {
        return mock(RuntimeContext.class);
    }

    private static Flux<AgentEvent> stream(AgentEvent... events) {
        return Flux.just(events);
    }

    @Test
    void shouldAuditStartAndEndRowsWithResultStateAndCountMetric() {
        when(resolver.canonicalSessionId(any())).thenReturn("sess-1");
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent");

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "weather"))),
                i -> stream(
                    new ToolCallStartEvent("r1", "call-1", "weather"),
                    new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS)))
            .blockLast();

        InOrder order = inOrder(auditStore);
        order.verify(auditStore).record(eq("sess-1"), eq("weather"), eq("call-1"),
            eq("TOOL_CALL_START"), isNull(), isNull());
        order.verify(auditStore).record(eq("sess-1"), eq("weather"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("success"), anyString());

        var counter = registry.find("oaf.tool.calls")
            .tag("service", "my-agent").tag("tool", "weather").tag("result", "success")
            .counter();
        assertNotNull(counter, "成功调用必须产出 success 计数");
        assertEquals(1.0, counter.count());
    }

    @Test
    void shouldCountErrorResultAsError() {
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent");

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "shell"))),
                i -> stream(new ToolResultEndEvent("r1", "call-1", "shell", ToolResultState.ERROR)))
            .blockLast();

        verify(auditStore).record(any(), eq("shell"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("error"), anyString());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "shell").tag("result", "error").counter().count());
    }

    /** 非成功终态（INTERRUPTED/DENIED）一律按 error 桶计（与 Monitor 问题口径一致） */
    @Test
    void shouldCountNonSuccessTerminalStatesAsError() {
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent");

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "write_file"))),
                i -> stream(new ToolResultEndEvent("r1", "call-1", "write_file",
                    ToolResultState.INTERRUPTED)))
            .blockLast();

        verify(auditStore).record(any(), eq("write_file"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("error"), anyString());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "write_file").tag("result", "error").counter().count());
    }

    @Test
    void shouldHandleParallelBatchCalls() {
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent");

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "weather"), toolCall("call-2", "stock"))),
                i -> stream(
                    new ToolCallStartEvent("r1", "call-1", "weather"),
                    new ToolCallStartEvent("r1", "call-2", "stock"),
                    new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS),
                    new ToolResultEndEvent("r1", "call-2", "stock", ToolResultState.ERROR)))
            .blockLast();

        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "weather").tag("result", "success").counter().count());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "stock").tag("result", "error").counter().count());
    }

    @Test
    void shouldPassEventsThroughUnchanged() {
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent");

        var events = List.of(
            new ToolCallStartEvent("r1", "call-1", "weather"),
            new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS));
        var result = middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "weather"))), i -> Flux.fromIterable(events))
            .collectList().block();

        assertEquals(events, result, "观测中间件必须透传事件流");
    }

    @Test
    void shouldFallBackToRawSessionIdWithoutResolver() {
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, null, "my-agent");
        var ctx = ctx();

        middleware.onActing(mock(Agent.class), ctx,
                new ActingInput(List.of(toolCall("call-1", "weather"))),
                i -> stream(new ToolCallStartEvent("r1", "call-1", "weather")))
            .blockLast();

        verify(auditStore).record(eq(ctx.getSessionId()), eq("weather"), eq("call-1"),
            eq("TOOL_CALL_START"), isNull(), isNull());
    }

    @Test
    void shouldNotFailWithoutMeterRegistry() {
        var middleware = new ToolCallObservabilityMiddleware(
            null, auditStore, resolver, "my-agent");

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "weather"))),
                i -> stream(new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS)))
            .blockLast();

        verify(auditStore).record(any(), eq("weather"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("success"), anyString());
    }

    @Test
    void shouldRecordStartRowOnlyOnEmptyStream() {
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent");

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "weather"))), i -> Flux.empty())
            .blockLast();

        // START 行在入口落（调用尝试口径）；无终态事件 → 无 RESULT 行、无计数
        verify(auditStore).record(any(), eq("weather"), eq("call-1"),
            eq("TOOL_CALL_START"), isNull(), isNull());
        assertEquals(0.0, registry.find("oaf.tool.calls").counters().stream()
            .mapToDouble(c -> c.count()).sum());
    }

    /**
     * HITL 确认放行（issue #421）：同一 tool call 经两次 onActing——挂起询问轮（fresh
     * 调用，state 未置）与确认恢复轮（SDK 已在上下文把它标为 ALLOWED）。START 行只允许
     * 在询问轮落一次，恢复轮不得双写。
     */
    @Test
    void shouldNotDoubleWriteStartRowOnHitlApproveResumePass() {
        when(resolver.canonicalSessionId(any())).thenReturn("sess-1");
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent");

        // 第 1 轮：挂起询问——入口落 START，流内无 ToolResultEndEvent
        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "shell"))),
                i -> Flux.empty())
            .blockLast();
        // 第 2 轮：确认放行恢复——SDK 把调用标为 ALLOWED 后重新进入 acting 并真实执行
        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "shell")
                    .withState(ToolCallState.ALLOWED))),
                i -> stream(new ToolResultEndEvent("r1", "call-1", "shell",
                    ToolResultState.SUCCESS)))
            .blockLast();

        verify(auditStore, times(1)).record(eq("sess-1"), eq("shell"), eq("call-1"),
            eq("TOOL_CALL_START"), isNull(), isNull());
        verify(auditStore, times(1)).record(eq("sess-1"), eq("shell"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("success"), anyString());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "shell").tag("result", "success").counter().count());
    }

    /**
     * HITL 全量拒绝（issue #421）：恢复轮 SDK 在本中间件之外合成 DENIED 结果，链内只发
     * AllToolsDeniedEvent（ASKING 状态调用重进 acting）——必须据此补写 RESULT 行并计数
     * （非 SUCCESS 终态按 error 桶，与既有口径一致），且 START 行不因恢复轮双写。
     */
    @Test
    void shouldRecordResultAndCountOnAllToolsDeniedResumePass() {
        when(resolver.canonicalSessionId(any())).thenReturn("sess-1");
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent");

        // 第 1 轮：挂起询问——START 已落
        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "shell"))),
                i -> Flux.empty())
            .blockLast();
        // 第 2 轮：用户拒绝恢复——ASKING 状态重进 acting，链内只有 AllToolsDeniedEvent
        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "shell")
                    .withState(ToolCallState.ASKING))),
                i -> stream(new AllToolsDeniedEvent(List.of(
                    toolCall("call-1", "shell").withState(ToolCallState.ASKING)))))
            .blockLast();

        verify(auditStore, times(1)).record(eq("sess-1"), eq("shell"), eq("call-1"),
            eq("TOOL_CALL_START"), isNull(), isNull());
        verify(auditStore, times(1)).record(eq("sess-1"), eq("shell"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("error"), anyString());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "shell").tag("result", "error").counter().count());
    }

    /** 拒绝恢复轮的 RESULT 行按 denied 调用逐个落（批量拒绝各自可见、各自计数） */
    @Test
    void shouldRecordResultRowPerDeniedCallOnAllToolsDenied() {
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent");

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "shell"), toolCall("call-2", "read_file"))),
                i -> stream(new AllToolsDeniedEvent(List.of(
                    toolCall("call-1", "shell"), toolCall("call-2", "read_file")))))
            .blockLast();

        verify(auditStore).record(any(), eq("shell"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("error"), anyString());
        verify(auditStore).record(any(), eq("read_file"), eq("call-2"),
            eq("TOOL_RESULT_END"), eq("error"), anyString());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "shell").tag("result", "error").counter().count());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "read_file").tag("result", "error").counter().count());
    }

    /** 事件流中的 ToolCallStartEvent 不触发二次写入（START 行唯一来源 = ActingInput 入口） */
    @Test
    void shouldNotDoubleRecordOnStartEvent() {
        when(resolver.canonicalSessionId(any())).thenReturn("sess-1");
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent");

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "weather"))),
                i -> stream(
                    new ToolCallStartEvent("r1", "call-1", "weather"),
                    new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS)))
            .blockLast();

        InOrder order = inOrder(auditStore);
        order.verify(auditStore).record(eq("sess-1"), eq("weather"), eq("call-1"),
            eq("TOOL_CALL_START"), isNull(), isNull());
        order.verify(auditStore).record(eq("sess-1"), eq("weather"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("success"), anyString());
        verify(auditStore, times(2)).record(anyString(), eq("weather"), eq("call-1"),
            anyString(), any(), any());
    }
}
