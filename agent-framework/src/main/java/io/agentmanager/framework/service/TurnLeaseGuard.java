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
 * <p>持有一个已获取的 turn_lease token，并启动后台续租（默认每 20s，TTL 60s）；release 幂等：
 * 停续租 + 删除租约行。
 *
 * <p>续租的三种结果处置不同（见 {@link TurnLeaseStore.RenewOutcome}）：
 * <ul>
 *   <li>{@code HELD} —— 正常，刷新「最近一次确认持有」的时刻</li>
 *   <li>{@code ERROR}（瞬时故障）—— <b>不停止</b>续租，下一拍自动重试；只有持续到
 *       {@link #isLost()} 的判据超时才算丢锁</li>
 *   <li>{@code LOST}（真被接管/释放）—— 立刻判定失去执行权</li>
 * </ul>
 *
 * <p><b>调用方必须在写入前检查 {@link #isLost()}</b>：判定为丢锁意味着本副本已不再拥有该
 * session 的写入权，继续 append 会用与接管者重叠的 seq 区间写事件。
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

    /**
     * 到「必须停手」的时长，见 {@link #lostAfterNanos(TurnLeaseStore)}。
     */
    private final long lostAfterNanos;

    /** 是否已确认失去执行权（不可逆：一旦置位，后续续租成功也不会翻回来） */
    private final AtomicBoolean lost = new AtomicBoolean(false);

    /** 最近一次确认持有租约的**单调**时刻 */
    private volatile long lastHeldNanos;

    /** 丢锁终态帧的一次性闸门（见 {@link #tryMarkLostNotified()}） */
    private final AtomicBoolean lostNotified = new AtomicBoolean(false);

    public TurnLeaseGuard(TurnLeaseStore store, String sessionId, String token) {
        this.store = store;
        this.sessionId = sessionId;
        this.token = token;
        // 先算判据再起线程：构造失败不该留下一个已经在跑的续租线程
        this.lostAfterNanos = lostAfterNanos(store);
        this.lastHeldNanos = System.nanoTime();
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
     *
     * <p><b>时间判据由调用方（写入侧）求值</b>，而不是只读续租线程置的标志位。原因是续租
     * 线程自己也可能不再转动——JDBC 连接挂死、长 GC、线程饥饿——那时一拍都不会来，
     * 标志位永远不置位，而租约其实早已到期、别的副本随时可能接管。把判据放在这里，
     * 那种情况下照样停手。
     *
     * <p>{@code released} 参与判断：HITL 暂停点与 turn 正常结束都会**有意**让出锁，
     * 那之后不再续租，时间判据当然会超时——但那不是被抢占，必须排除在外，否则
     * AGENT_END 之后残留的事件会被误判成丢锁、把该刷的缓冲丢掉。
     */
    public boolean isLost() {
        return lost.get()
            || (!released.get() && System.nanoTime() - lastHeldNanos > lostAfterNanos);
    }

    /**
     * 丢锁终态帧的一次性闸门。
     *
     * <p>{@code handleEventAndEmit} 是每个事件都进来一次，没有闸门就会为剩下的每个事件
     * 各发一帧 interrupted。
     *
     * <p>返回 true 时同时把 {@link #lost} 钉死：既然已经按丢锁处理并中止了本副本的写入，
     * 就不能因为续租线程随后苏醒又续上一拍而"复活"——那会让后面的事件重新走上落库路径。
     *
     * @return true 仅首次——调用方负责发帧与收流
     */
    public boolean tryMarkLostNotified() {
        if (lostNotified.compareAndSet(false, true)) {
            lost.set(true);
            renewer.shutdown();
            return true;
        }
        return false;
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
        renewer.scheduleWithFixedDelay(this::renewOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void renewOnce() {
        if (released.get()) {
            return;
        }
        switch (store.renew(sessionId, token)) {
            case HELD -> lastHeldNanos = System.nanoTime();
            case LOST -> markLost("takeover detected (renew matched 0 rows)");
            case ERROR -> {
                // 瞬时故障：不关线程，下一拍自动重试——旧实现在这里 shutdownNow，
                // 于是「一次连接池抖动 = 永久停掉本 turn 的续租」。
                if (System.nanoTime() - lastHeldNanos > lostAfterNanos) {
                    markLost("renew has been failing for over " + lostAfterNanos + "ns");
                } else {
                    log.warn("Turn lease renew errored (sid={}), retrying at next tick", sessionId);
                }
            }
        }
    }

    private void markLost(String reason) {
        if (lost.compareAndSet(false, true)) {
            log.error("Turn lease LOST (sid={}): {} —— 执行副本必须立即停止写入该 session 的事件",
                sessionId, reason);
        }
        // shutdown 而非 shutdownNow：本方法可能正跑在续租线程上，shutdownNow 会自我中断
        renewer.shutdown();
    }

    /**
     * 到「必须停手」的时长：{@code max(renewInterval, ttl - renewInterval)}。
     *
     * <p><b>为什么不是 ttl</b>：接管方在 {@code expires_at} 一过就能得手，而本副本对租约状态
     * 的认知粒度是一拍续租。取 {@code ttl - renewInterval} 意味着在别人**可能**接管之前
     * 就停手，而不是等确认自己已经被踢掉。
     *
     * <p><b>为什么有 renewInterval 这个下限</b>：配置失当（interval ≥ ttl）会让
     * {@code ttl - interval} 变成 0 或负数，那样每个 turn 刚开头就自称丢锁。至少给一拍机会。
     */
    private static long lostAfterNanos(TurnLeaseStore store) {
        var interval = store.renewInterval();
        var bound = store.ttl().minus(interval);
        if (bound.compareTo(interval) < 0) {
            bound = interval;
        }
        return Math.max(1L, bound.toNanos());
    }
}
