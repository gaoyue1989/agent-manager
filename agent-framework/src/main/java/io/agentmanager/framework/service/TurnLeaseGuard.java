package io.agentmanager.framework.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turn 租约守卫（无状态单次流架构共用，见 stateless-single-stream-plan 4.1.3 执行权语义）。
 *
 * <p>持有一个已获取的 turn_lease token，并启动后台续租（每 20s，TTL 60s）；release 幂等：
 * 停续租 + 删除租约行。续租失败 = 租约被接管/已释放 → 续租线程自停（不主动删锁，避免误删他人锁）。
 *
 * <p>语义约束：租约只覆盖活跃执行段；permission_ask（HITL 暂停点）即让出锁；挂起期间新消息
 * 可直接执行；confirm-stream 恢复 = 新执行段需重新 acquire。
 */
public final class TurnLeaseGuard implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TurnLeaseGuard.class);

    private final TurnLeaseStore store;
    private final String sessionId;
    private final String token;
    private final ScheduledExecutorService renewer;
    private final AtomicBoolean released = new AtomicBoolean(false);

    public TurnLeaseGuard(TurnLeaseStore store, String sessionId, String token) {
        this.store = store;
        this.sessionId = sessionId;
        this.token = token;
        this.renewer = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "turn-renew-" + Math.abs(sessionId.hashCode()));
            t.setDaemon(true);
            return t;
        });
        startRenew();
    }

    public String token() {
        return token;
    }

    /** 幂等释放：停续租 + 删租约行 */
    public void release() {
        if (released.compareAndSet(false, true)) {
            renewer.shutdownNow();
            store.release(sessionId, token);
            log.info("Turn lease released: sid={}", sessionId);
        }
    }

    @Override
    public void close() {
        release();
    }

    private void startRenew() {
        long intervalSeconds = store.renewInterval().toSeconds();
        renewer.scheduleWithFixedDelay(() -> {
            if (!store.renew(sessionId, token)) {
                log.warn("Turn lease takeover detected (sid={}): renew failed, stopping", sessionId);
                renewer.shutdownNow();
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }
}