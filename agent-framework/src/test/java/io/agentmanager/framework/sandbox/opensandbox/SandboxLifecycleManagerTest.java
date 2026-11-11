package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;

import io.agentmanager.framework.config.SandboxLifecycleConfig;
import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.TurnLeaseStore;

/**
 * U3 {@code SandboxLifecycleManagerTest}（U3 分支枚举即 DoD，sandbox-pool-opensandbox-design §9.1）：
 * acquire 主干（① 命中 touch 先于 probe / touch 0 行 / probe 404 重建 / 配额内 create），
 * 配额满（LRU 改绑乐观校验 / 全忙异常 / busy 双事实源），并发重试（rebind affected=0 → ⑤ 重试），
 * fail-open（直建不记账 + 限流），预检（409 / 行龄过滤 / fail-open / userKey 顺序），
 * 池耗尽补偿，启动校验，quota=0 不限。
 */
class SandboxLifecycleManagerTest {

    private static final String SID = "sess-1";
    private static final String USER = "user-1";
    private static final String SBX = "sbx-1";

    private SandboxBindingStore store;
    private SandboxRenewalRegistry registry;
    private TurnLeaseStore leaseStore;
    private ConfirmContextStore confirmStore;
    private SandboxLifecycleManager manager;

    private SandboxLifecycleManager.SandboxConnector connector;
    private SandboxLifecycleManager.SandboxCreateDelegate createDelegate;
    private SandboxLifecycleManager.SandboxReuseWrapper reuseWrapper;
    private Sandbox osbHandle;

    private static SandboxBindingStore.BindingRow row(String sessionId, String sandboxId,
                                                      String expiresAt, String updatedAt, boolean fresh) {
        return new SandboxBindingStore.BindingRow(1L, USER, sessionId, sandboxId, expiresAt, updatedAt, fresh);
    }

    @BeforeEach
    void setUp() throws Exception {
        store = mock(SandboxBindingStore.class);
        registry = mock(SandboxRenewalRegistry.class);
        leaseStore = mock(TurnLeaseStore.class);
        confirmStore = mock(ConfirmContextStore.class);
        var config = new SandboxLifecycleConfig(true, "pool-e2e", 5, 60, 20, 2, 1000, false);
        manager = new SandboxLifecycleManager(store, registry, config, 30, leaseStore, confirmStore);
        connector = mock(SandboxLifecycleManager.SandboxConnector.class);
        createDelegate = mock(SandboxLifecycleManager.SandboxCreateDelegate.class);
        reuseWrapper = mock(SandboxLifecycleManager.SandboxReuseWrapper.class);
        osbHandle = mock(Sandbox.class);
        when(connector.probe(anyString())).thenReturn(
            SandboxLifecycleManager.ProbeResult.unknown());   // 默认保守；用例按需覆盖
        when(reuseWrapper.wrap(any(), anyString())).thenReturn(mock(OpenSandbox.class));
    }

    private SandboxLifecycleManager.CreatedSandbox created(String sandboxId) {
        return new SandboxLifecycleManager.CreatedSandbox(sandboxId, "2026-11-08T01:00:00Z",
            mock(OpenSandbox.class), osbHandle);
    }

    // ---- acquire 主干 ----

    @Test
    void acquireHitTouchesBeforeProbeThenReuses() throws Exception {
        var existing = row(SID, SBX, "2026-11-08T00:20:00.000", "2026-11-07T23:00:00.000", false);
        when(store.findBySessionId(SID)).thenReturn(Optional.of(existing));
        when(store.touchOnReuse(existing.id(), SID)).thenReturn(true);
        when(connector.probe(SBX)).thenReturn(SandboxLifecycleManager.ProbeResult.alive(osbHandle));

        manager.acquire(SID, USER, connector, createDelegate, reuseWrapper);

        // 终审 d2：touch 先行（先于 probe），probe 200 → connect 复用（resume 语义）
        InOrder order = inOrder(store, connector);
        order.verify(store).touchOnReuse(existing.id(), SID);
        order.verify(connector).probe(SBX);
        verify(store, never()).occupyQuota(anyString(), anyString(), anyInt());
        org.mockito.Mockito.verifyNoInteractions(createDelegate);
        verify(registry).acquireRef(eq(SBX), eq(SID), eq(1800L), any());
        verify(reuseWrapper).wrap(osbHandle, SBX);
    }

    @Test
    void acquireTouchLostRaceFallsThroughToOccupyAndCreate() throws Exception {
        when(store.findBySessionId(SID)).thenReturn(Optional.of(row(SID, SBX,
            "2026-11-08T00:20:00.000", "2026-11-07T23:00:00.000", false)));
        when(store.touchOnReuse(anyLong(), anyString())).thenReturn(false);   // 已被改绑/懒剔除
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.OCCUPIED, "pending:u1"));
        when(createDelegate.create()).thenReturn(created(SBX));

        manager.acquire(SID, USER, connector, createDelegate, reuseWrapper);

        // affected=0 → 放弃复用（不 connect）、视同 miss 走 ②（终审 d2）
        verify(connector, never()).probe(anyString());
        verify(store).occupyQuota(USER, SID, 5);
        verify(store).promotePending(eq("pending:u1"), eq(SBX), anyString());
        verify(registry).acquireRef(eq(SBX), eq(SID), eq(1800L), any());
    }

    @Test
    void acquireProbe404LazilyDeletesOwnRowThenRebuilds() throws Exception {
        var existing = row(SID, SBX, "2026-11-08T00:20:00.000", "2026-11-07T23:00:00.000", false);
        when(store.findBySessionId(SID)).thenReturn(Optional.of(existing));
        when(store.touchOnReuse(anyLong(), anyString())).thenReturn(true);
        when(connector.probe(SBX)).thenReturn(SandboxLifecycleManager.ProbeResult.dead());   // getInfo 404/410
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.OCCUPIED, "pending:u2"));
        when(createDelegate.create()).thenReturn(created("sbx-2"));

        manager.acquire(SID, USER, connector, createDelegate, reuseWrapper);

        // ① probe 404 → 懒剔除自己行 + count-1 → 走 ② 重建（§4.4 到期重建语义）
        verify(store).lazyDeleteOwned(SBX, SID);
        verify(store).occupyQuota(USER, SID, 5);
        verify(store).promotePending(eq("pending:u2"), eq("sbx-2"), anyString());
    }

    @Test
    void acquireMissCreatesWithinQuotaAndPromotesWithServerExpiresAt() throws Exception {
        when(store.findBySessionId(SID)).thenReturn(Optional.empty());
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.OCCUPIED, "pending:u3"));
        when(createDelegate.create()).thenReturn(created("sbx-3"));

        manager.acquire(SID, USER, connector, createDelegate, reuseWrapper);

        // 断言 extensions.poolRef/metadata/timeout 由 client 侧透传（本层验证转正回填服务端绝对值）
        verify(store).promotePending("pending:u3", "sbx-3", "2026-11-08T01:00:00Z");
        verify(store, never()).compensatePending(anyString(), anyString());
        verify(registry).acquireRef(eq("sbx-3"), eq(SID), eq(1800L), any());
    }

    @Test
    void acquirePromoteMissedOnlyWarnsCountUntouched() throws Exception {
        // 终审 R1/d8：转正 affected=0（行被并发误删/回收）→ 仅告警放弃，计数不动、不抛
        when(store.findBySessionId(SID)).thenReturn(Optional.empty());
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.OCCUPIED, "pending:u4"));
        when(createDelegate.create()).thenReturn(created("sbx-4"));
        when(store.promotePending(eq("pending:u4"), eq("sbx-4"), anyString())).thenReturn(false);

        var result = manager.acquire(SID, USER, connector, createDelegate, reuseWrapper);

        assertNotNull(result);
        verify(store, never()).compensatePending(anyString(), anyString());
        assertTrue(manager.recentEvents().stream()
            .anyMatch(e -> "promote_miss".equals(e.get("type"))));
    }

    @Test
    void acquireCreateFailureCompensatesAndRethrows() throws Exception {
        when(store.findBySessionId(SID)).thenReturn(Optional.empty());
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.OCCUPIED, "pending:u5"));
        when(createDelegate.create()).thenThrow(new RuntimeException("boom"));

        assertThrows(RuntimeException.class,
            () -> manager.acquire(SID, USER, connector, createDelegate, reuseWrapper));

        // ③ 失败补偿：DELETE PENDING + 计数递减（§4.6 异常面）
        verify(store).compensatePending("pending:u5", USER);
    }

    @Test
    void acquirePoolExhausted429CompensatesAndSurfaces() throws Exception {
        // 池耗尽（§4.6）：create 429 PoolCapacityExhausted → 补偿 + 异常透出，不静默降级
        when(store.findBySessionId(SID)).thenReturn(Optional.empty());
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.OCCUPIED, "pending:u6"));
        var poolExhausted = new SandboxApiException("pool full", null, 429, mock(com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError.class));
        when(createDelegate.create()).thenThrow(poolExhausted);

        assertThrows(SandboxApiException.class,
            () -> manager.acquire(SID, USER, connector, createDelegate, reuseWrapper));
        verify(store).compensatePending("pending:u6", USER);
    }

    @Test
    void acquireDuplicateSessionRetriesFromFirstThenFailsBounded() {
        // 终审 d10：INSERT PENDING 撞 uk_session（同用户 A2A 在途）→ 回 ① 重试而非补偿
        when(store.findBySessionId(SID)).thenReturn(Optional.empty());
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.DUPLICATE_SESSION, null));

        assertThrows(io.agentscope.harness.agent.sandbox.SandboxException.class,
            () -> manager.acquire(SID, USER, connector, createDelegate, reuseWrapper));
        verify(store, times(3)).occupyQuota(USER, SID, 5);   // ACQUIRE_ROUNDS=3 有界
        org.mockito.Mockito.verifyNoInteractions(createDelegate);
        verify(store, never()).compensatePending(anyString(), anyString());
    }

    @Test
    void acquireDuplicateSessionThenBindingAppearsReusesIt() throws Exception {
        // 重试 ① 命中（并发获取已落绑定）→ 复用而非重复占额
        when(store.findBySessionId(SID))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(row(SID, SBX, "2026-11-08T00:20:00.000", "2026-11-07T23:00:00.000", false)));
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.DUPLICATE_SESSION, null));
        when(store.touchOnReuse(anyLong(), anyString())).thenReturn(true);
        when(connector.probe(SBX)).thenReturn(SandboxLifecycleManager.ProbeResult.alive(osbHandle));

        manager.acquire(SID, USER, connector, createDelegate, reuseWrapper);
        verify(reuseWrapper).wrap(osbHandle, SBX);
    }

    // ---- fail-open ----

    @Test
    void acquireStoreDownCreatesUntracked() throws Exception {
        // fail-open（§4.2 要点）：MySQL 不可达 → 跳过 ②③⑤ 直接 create 不记账（60s 限流 WARN）
        when(store.findBySessionId(SID)).thenThrow(new SandboxBindingStore.DataAccessException("db down", null));
        when(createDelegate.create()).thenReturn(created(SBX));

        var result = manager.acquire(SID, USER, connector, createDelegate, reuseWrapper);

        assertNotNull(result);
        verify(store, never()).occupyQuota(anyString(), anyString(), anyInt());
        verify(store, never()).promotePending(anyString(), anyString(), anyString());
        assertTrue(manager.recentEvents().stream().anyMatch(e -> "fail_open".equals(e.get("type"))));
        // 窗口内第二次不重复 WARN（限流后事件只发一次）
        when(createDelegate.create()).thenReturn(created(SBX));
        manager.acquire(SID, USER, connector, createDelegate, reuseWrapper);
        assertEquals(1, manager.recentEvents().stream().filter(e -> "fail_open".equals(e.get("type"))).count());
    }

    // ---- 配额满路径（⑤）----

    private void quotaFullSetup() {
        when(store.findBySessionId(SID)).thenReturn(Optional.empty());
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.QUOTA_FULL, null));
        when(store.recycleStalePending(USER)).thenReturn(0);
        when(store.lazyDeleteExpired(USER)).thenReturn(0);
        when(store.recomputeCount(USER)).thenReturn(5);   // 重算仍满 → 走 ⑤c
    }

    @Test
    void quotaFullLruRebindsIdleVictimWithOptimisticCheck() throws Exception {
        quotaFullSetup();
        var victim = row("other-sid", "sbx-old", "2026-11-08T00:10:00.000", "2026-11-07T20:00:00.000", false);
        when(store.listLive(eq(USER), anyInt())).thenReturn(List.of(victim));
        when(connector.probe("sbx-old")).thenReturn(SandboxLifecycleManager.ProbeResult.alive(osbHandle));
        when(leaseStore.isHeldStrict("other-sid")).thenReturn(false);   // 双事实源双假 → idle
        when(store.rebind(victim.id(), "other-sid", SID, victim.updatedAt())).thenReturn(true);
        var renewed = mock(Sandbox.class);
        var renewResp = mock(com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxRenewResponse.class);
        when(renewResp.getExpiresAt()).thenReturn(java.time.OffsetDateTime.parse("2026-11-08T01:30:00Z"));
        when(osbHandle.renew(Duration.ofSeconds(1800))).thenReturn(renewResp);

        manager.acquire(SID, USER, connector, createDelegate, reuseWrapper);

        // LRU = expires_at 最小者；改绑含 updated_at 快照乐观校验；renew + 服务端绝对值回填
        verify(store).rebind(victim.id(), "other-sid", SID, victim.updatedAt());
        verify(osbHandle).renew(Duration.ofSeconds(1800));
        verify(store).touchExpiresAt("sbx-old", "2026-11-08T01:30Z");   // OffsetDateTime.toString() 截断整秒
        verify(registry).acquireRef(eq("sbx-old"), eq(SID), eq(1800L), any());
        verify(reuseWrapper).wrap(osbHandle, "sbx-old");
        org.mockito.Mockito.verifyNoInteractions(createDelegate);
    }

    @Test
    void quotaFullAllBusyThrowsUserQuotaExhaustedWithPayload() {
        quotaFullSetup();
        var busy = row("other-sid", "sbx-busy", "2026-11-08T00:10:00.000", "2026-11-07T23:59:00.000", true);
        when(store.listLive(eq(USER), anyInt())).thenReturn(List.of(busy));   // updated_at 新鲜 → busy

        var ex = assertThrows(UserQuotaExhaustedException.class,
            () -> manager.acquire(SID, USER, connector, createDelegate, reuseWrapper));

        assertEquals(5, ex.quota());
        assertEquals(1, ex.bindings().size());
        assertEquals("other-sid", ex.bindings().get(0).sessionId());
        assertEquals("executing", ex.bindings().get(0).state());
        // 409 / SSE 双载荷可组装
        assertEquals("user_quota_exhausted", ex.payload409().get("code"));
        assertTrue(ex.sseFrameJson().contains("user_quota_exhausted"));
    }

    @Test
    void quotaFullBusyByLeaseOnlyA2aHasNoLeaseButFreshUpdatedAt() {
        // busy 双事实源四态之一：A2A 无 lease 靠 updated_at 新鲜判 busy
        quotaFullSetup();
        var a2a = row("a2a-user", "sbx-a2a", "2026-11-08T00:10:00.000", "2026-11-07T23:59:00.000", true);
        when(store.listLive(eq(USER), anyInt())).thenReturn(List.of(a2a));
        when(leaseStore.isHeldStrict("a2a-user")).thenReturn(false);

        var ex = assertThrows(UserQuotaExhaustedException.class,
            () -> manager.acquire(SID, USER, connector, createDelegate, reuseWrapper));
        assertEquals(1, ex.bindings().size());
    }

    @Test
    void quotaFullBusyByLeaseEvenWhenUpdatedAtStale() {
        // turn_lease 硬寿命 10min 到期物理删行场景（评审轮一 P0）的反向：租约在、updated_at 陈旧 → busy
        quotaFullSetup();
        var exec = row("exec-sid", "sbx-exec", "2026-11-08T00:10:00.000", "2026-11-07T20:00:00.000", false);
        when(store.listLive(eq(USER), anyInt())).thenReturn(List.of(exec));
        when(leaseStore.isHeldStrict("exec-sid")).thenReturn(true);

        assertThrows(UserQuotaExhaustedException.class,
            () -> manager.acquire(SID, USER, connector, createDelegate, reuseWrapper));
    }

    @Test
    void quotaFullRebindLostRetriesThenTreatsAsAllBusy() {
        // 改绑 affected=0（他副本抢先 或 受害在途 ① touch 先行提交）→ ⑤ 重试 ≤2 次后按全忙处理
        quotaFullSetup();
        var victim = row("other-sid", "sbx-old", "2026-11-08T00:10:00.000", "2026-11-07T20:00:00.000", false);
        when(store.listLive(eq(USER), anyInt())).thenReturn(List.of(victim));
        when(connector.probe("sbx-old")).thenReturn(SandboxLifecycleManager.ProbeResult.alive(osbHandle));
        when(store.rebind(anyLong(), anyString(), anyString(), anyString())).thenReturn(false);

        assertThrows(UserQuotaExhaustedException.class,
            () -> manager.acquire(SID, USER, connector, createDelegate, reuseWrapper));
        verify(store, times(3)).rebind(anyLong(), anyString(), anyString(), anyString());   // QUOTA_FULL_ROUNDS=3
    }

    @Test
    void quotaFullRecomputeFreesSlotRetriesOccupy() throws Exception {
        // ⑤b 重算 < quota → 回 ② 重试 CAS（懒剔除/回收生效）
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.QUOTA_FULL, null))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.OCCUPIED, "pending:u7"));
        when(store.recomputeCount(USER)).thenReturn(2);   // 懒剔除后有额度
        when(store.listLive(eq(USER), anyInt())).thenReturn(List.of());
        when(createDelegate.create()).thenReturn(created("sbx-7"));

        manager.acquire(SID, USER, connector, createDelegate, reuseWrapper);
        verify(store, times(2)).occupyQuota(USER, SID, 5);
        verify(store).promotePending(eq("pending:u7"), eq("sbx-7"), anyString());
    }

    @Test
    void quotaFullLazyDeletesDeadRowsProbedOutsideLock() throws Exception {
        quotaFullSetup();
        var dead = row("dead-sid", "sbx-dead", "2026-11-08T00:10:00.000", "2026-11-07T20:00:00.000", false);
        when(store.listLive(eq(USER), anyInt())).thenReturn(List.of(dead));
        when(connector.probe("sbx-dead")).thenReturn(SandboxLifecycleManager.ProbeResult.dead());
        when(store.deleteByIdGuarded(dead.id(), "dead-sid", dead.updatedAt())).thenReturn(true);
        when(store.recomputeCount(USER)).thenReturn(0);   // 删后不满 → 回 ②
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.QUOTA_FULL, null))
            .thenThrow(new RuntimeException("stop-here"));

        assertThrows(RuntimeException.class,
            () -> manager.acquire(SID, USER, connector, createDelegate, reuseWrapper));
        // ⑤a：probe 404 行守卫式删除（id+session+updated_at 未变）
        verify(store).deleteByIdGuarded(dead.id(), "dead-sid", dead.updatedAt());
    }

    @Test
    void quotaFullUnknownProbeConservativelyAliveNotVictim() {
        // ⑤a：probe 网络异常 → 保守视为活不删，但无句柄不可作 victim
        quotaFullSetup();
        var unknown = row("u-sid", "sbx-unknown", "2026-11-08T00:10:00.000", "2026-11-07T20:00:00.000", false);
        when(store.listLive(eq(USER), anyInt())).thenReturn(List.of(unknown));
        when(connector.probe("sbx-unknown")).thenReturn(SandboxLifecycleManager.ProbeResult.unknown());

        assertThrows(UserQuotaExhaustedException.class,
            () -> manager.acquire(SID, USER, connector, createDelegate, reuseWrapper));
        verify(store, never()).deleteByIdGuarded(anyLong(), anyString(), anyString());
        verify(store, never()).rebind(anyLong(), anyString(), anyString(), anyString());
    }

    // ---- 预检（§4.3）----

    @Test
    void precheckQuotaFullAllBusyReturns409Payload() {
        // 配额 5：5 条全部有执行中迹象 → 满 + 全忙 → 409 载荷
        var rows = new java.util.ArrayList<SandboxBindingStore.BindingRow>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("other-sid-" + i, "sbx-busy-" + i,
                "2026-11-08T00:10:00.000", "2026-11-07T23:59:00.000", true));
        }
        when(store.findBySessionId(SID)).thenReturn(Optional.empty());
        when(store.listLive(eq(USER), anyInt())).thenReturn(rows);
        when(confirmStore.findPending(anyString())).thenReturn(Optional.empty());
        var pending = mock(ConfirmContextStore.PendingConfirm.class);
        when(pending.createdAt()).thenReturn(java.time.Instant.parse("2026-11-07T23:55:00Z"));
        when(confirmStore.findPending("other-sid-0")).thenReturn(Optional.of(pending));   // 确认等待中

        var reject = manager.precheck(USER, SID);

        assertNotNull(reject);
        assertEquals("user_quota_exhausted", reject.payload409().get("code"));
        assertEquals(5, reject.bindings().size());
        assertEquals("confirm_holding", reject.bindings().get(0).state());   // 保留窗语义态
        assertEquals("executing", reject.bindings().get(1).state());
        assertNotNull(reject.bindings().get(0).confirmHoldExpiresAt());
    }

    @Test
    void precheckExpiredDeadRowsFilteredOutAllowsPass() {
        // 行龄过滤（假 409 修正）：listLive SQL 侧排除过期行/PENDING → 计数不满 → 放行
        when(store.findBySessionId(SID)).thenReturn(Optional.empty());
        when(store.listLive(eq(USER), anyInt())).thenReturn(List.of());   // 过期行已被读侧过滤
        assertNull(manager.precheck(USER, SID));
        verify(leaseStore, never()).isHeldStrict(anyString());
    }

    @Test
    void precheckExistingLiveBindingPasses() {
        // 会话有存活绑定 → ① 将复用，无需预检
        when(store.findBySessionId(SID)).thenReturn(Optional.of(row(SID, SBX,
            "2026-11-08T00:20:00.000", "2026-11-07T23:00:00.000", true)));
        assertNull(manager.precheck(USER, SID));
        verify(store, never()).listLive(anyString(), anyInt());
    }

    @Test
    void precheckFailOpenOnStoreError() {
        // 预检 fail-open（存储异常放行，§4.3）
        when(store.findBySessionId(SID)).thenThrow(new SandboxBindingStore.DataAccessException("db down", null));
        assertNull(manager.precheck(USER, SID));
    }

    // ---- quota=0（不限，终审 R2）----

    @Test
    void acquireQuotaZeroStillAccountsButNeverJudgedFull() throws Exception {
        var config = new SandboxLifecycleConfig(true, "", 0, 60, 20, 2, 1000, false);
        var m = new SandboxLifecycleManager(store, registry, config, 30, leaseStore, confirmStore);
        when(store.findBySessionId(SID)).thenReturn(Optional.empty());
        when(store.occupyQuota(USER, SID, 0))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.OCCUPIED, "pending:u8"));
        when(createDelegate.create()).thenReturn(created("sbx-8"));

        var result = m.acquire(SID, USER, connector, createDelegate, reuseWrapper);
        assertNotNull(result);
        verify(store).occupyQuota(USER, SID, 0);   // 0 = 不判满仍记账（capless CAS 由 store 层实现）
    }

    // ---- 生命周期挂钩 ----

    @Test
    void hooksDelegateToRegistry() {
        manager.onTurnStart(SID);
        manager.onTurnEnd(SID);
        manager.onConfirmHold(SID);
        manager.onCallStop(SBX);
        verify(registry).registerTurn(SID);
        verify(registry).unregisterTurn(SID);
        verify(registry).suspendForConfirm(SID);
        verify(registry).releaseRef(SBX);
    }

    // ---- 启动校验（§4.7 / U3）----

    @Test
    void startupValidationRejectsTtlBelowOneMinute() {
        var config = new SandboxLifecycleConfig(true, "", 5, 60, 20, 2, 1000, false);
        var sandboxConfig = new io.agentmanager.framework.config.SandboxConfig(false, "img", 0, 30, 1024, 1,
            List.of("/entry"), Duration.ofMillis(100), true, true, 900, true, true, true, true, 500, 30,
            new io.agentmanager.framework.config.SandboxConfig.OpenSandboxConfig("u", "k"));
        assertThrows(IllegalStateException.class, () -> AgentScopeValidateHelper.validate(sandboxConfig, config));
    }

    @Test
    void startupValidationRejectsRenewIntervalNotUnderHalfTtl() {
        var config = new SandboxLifecycleConfig(true, "", 5, 900, 20, 2, 1000, false);   // 900s ≥ 30min*60/2=900
        var sandboxConfig = new io.agentmanager.framework.config.SandboxConfig(false, "img", 30, 30, 1024, 1,
            List.of("/entry"), Duration.ofMillis(100), true, true, 900, true, true, true, true, 500, 30,
            new io.agentmanager.framework.config.SandboxConfig.OpenSandboxConfig("u", "k"));
        assertThrows(IllegalStateException.class, () -> AgentScopeValidateHelper.validate(sandboxConfig, config));
    }

    @Test
    void startupValidationAcceptsSaneDefaults() {
        var config = new SandboxLifecycleConfig(true, "", 5, 60, 20, 2, 1000, false);
        var sandboxConfig = new io.agentmanager.framework.config.SandboxConfig(false, "img", 30, 30, 1024, 1,
            List.of("/entry"), Duration.ofMillis(100), true, true, 900, true, true, true, true, 500, 30,
            new io.agentmanager.framework.config.SandboxConfig.OpenSandboxConfig("u", "k"));
        AgentScopeValidateHelper.validate(sandboxConfig, config);   // 不抛即通过
    }

    /** AgentScopeConfig.validateLifecycle 的包外桥（该方法是包私有静态，测试桥接调用） */
    private static final class AgentScopeValidateHelper {
        static void validate(io.agentmanager.framework.config.SandboxConfig c,
                             io.agentmanager.framework.config.SandboxLifecycleConfig l) {
            io.agentmanager.framework.config.AgentScopeConfig.validateLifecycle(c, l);
        }
    }

    @Test
    void onResumeAcquiredRegistersCallRef() {
        var handle = mock(Sandbox.class);
        manager.onResumeAcquired(SBX, SID, handle);
        verify(registry).acquireRef(eq(SBX), eq(SID), eq(1800L), any());
        assertTrue(manager.recentEvents().stream().anyMatch(e -> "acquire_resume".equals(e.get("type"))));
    }

    @Test
    void ownershipFailOpenEmitsRateLimitedEvent() {
        manager.recordOwnershipFailOpen(SBX, SID, new RuntimeException("db down"));
        assertTrue(manager.recentEvents().stream().anyMatch(e -> "fail_open".equals(e.get("type"))));
        long count = manager.recentEvents().stream().filter(e -> "fail_open".equals(e.get("type"))).count();
        manager.recordOwnershipFailOpen(SBX, SID, new RuntimeException("still down"));   // 窗口内限流
        assertEquals(count, manager.recentEvents().stream().filter(e -> "fail_open".equals(e.get("type"))).count());
    }

    @Test
    void accessorsAndRegistryDelegatesExposeHealth() {
        assertEquals(store, manager.bindingStore());
        assertEquals(5, manager.config().userQuota());
        when(registry.registrySize()).thenReturn(2);
        when(registry.activeTurnCount()).thenReturn(1);
        when(registry.oldestEntryAgeMs()).thenReturn(9000L);
        when(registry.lastLoopLagMs()).thenReturn(5L);
        when(registry.lastSweepAt()).thenReturn(java.time.Instant.EPOCH);
        var entries = java.util.List.<java.util.Map<String, Object>>of(java.util.Map.of("k", "v"));
        when(registry.debugEntries()).thenReturn(entries);
        assertEquals(2, manager.registrySize());
        assertEquals(1, manager.registryActiveTurnCount());
        assertEquals(9000L, manager.registryOldestEntryAgeMs());
        assertEquals(5L, manager.registryLoopLagMs());
        assertEquals(java.time.Instant.EPOCH, manager.registryLastSweepAt());
        assertEquals(entries, manager.registryDebugEntries());
        // grace 下限（评审轮一 P1：> 续期间隔）：60s 间隔 → max(180, 120) = 180
        assertEquals(180, manager.graceSeconds());
    }

    @Test
    void ownershipLostNoticePopsOnceAndHonoursSince() {
        manager.markSandboxRebuilt(SID);
        // since 晚于登记时刻 → 不弹（旧 turn 不吃新登记）；+1s 恒大于 mark 时刻
        long future = System.currentTimeMillis() + 1_000;
        assertFalse(manager.popSandboxRebuiltSince(SID, future));
        // 一次性弹出后不再重复（换代提示帧只发一次）
        assertTrue(manager.popSandboxRebuiltSince(SID, 0));
        assertFalse(manager.popSandboxRebuiltSince(SID, 0));
        // 未登记 sid 恒 false
        assertFalse(manager.popSandboxRebuiltSince("other-sid", 0));
        // 登记即落事件（V6⑧ live 排障：/debug/sandbox-bindings 直接可查，不依赖流内帧时序）
        assertTrue(manager.recentEvents().stream()
            .anyMatch(e -> "sandbox_rebuilt_registered".equals(e.get("type"))
                && String.valueOf(e.get("detail")).contains(SID)), "缺登记事件: " + manager.recentEvents());
    }

    // ---- ③ create 瞬时类失败重试（oa-1110-repro-fixes-design §2.3a） ----

    /** 独立 manager：backoff=1ms 避免测试真实退避等待；attempts 可配 */
    private SandboxLifecycleManager retryManager(int attempts) {
        return new SandboxLifecycleManager(store, registry,
            new SandboxLifecycleConfig(true, "", 5, 60, 20, attempts, 1, false), 30, leaseStore, confirmStore);
    }

    private static com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxTimeoutException sdkTimeout() {
        return new com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxTimeoutException(
            "Request timed out: timeout", null, false);
    }

    @Test
    void transientCreateFailureShouldRetryThenSucceed() throws Exception {
        var m = retryManager(2);
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.OCCUPIED, "pending:u1"));
        when(createDelegate.create()).thenThrow(sdkTimeout()).thenReturn(created(SBX));

        var result = m.acquire(SID, USER, connector, createDelegate, reuseWrapper);

        org.mockito.Mockito.verify(createDelegate, org.mockito.Mockito.times(2)).create();
        verify(store, never()).compensatePending(anyString(), anyString());
        verify(store).promotePending(eq("pending:u1"), eq(SBX), anyString());
        org.junit.jupiter.api.Assertions.assertNotNull(result);
        // 重试事件留痕
        assertTrue(m.recentEvents().stream().anyMatch(e -> "create_retry".equals(e.get("type"))));
    }

    @Test
    void transientCreateFailureExhaustedShouldThrowAndCompensateOnce() throws Exception {
        var m = retryManager(2);
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.OCCUPIED, "pending:u1"));
        when(createDelegate.create()).thenThrow(sdkTimeout());

        var ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            () -> m.acquire(SID, USER, connector, createDelegate, reuseWrapper));

        org.mockito.Mockito.verify(createDelegate, org.mockito.Mockito.times(2)).create();
        verify(store, org.mockito.Mockito.times(1)).compensatePending("pending:u1", USER);
        assertEquals("Request timed out: timeout", ex.getMessage());
    }

    @Test
    void nonTransientCreateFailureShouldNotRetry() throws Exception {
        var m = retryManager(3);
        when(store.occupyQuota(USER, SID, 5))
            .thenReturn(new SandboxBindingStore.OccupyResult(SandboxBindingStore.OccupyStatus.OCCUPIED, "pending:u1"));
        when(createDelegate.create()).thenThrow(new IllegalArgumentException("bad image"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> m.acquire(SID, USER, connector, createDelegate, reuseWrapper));

        org.mockito.Mockito.verify(createDelegate, org.mockito.Mockito.times(1)).create();
        verify(store, org.mockito.Mockito.times(1)).compensatePending("pending:u1", USER);
    }
}
