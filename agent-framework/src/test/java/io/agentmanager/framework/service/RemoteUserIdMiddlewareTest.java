package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.AgentInput;
import reactor.core.publisher.Flux;

/**
 * RemoteUserIdMiddleware 单测（travel-fulfillment §8 lead-4，F10）：
 * Channel 链路 peer → session_user 反查规范 userId 反射写回；sessionId 不动（§5.6）；
 * A2A/直调/未登记链路原值直通。
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

    /** Channel 链路形态：sessionId=网关 gw-hash、userId=peer（=前端 sid，session_user 已登记） */
    @Test
    void channelPeerShouldBeRewrittenToCanonicalUser() {
        var mw = middleware(storeMapping("sess-abc", "u-canonical-42"));
        var ctx = RuntimeContext.builder().sessionId("gw-hash").userId("sess-abc").build();
        var nextCalled = new AtomicBoolean(false);

        mw.onAgent(null, ctx, new AgentInput(List.of()),
                input -> {
                    nextCalled.set(true);
                    return Flux.empty();
                })
            .blockLast();

        assertTrue(nextCalled.get(), "下游链路应正常执行");
        assertEquals("u-canonical-42", ctx.getUserId(), "agent_spawn 直取的 ctx.userId 应为规范用户");
        assertEquals("gw-hash", ctx.getSessionId(), "只写 userId 不写 sessionId（设计 §5.6）");
    }

    /** A2A 链路形态：userId 已是真实用户（反查未命中回落原值），不应改写 */
    @Test
    void realUserShouldStayUnchanged() {
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("caller-sid")).thenReturn(null);
        when(store.findUserIdBySession("u-real")).thenReturn(null);
        var mw = middleware(store);
        var ctx = RuntimeContext.builder().sessionId("caller-sid").userId("u-real").build();

        mw.onAgent(null, ctx, new AgentInput(List.of()), input -> Flux.empty()).blockLast();

        assertEquals("u-real", ctx.getUserId());
    }

    /** 未登记会话（如 gw-hash 桶未知用户）：fail-soft 原值直通，不阻断调用 */
    @Test
    void unregisteredSessionShouldFallBackToRawValue() {
        var store = mock(SessionUserStore.class);
        var mw = middleware(store);
        var ctx = RuntimeContext.builder().sessionId("gw-hash").userId("peer-unknown").build();
        var nextCalled = new AtomicBoolean(false);

        mw.onAgent(null, ctx, new AgentInput(List.of()),
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

        mw.onAgent(null, ctx, new AgentInput(List.of()),
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

        mw.onAgent(null, null, new AgentInput(List.of()),
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

        mw.onAgent(null, ctx, new AgentInput(List.of()), input -> Flux.empty()).blockLast();

        assertEquals("", ctx.getUserId());
    }
}
