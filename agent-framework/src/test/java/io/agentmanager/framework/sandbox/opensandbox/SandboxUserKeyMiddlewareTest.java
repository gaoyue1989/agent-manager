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
 * ThreadLocal 用后即清（防跨 turn 泄漏污染下个 create 的 bindUserKey）、
 * 本 turn 沙箱从 RuntimeContext 的 SandboxAcquireResult 寻址（并发下不串他人沙箱）。
 */
class SandboxUserKeyMiddlewareTest {

    private static RuntimeContext ctx(String sessionId, String userId) {
        return RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    /** 装配 mock 沙箱实例（getOsbState 供日志取 sandboxId） */
    private static OpenSandbox mockSandbox(String sandboxId) {
        var sandbox = mock(OpenSandbox.class);
        var state = new OpenSandboxState();
        state.setSandboxId(sandboxId);
        when(sandbox.getOsbState()).thenReturn(state);
        return sandbox;
    }

    /** 模拟 HarnessAgent.acquireForCall 预置：本 turn 沙箱放入 RuntimeContext（SandboxAcquireResult） */
    private static RuntimeContext turnCtx(String sessionId, String userId, OpenSandbox sandbox) {
        var c = ctx(sessionId, userId);
        if (sandbox != null) {
            c.put(io.agentscope.harness.agent.sandbox.SandboxAcquireResult.class,
                io.agentscope.harness.agent.sandbox.SandboxAcquireResult.userManaged(sandbox));
        }
        return c;
    }

    private static void run(SandboxUserKeyMiddleware mw, RuntimeContext ctx) {
        Function<AgentInput, Flux<AgentEvent>> next = input -> Flux.<AgentEvent>empty();
        mw.onAgent(mock(Agent.class), ctx, (AgentInput) null, next).blockLast();
    }

    @Test
    void shouldResolveRealUserViaSessionLookup() {
        // Channel 链路：ctx.sessionId=共享 gw-hash、ctx.userId=前端 sessionId（peer）
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = mockSandbox("sbx-1");
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("gw-hash")).thenReturn(null);
        when(store.findUserIdBySession("front-sid")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(store));

        run(mw, turnCtx("gw-hash", "front-sid", sandbox));

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
        var sandbox = mockSandbox("sbx-1");
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession(anyString())).thenReturn(null);
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(store));

        run(mw, turnCtx("s-1", "u-42", sandbox));

        verify(sandbox).setUserKey("u-42");
    }

    @Test
    void shouldResolveBySessionIdWhenUserIdBlank() {
        // issue #52 修复点：userKey 取空时按 sessionId 反查，反查到即用真实用户
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = mockSandbox("sbx-1");
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession("s-1")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(store));

        run(mw, turnCtx("s-1", " ", sandbox));

        verify(sandbox).setUserKey("alice");
    }

    @Test
    void shouldDegradeToSessionIdWhenNothingResolves() {
        // 未登记会话（debug 页面等）：保持旧行为降级 sessionId
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = mockSandbox("sbx-1");
        var store = mock(SessionUserStore.class);
        when(store.findUserIdBySession(anyString())).thenReturn(null);
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(store));

        run(mw, turnCtx("s-1", null, sandbox));

        verify(sandbox).setUserKey("s-1");
    }

    @Test
    void shouldFallBackToLegacyResolutionWithoutResolver() {
        // 未装配 resolver（旧构造）：userId 优先，空则 sessionId（行为不变）
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = mockSandbox("sbx-1");
        var mw = new SandboxUserKeyMiddleware(spec);

        run(mw, turnCtx("s-1", "u-42", sandbox));
        verify(sandbox).setUserKey("u-42");

        run(mw, turnCtx("s-1", " ", sandbox));
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
        var sandbox = mockSandbox("sbx-1");
        var store = mock(io.agentmanager.framework.service.FileAssetStore.class);
        spec.fileAssetStore(store);
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(mock(SessionUserStore.class)));

        run(mw, turnCtx("s-1", "alice", sandbox));
        verify(store, times(1)).resetInjectedToPending("alice");

        run(mw, turnCtx("s-1", "alice", sandbox));
        verify(store, times(1)).resetInjectedToPending("alice");
        verify(sandbox, times(2)).setUserKey("alice");
    }

    @Test
    void shouldBindOnlyItsOwnTurnSandboxUnderConcurrency() {
        // 并发串沙箱回归：两个并发 turn 各自 ctx 持有自己的沙箱，onAgent 只重绑本 turn 的沙箱
        // （历史上 spec.latestSandbox 进程级指针会被其他用户 create/resume 覆盖 → 误绑他人沙箱）
        var spec = new OpenSandboxFilesystemSpec();
        var sandboxA = mockSandbox("sbx-a");
        var sandboxB = mockSandbox("sbx-b");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(mock(SessionUserStore.class)));

        run(mw, turnCtx("s-a", "alice", sandboxA));
        run(mw, turnCtx("s-b", "bob", sandboxB));

        verify(sandboxA, never()).setUserKey("bob");
        verify(sandboxB, never()).setUserKey("alice");
        verify(sandboxA).setUserKey("alice");
        verify(sandboxB).setUserKey("bob");
    }

    @Test
    void shouldNotTouchSandboxWhenTurnHasNoAcquireResult() {
        // 本 turn ctx 无 SandboxAcquireResult（acquire 失败/非沙箱部署）时，
        // middleware 不得回退任何全局指针去重绑/注入他人沙箱
        var spec = new OpenSandboxFilesystemSpec();
        var foreignSandbox = mockSandbox("sbx-other");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(mock(SessionUserStore.class)));

        run(mw, ctx("s-1", "alice"));

        verify(foreignSandbox, never()).setUserKey(anyString());
        verify(foreignSandbox, never()).injectPendingUploads();
        verify(foreignSandbox, never()).materializeUserSkills();
    }
}
