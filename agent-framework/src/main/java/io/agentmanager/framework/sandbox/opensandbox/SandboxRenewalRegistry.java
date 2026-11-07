package io.agentmanager.framework.sandbox.opensandbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;

/**
 * 沙箱 TTL 续期注册表（sandbox-pool-opensandbox-design §4.4 T3/T4，进程内可丢弃缓存——
 * 架构原则二：Pod 本地态只允许可丢弃/可重建，崩溃 = 注册表消失 = 续期停止 = 服务端 TTL GC 兜底）。
 *
 * <p><b>双信号生命周期</b>（评审轮一 P1：chat 多 call 轮次与 A2A 无 TurnFinalizer 链路统一覆盖）：
 * <ul>
 *   <li><b>call 引用计数</b>：acquire 成功（{@link SandboxLifecycleManager} ①/③/⑤c 各路径）
 *       {@code acquireRef +1}；{@code OpenSandbox.stop()}（SDK per-call 通用收尾路径，
 *       chat/A2A/AG-UI/background spawn 全链路必经）{@code releaseRef -1 并顺手终刷}
 *       （终审 R2：终刷仍做，覆盖 A2A 等不经 TurnFinalizer 链路的 expires_at 回填）；</li>
 *   <li><b>turn 注册</b>：chat/AG-UI 链路 turn 开始 {@code registerTurn}，turn 终态
 *       （TurnFinalizer 收尾）{@code unregisterTurn + 终刷}（= T2）；<b>注销只挂 turn 终态，
 *       不挂 per-call stop()</b>（一个 turn 可能多 call，per-call 注销会提前停续期）。</li>
 * </ul>
 * 扫描时「refcount&gt;0 或 turn 注册中或 hold 未到期」即续期：LLM 循环期 refcount 短暂归零
 * 由 turn 信号兜住；A2A 链路 refcount 覆盖执行段。
 *
 * <p><b>T4 挂起保留窗</b>：confirm ask 挂起 {@code suspendForConfirm} 置 {@code holdUntil}
 * （每次 ask 重新计窗）；hold 内继续每拍续期——续期即保护（updated_at 持续新鲜 → busy
 * → LRU 绝不选它）；窗口过后停续注销 → 可被复用/自然 GC；confirm 恢复 {@code registerTurn}
 * 重注册并清 hold。
 *
 * <p><b>超龄剔除</b>：注册项带 bornAt，超过 {@code max(TTL×2, 2h)} 强制剔除并 ERROR——
 * SSE 取消后 SDK 不推进、终态永不来时，注册项不得无限续期把沙箱变成「永不过期 + 永不可抢占」。
 *
 * <p><b>renew 失败分类</b>（评审轮一 P2）：404/410（已被服务端 GC/删除）→ 剔注册 +
 * 懒剔除绑定行（提前释放配额）；409（同秒幂等）→ 忽略；网络异常 → WARN + 心跳写
 * （updated_at 刷新 = 跨副本可见的「暂不参与 LRU victim」保守标记，终审 R1）+ 下拍重试，
 * 连续 3 次失败事件告警。
 *
 * <p><b>健康指标</b>（§4.8）：注册数 / 最老条目年龄 / 守护线程循环滞后——滞后是全集群
 * busy 判定退化的前置告警（线程死 = 退回误判窗口）。
 */
public class SandboxRenewalRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SandboxRenewalRegistry.class);

    /** bornAt 超龄下限（max(TTL×2, 2h) 的下限项） */
    private static final Duration MIN_OVERAGE = Duration.ofHours(2);
    /** 连续失败告警阈值（§4.4：连续 N 次失败 → 事件告警） */
    private static final int RENEW_ALERT_THRESHOLD = 3;

    /** 单条续期操作（由 acquire 路径用当前 SDK 句柄构造；单测可桩） */
    @FunctionalInterface
    public interface SandboxRenewer {
        /** 执行一次续期，返回服务端绝对 expiresAt（ISO-8601）；异常由注册表分类 */
        String renew() throws Exception;
    }

    /** 事件汇（SandboxLifecycleManager 的环形缓冲，null = 不记） */
    @FunctionalInterface
    public interface EventSink {
        void accept(String type, String sandboxId, String detail);
    }

    /** 注册表条目：bornAt = 本副本首次注册时刻（进程重启自然重置，无重建依赖） */
    private static final class Entry {
        final String sandboxId;
        final String sessionId;
        final Instant bornAt;
        final long ttlSeconds;
        volatile SandboxRenewer renewer;
        final AtomicInteger callRefs = new AtomicInteger();
        volatile Instant holdUntil;
        volatile Instant lastRenewedAt;
        volatile int renewFailures;

        Entry(String sandboxId, String sessionId, long ttlSeconds, SandboxRenewer renewer, Instant bornAt) {
            this.sandboxId = sandboxId;
            this.sessionId = sessionId;
            this.ttlSeconds = ttlSeconds;
            this.renewer = renewer;
            this.bornAt = bornAt;
        }
    }

    private final SandboxBindingStore bindingStore;
    private final long renewIntervalSeconds;
    private final long confirmHoldMinutes;
    private final Clock clock;
    /** 事件汇（volatile：装配期 Manager 建好后桥接，运行期只读） */
    private volatile EventSink eventSink;

    /** 装配期桥接事件汇（Manager 环形缓冲） */
    public void setEventSink(EventSink sink) {
        this.eventSink = sink;
    }

    /** sandboxId → 条目（acquireRef 重入刷新 renewer 句柄） */
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    /** turn 注册表：sid → 注册时刻（终态注销；注销只挂终态） */
    private final ConcurrentHashMap<String, Instant> activeTurns = new ConcurrentHashMap<>();

    private volatile java.util.concurrent.ScheduledExecutorService scheduler;
    private volatile long lastLoopLagMs = 0;
    private volatile Instant lastSweepAt;

    public SandboxRenewalRegistry(SandboxBindingStore bindingStore,
                                  long renewIntervalSeconds,
                                  long confirmHoldMinutes,
                                  EventSink eventSink) {
        this(bindingStore, renewIntervalSeconds, confirmHoldMinutes, eventSink, Clock.systemUTC());
    }

    /** 测试构造：时钟注入（超龄/hold 用例不 sleep） */
    public SandboxRenewalRegistry(SandboxBindingStore bindingStore,
                                  long renewIntervalSeconds,
                                  long confirmHoldMinutes,
                                  EventSink eventSink,
                                  Clock clock) {
        this.bindingStore = bindingStore;
        this.renewIntervalSeconds = renewIntervalSeconds;
        this.confirmHoldMinutes = confirmHoldMinutes;
        this.eventSink = eventSink;
        this.clock = clock;
    }

    private void emit(String type, String sandboxId, String detail) {
        var sink = this.eventSink;
        if (sink != null) {
            try {
                sink.accept(type, sandboxId, detail);
            } catch (Exception ignored) {
                // 事件是可观测增强，不反噬生命周期
            }
        }
    }

    // ---- 双信号注册 ----

    /**
     * call 引用计数 +1（acquire 成功各路径；守卫进入同线程调用）。同沙箱重入刷新
     * renewer（新 call 新句柄）；bornAt 保留首次注册时刻（超龄判定不被长会话漂移）。
     */
    public void acquireRef(String sandboxId, String sessionId, long ttlSeconds, SandboxRenewer renewer) {
        entries.compute(sandboxId, (k, existing) -> {
            if (existing != null) {
                existing.renewer = renewer;
                return existing;
            }
            return new Entry(sandboxId, sessionId, ttlSeconds, renewer, clock.instant());
        }).callRefs.incrementAndGet();
    }

    /**
     * call 引用计数 -1 + 顺手终刷（{@code OpenSandbox.stop()} per-call 收尾，终审 R2 统一口径：
     * stop() 不承担 turn 注销，但终刷仍做——A2A 链路的 binding.expires_at 回填全靠它）。
     * 计数下限 0（重复 stop/超龄剔除后的迟到 stop 幂等无害）。
     */
    public void releaseRef(String sandboxId) {
        Entry entry = entries.get(sandboxId);
        if (entry == null) {
            return;   // 非生命周期记账沙箱（lifecycle 中途关闭/历史沙箱）：基线行为
        }
        int refs = entry.callRefs.updateAndGet(v -> Math.max(0, v - 1));
        // 终刷：续期一拍把 expires_at 顶到当前 TTL（turn 收尾后 LRU 宽限从本次刷新起算）
        renewEntry(entry, "stop_flush");
        if (refs == 0 && !activeTurns.containsKey(entry.sessionId)
                && !holding(entry, clock.instant())) {
            // call 归零、无 turn 注册、无 hold：chat 单 call 会话在此已无活跃信号——
            // 条目保留（bornAt 超龄/服务端 GC 的 404 兜底剔除），不再续期即自然进入 grace
            log.debug("[sandbox-lifecycle] call ref released to 0 (sid={}, sandbox={})",
                entry.sessionId, sandboxId);
        }
    }

    /** turn 注册（chat/AG-UI turn 开始；confirm 恢复重注册同入口，顺带清除 holdUntil） */
    public void registerTurn(String sessionId) {
        activeTurns.put(sessionId, clock.instant());
        Instant now = clock.instant();
        entries.forEachValue(1, e -> {
            if (sessionId.equals(e.sessionId)) {
                e.holdUntil = null;   // confirm 恢复重注册：清除保留窗
            }
        });
        log.debug("[sandbox-lifecycle] turn registered (sid={})", sessionId);
    }

    /**
     * turn 终态注销 + 终刷（= T2；只挂 TurnFinalizer 终态：正常收尾/出错/被拒）。
     * 终刷覆盖该 sid 名下全部条目——turn 内最后一次 renew 可能已是 RENEW_INTERVAL 前。
     */
    public void unregisterTurn(String sessionId) {
        activeTurns.remove(sessionId);
        entries.forEachValue(1, e -> {
            if (sessionId.equals(e.sessionId)) {
                renewEntry(e, "turn_flush");
            }
        });
        log.debug("[sandbox-lifecycle] turn unregistered (sid={})", sessionId);
    }

    /**
     * T4 挂起保留窗：confirm ask 挂起时对 sid 名下条目置 holdUntil（每次 ask 重新计窗）。
     * hold 内守护线程继续每拍续期——续期即保护（busy → LRU 不可调度、沙箱不会中途到期）。
     */
    public void suspendForConfirm(String sessionId) {
        Instant holdUntil = clock.instant().plus(Duration.ofMinutes(confirmHoldMinutes));
        entries.forEachValue(1, e -> {
            if (sessionId.equals(e.sessionId)) {
                e.holdUntil = holdUntil;
            }
        });
        log.info("[sandbox-lifecycle] confirm hold armed (sid={}, until={})", sessionId, holdUntil);
    }

    // ---- 守护线程 ----

    /** 启动守护续期线程（daemon；AgentScopeConfig 装配调用，destroy 经 close 停止） */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "sandbox-renewal-registry");
            t.setDaemon(true);
            return t;
        });
        long intervalMs = Math.max(1, renewIntervalSeconds) * 1000L;
        scheduler.scheduleWithFixedDelay(this::sweepSafely, intervalMs, intervalMs,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        log.info("[sandbox-lifecycle] renewal registry started (interval={}s, holdMinutes={})",
            renewIntervalSeconds, confirmHoldMinutes);
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** 扫描一拍（含滞后指标；包级可见供单测直接驱动） */
    void sweepSafely() {
        long start = System.nanoTime();
        try {
            sweep();
        } catch (Throwable t) {
            log.warn("[sandbox-lifecycle] renewal sweep errored: {}", t.getMessage());
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // 循环滞后 = 本拍实际间隔超出配置周期的部分（scheduleWithFixedDelay 无重叠，
            // 滞后即 GC/阻塞征兆——全集群 busy 判定退化的前置告警）
            lastLoopLagMs = Math.max(0, elapsedMs - Math.max(1, renewIntervalSeconds) * 1000L);
            lastSweepAt = clock.instant();
        }
    }

    /** 扫描一拍（包级可见供单测直接驱动，不 sleep 等 scheduler） */
    void sweep() {
        Instant now = clock.instant();
        for (Entry e : entries.values()) {
            // 超龄剔除（防挂死泄漏）：bornAt 超过 max(TTL×2, 2h) 强制剔除 + ERROR，
            // 沙箱失去续期后按服务端 TTL 自然到期——「永不过期 + 永不可抢占」被封死
            Duration overage = Duration.ofSeconds(e.ttlSeconds * 2).compareTo(MIN_OVERAGE) > 0
                ? Duration.ofSeconds(e.ttlSeconds * 2) : MIN_OVERAGE;
            if (Duration.between(e.bornAt, now).compareTo(overage) > 0) {
                entries.remove(e.sandboxId);
                emit("registry_overage", e.sandboxId,
                    "bornAt=" + e.bornAt + " overage=" + overage + " sid=" + e.sessionId);
                log.error("[sandbox-lifecycle] registry entry overage evicted (sid={}, sandbox={}, age={}s)",
                    e.sessionId, e.sandboxId, Duration.between(e.bornAt, now).toSeconds());
                continue;
            }
            boolean holding = holding(e, now);
            if (e.callRefs.get() > 0 || activeTurns.containsKey(e.sessionId) || holding) {
                renewEntry(e, holding ? "sweep_hold" : "sweep");
            }
        }
    }

    /** hold 语义：holdUntil 未到期即挂起保留中（每次 ask 重新计窗） */
    private boolean holding(Entry e, Instant now) {
        return e.holdUntil != null && now.isBefore(e.holdUntil);
    }

    /** 续期一拍 + 服务端绝对 expiresAt 回填 binding（失败分类，§4.4） */
    private void renewEntry(Entry e, String phase) {
        var renewer = e.renewer;
        if (renewer == null) {
            return;
        }
        try {
            String expiresAt = renewer.renew();
            e.lastRenewedAt = clock.instant();
            e.renewFailures = 0;
            try {
                bindingStore.touchExpiresAt(e.sandboxId, expiresAt);
            } catch (SandboxBindingStore.DataAccessException dae) {
                // 续期成功但簿记失败：不重试（下拍续期再写），仅告警
                log.warn("[sandbox-lifecycle] binding flush after renew failed (sandbox={}): {}",
                    e.sandboxId, dae.getMessage());
            }
            log.debug("[sandbox-lifecycle] renewed (phase={}, sandbox={}, expiresAt={})",
                phase, e.sandboxId, expiresAt);
        } catch (SandboxApiException api) {
            int status = api.getStatusCode() != null ? api.getStatusCode() : 0;
            if (status == 404 || status == 410) {
                // 沙箱已被服务端 ttl_expiry GC 或运维删除：剔注册 + 懒剔除绑定（提前释放配额）；
                // turn 内下条命令失败报错，重建发生在下一 turn
                entries.remove(e.sandboxId);
                try {
                    bindingStore.lazyDeleteOwned(e.sandboxId, e.sessionId);
                } catch (SandboxBindingStore.DataAccessException dae) {
                    log.warn("[sandbox-lifecycle] lazy delete after renew 404 failed (sandbox={}): {}",
                        e.sandboxId, dae.getMessage());
                }
                emit("renew_404", e.sandboxId, "sid=" + e.sessionId + " phase=" + phase);
                log.info("[sandbox-lifecycle] renew {} → entry evicted + binding lazily deleted (sid={}, sandbox={})",
                    status, e.sessionId, e.sandboxId);
            } else if (status == 409) {
                // 非严格更晚（同秒幂等）：忽略
                log.debug("[sandbox-lifecycle] renew 409 idempotent-ignored (sandbox={})", e.sandboxId);
            } else {
                renewFailed(e, phase, "http " + status);
            }
        } catch (Exception net) {
            renewFailed(e, phase, net.getClass().getSimpleName() + ": " + net.getMessage());
        }
    }

    /** 网络异常/其他失败：WARN + 心跳写（跨副本保守标记）+ 下拍重试；连续 3 次事件告警 */
    private void renewFailed(Entry e, String phase, String cause) {
        e.renewFailures++;
        log.warn("[sandbox-lifecycle] renew failed ({}/{}: {}) phase={} sid={} sandbox={}",
            e.renewFailures, RENEW_ALERT_THRESHOLD, cause, phase, e.sessionId, e.sandboxId);
        try {
            bindingStore.heartbeat(e.sandboxId);   // updated_at 心跳 = 跨副本「暂不参与 victim」保守标记
        } catch (SandboxBindingStore.DataAccessException dae) {
            log.warn("[sandbox-lifecycle] renew-fail heartbeat failed (sandbox={}): {}",
                e.sandboxId, dae.getMessage());
        }
        if (e.renewFailures >= RENEW_ALERT_THRESHOLD) {
            emit("renew_fail", e.sandboxId, "sid=" + e.sessionId + " consecutive=" + e.renewFailures
                + " cause=" + cause);
        }
    }

    // ---- 健康指标（§4.8 debug 页）----

    /** 注册条目数（本副本） */
    public int registrySize() {
        return entries.size();
    }

    /** turn 注册数（本副本） */
    public int activeTurnCount() {
        return activeTurns.size();
    }

    /** 最老条目年龄（ms；空表 0） */
    public long oldestEntryAgeMs() {
        Instant now = clock.instant();
        return entries.values().stream()
            .mapToLong(e -> Math.max(0, Duration.between(e.bornAt, now).toMillis()))
            .max().orElse(0);
    }

    /** 守护线程最近一拍循环滞后（ms；0 = 准时） */
    public long lastLoopLagMs() {
        return lastLoopLagMs;
    }

    /** 最近一拍扫描时刻（可空 = 尚未开扫） */
    public Instant lastSweepAt() {
        return lastSweepAt;
    }

    /** debug 视图：条目明细（sid/sandbox/refcount/hold/bornAt） */
    public List<Map<String, Object>> debugEntries() {
        Instant now = clock.instant();
        return entries.values().stream()
            .<Map<String, Object>>map(e -> Map.of(
                "session_id", e.sessionId,
                "sandbox_id", e.sandboxId,
                "call_refs", e.callRefs.get(),
                "turn_registered", activeTurns.containsKey(e.sessionId),
                "hold_until", e.holdUntil == null ? "" : e.holdUntil.toString(),
                "age_ms", Math.max(0, Duration.between(e.bornAt, now).toMillis()),
                "last_renewed_at", e.lastRenewedAt == null ? "" : e.lastRenewedAt.toString(),
                "renew_failures", e.renewFailures))
            .toList();
    }
}
