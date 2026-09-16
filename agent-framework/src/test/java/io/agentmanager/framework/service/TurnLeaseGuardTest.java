package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TurnLeaseGuard 续租处置单测。
 *
 * <p>钉住两件在真实故障下才会显形的事：
 * <ol>
 *   <li>瞬时故障（DB 抖动）不停止续租——旧实现把每个 false 都当接管，
 *       {@code renewer.shutdownNow()} 一次，于是「一次连接池抖动 = 永久停掉续租」，
 *       TTL 过后另一个副本接管，而本副本仍在执行、仍在写，两侧 seq 区间重叠。</li>
 *   <li>丢锁判据由**写入侧**按时间求值，而不是只读续租线程置的标志位——续租线程
 *       自己也可能不再转动（连接挂死、长 GC），那时一拍都不会来。</li>
 * </ol>
 *
 * <p>亚秒续租间隔之所以可行，依赖 guard 改用毫秒调度——原实现 {@code toSeconds()}
 * 会把 20ms 截断成 0，变成忙轮询。
 */
@ExtendWith(MockitoExtension.class)
class TurnLeaseGuardTest {

    @Mock private TurnLeaseStore store;

    private TurnLeaseGuard guard;

    @AfterEach
    void tearDown() {
        if (guard != null) {
            guard.release();
        }
    }

    private void stubIntervals(Duration renewInterval, Duration ttl) {
        when(store.renewInterval()).thenReturn(renewInterval);
        when(store.ttl()).thenReturn(ttl);   // 判据在构造时就算出来，必然被读
    }

    @Test
    void transientErrorsKeepRetryingAndDoNotMarkLost() {
        // 判据 max(20ms, 30s-20ms) ≈ 30s，远大于观察窗口：故障没持续到判据就不得算丢锁
        stubIntervals(Duration.ofMillis(20), Duration.ofSeconds(30));
        when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.ERROR);

        guard = new TurnLeaseGuard(store, "sid", "tok");

        assertTrue(renewCalledAtLeast(3, 2000), "瞬时故障必须继续重试，而不是停掉续租");
        assertFalse(guard.isLost(), "未超过判据的续租故障不得判定为丢锁");
    }

    @Test
    void errorsPersistingBeyondBoundMarkLost() {
        // 判据 = max(20ms, 60ms-20ms) = 40ms
        stubIntervals(Duration.ofMillis(20), Duration.ofMillis(60));
        when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.ERROR);

        guard = new TurnLeaseGuard(store, "sid", "tok");

        assertTrue(awaitTrue(guard::isLost, 2000),
            "续租故障持续超过判据说明租约随时可能被接管，必须判定丢锁");
    }

    @Test
    void detectsLossEvenWhenRenewerThreadIsStuck() {
        // 续租线程卡在 renew 里（连接挂死 / 长 GC / 线程饥饿）：一拍都不会完成。
        // 若 isLost() 只读续租线程置的标志位，这里永远返回 false——而租约其实早已到期。
        var stuck = new CountDownLatch(1);
        stubIntervals(Duration.ofMillis(20), Duration.ofMillis(60));
        when(store.renew("sid", "tok")).thenAnswer(inv -> {
            try {
                stuck.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TurnLeaseStore.RenewOutcome.HELD;
        });

        guard = new TurnLeaseGuard(store, "sid", "tok");

        assertTrue(awaitTrue(guard::isLost, 2000),
            "续租线程不再转动时，写入侧必须自己按时间判据停手");
        stuck.countDown();
    }

    @Test
    void stopsBeforeTakeoverIsPossibleNotAtExpiry() {
        // 判据是 ttl - renewInterval 而不是 ttl：接管方在 expires_at 一过就能得手，
        // 等确认自己已经被踢掉再停手就晚了。ttl=6s / interval=3s → 判据 3s。
        // 若是 ttl=6s，下面 4.5s 那次断言就会失败。
        stubIntervals(Duration.ofSeconds(3), Duration.ofSeconds(6));
        // 本用例靠**读写侧的时间判据**在 3s 处停手，与第一拍续租（同样在 3s）是竞态，
        // 谁先谁后不影响结论——renew 可能一次都没被调到，故 lenient
        lenient().when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.ERROR);

        guard = new TurnLeaseGuard(store, "sid", "tok");

        sleep(1000);
        assertFalse(guard.isLost(), "才过 1s（判据 3s），不该已经判定丢锁");
        assertTrue(awaitTrue(guard::isLost, 3500),
            "判据是 3s：必须在 6s 到期之前就停手，不能等到租约真的过期");
    }

    @Test
    void lostMarksImmediately() {
        stubIntervals(Duration.ofMillis(20), Duration.ofSeconds(30));
        when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.LOST);

        guard = new TurnLeaseGuard(store, "sid", "tok");

        assertTrue(awaitTrue(guard::isLost, 2000), "真被接管必须立刻判定丢锁");
    }

    @Test
    void healthyRenewNeverMarksLost() {
        stubIntervals(Duration.ofMillis(20), Duration.ofSeconds(30));
        when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.HELD);

        guard = new TurnLeaseGuard(store, "sid", "tok");

        assertTrue(renewCalledAtLeast(3, 1000), "正常路径应持续续租");
        sleep(300);
        // 20ms 间隔 → 300ms 内约 15 次。若间隔被 toSeconds() 截断成 0（或被 max(1,·) 兜成 1ms），
        // 这里会变成数百次——钉住「按毫秒调度」这个修正
        int calls = renewCallCount();
        assertTrue(calls < 100, "续租退化成忙轮询了：300ms 内调用了 " + calls + " 次");
        assertFalse(guard.isLost());
    }

    @Test
    void releasedTurnIsNotReportedAsLost() {
        // HITL 暂停点与 AGENT_END 都会主动 release：那是有意的执行段边界，不是被抢占。
        // 时间判据在 release 之后必然超时（已经不再续租），必须被排除——否则 AGENT_END
        // 之后残留的事件会被误判成丢锁，把本该刷出的缓冲丢掉。
        stubIntervals(Duration.ofMillis(20), Duration.ofMillis(60));
        lenient().when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.HELD);

        guard = new TurnLeaseGuard(store, "sid", "tok");
        guard.release();
        sleep(300);   // 远超判据（40ms）

        assertFalse(guard.isLost(), "主动让出锁之后不得再报丢锁");
    }

    @Test
    void lostNotificationGateOpensOnlyOnce() {
        stubIntervals(Duration.ofMillis(20), Duration.ofSeconds(30));
        when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.LOST);

        guard = new TurnLeaseGuard(store, "sid", "tok");
        assertTrue(awaitTrue(guard::isLost, 2000));

        assertTrue(guard.tryMarkLostNotified(), "首次调用应当拿到闸门");
        assertFalse(guard.tryMarkLostNotified(),
            "handleEventAndEmit 每个事件都进来一次，终态帧只能发一帧");
    }

    @Test
    void releaseDoesNotTouchLeaseAfterLoss() {
        stubIntervals(Duration.ofMillis(20), Duration.ofSeconds(30));
        when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.LOST);

        guard = new TurnLeaseGuard(store, "sid", "tok");
        assertTrue(awaitTrue(guard::isLost, 2000));
        guard.release();

        // 行已属于接管者：DELETE 有 token 校验不会误删，但也不该谎称是自己释放的
        verify(store, never()).release("sid", "tok");
    }

    @Test
    void releaseDeletesLeaseOnHealthyPath() {
        stubIntervals(Duration.ofMillis(20), Duration.ofSeconds(30));
        // 本用例 release 紧接构造，续租线程未必来得及跑一次——stub 备而不用，故 lenient
        lenient().when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.HELD);

        guard = new TurnLeaseGuard(store, "sid", "tok");
        guard.release();

        verify(store).release("sid", "tok");
    }

    // ===== 辅助方法 =====

    /** 续租线程在另一个线程上追加调用记录，读取时容忍并发写 */
    private int renewCallCount() {
        try {
            return (int) mockingDetails(store).getInvocations().stream()
                .filter(i -> "renew".equals(i.getMethod().getName())).count();
        } catch (Exception e) {
            return 0;   // 正在并发追加，下一轮再看
        }
    }

    private boolean renewCalledAtLeast(int n, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (renewCallCount() >= n) {
                return true;
            }
            sleep(10);
        }
        return false;
    }

    private static boolean awaitTrue(BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            sleep(10);
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
