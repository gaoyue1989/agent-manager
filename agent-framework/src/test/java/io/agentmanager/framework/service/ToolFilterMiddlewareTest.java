package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

/**
 * {@link ToolFilterMiddleware} 单测（session-tool-list-design §4）：
 * 名单 → MCP/自定义 schema 收敛、内置恒保留；[] → 仅内置；缺省 → 直通原引用；
 * 未知名忽略、内置名不生效、远端裸名别名解析；重复/空白归一；最后一条 USER 生效；
 * 畸形 metadata 不抛；onActing 白名单外 MCP/自定义 DENY 合成、内置调用放行；
 * 非白名单轮 acting 直通（fail-open）。
 */
class ToolFilterMiddlewareTest {

    // ===== 打桩 =====

    /** 标准三分类：MCP 注册名={mcp__db__query,mcp__db__search}、自定义={get_order} */
    private static SessionToolScopeService standardScope() {
        return scope(Set.of("mcp__db__query", "mcp__db__search"), Set.of("get_order"),
            Map.of("query", List.of("mcp__db__query"), "search", List.of("mcp__db__search")));
    }

    /** 可控三分类源：mcp/custom 注册名集与远端裸名 → 注册名映射由用例给定 */
    private static SessionToolScopeService scope(Set<String> mcp, Set<String> custom,
                                                 Map<String, List<String>> wireAliases) {
        return new SessionToolScopeService(null, null) {
            @Override
            public Set<String> mcpToolNames() {
                return mcp;
            }

            @Override
            public Set<String> customToolNames() {
                return custom;
            }

            @Override
            protected List<String> byWireName(String wireName) {
                return wireAliases.getOrDefault(wireName, List.of());
            }
        };
    }

    private static Msg userMsg(Object toolsValue) {
        var md = new LinkedHashMap<String, Object>();
        if (toolsValue != null) {
            md.put(ToolFilterMiddleware.METADATA_TOOLS_KEY, toolsValue);
        }
        return Msg.builder().role(MsgRole.USER).textContent("hi").metadata(md).build();
    }

    private static Msg assistantMsg() {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent("done").build();
    }

    private static ReasoningInput reasoning(List<Msg> msgs, List<ToolSchema> tools) {
        return new ReasoningInput(msgs, tools, null);
    }

    private static ToolSchema schema(String name) {
        return ToolSchema.builder().name(name).description("d").build();
    }

    /** 全量 schema：内置 2 个 + MCP 注册名 2 个 + 自定义 1 个（模型侧可见名形态） */
    private static List<ToolSchema> allSchemas() {
        return List.of(schema("read_file"), schema("execute"),
            schema("mcp__db__query"), schema("mcp__db__search"), schema("get_order"));
    }

    /** 跑 onReasoning，捕获传给 next 的 input（未调 next 时为 null） */
    private record ReasoningRun(ReasoningInput passed, RuntimeContext ctx) {
    }

    private ReasoningRun runReasoning(List<Msg> msgs, List<ToolSchema> tools,
                                      SessionToolScopeService scope) {
        var passedRef = new AtomicReference<ReasoningInput>();
        var ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        Function<ReasoningInput, Flux<AgentEvent>> next = in -> {
            passedRef.set(in);
            return Flux.empty();
        };
        new ToolFilterMiddleware(scope).onReasoning(mock(Agent.class), ctx, reasoning(msgs, tools), next)
            .blockLast();
        return new ReasoningRun(passedRef.get(), ctx);
    }

    private static List<String> names(List<ToolSchema> schemas) {
        return schemas == null ? List.of() : schemas.stream().map(ToolSchema::getName).toList();
    }

    // ===== onReasoning：schema 收敛 =====

    @Test
    void whitelistShouldFilterBusinessToolsKeepBuiltins() {
        var scope = standardScope();
        var run = runReasoning(List.of(userMsg(List.of("mcp__db__query"))), allSchemas(), scope);

        assertEquals(List.of("read_file", "execute", "mcp__db__query"), names(run.passed().tools()),
            "内置恒保留 + 白名单内 MCP 保留；名单外 MCP/自定义剔除");
        assertNotNull(run.ctx().get(ToolFilterMiddleware.TurnToolWhitelist.class),
            "白名单必须登记 RuntimeContext（onActing 消费）");
    }

    @Test
    void emptyListShouldYieldBuiltinsOnly() {
        var scope = standardScope();
        var run = runReasoning(List.of(userMsg(List.of())), allSchemas(), scope);

        assertEquals(List.of("read_file", "execute"), names(run.passed().tools()),
            "显式 [] = 仅内置（MCP/自定义全剔除）");
    }

    @Test
    void missingKeyShouldPassThroughOriginalInput() {
        var scope = standardScope();
        var input = reasoning(List.of(userMsg(null), assistantMsg()), allSchemas());
        var passedRef = new AtomicReference<ReasoningInput>();
        var ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        new ToolFilterMiddleware(scope).onReasoning(mock(Agent.class), ctx, input,
            in -> {
                passedRef.set(in);
                return Flux.empty();
            }).blockLast();

        assertNull(ctx.get(ToolFilterMiddleware.TurnToolWhitelist.class),
            "未传 tools 不登记白名单");
        assertTrue(passedRef.get() == input, "缺省必须原引用直通（零拷贝）");
    }

    @Test
    void onlyLastUserMessageMetadataCounts() {
        var scope = standardScope();
        // 历史 USER 带 tools，最后一条 USER 不带 → 本轮未传
        var msgs = List.of(userMsg(List.of("mcp__db__query")), assistantMsg(), userMsg(null));
        var run = runReasoning(msgs, allSchemas(), scope);

        assertNull(run.ctx().get(ToolFilterMiddleware.TurnToolWhitelist.class),
            "只认最后一条 USER 消息（历史同名键属旧轮次）");
    }

    @Test
    void builtinNameInListShouldNotChangeVisibility() {
        var scope = standardScope();
        // read_file 是内置：入名单 WARN 不生效，MCP/自定义全剔除（内置恒可见）
        var run = runReasoning(List.of(userMsg(List.of("read_file"))), allSchemas(), scope);

        assertEquals(List.of("read_file", "execute"), names(run.passed().tools()),
            "内置名入名单不改变可见性（= 仅内置）");
    }

    @Test
    void wireNameAliasShouldWorkInReasoning() {
        var scope = standardScope();
        var run = runReasoning(List.of(userMsg(List.of("query"))), allSchemas(), scope);

        assertEquals(List.of("read_file", "execute", "mcp__db__query"), names(run.passed().tools()),
            "远端裸名别名归一为注册名后生效");
    }

    @Test
    void commaStringAndMalformedMetadataShouldNotThrow() {
        var scope = standardScope();
        // 逗号字符串（A2A 旧客户端形态）
        var run = runReasoning(List.of(userMsg("mcp__db__query, mcp__db__search")), allSchemas(), scope);
        assertEquals(List.of("read_file", "execute", "mcp__db__query", "mcp__db__search"),
            names(run.passed().tools()));
        // 畸形值（数字）→ fail-soft：按未知名处理（不抛、白名单生效但为空 → 仅内置）
        var run2 = runReasoning(List.of(userMsg(42)), allSchemas(), scope);
        assertEquals(List.of("read_file", "execute"), names(run2.passed().tools()));
    }

    @Test
    void noFilterNeededShouldPassOriginalReference() {
        var scope = standardScope();
        // 白名单覆盖全部业务工具 → 无过滤 → 原引用直传（零拷贝）
        var input = reasoning(List.of(
            userMsg(List.of("mcp__db__query", "mcp__db__search", "get_order"))), allSchemas());
        var passedRef = new AtomicReference<ReasoningInput>();
        var ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        new ToolFilterMiddleware(scope).onReasoning(mock(Agent.class), ctx, input,
            in -> {
                passedRef.set(in);
                return Flux.empty();
            }).blockLast();

        assertTrue(passedRef.get() == input, "全保留时必须原引用直传（零拷贝）");
        assertEquals(5, names(passedRef.get().tools()).size());
    }

    // ===== onActing：兜底 DENY =====

    private Flux<AgentEvent> runActing(RuntimeContext ctx, List<ToolUseBlock> calls,
                                       SessionToolScopeService scope,
                                       AtomicReference<ActingInput> passedRef) {
        return runActing(ctx, calls, scope, passedRef, null);
    }

    /**
     * 真实链路形态：agent.getAgentState() 是 deprecated 默认槽（与本会话无关，恒空），
     * 当前会话 state 经 ctx 注入（ReActAgent.beforeAgentExecution）——拦截注入必须落
     * per-call state（模型可感知、turn 末 write-through 可归档），严禁写默认槽。
     */
    private Flux<AgentEvent> runActing(RuntimeContext ctx, List<ToolUseBlock> calls,
                                       SessionToolScopeService scope,
                                       AtomicReference<ActingInput> passedRef,
                                       AtomicReference<java.util.List<io.agentscope.core.message.Msg>> injectedRef) {
        Function<ActingInput, Flux<AgentEvent>> next = in -> {
            passedRef.set(in);
            return Flux.empty();
        };
        var agent = mock(Agent.class);
        var defaultState = mock(io.agentscope.core.state.AgentState.class);
        when(defaultState.contextMutable()).thenReturn(new java.util.ArrayList<io.agentscope.core.message.Msg>());
        when(agent.getAgentState()).thenReturn(defaultState);
        var perCall = new java.util.ArrayList<io.agentscope.core.message.Msg>();
        var perCallState = mock(io.agentscope.core.state.AgentState.class);
        when(perCallState.contextMutable()).thenReturn(perCall);
        ctx.setAgentState(perCallState);
        if (injectedRef != null) {
            injectedRef.set(perCall);
        }
        return new ToolFilterMiddleware(scope).onActing(agent, ctx, new ActingInput(calls), next);
    }

    @Test
    void actingShouldDenyNonWhitelistedBusinessCalls() {
        var scope = standardScope();
        // 先 reasoning 登记 [] 白名单（仅内置）
        var run = runReasoning(List.of(userMsg(List.of())), allSchemas(), scope);
        var passedRef = new AtomicReference<ActingInput>();
        var injectedRef = new AtomicReference<java.util.List<io.agentscope.core.message.Msg>>();
        var events = runActing(run.ctx(), List.of(
                new ToolUseBlock("t1", "read_file", Map.of()),      // 内置 → 放行
                new ToolUseBlock("t2", "mcp__db__query", Map.of()), // MCP 白名单外 → DENY
                new ToolUseBlock("t3", "get_order", Map.of())),     // 自定义白名单外 → DENY
            scope, passedRef, injectedRef).collectList().block();

        assertNotNull(passedRef.get(), "白名单内置调用必须放行到 next");
        assertEquals(List.of("read_file"),
            passedRef.get().toolCalls().stream().map(ToolUseBlock::getName).toList(),
            "放行子集只含内置调用");
        // DENY 合成事件：每个 denied 调用 5 帧（CallStart/End + ResultStart/TextDelta/End），
        // 2 个 denied（mcp__db__query、get_order）= 10 帧，末帧 state=DENIED
        assertEquals(10, events.size());
        var last = (ToolResultEndEvent) events.get(events.size() - 1);
        assertEquals(ToolResultState.DENIED, last.getState());
        // DENIED TOOL 消息必须注入 per-call state（模型可感知、归档轨可落库），不得写默认槽
        assertEquals(2, injectedRef.get().size(),
            "DENIED 合成 TOOL 消息必须注入当前会话 per-call state");
    }

    @Test
    void actingWithoutWhitelistShouldFailOpen() {
        var scope = standardScope();
        var ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build(); // 无登记（HITL 恢复轮形态）
        var passedRef = new AtomicReference<ActingInput>();
        runActing(ctx, List.of(new ToolUseBlock("t1", "mcp__db__query", Map.of())), scope, passedRef)
            .blockLast();

        assertNotNull(passedRef.get(), "无白名单轮 fail-open（恢复轮放行原轮确认过的调用）");
    }

    @Test
    void actingBuiltinCallsNeverBlocked() {
        var scope = standardScope();
        var run = runReasoning(List.of(userMsg(List.of("mcp__db__query"))), allSchemas(), scope);
        var passedRef = new AtomicReference<ActingInput>();
        runActing(run.ctx(), List.of(new ToolUseBlock("t1", "read_file", Map.of())),
            scope, passedRef).blockLast();

        assertNotNull(passedRef.get(), "内置调用永不触发防线");
    }
}
