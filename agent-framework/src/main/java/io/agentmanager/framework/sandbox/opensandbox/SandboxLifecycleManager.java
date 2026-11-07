package io.agentmanager.framework.sandbox.opensandbox;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;

import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;

/**
 * 沙箱借出决策编排（sandbox-pool-opensandbox-design §4.2，PR-D 核心）。
 *
 * <p>入口决议（§4.2）：harness acquire 链路 P1~P3 维持现状；P4（全 miss → create）与
 * P3 resume 校验失败处改为走本决策——由 {@link OpenSandboxClient#create} /
 * {@link OpenSandboxClient#resume} 挂接（身份捕获点 = 守卫 tryEnter 同线程注入，
 * {@link OpenSandboxFilesystemSpec} ThreadLocal，控制器侧注入会被 defer 挪线程失效）。
 *
 * <pre>
 * acquire(sid, userKey):
 *   ① binding 命中 → touch 先行（封 grace 空窗）→ probe → connect 复用
 *   ② 占额（CAS + PENDING 行同事务）→ ③ create（事务外 HTTP）→ 转正
 *   ⑤ 配额满 → a. 懒剔除（守卫式）→ b. 锚行锁重算 → 回 ②
 *            → c. LRU victim 改绑（updated_at 快照乐观校验）→ connect+renew
 *            → 全忙 → UserQuotaExhaustedException
 * </pre>
 *
 * <p><b>busy 双事实源</b>（评审 P0 修订）：{@code turn_lease.isHeldStrict(sid)}（自限 10min，
 * 补 >10min turn 盲区）OR {@code binding.updated_at} 新鲜度（grace = max(3×RENEW_INTERVAL, 120s)，
 * 补 A2A 无租约盲区；阈值必须 &gt; 续期间隔，时间比较下推 SQL 用 DB 时钟）。
 *
 * <p><b>fail-open</b>（§4.2 要点）：MySQL 不可达 → 跳过 ②③⑤ 直接 create 不记账
 * （60s 限流 WARN + 事件）——存储故障期不制造沙箱重建风暴，恢复后由懒剔除/定时清理收敛。
 *
 * <p><b>事件环形缓冲</b>（§4.8）：100 条进程内可丢弃缓存，借出/LRU 复用/懒剔除/renew 失败
 * （分类）/fail-open 触发全部落事件——后两者只 WARN 不进事件流会让「续期失败→到期重建」无法归因。
 */
public class SandboxLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleManager.class);

    /**
     * grace 下限（评审轮一 P1：必须 > 续期间隔，60s 宽限与 60s 续期同值会拍间抖动）。
     * 生产默认 120s；e2e 经系统属性 {@code -Dagent.sandbox.lifecycle.grace-floor-seconds=6}
     * 压低加速 idle 判定（先例同 turn-lease lifetime 的 JVM 参数压低，设计 §9.3）。
     */
    private static final long GRACE_FLOOR_SECONDS = Long.getLong(
        "agent.sandbox.lifecycle.grace-floor-seconds", 120L);
    /** ⑤ 整体重试上限（改绑 affected=0：他副本抢先或受害在途 touch 先行） */
    private static final int QUOTA_FULL_ROUNDS = 3;
    /** fail-open 告警限流窗（ms） */
    private static final long FAIL_OPEN_WARN_INTERVAL_MS = 60_000;
    /** acquire ①/② 循环重试上限（DUPLICATE_SESSION：同会话获取在途，终审 d10） */
    private static final int ACQUIRE_ROUNDS = 3;
    /** 事件环形缓冲容量（§4.8） */
    private static final int EVENT_RING_SIZE = 100;
    /** 换代提示登记表上界（进程内可丢弃缓存，防泄漏） */
    private static final int OWNERSHIP_LOST_MAX = 256;

    private final SandboxBindingStore bindingStore;
    private final SandboxRenewalRegistry registry;
    private final io.agentmanager.framework.config.SandboxLifecycleConfig config;
    private final int ttlMinutes;
    private final io.agentmanager.framework.service.TurnLeaseStore turnLeaseStore;
    private final io.agentmanager.framework.service.ConfirmContextStore confirmContextStore;

    /** fail-open 限流：上次告警时间戳 */
    private volatile long lastFailOpenWarnAt = 0L;

    /** 事件环形缓冲（100 条，§4.8） */
    private final Deque<Map<String, Object>> eventRing = new ArrayDeque<>();

    /**
     * 会话沙箱换代登记（sid → 时刻，容量有界）：两种换代路径均登记——
     * ① P3 resume 归属校验拦截（行在但归属他会话，OpenSandboxClient 归属块）；
     * ② P3 resume 命中已销毁沙箱（404/410，行被懒剔除/renew-404 sweep 后沙箱 GC——
     *    设计 §4.4「① miss → 重建 + 换代提示帧」分支，V6⑧ live 实锤此前漏发）。
     * chat/confirm 流的换代提示帧（「沙箱已重建，未保存的工作区文件可能丢失」）
     * 据此一次性弹出（popSandboxRebuiltSince）。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> ownershipLostAt =
        new java.util.concurrent.ConcurrentHashMap<>();

    public SandboxLifecycleManager(SandboxBindingStore bindingStore,
                                   SandboxRenewalRegistry registry,
                                   io.agentmanager.framework.config.SandboxLifecycleConfig config,
                                   int ttlMinutes,
                                   io.agentmanager.framework.service.TurnLeaseStore turnLeaseStore,
                                   io.agentmanager.framework.service.ConfirmContextStore confirmContextStore) {
        this.bindingStore = bindingStore;
        this.registry = registry;
        this.config = config;
        this.ttlMinutes = ttlMinutes;
        this.turnLeaseStore = turnLeaseStore;
        this.confirmContextStore = confirmContextStore;
    }

    // ---- 协作接口（OpenSandboxClient 提供，单测可桩）----

    /** 探活三态：ALIVE（携句柄）/ DEAD（明确 404/410）/ UNKNOWN（网络异常，保守视为活） */
    public record ProbeResult(Sandbox handle, ProbeState state) {
        public enum ProbeState { ALIVE, DEAD, UNKNOWN }

        public static ProbeResult alive(Sandbox handle) {
            return new ProbeResult(handle, ProbeState.ALIVE);
        }

        public static ProbeResult dead() {
            return new ProbeResult(null, ProbeState.DEAD);
        }

        public static ProbeResult unknown() {
            return new ProbeResult(null, ProbeState.UNKNOWN);
        }
    }

    /**
     * 连接沙箱并 getInfo 探活（①/⑤a）：活 → ALIVE 携句柄（⑤c 复用连接）；明确 404/410 →
     * DEAD（懒剔除依据）；网络异常/不可判定 → UNKNOWN（§4.2 ⑤a：保守视为活，不删）。
     */
    @FunctionalInterface
    public interface SandboxConnector {
        ProbeResult probe(String sandboxId);
    }

    /** ③ 真实新建委托（SDK create + OpenSandbox 包装 + start，事务外 HTTP） */
    @FunctionalInterface
    public interface SandboxCreateDelegate {
        CreatedSandbox create() throws Exception;
    }

    /** 新建产物：SDK 句柄供续期注册；serverExpiresAt 为 create 响应的绝对过期（回填绑定） */
    public record CreatedSandbox(String sandboxId, String serverExpiresAt,
                                 OpenSandbox sandbox, Sandbox osbHandle) {}

    /** 复用包装委托（①/⑤c：连接的 SDK 句柄 → OpenSandbox，resume 语义，不 start） */
    @FunctionalInterface
    public interface SandboxReuseWrapper {
        OpenSandbox wrap(Sandbox osbHandle, String sandboxId) throws Exception;
    }

    // ---- acquire 决策编排（§4.2 ①~⑤）----

    /**
     * 借出决策：优先复用本会话绑定，其次占额新建，配额满走 LRU，全忙抛
     * {@link UserQuotaExhaustedException}。create 失败补偿占额（PENDING 删 + 计数递减）。
     */
    public OpenSandbox acquire(String sessionId, String userKey,
                               SandboxConnector connector,
                               SandboxCreateDelegate createDelegate,
                               SandboxReuseWrapper reuseWrapper) throws Exception {
        try {
            return acquireInner(sessionId, userKey, connector, createDelegate, reuseWrapper);
        } catch (UserQuotaExhaustedException | SandboxException e) {
            throw e;   // 业务异常面照常透出（全忙 409/SSE、probe 不可判定等）
        } catch (SandboxBindingStore.DataAccessException dae) {
            // fail-open 安全网：任意簿记步（touch/占额/懒剔除）存储故障 → 直建不记账（§4.2 要点）
            warnFailOpen("acquire", dae);
            return createUntracked(createDelegate);
        }
    }

    private OpenSandbox acquireInner(String sessionId, String userKey,
                                     SandboxConnector connector,
                                     SandboxCreateDelegate createDelegate,
                                     SandboxReuseWrapper reuseWrapper) throws Exception {
        // 同会话在途获取（DUPLICATE_SESSION）回 ① 重试（终审 d10），有界防自旋
        for (int round = 0; round < ACQUIRE_ROUNDS; round++) {
            // ===== ① 绑定复用 =====
            SandboxBindingStore.BindingRow existing = null;
            try {
                existing = bindingStore.findBySessionId(sessionId).orElse(null);
            } catch (SandboxBindingStore.DataAccessException dae) {
                warnFailOpen("acquire_1_lookup", dae);
                // fail-open：簿记不可读 → 直建不记账（旧沙箱 TTL 内并存自然 GC，§4.2 要点）
                return createUntracked(createDelegate);
            }
            if (existing != null) {
                // touch 先行（终审 d2）：在 probe/connect 之前占住 updated_at，使他副本 ⑤c
                // 的乐观校验能感知本会话「在途复用」——只写 updated_at（终审 R2）
                boolean touched = bindingStore.touchOnReuse(existing.id(), sessionId);
                if (touched) {
                    var probe = connector.probe(existing.sandboxId());
                    if (probe.state() == SandboxLifecycleManager.ProbeResult.ProbeState.ALIVE) {
                        registry.acquireRef(existing.sandboxId(), sessionId, ttlSeconds(),
                            renewer(probe.handle()));
                        emit("acquire_reuse", existing.sandboxId(), "sid=" + sessionId);
                        return reuseWrapper.wrap(probe.handle(), existing.sandboxId());
                    }
                    if (probe.state() == SandboxLifecycleManager.ProbeResult.ProbeState.DEAD) {
                        // probe 明确 404/410 → 本会话行懒剔除 + 计数递减 → 走 ② 重建（§4.4 到期重建语义）
                        log.info("[sandbox-lifecycle] binding sandbox dead, lazy delete + rebuild (sid={}, sandbox={})",
                            sessionId, existing.sandboxId());
                        bindingStore.lazyDeleteOwned(existing.sandboxId(), sessionId);
                        emit("lazy_delete", existing.sandboxId(), "sid=" + sessionId + " reason=probe_404");
                    } else {
                        // 不可判定（网络异常）→ 保守视为活不删，但本尝试无法复用 → 报错（下轮重试）
                        throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR, "probe",
                            "sandbox probe unavailable (sid=" + sessionId + ", sandbox="
                                + existing.sandboxId() + ")", null);
                    }
                } else {
                    // affected=0：绑定已被他副本改绑或懒剔除 → 放弃复用（不 connect），视同 miss 走 ②
                    log.info("[sandbox-lifecycle] touch lost race, treat as miss (sid={})", sessionId);
                    emit("touch_lost", existing.sandboxId(), "sid=" + sessionId);
                }
            }

            // ===== ② 占额 =====
            SandboxBindingStore.OccupyResult occupy =
                bindingStore.occupyQuota(userKey, sessionId, config.userQuota());
            switch (occupy.status()) {
                case DUPLICATE_SESSION -> {
                    // 同会话/同用户 A2A 获取在途：回 ① 重试而非补偿（终审 d10）
                    log.info("[sandbox-lifecycle] concurrent acquire in flight for session, retry ① (sid={})",
                        sessionId);
                    continue;
                }
                case QUOTA_FULL -> {
                    // ===== ⑤ 配额满路径（null = 懒剔除/重算后已有额度，回 ② 重试）=====
                    OpenSandbox reused = quotaFull(sessionId, userKey, connector, reuseWrapper);
                    if (reused == null) {
                        continue;
                    }
                    return reused;
                }
                case OCCUPIED -> {
                    // ===== ③ create（事务外 HTTP，不持任何锁）=====
                    try {
                        CreatedSandbox created = createDelegate.create();
                        boolean promoted;
                        try {
                            promoted = bindingStore.promotePending(occupy.pendingSandboxId(),
                                created.sandboxId(),
                                created.serverExpiresAt() != null
                                    ? created.serverExpiresAt() : serverNowPlusTtl());
                        } catch (SandboxBindingStore.DataAccessException dae) {
                            // 转正簿记失败：沙箱已建成，直建记账缺失由懒剔除/清理收敛（fail-open 纪律）
                            warnFailOpen("acquire_3_promote", dae);
                            promoted = false;
                        }
                        if (!promoted) {
                            // 转正 affected=0（行被并发误删/回收，终审 R1/d8）：仅告警放弃——
                            // 计数不动（删方已递减），孤儿沙箱靠服务端 TTL GC
                            log.warn("[sandbox-lifecycle] PENDING promote missed (sandbox={}, pending={})",
                                created.sandboxId(), occupy.pendingSandboxId());
                            emit("promote_miss", created.sandboxId(), "sid=" + sessionId);
                        }
                        registry.acquireRef(created.sandboxId(), sessionId, ttlSeconds(),
                            renewer(created.osbHandle()));
                        emit("acquire_create", created.sandboxId(), "sid=" + sessionId
                            + (config.poolRef() == null || config.poolRef().isBlank()
                                ? "" : " pool=" + config.poolRef()));
                        return created.sandbox();
                    } catch (Exception createFail) {
                        // create 失败：补偿短事务 DELETE PENDING + 计数递减（§4.6 异常面）
                        try {
                            bindingStore.compensatePending(occupy.pendingSandboxId(), userKey);
                        } catch (SandboxBindingStore.DataAccessException dae) {
                            warnFailOpen("acquire_3_compensate", dae);
                        }
                        emit("create_failed", null, "sid=" + sessionId
                            + " cause=" + createFail.getClass().getSimpleName());
                        throw createFail;
                    }
                }
                default -> throw new IllegalStateException("unreachable occupy status");
            }
        }
        throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR, "acquire",
            "concurrent acquire for session kept colliding after "
                + ACQUIRE_ROUNDS + " rounds (sid=" + sessionId + ")", null);
    }

    /** fail-open 直建：不占额、不落绑定（沙箱无记账无续期，TTL 到期服务端 GC 自然收敛） */
    private OpenSandbox createUntracked(SandboxCreateDelegate createDelegate) throws Exception {
        CreatedSandbox created = createDelegate.create();
        emit("acquire_create_untracked", created.sandboxId(), "reason=fail_open");
        return created.sandbox();
    }

    /**
     * ⑤ 配额满路径：a. 懒剔除（守卫式，probe 锁外）→ b. 锚行锁重算（存活行含 PENDING）
     * → 有额度回 ② 重试 CAS（返回 null）；c. LRU victim 改绑（updated_at 快照乐观校验）
     * → renew + 复用 probe 句柄；idle 空 → {@link UserQuotaExhaustedException}。
     */
    private OpenSandbox quotaFull(String sessionId, String userKey,
                                  SandboxConnector connector,
                                  SandboxReuseWrapper reuseWrapper) throws Exception {
        for (int round = 0; round < QUOTA_FULL_ROUNDS; round++) {
            // a. 懒剔除（全部守卫式 DELETE；probe 在锚锁外做，锁内只做守卫式 DELETE/改绑）
            bindingStore.recycleStalePending(userKey);
            bindingStore.lazyDeleteExpired(userKey);
            List<SandboxBindingStore.BindingRow> live = bindingStore.listLive(userKey, graceSeconds());
            // probe 逐条（≤quota 条，锁外）：活 → 携句柄参与 victim 选取；
            // 死 → 守卫式删除 + 计数递减；不可判定 → 保守视为活但无句柄（不删、不可作 victim）
            record Candidate(SandboxBindingStore.BindingRow row, Sandbox handle) {}
            List<Candidate> candidates = new ArrayList<>();
            for (var row : live) {
                var probe = connector.probe(row.sandboxId());
                switch (probe.state()) {
                    case ALIVE -> candidates.add(new Candidate(row, probe.handle()));
                    case DEAD -> {
                        boolean deleted = bindingStore.deleteByIdGuarded(row.id(), row.sessionId(),
                            row.updatedAt());
                        if (deleted) {
                            emit("lazy_delete", row.sandboxId(),
                                "sid=" + row.sessionId() + " reason=probe_404");
                        }
                    }
                    case UNKNOWN -> {
                        log.warn("[sandbox-lifecycle] probe unknown, conservatively alive (sid={}, sandbox={})",
                            row.sessionId(), row.sandboxId());
                        candidates.add(new Candidate(row, null));
                    }
                }
            }
            // b. 锚行锁内重算（不变量成立）→ 有额度回 ② 重试 CAS
            int count = bindingStore.recomputeCount(userKey);
            if (config.userQuota() <= 0 || count < config.userQuota()) {
                log.info("[sandbox-lifecycle] quota recomputed to {}, retry acquire (sid={}, user={})",
                    count, sessionId, userKey);
                emit("quota_recompute", null, "user=" + userKey + " count=" + count);
                return null;
            }
            // c. busy/idle 判定（双事实源）；仅句柄可用的行可作 victim（UNKNOWN 行不可连接）
            List<Candidate> idle = new ArrayList<>();
            for (var c : candidates) {
                if (c.handle() != null && !busy(c.row())) {
                    idle.add(c);
                }
            }
            if (idle.isEmpty()) {
                continue;   // 本轮全忙（可能是在途 touch 造成的瞬时态）：下一轮重试
            }
            // LRU = 距过期最近（执行中持续续期，expires_at 最小 ≈ 最久未使用）
            var victim = idle.stream()
                .min(Comparator.comparing(c -> c.row().expiresAt()))
                .orElseThrow();
            boolean rebound = bindingStore.rebind(victim.row().id(), victim.row().sessionId(),
                sessionId, victim.row().updatedAt());
            if (!rebound) {
                // 他副本抢先，或受害会话在途 ① touch / P3 占位写先行提交（乐观校验拦截）→ 整个 ⑤ 重试
                emit("rebind_lost", victim.row().sandboxId(), "sid=" + sessionId);
                continue;
            }
            // renew 顶到当前 TTL + 服务端绝对 expiresAt 回填（binding_count 不变：同 user 内转移）
            String expiresAt = serverNowPlusTtl();
            try {
                expiresAt = victim.handle().renew(Duration.ofSeconds(ttlSeconds()))
                    .getExpiresAt().toString();
            } catch (Exception renewFail) {
                log.warn("[sandbox-lifecycle] victim renew failed (sandbox={}): {}",
                    victim.row().sandboxId(), renewFail.getMessage());
            }
            bindingStore.touchExpiresAt(victim.row().sandboxId(), expiresAt);
            registry.acquireRef(victim.row().sandboxId(), sessionId, ttlSeconds(),
                renewer(victim.handle()));
            emit("lru_reuse", victim.row().sandboxId(), "sid=" + sessionId
                + " victim_sid=" + victim.row().sessionId());
            return reuseWrapper.wrap(victim.handle(), victim.row().sandboxId());
        }
        // ⑤ 重试耗尽仍全忙 → 异常面（§4.6：SSE error / chat 预检 409）
        List<SandboxBindingStore.BindingRow> live =
            bindingStore.listLive(userKey, graceSeconds());
        List<SandboxBindingStore.BindingRow> busyRows = live.stream().filter(this::busy).toList();
        throw new UserQuotaExhaustedException(config.userQuota(),
            views(busyRows.isEmpty() ? live : busyRows));
    }

    // ---- 预检（§4.3 chat 入口，只读版 ②⑤）----

    /**
     * chat 预检：该会话无存活绑定时执行 ②⑤ 的只读版本（无锚行写、无 create、无 probe）。
     * 配额满且全部绑定有执行中迹象 → 返回 {@link UserQuotaExhaustedException}
     * （GlobalExceptionHandler 转 HTTP 409 + payload409）；否则 null 放行（绑定仍惰性）。
     * <p>计数侧<b>行龄过滤</b>：过期行与 PENDING 行不计入「满」判定（listLive 读侧过滤，
     * 不写）——死绑定触发假 409 而 exec 时 ⑤a 懒剔除后本可成功，是评审点名的假 409 缺口。
     * 预检本身 fail-open（存储异常放行）。
     */
    public UserQuotaExhaustedException precheck(String userKey, String sessionId) {
        try {
            if (bindingStore.findBySessionId(sessionId).isPresent()) {
                return null;   // 有存活绑定 → ① 将复用，无需预检
            }
            List<SandboxBindingStore.BindingRow> live = bindingStore.listLive(userKey, graceSeconds());
            if (config.userQuota() > 0 && live.size() >= config.userQuota()) {
                List<SandboxBindingStore.BindingRow> busyRows = live.stream().filter(this::busy).toList();
                if (busyRows.size() == live.size()) {
                    return new UserQuotaExhaustedException(config.userQuota(), views(busyRows));
                }
            }
            return null;
        } catch (SandboxBindingStore.DataAccessException dae) {
            warnFailOpen("precheck", dae);
            return null;   // 预检 fail-open（§4.3）
        }
    }

    // ---- 生命周期挂钩（AgentScopeConfig/控制器接线）----

    /** P3 resume 归属校验+占位通过后的 call 注册（refcount+1，与 ①/③/⑤c 各 acquire 路径同权） */
    public void onResumeAcquired(String sandboxId, String sessionId, Sandbox handle) {
        try {
            registry.acquireRef(sandboxId, sessionId, ttlSeconds(), renewer(handle));
            emit("acquire_resume", sandboxId, "sid=" + sessionId);
        } catch (Exception e) {
            log.warn("[sandbox-lifecycle] resume ref register failed (sandbox={}): {}",
                sandboxId, e.getMessage());
        }
    }

    /** binding 存储访问器（OpenSandboxClient/SandboxLocator 的校验+占位条件写入口） */
    public SandboxBindingStore bindingStore() {
        return bindingStore;
    }

    /** P3/locate 校验写异常 fail-open 记录（放行 + 事件 WARN，§4.2 两道防线第 2 条） */
    public void recordOwnershipFailOpen(String sandboxId, String sessionId, Exception cause) {
        long now = System.currentTimeMillis();
        boolean emitNow = now - lastFailOpenWarnAt > FAIL_OPEN_WARN_INTERVAL_MS;
        if (emitNow) {
            lastFailOpenWarnAt = now;
        }
        log.warn("[sandbox-lifecycle] ownership check unavailable, fail-open (sid={}, sandbox={}): {}",
            sessionId, sandboxId, cause.getMessage());
        if (emitNow) {
            emit("fail_open", sandboxId, "phase=ownership_check sid=" + sessionId);
        }
    }

    /** per-call 收尾（{@code OpenSandbox.stop()}）：call 引用计数 -1 + 顺手终刷（终审 R2 口径） */
    public void onCallStop(String sandboxId) {
        try {
            registry.releaseRef(sandboxId);
        } catch (Exception e) {
            log.warn("[sandbox-lifecycle] stop flush failed (sandbox={}): {}", sandboxId, e.getMessage());
        }
    }

    /** turn 开始注册（chat/AG-UI turn 开始、confirm 恢复重注册；顺带清除 holdUntil） */
    public void onTurnStart(String sessionId) {
        try {
            registry.registerTurn(sessionId);
        } catch (Exception e) {
            log.warn("[sandbox-lifecycle] turn register failed (sid={}): {}", sessionId, e.getMessage());
        }
    }

    /** turn 终态注销 + 终刷（= T2；只挂 TurnFinalizer 终态，不挂 per-call stop） */
    public void onTurnEnd(String sessionId) {
        try {
            registry.unregisterTurn(sessionId);
        } catch (Exception e) {
            log.warn("[sandbox-lifecycle] turn unregister failed (sid={}): {}", sessionId, e.getMessage());
        }
    }

    /** T4：confirm ask 挂起 → holdUntil 保留窗（每次 ask 重新计窗） */
    public void onConfirmHold(String sessionId) {
        try {
            registry.suspendForConfirm(sessionId);
        } catch (Exception e) {
            log.warn("[sandbox-lifecycle] confirm hold failed (sid={}): {}", sessionId, e.getMessage());
        }
    }

    // ---- 内部 ----

    /** busy 双事实源：updated_at 新鲜（DB 时钟）OR turn_lease 严格持有 */
    private boolean busy(SandboxBindingStore.BindingRow row) {
        if (row.fresh()) {
            return true;
        }
        try {
            return turnLeaseStore != null && turnLeaseStore.isHeldStrict(row.sessionId());
        } catch (Exception e) {
            // 租约探测失败按不 busy（fail-open 方向：不因探测抖动制造 409）；binding 存储同库
            // 不可用时上游早已走 fail-open 直建，此处仅为防御
            log.warn("[sandbox-lifecycle] lease probe failed (sid={}): {}", row.sessionId(), e.getMessage());
            return false;
        }
    }

    /** grace = max(3×RENEW_INTERVAL, 下限)（下限默认 120s，e2e 经系统属性压低；必须 > 续期间隔） */
    public int graceSeconds() {
        return (int) Math.max(GRACE_FLOOR_SECONDS, 3L * config.renewIntervalSeconds());
    }

    /**
     * P3 沙箱换代登记（{@code OpenSandboxClient.resume} 归属校验 affected=0 或 connect
     * 命中已销毁沙箱 404/410 时调用）：供流侧换代提示帧一次性弹出（§4.4 T4 恢复路径）。
     */
    public void markSandboxRebuilt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (ownershipLostAt.size() >= OWNERSHIP_LOST_MAX) {
            ownershipLostAt.clear();   // 上界兜底：清空重来（提示帧是体验增强，丢失无害）
        }
        ownershipLostAt.put(sessionId, System.currentTimeMillis());
    }

    /**
     * 弹出 sid 在 {@code sinceEpochMs} 之后的沙箱换代登记（一次性；无/早于 since 则 false
     * 且<b>不消费</b>——旧 turn 的迟到探测不得吃掉留给新 turn 的提示）。
     * chat/confirm 流在 turn 开始时取时刻、逐事件探测，命中即发换代提示帧。
     */
    public boolean popSandboxRebuiltSince(String sessionId, long sinceEpochMs) {
        if (sessionId == null) {
            return false;
        }
        Long at = ownershipLostAt.get(sessionId);
        if (at == null || at < sinceEpochMs) {
            return false;
        }
        return ownershipLostAt.remove(sessionId, at);   // 条件删除：并发探测恰一方弹出
    }

    private long ttlSeconds() {
        return ttlMinutes * 60L;
    }

    /** 续期一拍的执行器（SDK 句柄 renew → 服务端绝对 expiresAt 字符串） */
    private SandboxRenewalRegistry.SandboxRenewer renewer(Sandbox handle) {
        long ttl = ttlSeconds();
        return () -> handle.renew(Duration.ofSeconds(ttl)).getExpiresAt().toString();
    }

    /**
     * 本地 TTL 推算兜底：仅当 create/renew 响应缺失绝对 expiresAt 时使用（超前于服务端
     * 真相方向保守，仅影响 LRU 排序精度；正常路径一律回填服务端绝对值，§4.2 要点）。
     */
    private String serverNowPlusTtl() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .format(java.time.LocalDateTime.now().plusSeconds(ttlSeconds()));
    }

    /** 视图组装：confirm_holding 判定 + 保留窗剩余（§4.3 state 区分，前端卡片据此给建议） */
    private List<UserQuotaExhaustedException.BindingView> views(List<SandboxBindingStore.BindingRow> rows) {
        List<UserQuotaExhaustedException.BindingView> views = new ArrayList<>();
        for (var row : rows) {
            boolean confirmHolding = false;
            String holdExpires = null;
            if (confirmContextStore != null) {
                var pending = java.util.Optional.ofNullable(
                    confirmContextStore.findPending(row.sessionId())).orElse(java.util.Optional.empty());
                if (pending.isPresent() && pending.get().createdAt() != null) {
                    confirmHolding = true;
                    holdExpires = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
                        .format(pending.get().createdAt().atZone(java.time.ZoneId.systemDefault())
                            .toLocalDateTime().plusMinutes(config.confirmHoldMinutes()));
                }
            }
            boolean turnRunning = busy(row);
            views.add(new UserQuotaExhaustedException.BindingView(
                row.sessionId(),
                iso(row.expiresAt()),
                turnRunning,
                confirmHolding,
                confirmHolding ? holdExpires : null));
        }
        return views;
    }

    /** SQL 字面量 → ISO-8601（前端 new Date 兼容） */
    private static String iso(String sqlDatetime) {
        return sqlDatetime == null ? null : sqlDatetime.replace(' ', 'T');
    }

    /** fail-open：60s 限流 WARN + 事件（§4.2 要点 / §4.8 排障盲区） */
    private void warnFailOpen(String phase, Exception cause) {
        long now = System.currentTimeMillis();
        boolean emitNow = now - lastFailOpenWarnAt > FAIL_OPEN_WARN_INTERVAL_MS;
        if (emitNow) {
            lastFailOpenWarnAt = now;
        }
        log.warn("[sandbox-lifecycle] fail-open ({}): {}——直建不记账，恢复后由懒剔除/定时清理收敛",
            phase, cause.getMessage());
        if (emitNow) {
            emit("fail_open", null, "phase=" + phase + " cause=" + cause.getMessage());
        }
    }

    /** 事件环形缓冲追加（容量 100，满则丢最旧；进程内可丢弃缓存） */
    private void emit(String type, String sandboxId, String detail) {
        var m = new LinkedHashMap<String, Object>();
        m.put("at", Instant.now().toString());
        m.put("type", type);
        m.put("sandbox_id", sandboxId == null ? "" : sandboxId);
        m.put("detail", detail == null ? "" : detail);
        synchronized (eventRing) {
            if (eventRing.size() >= EVENT_RING_SIZE) {
                eventRing.pollFirst();
            }
            eventRing.addLast(m);
        }
    }

    /** Registry → Manager 的事件桥（AgentScopeConfig 装配期 setEventSink 到本方法） */
    public void recordLifecycleEvent(String type, String sandboxId, String detail) {
        emit(type, sandboxId, detail);
    }

    /** debug 事件快照（§4.8：含续期失败/fail-open 事件） */
    public List<Map<String, Object>> recentEvents() {
        synchronized (eventRing) {
            return new ArrayList<>(eventRing);
        }
    }

    // ---- 注册表健康透出（§4.8 debug 页，Manager 委托 Registry）----

    public int registrySize() {
        return registry.registrySize();
    }

    public int registryActiveTurnCount() {
        return registry.activeTurnCount();
    }

    public long registryOldestEntryAgeMs() {
        return registry.oldestEntryAgeMs();
    }

    public long registryLoopLagMs() {
        return registry.lastLoopLagMs();
    }

    public Instant registryLastSweepAt() {
        return registry.lastSweepAt();
    }

    public List<Map<String, Object>> registryDebugEntries() {
        return registry.debugEntries();
    }

    public io.agentmanager.framework.config.SandboxLifecycleConfig config() {
        return config;
    }
}
