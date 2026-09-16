package io.agentmanager.framework.service;

import java.time.Duration;
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
 * <p>改动前的两处叠加：{@code TurnLeaseStore.renew} 把瞬时故障也报成 {@code false}，
 * 而 guard 对每个 {@code false} 都 {@code renewer.shutdownNow()} —— 于是**一次连接池抖动
 * 就永久停掉本 turn 的续租**。60s TTL 过后另一个副本接管，而本副本仍在执行、仍在写，
 * 两侧 seq 区间重叠。本类钉住修正后的三态处置。
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
        // ttl 只在 ERROR 分支被读；HELD/LOST 用例用不到，故 lenient
        lenient().when(store.ttl()).thenReturn(ttl);
    }

    @Test
    void transientErrorsKeepRetryingAndDoNotMarkLost() {
        // TTL 远大于观察窗口：故障始终没持续到一个租约周期，因此不得判定丢锁
        stubIntervals(Duration.ofMillis(20), Duration.ofSeconds(30));
        when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.ERROR);

        guard = new TurnLeaseGuard(store, "sid", "tok");

        assertTrue(renewCalledAtLeast(3, 2000), "瞬时故障必须继续重试，而不是停掉续租");
        assertFalse(guard.isLost(), "未超过 TTL 的续租故障不得判定为丢锁");
    }

    @Test
    void errorsPersistingBeyondTtlMarkLost() {
        stubIntervals(Duration.ofMillis(20), Duration.ofMillis(60));
        when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.ERROR);

        guard = new TurnLeaseGuard(store, "sid", "tok");

        assertTrue(awaitTrue(guard::isLost, 2000),
            "续租故障持续超过一个 TTL 说明租约早已到期，必须判定丢锁");
    }

    @Test
    void lostMarksImmediately() {
        stubIntervals(Duration.ofMillis(20), Duration.ofSeconds(30));
        when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.LOST);

        guard = new TurnLeaseGuard(store, "sid", "tok");

        assertTrue(awaitTrue(guard::isLost, 2000), "真被接管必须立刻置 lost");
    }

    @Test
    void healthyRenewNeverMarksLost() {
        stubIntervals(Duration.ofMillis(20), Duration.ofSeconds(30));
        when(store.renew("sid", "tok")).thenReturn(TurnLeaseStore.RenewOutcome.HELD);

        guard = new TurnLeaseGuard(store, "sid", "tok");

        assertTrue(renewCalledAtLeast(3, 1000), "正常路径应持续续租");
        sleep(300);
        // 20ms 间隔 → 300ms 内约 15 次。若间隔被 toSeconds() 截断成 0（或 max(1,·) 兜成 1ms），
        // 这里会变成数百次——钉住「按毫秒调度」这个修正
        int calls = renewCallCount();
        assertTrue(calls < 100, "续租退化成忙轮询了：300ms 内调用了 " + calls + " 次");
        assertFalse(guard.isLost());
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
