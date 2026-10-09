package io.agentmanager.framework.service.a2ajob;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentA2aJobProperties;
import io.agentmanager.framework.redis.RedisConnectionFacade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2aJobService 状态机单测（Issue #69 §2.4/§2.6；mock RedisStore + 覆写发送 hook）：
 * 幂等命中不重发 / 409 in-flight / 确定拒绝释放可重试 / 结果未知保留禁重发 /
 * 键与 text 校验 / taskId 三级回落 / claim 前缀碰撞拒绝 / Semaphore 准入 503 /
 * Redis 故障 fail-closed。真实 loopback HTTP 路径由部署验证与阶段 3 P7 覆盖。
 */
class A2aJobServiceTest {

    private A2aJobRedisStore store;
    private A2aJobService service;

    @BeforeEach
    void setUp() {
        store = mock(A2aJobRedisStore.class);
        var props = new AgentA2aJobProperties(true, "tok", 1, 24, 4);
        service = new A2aJobService(store, props, "1");
    }

    // ===== 输入校验 =====

    @Test
    void invalidKeyShouldFailFast() {
        assertThrows(A2aJobService.JobInvalidException.class,
            () -> service.submit("bad key!", "hello"));
        assertThrows(A2aJobService.JobInvalidException.class,
            () -> service.submit("k".repeat(129), "hello"));
    }

    @Test
    void blankOrOversizedTextShouldFailFast() {
        assertThrows(A2aJobService.JobInvalidException.class, () -> service.submit("k1", "  "));
        assertThrows(A2aJobService.JobInvalidException.class,
            () -> service.submit("k1", "中".repeat(11_000)));   // 33KB UTF-8
        verify(store, never()).claim(anyString(), anyString(), any());
    }

    // ===== 幂等命中 =====

    @Test
    void idempotentHitShouldReturnExistingTaskIdWithoutResend() {
        when(store.claim(eq("k1"), anyString(), any())).thenReturn("task-abc");

        var out = service.submit("k1", "hello");

        assertEquals("task-abc", ((java.util.Map<?, ?>) out.get("job")).get("taskId"));
        assertEquals(Boolean.TRUE, out.get("idempotent"));
        verify(store, never()).completeWithTaskId(anyString(), anyString(), anyString(), any());
    }

    // ===== in-flight 409 =====

    @Test
    void inFlightClaimShouldConflict() {
        when(store.claim(eq("k1"), anyString(), any())).thenReturn("claim:other");
        when(store.get("k1")).thenReturn("claim:other");

        var ex = assertThrows(A2aJobService.JobConflictException.class,
            () -> service.submit("k1", "hello"));
        assertTrue(ex.getMessage().contains("in flight"));
    }

    // ===== claim 残留（瞬时进程死亡）→ 重试一次 NX =====

    /**
     * 首轮 claim 撞上残留 claim（进程死于 SET NX 与 complete 之间），复查时键已随租约消失
     * → 允许恰一次 NX 重抢（否则瞬时死亡让同键请求永久 409）。第二轮拿到执行权即正常受理。
     */
    @Test
    void vanishedClaimResidueShouldRetryClaimOnceAndSubmit() {
        var svc = new A2aJobService(store, new AgentA2aJobProperties(true, "tok", 1, 24, 4), "1") {
            @Override
            String sendBlocking(String key, String text) {
                return "task-retry";
            }
        };
        // 第一轮：键被他人残留占用；第二轮：NX 成功
        when(store.claim(eq("k5"), anyString(), any())).thenReturn("claim:other").thenReturn(null);
        when(store.get("k5")).thenReturn(null);   // 复查：残留键已消失
        when(store.completeWithTaskId(eq("k5"), anyString(), eq("task-retry"), any())).thenReturn(true);

        var out = svc.submit("k5", "hello");

        assertEquals("task-retry", ((java.util.Map<?, ?>) out.get("job")).get("taskId"));
        assertEquals(Boolean.FALSE, out.get("idempotent"), "重抢后是新执行，非幂等命中");
        verify(store, times(2)).claim(eq("k5"), anyString(), any());
    }

    /**
     * 两轮都撞上残留 claim（键每次复查都恰好又消失、又立刻被抢回）：第二轮的 RetryOnceSignal
     * 必须以 409 收口而非死循环——门控就在 attempt==1。
     */
    @Test
    void repeatedVanishingClaimResidueShouldConflictAfterOneRetry() {
        when(store.claim(eq("k6"), anyString(), any())).thenReturn("claim:a").thenReturn("claim:b");
        when(store.get("k6")).thenReturn(null);

        var ex = assertThrows(A2aJobService.JobConflictException.class,
            () -> service.submit("k6", "hello"));

        assertTrue(ex.getMessage().contains("in flight"), ex.getMessage());
        verify(store, times(2)).claim(eq("k6"), anyString(), any());
        verify(store, never()).completeWithTaskId(anyString(), anyString(), anyString(), any());
    }

    // ===== 确定未受理 → 释放可重试 =====

    @Test
    void rejectedSendShouldReleaseClaim() {
        var props = new AgentA2aJobProperties(true, "tok", 1, 24, 4);
        var svc = new A2aJobService(store, props, "1") {
            @Override
            String sendBlocking(String key, String text) {
                throw new A2aJobService.SendRejectedException("transport error: refused");
            }
        };
        when(store.claim(eq("k1"), anyString(), any())).thenReturn(null);   // 认领成功
        when(store.release(eq("k1"), anyString())).thenReturn(true);

        var ex = assertThrows(A2aJobService.SendRejectedException.class,
            () -> svc.submit("k1", "hello"));
        assertTrue(ex.getMessage().contains("transport error"));
        verify(store).release(eq("k1"), anyString());
        verify(store, never()).extendLease(anyString(), anyString(), any());
    }

    // ===== 结果未知 → 保留禁重发 =====

    @Test
    void unknownOutcomeSendShouldKeepClaimAndConflict() {
        var props = new AgentA2aJobProperties(true, "tok", 1, 24, 4);
        var svc = new A2aJobService(store, props, "1") {
            @Override
            String sendBlocking(String key, String text) {
                throw new A2aJobService.SendUnknownException("member HTTP 502");
            }
        };
        when(store.claim(eq("k2"), anyString(), any())).thenReturn(null);
        when(store.extendLease(eq("k2"), anyString(), any(Duration.class))).thenReturn(true);

        var ex = assertThrows(A2aJobService.JobConflictException.class, () -> svc.submit("k2", "hello"));
        assertTrue(ex.getMessage().contains("outcome unknown"));
        verify(store, atLeastOnce()).extendLease(eq("k2"), anyString(), any());
        verify(store, never()).release(anyString(), anyString());
        verify(store, never()).completeWithTaskId(anyString(), anyString(), anyString(), any());
    }

    // ===== CAS 失败（被接管）409 =====

    @Test
    void casFailureShouldConflict() {
        var props = new AgentA2aJobProperties(true, "tok", 1, 24, 4);
        var svc = new A2aJobService(store, props, "1") {
            @Override
            String sendBlocking(String key, String text) {
                return "task-ok";
            }
        };
        when(store.claim(eq("k3"), anyString(), any())).thenReturn(null);
        when(store.completeWithTaskId(eq("k3"), anyString(), eq("task-ok"), any())).thenReturn(false);

        var ex = assertThrows(A2aJobService.JobConflictException.class, () -> svc.submit("k3", "hello"));
        assertTrue(ex.getMessage().contains("claim lost"));
    }

    // ===== 成功路径 =====

    @Test
    void successfulSendShouldSettleMappingAndReturnJob() {
        var props = new AgentA2aJobProperties(true, "tok", 1, 24, 4);
        var svc = new A2aJobService(store, props, "1") {
            @Override
            String sendBlocking(String key, String text) {
                return "task-ok";
            }
        };
        when(store.claim(eq("k4"), anyString(), any())).thenReturn(null);
        when(store.completeWithTaskId(eq("k4"), anyString(), eq("task-ok"), any())).thenReturn(true);

        var out = svc.submit("k4", "hello");
        assertEquals("task-ok", ((java.util.Map<?, ?>) out.get("job")).get("taskId"));
        assertEquals(Boolean.FALSE, out.get("idempotent"));
    }

    // ===== extractTaskId 三级回落 =====

    @Test
    void extractTaskIdShouldFallBackThroughThreeLevels() {
        assertEquals("t1", A2aJobService.extractTaskId(
            "{\"result\":{\"taskId\":\"t1\"}}"));
        assertEquals("t2", A2aJobService.extractTaskId(
            "{\"result\":{\"id\":\"t2\"}}"));
        assertEquals("t3", A2aJobService.extractTaskId(
            "{\"result\":{\"task\":{\"id\":\"t3\"}}}"));
    }

    @Test
    void extractTaskIdShouldRejectJsonRpcErrorAndClaimPrefix() {
        assertThrows(A2aJobService.SendRejectedException.class,
            () -> A2aJobService.extractTaskId("{\"error\":{\"code\":-32000,\"message\":\"bad\"}}"));
        assertThrows(A2aJobService.SendRejectedException.class,
            () -> A2aJobService.extractTaskId("{\"result\":{\"taskId\":\"claim:x\"}}"));
        assertThrows(A2aJobService.SendUnknownException.class,
            () -> A2aJobService.extractTaskId("{\"result\":{}}"));
    }

    // ===== Semaphore 准入 =====

    @Test
    void saturationShouldThrowUnavailableWithoutQueueing() throws Exception {
        var props = new AgentA2aJobProperties(true, "tok", 1, 24, 1);
        var svc = new A2aJobService(new HangingStore(), props, "1") {
            @Override
            String sendBlocking(String key, String text) {
                return "task-x";
            }
        };
        // 占满唯一许可（后台线程提交后由 finishGate 放行）
        var finishGate = new java.util.concurrent.CountDownLatch(1);
        var t = new Thread(() -> {
            try {
                svc.submit("k-hang", "x");
            } catch (Exception ignored) {
            } finally {
                finishGate.countDown();
            }
        });
        t.start();
        Thread.sleep(300);
        assertThrows(A2aJobService.JobUnavailableException.class, () -> svc.submit("k2", "x"));
        t.join(3000);
    }

    /** 认领后阻塞到测试放行的 fake store（准入测试用；其余原语 no-op） */
    static class HangingStore extends A2aJobRedisStore {
        HangingStore() {
            super(mockFacade());
        }

        static RedisConnectionFacade mockFacade() {
            var f = mock(RedisConnectionFacade.class);
            org.mockito.Mockito.lenient().when(f.key(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
            return f;
        }

        @Override
        public String claim(String idempotencyKey, String token, Duration lease) {
            try {
                Thread.sleep(2000);   // 占住 Job 直到准入断言完成
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }

        @Override
        public boolean completeWithTaskId(String idempotencyKey, String token, String taskId, Duration retention) {
            return true;
        }
    }

    // ===== fail-closed =====

    @Test
    void redisFailureShouldBeUnavailable() {
        when(store.claim(anyString(), anyString(), any())).thenThrow(new IllegalStateException("down"));
        assertThrows(A2aJobService.JobUnavailableException.class, () -> service.submit("k9", "hello"));
    }

    // ===== status =====

    @Test
    void statusShouldMapClaimToInProgressAndTaskIdToDone() {
        when(store.get("k3")).thenReturn("claim:x");
        var inFlight = service.status("k3");
        assertEquals("in_progress", ((java.util.Map<?, ?>) inFlight.get("job")).get("state"));

        when(store.get("k3")).thenReturn("task-1");
        var done = service.status("k3");
        assertEquals("task-1", ((java.util.Map<?, ?>) done.get("job")).get("taskId"));

        when(store.get("k4")).thenReturn(null);
        assertNull(service.status("k4"));
    }
}
