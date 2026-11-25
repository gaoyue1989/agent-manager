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
        when(store.findUserIdBySession("gw-3f20f08c5499")).thenReturn(null);
        when(store.findUserIdBySession("front-sid")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(store));

        run(mw, turnCtx("gw-3f20f08c5499", "front-sid", sandbox));

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
        verify(store, times(1)).resetInjectedToPending("s-1");

        run(mw, turnCtx("s-1", "alice", sandbox));
        verify(store, times(1)).resetInjectedToPending("s-1");
        verify(sandbox, times(2)).setUserKey("alice");
    }

    @Test
    void shouldResetInjectedStateUsePendingSessionIdWhenUserIdBlank() {
        // userId 空白：pendingSessionId 降级取 sessionId（与 userKey 降级同值），reset 与
        // setSessionId 透传均按会话键执行（issue #345 P2 会话化语义）
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = mockSandbox("sbx-1");
        var store = mock(io.agentmanager.framework.service.FileAssetStore.class);
        spec.fileAssetStore(store);
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(mock(SessionUserStore.class)));

        run(mw, turnCtx("s-9", " ", sandbox));

        verify(store).resetInjectedToPending("s-9");
        verify(sandbox).setSessionId("s-9");
    }

    @Test
    void shouldResetInjectedStateScopeByPendingSessionIdNotUserKey() {
        // resolver 命中（Channel 链路：ctx.sessionId=共享 gw-hash、ctx.userId=前端 sid）：
        // userKey=alice ≠ pendingSessionId=front-sid（userId 优先推导）——reset 必须按会话键
        // 传参（issue #345 P2），任何回退 userKey 维度传参/透传的改动在此红
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = mockSandbox("sbx-1");
        var store = mock(io.agentmanager.framework.service.FileAssetStore.class);
        spec.fileAssetStore(store);
        var sessionStore = mock(SessionUserStore.class);
        when(sessionStore.findUserIdBySession("gw-3f20f08c5499")).thenReturn(null);
        when(sessionStore.findUserIdBySession("front-sid")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(sessionStore));

        run(mw, turnCtx("gw-3f20f08c5499", "front-sid", sandbox));

        verify(store).resetInjectedToPending("front-sid");
        verify(sandbox).setSessionId("front-sid");
        verify(sandbox).setUserKey("alice");
        verify(store, never()).resetInjectedToPending("alice");
    }

    @Test
    void shouldResetOncePerSessionAcrossAlternatingTurnsOfSameUser() {
        // B-13b（issue #413）：换代索引必须按会话键——Channel 链路同一真实用户（alice）两个
        // 前端会话（front-sid-a/b）交替时，旧 userKey 键实现的单槽索引被 B 会话翻转，回到 A
        // 误判换代 → 冗余 reset+重注入（覆写容器内已被 agent 修改的上传件）。A→B→A 交替，
        // 每 session 恰 reset 一次。
        var spec = new OpenSandboxFilesystemSpec();
        var sandboxA = mockSandbox("sbx-a");
        var sandboxB = mockSandbox("sbx-b");
        var store = mock(io.agentmanager.framework.service.FileAssetStore.class);
        spec.fileAssetStore(store);
        var sessionStore = mock(SessionUserStore.class);
        when(sessionStore.findUserIdBySession(anyString())).thenReturn(null);
        when(sessionStore.findUserIdBySession("front-sid-a")).thenReturn("alice");
        when(sessionStore.findUserIdBySession("front-sid-b")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(sessionStore));

        run(mw, turnCtx("gw-3f20f08c5498", "front-sid-a", sandboxA));
        run(mw, turnCtx("gw-3f20f08c5497", "front-sid-b", sandboxB));
        run(mw, turnCtx("gw-3f20f08c5498", "front-sid-a", sandboxA));

        verify(store, times(1)).resetInjectedToPending("front-sid-a");
        verify(store, times(1)).resetInjectedToPending("front-sid-b");
    }

    @Test
    void shouldInvalidateUploadMemoWhenGenerationResetRolledBackRows() {
        // B-13a（issue #413）：换代 reset 确有行回滚（n>0）→ 必须显式失效沙箱实例的注入备忘
        // （pendingEmptyChecked/uploadsInjected）——否则 wrapReused/resume 换代后实例备忘命中
        // 稳态短路，本 turn injectPendingUploads 不重查，历史上传件不重注入（首轮 404）
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = mockSandbox("sbx-gen-1");
        var store = mock(io.agentmanager.framework.service.FileAssetStore.class);
        when(store.resetInjectedToPending("front-sid")).thenReturn(1);
        spec.fileAssetStore(store);
        var sessionStore = mock(SessionUserStore.class);
        when(sessionStore.findUserIdBySession(anyString())).thenReturn(null);
        when(sessionStore.findUserIdBySession("front-sid")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(sessionStore));

        run(mw, turnCtx("gw-3f20f08c5499", "front-sid", sandbox));

        verify(sandbox).invalidatePendingUploadMemo();
    }

    @Test
    void shouldNotInvalidateUploadMemoWhenGenerationResetRolledNothingBack() {
        // n=0（同代重入/无 injected 行）：不失效备忘——保住 P2-16 稳态短路（实例每 doExec
        // 跑 hydrateForCall，无条件失效=每次换代判定白付一次 file_asset 查询）
        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = mockSandbox("sbx-gen-2");
        var store = mock(io.agentmanager.framework.service.FileAssetStore.class);
        when(store.resetInjectedToPending("front-sid")).thenReturn(0);
        spec.fileAssetStore(store);
        var sessionStore = mock(SessionUserStore.class);
        when(sessionStore.findUserIdBySession(anyString())).thenReturn(null);
        when(sessionStore.findUserIdBySession("front-sid")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(sessionStore));

        run(mw, turnCtx("gw-3f20f08c5499", "front-sid", sandbox));

        verify(sandbox, never()).invalidatePendingUploadMemo();
    }

    @Test
    void shouldSkipGenerationResetWhenSharedResetMarkHitsCrossReplica() {
        // 评审 g1（issue #413）：会话轮转到新副本/Pod 重启/LRU 淘汰丢 Pod 级换代标记 →
        // 共享层命中 = 同代已 reset → 跳过 reset+invalidate。否则 DB 行回滚 pending →
        // 原始上传字节重注入覆写容器内已被 agent 修改的文件（静默数据丢失，违反架构
        // 原则二「任意请求可落任意副本」）
        var spec = new OpenSandboxFilesystemSpec();
        var shared = mock(ResetMarkStore.class);
        when(shared.marked("front-sid", "sbx-gen-9")).thenReturn(true);
        spec.resetMarkStore(shared);
        var sandbox = mockSandbox("sbx-gen-9");
        var store = mock(io.agentmanager.framework.service.FileAssetStore.class);
        spec.fileAssetStore(store);
        var sessionStore = mock(SessionUserStore.class);
        when(sessionStore.findUserIdBySession(anyString())).thenReturn(null);
        when(sessionStore.findUserIdBySession("front-sid")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(sessionStore));

        run(mw, turnCtx("gw-3f20f08c5499", "front-sid", sandbox));

        verify(store, never()).resetInjectedToPending(anyString());
        verify(sandbox, never()).invalidatePendingUploadMemo();
        verify(sandbox).injectPendingUploads();
    }

    @Test
    void generationResetShouldRecordSharedMarkForOtherReplicas() {
        // reset 决策落定即共享层双写：他副本/重启后凭 mark 跳过同代重复 reset
        var spec = new OpenSandboxFilesystemSpec();
        var shared = mock(ResetMarkStore.class);
        when(shared.marked(anyString(), anyString())).thenReturn(false);
        spec.resetMarkStore(shared);
        var sandbox = mockSandbox("sbx-gen-10");
        var store = mock(io.agentmanager.framework.service.FileAssetStore.class);
        when(store.resetInjectedToPending("front-sid")).thenReturn(1);
        spec.fileAssetStore(store);
        var sessionStore = mock(SessionUserStore.class);
        when(sessionStore.findUserIdBySession(anyString())).thenReturn(null);
        when(sessionStore.findUserIdBySession("front-sid")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(sessionStore));

        run(mw, turnCtx("gw-3f20f08c5499", "front-sid", sandbox));

        verify(store).resetInjectedToPending("front-sid");
        verify(shared).mark("front-sid", "sbx-gen-10");
    }

    @Test
    void shouldNotMarkResetWhenDbUpdateFailsAndRetryNextTurn() {
        // issue #497：reset 的 DB 更新瞬时失败（返回 -1）= 重置未真正执行——此时写入
        // 7 天 ResetMarkStore 会把「该代已 reset」错误记账，后续所有 turn/副本凭命中跳过
        // reset，历史上传件在重建容器内永久缺失。失败 turn 不记账（本代保持未 reset，
        // 下 turn 重试），DB 恢复后的 turn 才完成 reset 并记账。
        var spec = new OpenSandboxFilesystemSpec();
        var shared = mock(ResetMarkStore.class);
        when(shared.marked(anyString(), anyString())).thenReturn(false);
        spec.resetMarkStore(shared);
        var sandbox = mockSandbox("sbx-gen-11");
        var store = mock(io.agentmanager.framework.service.FileAssetStore.class);
        when(store.resetInjectedToPending("front-sid")).thenReturn(-1).thenReturn(1);
        spec.fileAssetStore(store);
        var sessionStore = mock(SessionUserStore.class);
        when(sessionStore.findUserIdBySession(anyString())).thenReturn(null);
        when(sessionStore.findUserIdBySession("front-sid")).thenReturn("alice");
        var mw = new SandboxUserKeyMiddleware(spec, new SessionKeyResolver(sessionStore));

        run(mw, turnCtx("gw-3f20f08c5499", "front-sid", sandbox));

        verify(store, times(1)).resetInjectedToPending("front-sid");
        verify(shared, never()).mark(anyString(), anyString());
        verify(sandbox, never()).invalidatePendingUploadMemo();

        run(mw, turnCtx("gw-3f20f08c5499", "front-sid", sandbox));

        verify(store, times(2)).resetInjectedToPending("front-sid");
        verify(shared).mark("front-sid", "sbx-gen-11");
        verify(sandbox).invalidatePendingUploadMemo();
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

    @Test
    void onAgentShouldUseCanonicalSessionIdInA2aModeInsteadOfUserId() {
        var spec = mock(OpenSandboxFilesystemSpec.class);
        var resolver = mock(io.agentmanager.framework.service.SessionKeyResolver.class);
        var ctx = RuntimeContext.builder().userId("u-1").sessionId("a2a-A").build();
        when(resolver.realUserId(ctx)).thenReturn("u-1");
        when(resolver.canonicalSessionId(ctx)).thenReturn("a2a-A");

        var sandbox = mock(OpenSandbox.class);
        var osbState = mock(OpenSandboxState.class);
        when(osbState.getSandboxId()).thenReturn("sbx-a2a-A");
        when(sandbox.getOsbState()).thenReturn(osbState);
        var acquire = mock(io.agentscope.harness.agent.sandbox.SandboxAcquireResult.class);
        when(acquire.getSandbox()).thenReturn(sandbox);
        ctx.put(io.agentscope.harness.agent.sandbox.SandboxAcquireResult.class, acquire);

        var store = mock(io.agentmanager.framework.service.FileAssetStore.class);
        when(spec.getFileAssetStore()).thenReturn(store);
        when(spec.isInjectedOnSandbox("a2a-A", "sbx-a2a-A")).thenReturn(false);
        when(store.resetInjectedToPending("a2a-A")).thenReturn(1);

        var mw = new SandboxUserKeyMiddleware(spec, resolver);
        mw.onAgent(mock(Agent.class), ctx, mock(AgentInput.class), i -> Flux.empty()).blockLast();

        verify(spec).setPendingSessionId("a2a-A");
        verify(sandbox).setSessionId("a2a-A");
        verify(store).resetInjectedToPending("a2a-A");
        verify(spec).markInjectedOnSandbox("a2a-A", "sbx-a2a-A");
    }
}
