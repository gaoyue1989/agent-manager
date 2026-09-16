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
 * 停续租 + 删除租约行。
 *
 * <p>续租的三种结果处置不同（见 {@link TurnLeaseStore.RenewOutcome}）：
 * <ul>
 *   <li>{@code HELD} —— 正常，记下最近一次成功时间</li>
 *   <li>{@code ERROR}（瞬时故障）—— <b>不停止</b>，{@code scheduleWithFixedDelay} 下次自动重试；
 *       只有故障持续超过一个 TTL 才升级为失去执行权（那时租约早已到期，别的副本可能已接管）</li>
 *   <li>{@code LOST}（真被接管/释放）—— 置 {@link #isLost()} 并停止续租</li>
 * </ul>
 *
 * <p><b>调用方必须在写入前检查 {@link #isLost()}</b>：置位意味着本副本已不再拥有该 session
 * 的写入权，继续 append 会用与接管者重叠的 seq 区间写事件。
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

    /** 是否已确认失去执行权（由续租线程置位，只置一次） */
    private final AtomicBoolean lost = new AtomicBoolean(false);

    /** 最近一次续租成功的时刻；用于判断瞬时故障是否已持续超过一个 TTL */
    private volatile long lastRenewOkAt = System.currentTimeMillis();

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

    /**
     * 是否已失去执行权。置位后调用方必须停止写入该 session 的事件
     * （不能走 flush/closeSession——那会把按旧 seq 区间分配的行写下去）。
     */
    public boolean isLost() {
        return lost.get();
    }

    /** 幂等释放：停续租 + 删租约行 */
    public void release() {
        if (released.compareAndSet(false, true)) {
            renewer.shutdownNow();
            if (lost.get()) {
                // 行已经属于接管者。DELETE 有 token 校验不会误删，但也不该谎称是自己释放的
                log.info("Turn lease already lost, skipping release: sid={}", sessionId);
                return;
            }
            store.release(sessionId, token);
            log.info("Turn lease released: sid={}", sessionId);
        }
    }

    @Override
    public void close() {
        release();
    }

    private void startRenew() {
        // 用毫秒而非 toSeconds()：后者会把亚秒配置截断成 0，scheduleWithFixedDelay(0) 变忙轮询
        long intervalMs = Math.max(1, store.renewInterval().toMillis());
        renewer.scheduleWithFixedDelay(() -> {
            switch (store.renew(sessionId, token)) {
                case HELD -> lastRenewOkAt = System.currentTimeMillis();
                case LOST -> markLost("takeover detected (renew matched 0 rows)");
                case ERROR -> {
                    // 瞬时故障：scheduleWithFixedDelay 会自动重试，这里只需判断是否已超 TTL。
                    // 超过一个 TTL 就说明租约早已到期、别的副本可能已经接管，再续也没意义。
                    long silentMs = System.currentTimeMillis() - lastRenewOkAt;
                    if (silentMs > store.ttl().toMillis()) {
                        markLost("renew failing for " + silentMs + "ms (> TTL)");
                    } else {
                        log.warn("Turn lease renew errored (sid={}), retrying in {}ms",
                            sessionId, intervalMs);
                    }
                }
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void markLost(String reason) {
        if (lost.compareAndSet(false, true)) {
            log.error("Turn lease LOST (sid={}): {} —— 执行副本必须立即停止写入该 session 的事件",
                sessionId, reason);
            renewer.shutdownNow();
        }
    }
}
