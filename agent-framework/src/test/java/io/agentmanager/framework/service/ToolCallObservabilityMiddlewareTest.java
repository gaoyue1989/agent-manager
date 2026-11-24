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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ActingInput;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;

/**
 * ToolCallObservabilityMiddleware 测试：审计单一写点（TOOL_CALL_START +
 * TOOL_RESULT_END 行，含 result_state）、指标计数（service/tool/result 标签）、
 * 非成功终态按 error 计、事件透传、registry/resolver 缺省安全、HITL 部分拒绝/
 * 全量拒绝的被拒工具终态与计数（issue #486）。
 */
class ToolCallObservabilityMiddlewareTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ToolAuditStore auditStore = mock(ToolAuditStore.class);
    private final SessionKeyResolver resolver = mock(SessionKeyResolver.class);

    private static ToolUseBlock toolCall(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).content("{}").build();
    }

    private static ToolUseBlock toolCall(String id, String name, ToolCallState state) {
        return toolCall(id, name).withState(state);
    }

    private static RuntimeContext ctx() {
        return mock(RuntimeContext.class);
    }

    private static AgentInput agentInput() {
        return new AgentInput(List.of(Msg.builder().role(MsgRole.USER).textContent("go").build()));
    }

    private static Flux<AgentEvent> stream(AgentEvent... events) {
        return Flux.just(events);
    }

    @Test
    void shouldAuditStartAndEndRowsWithResultStateAndCountMetric() {
        when(resolver.canonicalSessionId(any())).thenReturn("sess-1");
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent", null);

        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
                i -> stream(
                    new ToolCallStartEvent("r1", "call-1", "weather"),
                    new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS)))
            .blockLast();

        verify(auditStore).record(eq("sess-1"), eq("weather"), eq("call-1"),
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

        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
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

        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
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

        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
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

        var events = List.<AgentEvent>of(
            new ToolCallStartEvent("r1", "call-1", "weather"),
            new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS));
        var result = middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
                i -> Flux.fromIterable(events))
            .collectList().block();

        assertEquals(events, result, "观测中间件必须透传事件流");
    }

    @Test
    void shouldFallBackToRawSessionIdWithoutResolver() {
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, null, "my-agent", null);
        var ctx = ctx();

        middleware.onAgent(mock(Agent.class), ctx, agentInput(),
                i -> stream(new ToolCallStartEvent("r1", "call-1", "weather"),
                    new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS)))
            .blockLast();

        verify(auditStore).record(eq(ctx.getSessionId()), eq("weather"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("success"), anyString());
    }

    @Test
    void shouldNotFailWithoutMeterRegistry() {
        var middleware = new ToolCallObservabilityMiddleware(
            null, auditStore, resolver, "my-agent", null);

        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
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

        // START 行在 onActing 入口落（调用尝试口径）；无终态事件 → 无 RESULT 行、无计数
        verify(auditStore).record(any(), eq("weather"), eq("call-1"),
            eq("TOOL_CALL_START"), isNull(), isNull());
        assertEquals(0.0, registry.find("oaf.tool.calls").counters().stream()
            .mapToDouble(c -> c.count()).sum());
    }

    /**
     * HITL 确认放行（issue #421）：同一 tool call 经两次 onActing——挂起询问轮（fresh
     * 调用，state 未置）与确认恢复轮（SDK 已在上下文把它标为 ALLOWED）。START 行只允许
     * 在询问轮落一次，恢复轮不得双写；终态行由恢复流上的 ToolResultEndEvent 驱动。
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
                new ActingInput(List.of(toolCall("call-1", "shell", ToolCallState.ALLOWED))),
                i -> Flux.empty())
            .blockLast();
        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
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
     * HITL 部分拒绝（issue #486）：单轮两工具一批准一拒绝时 SDK 对被拒调用在本中间件
     * 链之外直发 DENIED 终态事件（不发 AllToolsDeniedEvent）——恢复流（onAgent 视角）
     * 上必须看到该终态并补 RESULT 行与 error 计数，START 行不因恢复轮双写。
     */
    @Test
    void shouldRecordEndAndCountForRejectedToolOnPartialHitlDeny() {
        when(resolver.canonicalSessionId(any())).thenReturn("sess-1");
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent", null);

        // 第 1 轮：挂起询问——两个 fresh 调用各落 START
        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "weather"), toolCall("call-2", "stock"))),
                i -> Flux.empty())
            .blockLast();
        // 第 2 轮：批准 weather、拒绝 stock——恢复轮 acting 只含放行调用，不落 START
        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "weather", ToolCallState.ALLOWED))),
                i -> Flux.empty())
            .blockLast();
        // 恢复流：weather 链内正常终态 + stock 由 SDK 直发的 DENIED 终态（都在流上）
        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
                i -> stream(
                    new ToolResultEndEvent("r1", "call-1", "weather", ToolResultState.SUCCESS),
                    new ToolResultEndEvent("r1", "call-2", "stock", ToolResultState.DENIED)))
            .blockLast();

        verify(auditStore, times(1)).record(eq("sess-1"), eq("weather"), eq("call-1"),
            eq("TOOL_CALL_START"), isNull(), isNull());
        verify(auditStore, times(1)).record(eq("sess-1"), eq("stock"), eq("call-2"),
            eq("TOOL_CALL_START"), isNull(), isNull());
        verify(auditStore, times(1)).record(eq("sess-1"), eq("weather"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("success"), anyString());
        verify(auditStore, times(1)).record(eq("sess-1"), eq("stock"), eq("call-2"),
            eq("TOOL_RESULT_END"), eq("error"), anyString());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "weather").tag("result", "success").counter().count());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "stock").tag("result", "error").counter().count());
    }

    /**
     * HITL 全量拒绝：SDK 对每个被拒调用直发 DENIED 终态（链内同时有 AllToolsDeniedEvent，
     * 但它不再是写点信号）——逐个补 RESULT 行与 error 计数。
     */
    @Test
    void shouldRecordEndAndCountPerDeniedToolOnFullHitlDeny() {
        when(resolver.canonicalSessionId(any())).thenReturn("sess-1");
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent", null);

        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
                i -> stream(
                    new ToolResultEndEvent("r1", "call-1", "shell", ToolResultState.DENIED),
                    new ToolResultEndEvent("r1", "call-2", "read_file", ToolResultState.DENIED),
                    new AllToolsDeniedEvent(List.of(toolCall("call-1", "shell"),
                        toolCall("call-2", "read_file")))))
            .blockLast();

        verify(auditStore, times(1)).record(any(), eq("shell"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("error"), anyString());
        verify(auditStore, times(1)).record(any(), eq("read_file"), eq("call-2"),
            eq("TOOL_RESULT_END"), eq("error"), anyString());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "shell").tag("result", "error").counter().count());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "read_file").tag("result", "error").counter().count());
    }

    /**
     * 链内规则拒绝（PermissionGate autoDenied 经 actingStream concat 进链）与直发 HITL
     * 拒绝统一由 onAgent 写点覆盖：同一 DENIED 终态在完整洋葱链上恰好落一行、计一次。
     */
    @Test
    void shouldWriteDeniedEndExactlyOnceAcrossAgentAndActingLayers() {
        when(resolver.canonicalSessionId(any())).thenReturn("sess-1");
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent", null);
        var agent = mock(Agent.class);
        var ctx = ctx();

        Function<ActingInput, Flux<AgentEvent>> terminal = i -> stream(
            new ToolResultEndEvent("r1", "call-1", "shell", ToolResultState.DENIED),
            new AllToolsDeniedEvent(List.of(toolCall("call-1", "shell"))));
        middleware.onAgent(agent, ctx, agentInput(),
                i -> middleware.onActing(agent, ctx,
                    new ActingInput(List.of(toolCall("call-1", "shell"))), terminal))
            .blockLast();

        verify(auditStore, times(1)).record(any(), eq("shell"), eq("call-1"),
            eq("TOOL_RESULT_END"), eq("error"), anyString());
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "shell").tag("result", "error").counter().count());
    }

    /** 事件流中的 ToolCallStartEvent 不触发写入（START 行唯一来源 = ActingInput 入口） */
    @Test
    void shouldNotDoubleRecordOnStartEvent() {
        when(resolver.canonicalSessionId(any())).thenReturn("sess-1");
        var middleware = new ToolCallObservabilityMiddleware(
            registry, auditStore, resolver, "my-agent", null);

        middleware.onActing(mock(Agent.class), ctx(),
                new ActingInput(List.of(toolCall("call-1", "weather"))), i -> Flux.empty())
            .blockLast();
        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
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

        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
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

        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
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

        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
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

        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
                i -> stream(new ToolResultEndEvent("r1", "call-1", "read_file",
                    ToolResultState.SUCCESS)))
            .blockLast();
        assertEquals(1.0, registry.find("oaf.tool.calls")
            .tag("tool", "__unknown__").tag("result", "success").counter().count());

        names.set(Set.of("weather", "read_file"));
        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
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

        middleware.onAgent(mock(Agent.class), ctx(), agentInput(),
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
