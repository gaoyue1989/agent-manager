package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Set;
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
            registry, auditStore, resolver, "my-agent", null);

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
            registry, auditStore, resolver, "my-agent", null);

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
            registry, auditStore, resolver, "my-agent", null);

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
            registry, auditStore, resolver, "my-agent", null);

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
            registry, auditStore, resolver, "my-agent", null);

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
            registry, auditStore, null, "my-agent", null);
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
            null, auditStore, resolver, "my-agent", null);

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
            registry, auditStore, resolver, "my-agent", null);

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
            registry, auditStore, resolver, "my-agent", null);

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
            registry, auditStore, resolver, "my-agent", null);

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
            registry, auditStore, resolver, "my-agent", null);

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
            registry, auditStore, resolver, "my-agent", null);

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

    // ===== issue #422：指标 tool 标签只认已注册工具名，未注册/幻觉名归 __unknown__ 桶 =====

    private ToolCallObservabilityMiddleware middlewareWithRegisteredTools(String... tools) {
        return new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent", () -> Set.of(tools));
    }

    /**
     * 未注册/幻觉工具名（SDK 对不存在工具返回 Tool not found 的 ERROR 终态，事件携带
     * 模型输出的任意字符串）必须归入单一 __unknown__ 桶——Micrometer Meter 不回收，
     * 原样做标签会让序列数随模型输出无界增长；审计行保留真实名字便于排查。
     */
    @Test
    void shouldBucketUnregisteredToolNameIntoUnknownLabel() {
        var middleware = middlewareWithRegisteredTools("weather", "shell");

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "hallucinated_tool_0"))),
                i -> stream(new ToolResultEndEvent("r1", "call-1", "hallucinated_tool_0",
                    ToolResultState.ERROR)))
            .blockLast();

        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "__unknown__").tag("result", "error").counter().count());
        assertEquals(0L, registry.find("oaf.tool.calls").counters().stream()
                .filter(c -> !"__unknown__".equals(c.getId().getTag("tool")))
                .mapToDouble(c -> c.count()).sum(),
            "不得以未注册名新建时间序列");
        verify(auditStore).record(any(), eq("hallucinated_tool_0"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("error"), anyString());
    }

    /** 已注册工具名保持原样标签（归桶不影响正常面板 byTool 维度） */
    @Test
    void shouldKeepRegisteredToolNameAsMetricLabel() {
        var middleware = middlewareWithRegisteredTools("weather", "shell");

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "weather"))),
                i -> stream(new ToolResultEndEvent("r1", "call-1", "weather",
                    ToolResultState.SUCCESS)))
            .blockLast();

        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "weather").tag("result", "success").counter().count());
    }

    /** 未装配注册名单供给时 fail-soft 保持原样标签（现网未传 toolkit 引用的装配路径） */
    @Test
    void shouldPassThroughToolLabelWithoutRegisteredNamesSupplier() {
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent", null);

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "hallucinated_tool_0"))),
                i -> stream(new ToolResultEndEvent("r1", "call-1", "hallucinated_tool_0",
                    ToolResultState.ERROR)))
            .blockLast();

        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "hallucinated_tool_0").tag("result", "error").counter().count());
    }

    // ===== issue #485：名单供给必须在每次计数时实时求值（HarnessAgentFactory 以
    // AtomicReference 在 build() 后切换到 agent.getToolkit()——内置工具与 watchdog
    // 补偿注册的 MCP 工具只在运行期 toolkit 上，快照式名单会让它们全部误归 __unknown__）=====

    /**
     * 名单供给懒取语义锚点：同一中间件实例在名单切换前后，归名必须跟随最新名单。
     * 切换前 read_file 不在名单（build 前 toolkit 无内置工具）→ __unknown__；
     * 切换后（activeToolkit.set(agent.getToolkit())）→ 实名。名单供给若被实现成
     * 构造期快照，本测试即红。
     */
    @Test
    void shouldFollowLatestRegisteredNamesOnSupplierSwitch() {
        var names = new java.util.concurrent.atomic.AtomicReference<>(Set.of("weather"));
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent", () -> names.get());

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "read_file"))),
                i -> stream(new ToolResultEndEvent("r1", "call-1", "read_file",
                    ToolResultState.SUCCESS)))
            .blockLast();
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "__unknown__").tag("result", "success").counter().count());

        names.set(Set.of("weather", "read_file"));
        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-2", "read_file"))),
                i -> stream(new ToolResultEndEvent("r1", "call-2", "read_file",
                    ToolResultState.SUCCESS)))
            .blockLast();
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "read_file").tag("result", "success").counter().count(),
            "名单切换后同内置工具必须归实名（build 后 toolkit 名单生效）");
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "__unknown__").tag("result", "success").counter().count(),
            "切换前已落 __unknown__ 序列保持不变（不追溯改写历史计数）");
    }

    /** 名单含内置工具名时内置工具按实名计数（封顶语义不变：名单外仍归 __unknown__） */
    @Test
    void shouldKeepHarnessBuiltinToolRealNameWhenRegistered() {
        var middleware = middlewareWithRegisteredTools("read_file", "execute", "weather");

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "read_file"))),
                i -> stream(new ToolResultEndEvent("r1", "call-1", "read_file",
                    ToolResultState.SUCCESS)))
            .blockLast();

        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "read_file").tag("result", "success").counter().count());
        assertNull(registry.find("oaf.tool.calls")
                .tag("tool", "__unknown__").tag("result", "success").counter(),
            "内置工具实名命中名单，不得再落 __unknown__ 序列");
    }
}
