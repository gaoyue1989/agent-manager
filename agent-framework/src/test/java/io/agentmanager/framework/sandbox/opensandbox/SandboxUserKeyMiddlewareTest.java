package io.agentmanager.framework.sandbox.opensandbox;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.SessionKeyResolver;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link SandboxUserKeyMiddleware} 单元测试：userKey 经 SessionKeyResolver 反查真实用户
 * （Channel 链路 ctx.userId 是网关 peer=前端 sessionId，issue #52）、反查未命中回落
 * RuntimeContext 原值（A2A / invoke）、空值降级 sessionId、注入 reset 每沙箱代一次、
 * ThreadLocal 用后即清（防跨 turn 泄漏污染下个 create 的 bindUserKey）。
 */
class SandboxUserKeyMiddlewareTest {

    private static RuntimeContext ctx(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    /** 装配 mock 沙箱实例并注册为 spec.latestSandbox（onAgent 直接绑定的观察点） */
    private static OpenSandbox latestSandbox(OpenSandboxFilesystemSpec spec, String sandboxId) {
        var sandbox = mock(OpenSandbox.class);
        var state = new OpenSandboxState();
        state.setSandboxId(sandboxId);
        when(sandbox.getOsbState()).thenReturn(state);
        spec.registerSandbox(sandbox);
        return sandbox;
    }

    private static void run(SandboxUserKeyMiddleware mw, RuntimeContext ctx) {
        Function<AgentInput, Flux<AgentEvent>> next = input -> Flux.<AgentEvent>empty();
        mw.onAgent(mock(Agent.class), ctx, (AgentInput) null, next).blockLast();
    }

    @Test
    void shouldResolveRealUserViaSessionLookup() {
        // Channel 链路：ctx.sessionId=共享 gw-hash、ctx.userId=前端 sessionId（peer）
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = latestSandbox(spec, "sbx-1");
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("gw-hash")).thenReturn(null);
        when(store.findUserIdBySession("front-sid")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(store));

        run(mw, ctx("gw-hash", "front-sid"));

        verify(sandbox).setUserKey("alice");
        // per-user L4 物化与上传注入均按真实用户命名空间执行
        verify(sandbox).materializeUserSkills();
        verify(sandbox).injectPendingUploads();
        // ThreadLocal 用后即清：残留值会被同线程下个 turn 的 create/bindUserKey 消费
        assertNull(spec.peekPendingUserKey());
    }

    @Test
    void shouldFallbackToRawUserIdWhenSessionUnmapped() {
        // A2A / invoke 链路：反查未命中 → 原值（userId 优先）
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = latestSandbox(spec, "sbx-1");
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession(anyString())).thenReturn(null);
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(store));

        run(mw, ctx("s-1", "u-42"));

        verify(sandbox).setUserKey("u-42");
    }

    @Test
    void shouldResolveBySessionIdWhenUserIdBlank() {
        // issue #52 修复点：userKey 取空时按 sessionId 反查，反查到即用真实用户
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = latestSandbox(spec, "sbx-1");
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("s-1")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(store));

        run(mw, ctx("s-1", " "));

        verify(sandbox).setUserKey("alice");
    }

    @Test
    void shouldDegradeToSessionIdWhenNothingResolves() {
        // 未登记会话（debug 页面等）：保持旧行为降级 sessionId
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = latestSandbox(spec, "sbx-1");
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession(anyString())).thenReturn(null);
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(store));

        run(mw, ctx("s-1", null));

        verify(sandbox).setUserKey("s-1");
    }

    @Test
    void shouldFallBackToLegacyResolutionWithoutResolver() {
        // 未装配 resolver（旧构造）：userId 优先，空则 sessionId（行为不变）
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = latestSandbox(spec, "sbx-1");
        var mw = new SandboxUserKeyMiddleware(spec);

        run(mw, ctx("s-1", "u-42"));
        verify(sandbox).setUserKey("u-42");

        run(mw, ctx("s-1", " "));
        verify(sandbox).setUserKey("s-1");
    }

    @Test
    void shouldTolerateNullContext() {
        var spec = new OpenSandboxFilesystemSpec();
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(mock(SessionUserStore.class)));

        assertDoesNotThrow(() -> run(mw, null));
        assertNull(spec.peekPendingUserKey());
    }

    @Test
    void shouldResetInjectedStateOncePerSandboxGeneration() {
        // 新沙箱代首次 onAgent：回滚注入状态并标记；同代重复 onAgent 不再 reset（防循环）
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = latestSandbox(spec, "sbx-1");
        var store = mock(io.agentmanager.framework.service.FileAssetStore.class);
        spec.fileAssetStore(store);
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(mock(SessionUserStore.class)));

        run(mw, ctx("s-1", "alice"));
        verify(store, times(1)).resetInjectedToPending("alice");

        run(mw, ctx("s-1", "alice"));
        verify(store, times(1)).resetInjectedToPending("alice");
        verify(sandbox, times(2)).setUserKey("alice");
    }
}
