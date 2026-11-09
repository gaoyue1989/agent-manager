package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;

/**
 * U2 {@code SandboxRenewalRegistryTest}（U2 分支枚举即 DoD，sandbox-pool-opensandbox-design §9.1）：
 * 双信号（call refcount + turn 注册）/ holdUntil 保留窗 / bornAt 超龄剔除 / renew 失败分类
 * （404/410、409、网络）/ 崩溃语义（新建实例 = 注册表空）。时钟注入，不 sleep。
 */
class SandboxRenewalRegistryTest {

    private static final long RENEW_INTERVAL = 60;
    private static final long HOLD_MINUTES = 20;
    private static final long TTL_SECONDS = 1800;

    private final SandboxBindingStore store = mock(SandboxBindingStore.class);
    private final List<String> events = new ArrayList<>();

    /** 可编程假时钟：测试推进时间 */
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-11-08T00:00:00Z"));

    private SandboxRenewalRegistry registry(Clock clock) {
        return new SandboxRenewalRegistry(store, RENEW_INTERVAL, HOLD_MINUTES,
            (type, sid, detail) -> events.add(type), clock);
    }

    private Clock clock() {
        return new Clock() {
            @Override public Instant instant() { return now.get(); }
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        };
    }

    /** 续期计数器 renewer（可配置抛出） */
    private static final class FakeRenewer implements SandboxRenewalRegistry.SandboxRenewer {
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException toThrow;

        @Override
        public String renew() {
            calls.incrementAndGet();
            if (toThrow != null) {
                throw toThrow;
            }
            return "2026-11-08T01:00:00Z";
        }
    }

    private void advance(Duration d) {
        now.set(now.get().plus(d));
    }

    // ---- call refcount 双信号 ----

    @Test
    void acquireRefThenSweepRenewsAndFlushesBinding() {
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);

        reg.sweep();
        assertEquals(1, renewer.calls.get());
        verify(store).touchExpiresAt(eq("sb-1"), eq("2026-11-08T01:00:00Z"));

        // stop() 收尾：refcount-1 + 顺手终刷（终审 R2 口径）——releaseRef 本身触发一次 renew
        reg.releaseRef("sb-1");
        assertEquals(2, renewer.calls.get());
        verify(store, times(2)).touchExpiresAt(eq("sb-1"), anyString());
    }

    @Test
    void releaseRefToZeroWithTurnRegisteredKeepsRenewal() {
        // chat 多 call 间隙：refcount 归零但 turn 注册仍在 → 续期不中断（§4.4 T3）
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);
        reg.registerTurn("sid-1");
        reg.releaseRef("sb-1");   // stop flush（renew 第 1 次）

        int afterStop = renewer.calls.get();
        reg.sweep();              // 守护线程拍：turn 信号兜住
        assertTrue(renewer.calls.get() > afterStop);

        reg.unregisterTurn("sid-1");   // turn 终态注销 + 终刷
        reg.sweep();                   // 双信号均无 → 不再续期
        int afterUnregister = renewer.calls.get();
        advance(Duration.ofSeconds(RENEW_INTERVAL * 2));
        reg.sweep();
        assertEquals(afterUnregister, renewer.calls.get());
    }

    // ---- turn 生命周期 ----

    @Test
    void unregisterTurnFlushesEntriesOfSession() {
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);
        reg.releaseRef("sb-1");
        int before = renewer.calls.get();

        reg.unregisterTurn("sid-1");   // T2 终刷
        assertTrue(renewer.calls.get() > before);
        verify(store, atLeast(2)).touchExpiresAt(eq("sb-1"), anyString());
    }

    @Test
    void holdWithinWindowRenewsAndAfterWindowStops() {
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);
        reg.releaseRef("sb-1");   // call 归零，仅剩 hold 信号

        reg.suspendForConfirm("sid-1");   // T4：ask 挂起，holdUntil = now+20min
        int before = renewer.calls.get();
        advance(Duration.ofMinutes(10));
        reg.sweep();                       // hold 内：继续续期（续期即保护）
        assertTrue(renewer.calls.get() > before);

        // 同一 turn 连续多次 ask：重新计窗（第二次挂起延后 stop 时刻）
        reg.suspendForConfirm("sid-1");
        advance(Duration.ofMinutes(15));   // 距第一次挂起 25min（>默认窗）、距第二次 15min（<窗）
        reg.sweep();
        assertTrue(renewer.calls.get() > before);

        // confirm 恢复：重注册清 hold → turn 信号续期
        reg.registerTurn("sid-1");
        int atResume = renewer.calls.get();
        advance(Duration.ofMinutes(30));   // 远超任何 hold 窗
        reg.sweep();
        assertTrue(renewer.calls.get() > atResume);   // turn 信号仍续期

        // 过期停续：hold 过期且 turn 已注销 → 不再续期（可被 LRU 调度/自然 GC）
        reg.unregisterTurn("sid-1");
        int atEnd = renewer.calls.get();
        advance(Duration.ofMinutes(30));
        reg.sweep();
        assertEquals(atEnd, renewer.calls.get());
    }

    @Test
    void confirmHoldExpiryStopsRenewalWithoutTurn() {
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);
        reg.releaseRef("sb-1");   // call 归零（stop flush 产生一次 renew）
        org.mockito.Mockito.clearInvocations(store);
        reg.suspendForConfirm("sid-1");
        advance(Duration.ofMinutes(HOLD_MINUTES + 1));   // 窗口过后停续注销
        reg.sweep();
        // sweep 不 renew（bornAt 仍年轻）：本拍无续期
        verify(store, never()).touchExpiresAt(eq("sb-1"), anyString());
    }

    // ---- bornAt 超龄剔除 ----

    @Test
    void overageEntryEvictedWithErrorEventAndNoMoreRenewal() {
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);
        reg.registerTurn("sid-1");

        advance(Duration.ofHours(3));   // > max(TTL×2, 2h)
        reg.sweep();
        // 强制剔除 + ERROR 事件（沙箱不得无限续期变成永不过期 + 永不可抢占）
        assertTrue(events.contains("registry_overage"), "events=" + events);
        assertEquals(0, reg.registrySize());
        verify(store, never()).touchExpiresAt(eq("sb-1"), anyString());
    }

    @Test
    void metricsExposeSizeOldestAgeAndLoopLag() {
        var reg = registry(clock());
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, new FakeRenewer());
        reg.acquireRef("sb-2", "sid-2", TTL_SECONDS, new FakeRenewer());
        assertEquals(2, reg.registrySize());
        assertEquals(0, reg.oldestEntryAgeMs());
        advance(Duration.ofSeconds(90));
        assertEquals(90_000, reg.oldestEntryAgeMs());
        reg.sweepSafely();
        assertTrue(reg.lastLoopLagMs() >= 0);
    }

    // ---- renew 失败分类 ----

    @Test
    void renew404EvictsEntryAndLazilyDeletesBinding() {
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        renewer.toThrow = new SandboxApiException("gone", null, 404, mock(com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError.class));
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);

        reg.sweep();
        assertTrue(events.contains("renew_404"), "events=" + events);
        assertEquals(0, reg.registrySize());
        verify(store).lazyDeleteOwned("sb-1", "sid-1");   // 提前释放配额
        verify(store, never()).touchExpiresAt(anyString(), anyString());
    }

    @Test
    void renew409IsIdempotentlyIgnored() {
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        renewer.toThrow = new SandboxApiException("same-second", null, 409, mock(com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError.class));
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);

        reg.sweep();
        assertEquals(1, reg.registrySize());   // 条目保留
        assertTrue(!events.contains("renew_404") && !events.contains("renew_fail"));
        verify(store, never()).lazyDeleteOwned(anyString(), anyString());
        verify(store, never()).heartbeat(anyString());
    }

    @Test
    void renewNetworkFailureWarnsHeartbeatsAndAlertsAfter3() {
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        renewer.toThrow = new RuntimeException("connect timeout");
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);

        // 连续失败：每拍重试 + 心跳写（跨副本保守标记 = updated_at 新鲜 → 暂不参与 LRU victim）
        reg.sweep();
        reg.sweep();
        verify(store, times(2)).heartbeat("sb-1");
        assertFalse(events.contains("renew_fail"));

        reg.sweep();   // 第 3 次 → 事件告警
        verify(store, times(3)).heartbeat("sb-1");
        assertTrue(events.contains("renew_fail"), "events=" + events);
        assertEquals(1, reg.registrySize());   // 网络失败不剔注册（保守：失败≠已死）
    }

    @Test
    void renewHttp500ClassifiedAsRetryableFailure() {
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        renewer.toThrow = new SandboxApiException("boom", null, 500, mock(com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError.class));
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);

        reg.sweep();
        verify(store).heartbeat("sb-1");   // 非 404/410/409 → 同网络异常分支
        assertEquals(1, reg.registrySize());
    }

    // ---- 崩溃语义 ----

    @Test
    void freshInstanceHasEmptyRegistryAndNoRenewal() {
        var reg = registry(clock());
        reg.sweep();   // 进程重启（新建实例）→ 注册表空 → 不再续期
        assertEquals(0, reg.registrySize());
        verify(store, never()).touchExpiresAt(anyString(), anyString());
    }

    @Test
    void reAcquireRefreshesRenewerKeepsBornAt() {
        var reg = registry(clock());
        var first = new FakeRenewer();
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, first);
        advance(Duration.ofHours(1));
        var second = new FakeRenewer();
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, second);   // 同沙箱重入：刷新 renewer
        reg.sweep();
        assertEquals(1, second.calls.get());
        assertEquals(0, first.calls.get());
        // bornAt 保留首次注册时刻：再过 1.5h（累计 2.5h > 2h）→ 超龄剔除
        advance(Duration.ofHours(1).plus(Duration.ofMinutes(30)));
        reg.sweep();
        assertTrue(events.contains("registry_overage"));
    }

    @Test
    void touchExpiresAtWriteFailureDoesNotBreakRenewalLoop() {
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);
        when(store.touchExpiresAt(anyString(), anyString()))
            .thenThrow(new SandboxBindingStore.DataAccessException("db down", null));

        reg.sweep();   // 簿记写失败：仅告警，条目与续期循环不受影响
        assertEquals(1, renewer.calls.get());
        assertEquals(1, reg.registrySize());
    }

    @Test
    void startSpawnsDaemonAndCloseStopsIt() throws Exception {
        var reg = registry(clock());
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, new FakeRenewer());
        reg.start();
        reg.start();   // 幂等
        Thread.sleep(150);   // 等首拍（interval=60s 但首拍 delay=60s——这里只验证线程在跑不抛）
        reg.close();
        reg.close();   // 幂等
        // close 后手动 sweep 仍可用（纯函数），条目保留
        assertEquals(1, reg.registrySize());
    }

    @Test
    void debugEntriesExposePerEntryState() {
        var reg = registry(clock());
        var renewer = new FakeRenewer();
        reg.acquireRef("sb-1", "sid-1", TTL_SECONDS, renewer);
        reg.registerTurn("sid-1");
        reg.suspendForConfirm("sid-1");
        var entries = reg.debugEntries();
        assertEquals(1, entries.size());
        var e = entries.get(0);
        assertEquals("sid-1", e.get("session_id"));
        assertEquals("sb-1", e.get("sandbox_id"));
        assertEquals(1, e.get("call_refs"));
        assertEquals(true, e.get("turn_registered"));
        assertEquals(0, e.get("renew_failures"));
        assertTrue(((String) e.get("hold_until")).length() > 0);
        // holdUntil 清除：registerTurn 重注册
        reg.registerTurn("sid-1");
        assertTrue(((String) reg.debugEntries().get(0).get("hold_until")).isEmpty());
    }
}
