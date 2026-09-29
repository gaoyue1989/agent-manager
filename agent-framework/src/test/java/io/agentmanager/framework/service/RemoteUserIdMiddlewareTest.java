package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Flux;

/**
 * RemoteUserIdMiddleware 单测（travel-fulfillment §8 lead-4，F10）：
 * 仅纯 agent_spawn 轮次在 acting 作用域内写回规范 userId、Flux 终止即恢复原值
 * （PR #62 门禁教训：全程改写破坏 SDK HITL 挂起/恢复的 ctx 键一致性）；
 * sessionId 不动（§5.6）；A2A/直调/未登记链路与混编/非 spawn 轮次原值直通。
 */
class RemoteUserIdMiddlewareTest {

    private RemoteUserIdMiddleware middleware(SessionUserStore store) {
        return new RemoteUserIdMiddleware(store);
    }

    private SessionUserStore storeMapping(String session, String user) {
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession(session)).thenReturn(user);
        return store;
    }

    private static ToolUseBlock call(String name) {
        return ToolUseBlock.builder().id("tc-" + name).name(name).input(Map.of("k", "v")).build();
    }

    /** Channel 链路形态：纯 spawn 轮次内 userId 写回为规范用户，轮次终止后恢复原值 */
    @Test
    void pureSpawnRoundShouldRewriteDuringExecutionAndRestoreAfter() {
        var mw = middleware(storeMapping("sess-abc", "u-canonical-42"));
        var ctx = RuntimeContext.builder().sessionId("gw-hash").userId("sess-abc").build();
        var seenDuringExecution = new AtomicReference<String>();
        var nextCalled = new AtomicBoolean(false);

        mw.onActing(null, ctx,
                new ActingInput(List.of(call("agent_spawn"))),
                input -> {
                    nextCalled.set(true);
                    seenDuringExecution.set(ctx.getUserId());
                    return Flux.empty();
                })
            .blockLast();

        assertTrue(nextCalled.get(), "下游链路应正常执行");
        assertEquals("u-canonical-42", seenDuringExecution.get(),
            "agent_spawn 执行期 ctx.userId 应为规范用户（F10）");
        assertEquals("sess-abc", ctx.getUserId(), "轮次终止后应恢复原值（HITL 键一致性）");
        assertEquals("gw-hash", ctx.getSessionId(), "只写 userId 不写 sessionId（设计 §5.6）");
    }

    /** HITL ask 轮次（非 spawn 工具）：完全不触碰 ctx——挂起/恢复路径身份不被改写 */
    @Test
    void nonSpawnRoundMustNotTouchContext() {
        var mw = middleware(storeMapping("sess-abc", "u-canonical-42"));
        var ctx = RuntimeContext.builder().sessionId("gw-hash").userId("sess-abc").build();

        mw.onActing(null, ctx,
                new ActingInput(List.of(call("echo_query"))),
                input -> Flux.empty())
            .blockLast();

        assertEquals("sess-abc", ctx.getUserId(), "非 spawn 轮次不得改写 ctx");
    }

    /** 混编轮次（spawn 与其他工具同轮）：保守跳过，不改写 */
    @Test
    void mixedRoundShouldBeSkipped() {
        var mw = middleware(storeMapping("sess-abc", "u-canonical-42"));
        var ctx = RuntimeContext.builder().sessionId("gw-hash").userId("sess-abc").build();

        mw.onActing(null, ctx,
                new ActingInput(List.of(call("agent_spawn"), call("echo_query"))),
                input -> Flux.empty())
            .blockLast();

        assertEquals("sess-abc", ctx.getUserId(), "混编轮次保守跳过");
    }

    /** A2A 链路形态：userId 已是真实用户（反查未命中回落原值），不应改写 */
    @Test
    void realUserShouldStayUnchanged() {
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("u-real")).thenReturn(null);
        var mw = middleware(store);
        var ctx = RuntimeContext.builder().sessionId("caller-sid").userId("u-real").build();

        mw.onActing(null, ctx,
                new ActingInput(List.of(call("agent_spawn"))),
                input -> Flux.empty())
            .blockLast();

        assertEquals("u-real", ctx.getUserId());
    }

    /** 未登记会话（如 gw-hash 桶未知用户）：fail-soft 原值直通，不阻断调用 */
    @Test
    void unregisteredSessionShouldFallBackToRawValue() {
        var store = mock(SessionUserStore.class);
        var mw = middleware(store);
        var ctx = RuntimeContext.builder().sessionId("gw-hash").userId("peer-unknown").build();
        var nextCalled = new AtomicBoolean(false);

        mw.onActing(null, ctx,
                new ActingInput(List.of(call("agent_spawn"))),
                input -> {
                    nextCalled.set(true);
                    return Flux.empty();
                })
            .blockLast();

        assertTrue(nextCalled.get());
        assertEquals("peer-unknown", ctx.getUserId());
    }

    /** session_user 反查抛错（DB 不可用）：降级原值，调用链不断 */
    @Test
    void lookupFailureShouldNotBreakTheCall() {
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("sess-abc")).thenThrow(new RuntimeException("db down"));
        var mw = middleware(store);
        var ctx = RuntimeContext.builder().sessionId("gw-hash").userId("sess-abc").build();
        var nextCalled = new AtomicBoolean(false);

        mw.onActing(null, ctx,
                new ActingInput(List.of(call("agent_spawn"))),
                input -> {
                    nextCalled.set(true);
                    return Flux.empty();
                })
            .blockLast();

        assertTrue(nextCalled.get());
        assertEquals("sess-abc", ctx.getUserId());
    }

    @Test
    void nullContextShouldPassThrough() {
        var mw = middleware(storeMapping("sess-abc", "u-42"));
        var nextCalled = new AtomicBoolean(false);

        mw.onActing(null, null,
                new ActingInput(List.of(call("agent_spawn"))),
                input -> {
                    nextCalled.set(true);
                    return Flux.empty();
                })
            .blockLast();

        assertTrue(nextCalled.get());
    }

    @Test
    void blankUserIdShouldPassThrough() {
        var mw = middleware(storeMapping("sess-abc", "u-42"));
        var ctx = RuntimeContext.builder().sessionId("gw-hash").userId("").build();

        mw.onActing(null, ctx,
                new ActingInput(List.of(call("agent_spawn"))),
                input -> Flux.empty())
            .blockLast();

        assertEquals("", ctx.getUserId());
    }

    /** 工具调用为空的非 acting 轮次：直通 */
    @Test
    void emptyToolCallsShouldPassThrough() {
        var mw = middleware(storeMapping("sess-abc", "u-canonical-42"));
        var ctx = RuntimeContext.builder().sessionId("gw-hash").userId("sess-abc").build();

        mw.onActing(null, ctx, new ActingInput(List.of()), input -> Flux.empty()).blockLast();

        assertEquals("sess-abc", ctx.getUserId());
    }
}
