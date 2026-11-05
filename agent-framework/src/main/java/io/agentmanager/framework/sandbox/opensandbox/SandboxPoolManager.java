package io.agentmanager.framework.sandbox.opensandbox;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;

/**
 * 沙箱池管理器（sandbox-pool-design §5，D1/D10：池做在 OpenSandboxClient 契约层，
 * 账本/租约全在 Redis——同服务多副本共享一个集群级池）。
 *
 * <p><b>状态账本（单 ledger HASH，骑 {@code AGENT_REDIS_PREFIX}，per-service 天然隔离）</b>：
 * <ul>
 *   <li>{@code {prefix}sbx:pool:ledger} — HASH：{@code i:{sandboxId}}（idle 就绪）/
 *       {@code b:{sandboxId}} = JSON {@code {sid,userKey,podId,borrowedAt}}（busy，sid 为 D14 双保险
 *       查询键、userKey 为 D17 配额键）/{@code s:{sid}} → sandboxId（会话反查，touch/resume 校正入口，
 *       清理并入各单 key Lua 原子完成）/
 *       {@code c:{entryId}:{createdAt}}（creating，&gt;5min 扫描剪枝自愈）</li>
 *   <li>{@code {prefix}sbx:pool:lease:{sandboxId}} — STRING（TTL=idleReturnSeconds）：
 *       失活判定<b>唯一真相</b>（借出/续约五点写，D14 双保险与确认 touch 读）</li>
 * </ul>
 * <b>Cluster 兼容</b>：全部状态迁移为<b>单 key Lua</b>（borrow/cap/claim/promote/prune 都在
 * ledger 单 key 内完成），不聚合 hash tag（续约键按 sandboxId 天然分散，无热 slot）；
 * ledger 与租约键之间无跨 key 原子需求——borrow 两步缺口由 borrowedAt 60s 宽限规则兜底，
 * DEL 租约为卫生操作（TTL 自灭）。{@code AGENT_REDIS_MODE=cluster} 下无 CROSSSLOT 面。
 *
 * <p><b>fail-soft/fail-open（D8，对齐 RedisSandboxExecutionGuard 哲学）</b>：Redis 不可用时
 * 回收扫描整轮跳过（容器滞留退化为现状 Server TTL 兜底）、借出直接建容器（账本外，限流告警）——
 * 池是可用性增强，不得反成对话可用性单点。代价 = 故障期容量上限失效，属接受降级。
 *
 * <p><b>优雅停机（D12）</b>：{@code @PreDestroy} 仅清本地 creating 句柄（HDEL creating 条目），
 * <b>绝不杀任何容器</b>——容器是集群资产，副本 rollout 重启零影响；崩溃孤儿由存活副本扫描回收。
 *
 * <p><b>零后台写业务面</b>（原则一）：扫描只碰 OpenSandbox Server 容器面与共享存储读取，
 * 不写任何 K8s 资源；任一副本可扫、免选主（回收认领经 Lua HDEL 先到先得）。
 */
public class SandboxPoolManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxPoolManager.class);

    /** 裸 key 固定段（实际 key = facade.key(前缀 + ...)，前缀由门面统一追加，禁止自行拼接） */
    static final String LEDGER_KEY = "sbx:pool:ledger";
    static final String LEASE_PREFIX = "sbx:pool:lease:";

    /**
     * borrow Lua（单 key）：取一个 idle（i: 前缀字段）原子转 busy（并发借出各得唯一容器），
     * 并写 {@code s:{sid}} 反查字段（touch 判活与 resume 校正的 D14 查询入口；sid 空串不写）；
     * 无 idle 返回空串。ARGV[1]=busyJson（含 sid/userKey/podId/borrowedAt），ARGV[2]=sid。
     */
    static final String BORROW_LUA = """
        local fields = redis.call('HKEYS', KEYS[1])
        for _, f in ipairs(fields) do
          if string.sub(f, 1, 2) == 'i:' then
            local id = string.sub(f, 3)
            redis.call('HDEL', KEYS[1], f)
            redis.call('HSET', KEYS[1], 'b:' .. id, ARGV[1])
            if ARGV[2] ~= '' then redis.call('HSET', KEYS[1], 's:' .. ARGV[2], id) end
            return id
          end
        end
        return ''
        """;

    /** cap Lua（单 key）：i+b+c 计数 < maxSize 时占一个 creating 名额（集群级容量裁决）；已满返回空串 */
    static final String CAP_LUA = """
        local n = 0
        local fields = redis.call('HKEYS', KEYS[1])
        for _, f in ipairs(fields) do
          local p = string.sub(f, 1, 2)
          if p == 'i:' or p == 'b:' or p == 'c:' then n = n + 1 end
        end
        if n < tonumber(ARGV[1]) then
          local entry = ARGV[2] .. ':' .. tostring(redis.call('TIME')[1])
          redis.call('HSET', KEYS[1], 'c:' .. entry, ARGV[3])
          return entry
        end
        return ''
        """;

    /**
     * 回收认领 Lua（单 key）：HDEL busy 先到先得（多副本并发扫描唯一赢家，免选主），
     * 并原子清理 {@code s:{sid}} 反查字段（经 cjson 解析 b: 值取 sid；旧值/解析失败跳过——
     * 残余反查由 touch 的 sid 匹配防线兜底，安全方向 410）。
     */
    static final String CLAIM_RECYCLE_LUA = """
        local f = 'b:' .. ARGV[1]
        local v = redis.call('HGET', KEYS[1], f)
        if not v then return '' end
        redis.call('HDEL', KEYS[1], f)
        local ok, rec = pcall(cjson.decode, v)
        if ok and rec.sid ~= nil and rec.sid ~= '' then redis.call('HDEL', KEYS[1], 's:' .. rec.sid) end
        return ARGV[1]
        """;

    /**
     * 认领撤销 Lua（单 key）：kill 前复核发现租约被续约 → busy 记录与 {@code s:{sid}} 反查
     * 字段一并原样还原（认领期间容器不在 i:，无借出方可拿走，撤销安全）。
     * ARGV[1]=sandboxId，ARGV[2]=busyJson，ARGV[3]=sid。
     */
    static final String UNCLAIM_LUA = """
        redis.call('HSET', KEYS[1], 'b:' .. ARGV[1], ARGV[2])
        if ARGV[3] ~= '' then redis.call('HSET', KEYS[1], 's:' .. ARGV[3], ARGV[1]) end
        return 1
        """;

    /** creating → idle 晋升 Lua（单 key）：预热/补位完成后入池 */
    static final String PROMOTE_IDLE_LUA = """
        redis.call('HDEL', KEYS[1], 'c:' .. ARGV[1])
        redis.call('HSET', KEYS[1], 'i:' .. ARGV[2], ARGV[3])
        return 1
        """;

    /** creating → busy 直接借出 Lua（单 key）：按需同步建的容器直接给本次借出（消除"有容量却干等归还"） */
    static final String PROMOTE_BUSY_LUA = """
        redis.call('HDEL', KEYS[1], 'c:' .. ARGV[1])
        redis.call('HSET', KEYS[1], 'b:' .. ARGV[2], ARGV[3])
        return 1
        """;

    /** creating 名额释放 Lua（单 key）：建容器失败时归还容量（下轮重试补位） */
    static final String RELEASE_CREATING_LUA =
        "redis.call('HDEL', KEYS[1], 'c:' .. ARGV[1]) return 1";

    /** idle 认领移除 Lua（单 key）：保活探测失效时原子认领（并发只有一个赢家） */
    static final String CLAIM_IDLE_LUA =
        "if redis.call('HDEL', KEYS[1], 'i:' .. ARGV[1]) == 1 then return ARGV[1] end return ''";

    /** creating 剪枝 Lua（单 key）：删除年龄 > maxAge 秒的 c: 条目（崩溃 mid-create 自愈），返回删除数 */
    static final String PRUNE_CREATING_LUA = """
        local now = tonumber(redis.call('TIME')[1])
        local removed = 0
        local fields = redis.call('HKEYS', KEYS[1])
        for _, f in ipairs(fields) do
          if string.sub(f, 1, 2) == 'c:' then
            local sep = string.find(f, ':', 3)
            if sep then
              local createdAt = tonumber(string.sub(f, sep + 1))
              if createdAt and now - createdAt > tonumber(ARGV[1]) then
                redis.call('HDEL', KEYS[1], f)
                removed = removed + 1
              end
            end
          end
        end
        return removed
        """;

    /** 租约续约 Lua（单 key）：保留原值的幂等 upsert（值仅诊断用途；过期后重建自愈，真失活由 D14 兜底） */
    static final String RENEW_LEASE_LUA =
        "local v = redis.call('GET', KEYS[1]) redis.call('SET', KEYS[1], v or ARGV[1], 'EX', ARGV[2]) return 1";

    /**
     * 租约判活 Lua（单 key=租约键，缺陷③原子化）：EXISTS → EXPIRE 同脚本原子执行，
     * 关闭「exists=1 后、expire 前租约过期」的误放行窗口；返回 1=活（已续约）/0=确死。
     */
    static final String TOUCH_LEASE_LUA =
        "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) return 1 end return 0";

    /**
     * 确认 touch 账本探查 Lua（单 key=ledger，D16-④ + P3 sid 匹配防线）：按 {@code s:{sid}}
     * 反查容器并校验条目内 sid 归属——防「陈旧 s: 指向已被他人借走/继承的容器」误放行
     * （resume 校正的 sid≠S 分支会抛异常导致 SDK P4 中途借新容器丢产物）。
     * ARGV[1]=sid。返回：''（s: 缺失=从未经池借出，放行）/ 'B:{id}'（busy 且 sid 匹配，判活续约）/
     * 'R:{id}'（粘滞且 sid 匹配，已原子复活回 busy——D17 粘滞窗口内确认继续跑，挡住「继承 vs 复活」竞态）/
     * 'N'（条目已回收，410）/ 'X'（条目存在但 sid 不匹配=陈旧反查，410）。
     */
    static final String TOUCH_PROBE_LUA = """
        local id = redis.call('HGET', KEYS[1], 's:' .. ARGV[1])
        if not id then return '' end
        local bv = redis.call('HGET', KEYS[1], 'b:' .. id)
        if bv then
          local ok, rec = pcall(cjson.decode, bv)
          if ok and rec.sid ~= nil and rec.sid ~= '' and rec.sid == ARGV[1] then return 'B:' .. id end
          return 'X'
        end
        local iv = redis.call('HGET', KEYS[1], 'i:' .. id)
        if iv then
          local ok, rec = pcall(cjson.decode, iv)
          if ok and rec.sid ~= nil and rec.sid ~= '' and rec.sid == ARGV[1] then
            redis.call('HDEL', KEYS[1], 'i:' .. id)
            redis.call('HSET', KEYS[1], 'b:' .. id,
              cjson.encode({sid = ARGV[1], userKey = (rec.owner or ''), podId = '',
                            borrowedAt = tonumber(redis.call('TIME')[1])}))
            redis.call('HSET', KEYS[1], 's:' .. ARGV[1], id)
            return 'R:' .. id
          end
          return 'X'
        end
        return 'N'
        """;

    /**
     * resume 账本校正 Lua（单 key=ledger，D2 D17 扩展 + P4 顺序修正）：connect 成功后执行，
     * <b>不在 connect 前写账本</b>（杜绝 connect 404 时幻影 busy 被扫描 turn_lease 重置双重保活、
     * 占容量与配额至 turn 结束）。纵深校验与继承-复活竞态单赢家裁决：
     * ARGV[1]=sandboxId，ARGV[2]=sid，ARGV[3]=userKey，ARGV[4]=busyJson。
     * 返回：'B'（busy 且 sid==本会话，仅续约）/ 'R'（粘滞且 sid 或 owner==本会话 userKey，
     * 已原子换绑复活）/ 'X'（被他人借走/继承且校验不过）/ 'G'（账本无条目=已被回收）。
     */
    static final String RESUME_RECONCILE_LUA = """
        local id = ARGV[1]
        local bv = redis.call('HGET', KEYS[1], 'b:' .. id)
        if bv then
          local ok, rec = pcall(cjson.decode, bv)
          if ok and rec.sid ~= nil and rec.sid ~= '' and rec.sid == ARGV[2] then return 'B' end
          return 'X'
        end
        local iv = redis.call('HGET', KEYS[1], 'i:' .. id)
        if iv then
          local ok, rec = pcall(cjson.decode, iv)
          local sidOk = ok and rec.sid ~= nil and rec.sid ~= '' and rec.sid == ARGV[2]
          local ownerOk = ok and rec.owner ~= nil and rec.owner ~= '' and rec.owner == ARGV[3]
          if sidOk or ownerOk then
            redis.call('HDEL', KEYS[1], 'i:' .. id)
            redis.call('HSET', KEYS[1], 'b:' .. id, ARGV[4])
            if ARGV[2] ~= '' then redis.call('HSET', KEYS[1], 's:' .. ARGV[2], id) end
            return 'R'
          end
          return 'X'
        end
        return 'G'
        """;

    /** borrowedAt 宽限窗口（秒）：borrow「ledger 入账 → SET 租约」两步缺口的兜底（§5.3） */
    static final long BORROW_GRACE_SECONDS = 60;
    /** creating 剪枝年龄（秒）：副本崩溃 mid-create 的自愈上限 */
    static final long CREATING_PRUNE_SECONDS = 300;
    /** 借出前探测重试上限（连续失败报错，防 Server 故障期死循环建容器） */
    static final int PROBE_MAX_RETRY = 2;
    /** 池满 WARN 的连续扫描周期阈值（busy==maxSize 连续 2 个周期 = 容量该扩信号） */
    static final int FULL_WARN_CYCLES = 2;
    /** fail-open / Redis 异常限流告警间隔（ms） */
    private static final long FAIL_OPEN_WARN_INTERVAL_MS = 60_000;
    /** 池空轮询间隔（ms，设计 §5.2 步骤 1） */
    private static final long BORROW_POLL_INTERVAL_MS = 1000;

    private final RedisConnectionFacade facade;
    private final SandboxConfig.PoolConfig pool;
    /** D15 不变量校验用确认截止分钟数（≤0 = 截止关闭，校验豁免） */
    private final int confirmDeadlineMinutes;
    /** D14 双保险：绑定 sid 的 turn_lease 活跃即不回收（null = 未装配，按租约判定） */
    private final io.agentmanager.framework.service.TurnLeaseStore turnLeaseStore;
    private final ConnectionConfig connectionConfig;
    private final String image;
    private final Duration timeout;
    private final List<String> entrypoint;
    private final Map<String, String> resource;
    private final Map<String, String> environment;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String podId = UUID.randomUUID().toString().substring(0, 8);

    /** 本副本 in-flight creating 句柄（entryId → 建容器线程内标记）；@PreDestroy 仅清此表 + HDEL creating（D12） */
    private final ConcurrentHashMap<String, Boolean> localCreating = new ConcurrentHashMap<>();
    /** 流转事件环形缓冲（可观测性：Debug 端点读最近 N 条；进程内存、可丢弃——原则二） */
    private final Deque<Map<String, Object>> events = new ArrayDeque<>();
    /** 正在等待借出的会话数（观测用，进程内近似值） */
    private final AtomicInteger waiters = new AtomicInteger();
    /** busy==maxSize 连续扫描周期计数（达阈值 WARN） */
    private final AtomicInteger fullCycles = new AtomicInteger();
    private volatile long lastFailOpenWarnAt = 0L;
    private volatile ScheduledExecutorService scanScheduler;
    private volatile ExecutorService rebuildExecutor;
    private final Object eventsLock = new Object();

    /**
     * @param pool                    池参数（enabled 必须为 true——false 时不装配本 bean）
     * @param confirmDeadlineMinutes  确认硬截止分钟数（D15 校验）
     * @param sandbox                 沙箱静态配置（image/timeout/资源/entrypoint/env/serverUrl/apiKey）
     * @param turnLeaseStore          turn 租约存储（D14 双保险；可 null=按租约判定）
     * @throws IllegalStateException D15 不变量违反（idleReturnSeconds < deadline → 拒绝启动）
     */
    public SandboxPoolManager(RedisConnectionFacade facade,
                              SandboxConfig.PoolConfig pool,
                              int confirmDeadlineMinutes,
                              SandboxConfig sandbox,
                              io.agentmanager.framework.service.TurnLeaseStore turnLeaseStore) {
        // D15 硬不变量（启动 fail-fast）：IDLE_RETURN_SECONDS ≥ AGENT_CONFIRM_DEADLINE_MINUTES——
        // 截止内的每一刻容器必活（确认入口预检续约兜住边界竞态）；违反即配置错误，拒绝启动
        this.confirmDeadlineMinutes = confirmDeadlineMinutes;
        if (pool.enabled() && confirmDeadlineMinutes > 0
                && pool.idleReturnSeconds() < confirmDeadlineMinutes * 60L) {
            throw new IllegalStateException(
                "SANDBOX_POOL_IDLE_RETURN_SECONDS (" + pool.idleReturnSeconds()
                    + "s) must be >= AGENT_CONFIRM_DEADLINE_MINUTES (" + confirmDeadlineMinutes
                    + "min) — pool D15 invariant (sandbox-pool-design); 调小必须同步下调确认截止");
        }
        this.facade = facade;
        this.pool = pool;
        this.turnLeaseStore = turnLeaseStore;
        this.image = sandbox.image();
        this.timeout = Duration.ofMinutes(sandbox.timeoutMinutes());
        this.entrypoint = sandbox.entrypoint();
        this.resource = Map.of(
            "cpu", String.valueOf(sandbox.cpuCount()),
            "memory", sandbox.memoryMb() + "Mi");
        this.environment = Map.of(
            "EXECD_API_GRACE_SHUTDOWN", sandbox.execdGraceShutdown().toMillis() + "ms");
        this.connectionConfig = ConnectionConfig.builder()
            .domain(sandbox.opensandbox().serverUrl())
            .apiKey(sandbox.opensandbox().apiKey())
            .protocol("http")
            .useServerProxy(true)
            .build();
        log.info("[sandbox-pool] enabled: maxSize={} minIdle={} idleReturn={}s maxWait={}s scan={}s rebuild={}"
                + " deadline={}m podId={}",
            pool.maxSize(), pool.minIdle(), pool.idleReturnSeconds(), pool.maxWaitSeconds(),
            pool.scanIntervalSeconds(), pool.rebuildConcurrency(), confirmDeadlineMinutes, podId);
    }

    // ---------------------------------------------------------------- 借出（OpenSandboxClient.create 池分支）

    /** 借出结果：已 connect 的 OpenSandbox Server 句柄 + 容器 id（调用方据此构造 OpenSandbox 实例） */
    public record Borrowed(String sandboxId, Sandbox osbSandbox) {}

    /**
     * 借出一个就绪容器（§5.2）：idle 直取 → 按需建 → 池空轮询 ≤ maxWait（超时报错）→ Redis 不可达 fail-open 直建。
     *
     * @param sid     会话隔离键（busy 记录的 D14 查询键 + {@code s:{sid}} 反查键；可 null/空——D14 回退纯租约判定）
     * @param userKey 业务用户键（D17 配额计数键；可 null/空——守卫钩子失效组合下由 middleware 回填兜底）
     * @throws SandboxException CONFIGURATION_ERROR：池满等待超时 / 连续探测失败 / 按需建容器失败
     */
    public Borrowed borrow(String sid, String userKey) {
        String busyJson = busyJsonOf(sid, userKey);
        // 1. 非阻塞直取 idle
        String id = luaBorrow(busyJson, sid);
        if (id != null && !id.isEmpty()) {
            Borrowed b = attachLeaseAndProbe(sid, userKey, id);
            if (b != null) {
                return b;
            }
        }
        // 2. idle 空：容量未满则按需同步建（直接给本次借出）
        Borrowed created = createOnDemand(sid, userKey, busyJson);
        if (created != null) {
            return created;
        }
        // 3. 池满：轮询等待归还/补位（1s 间隔，≤ maxWait；0 = 无限等，不推荐）
        long maxWaitMs = pool.maxWaitSeconds() > 0 ? pool.maxWaitSeconds() * 1000L : Long.MAX_VALUE;
        long deadline = System.currentTimeMillis() + maxWaitMs;
        waiters.incrementAndGet();
        recordEvent("wait-start", sid, null);
        log.info("[sandbox-pool] borrow waiting: sid={}, userKey={}, waiters={}, maxWait={}s, snapshot={}",
            sid, userKey, waiters.get(), pool.maxWaitSeconds(), snapshotString());
        try {
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(BORROW_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                id = luaBorrow(busyJson, sid);
                if (id != null && !id.isEmpty()) {
                    Borrowed b = attachLeaseAndProbe(sid, userKey, id);
                    if (b != null) {
                        return b;
                    }
                }
                created = createOnDemand(sid, userKey, busyJson);
                if (created != null) {
                    return created;
                }
            }
        } finally {
            waiters.decrementAndGet();
        }
        recordEvent("wait-timeout", sid, null);
        log.warn("[sandbox-pool] borrow exhausted: sid={} waited {}s, snapshot={}",
            sid, pool.maxWaitSeconds(), snapshotString());
        throw new SandboxException(SandboxErrorCode.CONFIGURATION_ERROR, "borrow",
            "sandbox pool exhausted (waited " + pool.maxWaitSeconds()
                + "s); 沙箱繁忙请稍后重试", null);
    }

    /** 借出后置：SET 租约（续约①）→ 探测；探测失败 kill 换新（≤PROBE_MAX_RETRY 次），全败报错 */
    private Borrowed attachLeaseAndProbe(String sid, String userKey, String firstId) {
        String id = firstId;
        for (int attempt = 1; attempt <= PROBE_MAX_RETRY + 1; attempt++) {
            setLease(id, sid != null && !sid.isBlank() ? sid : podId);   // 续约①：借出即受保护
            if (probeAlive(id)) {
                recordEvent("borrow", sid, id);
                log.info("[sandbox-pool] borrowed: sid={}, userKey={}, sandboxId={}, attempt={}, snapshot={}",
                    sid, userKey, id, attempt, snapshotString());
                return new Borrowed(id, connect(id));
            }
            // 探测失败：容器已被 Server GC/异常 → kill 换新
            recordEvent("probe-fail", sid, id);
            log.warn("[sandbox-pool] probe failed, recycling borrowed container: sid={}, sandboxId={}, attempt={}",
                sid, id, attempt);
            killQuietly(id);
            delLease(id);
            // 认领移除 busy（换新前的卫生操作；失败由扫描兜底）
            final String deadId = id;
            claimBusy(() -> luaClaimRecycle(deadId));
            if (attempt > PROBE_MAX_RETRY) {
                break;
            }
            // 换新：优先按需建（容量允许），否则再取 idle
            Borrowed created = createOnDemand(sid, userKey, busyJsonOf(sid, userKey));
            if (created != null) {
                return created;
            }
            String next = luaBorrow(busyJsonOf(sid, userKey), sid);
            if (next == null || next.isEmpty()) {
                break;
            }
            id = next;
        }
        // 换新失败：不抛（调用方回落池空等待路径）——返回 null 让 borrow 走等待/报错分支
        return null;
    }

    /** 按需同步建容器（容量未满时）：cap 占位 → 建 → 直接转 busy 给本次借出；容量满/Redis 不可达返回 null */
    private Borrowed createOnDemand(String sid, String userKey, String busyJson) {
        String entry = luaCap();
        if (entry == null || entry.isEmpty()) {
            return null;
        }
        localCreating.put(entry, Boolean.TRUE);
        try {
            Sandbox osb = buildSandbox();
            String id = osb.getInfo().getId();
            eval(PROMOTE_BUSY_LUA, ScriptOutputType.INTEGER,
                new String[]{entry, id, busyJson});
            setLease(id, sid != null && !sid.isBlank() ? sid : podId);
            if (probeAlive(id)) {
                recordEvent("create-on-demand", sid, id);
                log.info("[sandbox-pool] created on demand: sid={}, sandboxId={}, snapshot={}",
                    sid, id, snapshotString());
                return new Borrowed(id, osb);
            }
            // 新建容器就绪探测失败（罕见）：kill 换重试路径
            recordEvent("probe-fail", sid, id);
            killQuietly(id);
            delLease(id);
            return null;
        } catch (SandboxException | RedisException e) {
            throw e instanceof SandboxException se ? se
                : new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR, "createOnDemand",
                    "Failed to create sandbox on demand: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR, "createOnDemand",
                "Failed to create sandbox on demand: " + e.getMessage(), e);
        } finally {
            localCreating.remove(entry);
            evalQuiet(RELEASE_CREATING_LUA, ScriptOutputType.INTEGER, new String[]{entry});
        }
    }

    /** Redis 故障期 fail-open 借出：跳过账本直接建容器（不计数、限流告警；账本外容器由 Server TTL 兜底消亡） */
    public Borrowed borrowFailOpen(String sid, String userKey) {
        warnFailOpen("borrow fail-open: redis unavailable, creating unmanaged container (sid=" + sid + ")");
        Sandbox osb = buildSandbox();
        String id = osb.getInfo().getId();
        recordEvent("borrow-fail-open", sid, id);
        log.warn("[sandbox-pool] fail-open borrow: sid={}, userKey={}, sandboxId={} (unmanaged, Server TTL will reap)",
            sid, userKey, id);
        return new Borrowed(id, osb);
    }

    // ---------------------------------------------------------------- 续约（五点中的 ②③④ 共用）与确认 touch

    /**
     * 租约续约（② resume / ③ doExec / ④ stop 共用）：保留原值的幂等 upsert（设计 §5.4——
     * 不用 EXPIRE：租约在扫描间隙过期时 upsert 直接重建自愈；真失活由 D14 双保险兜底）。
     * fail-soft：Redis 异常仅告警（续约失败 → 租约到期 → 扫描 D14 判定，活跃 turn 有 turn_lease 保命）。
     *
     * <p><b>缺陷①修复（2026-11-05）</b>：RENEW_LEASE_LUA 的 KEYS[1] 必须是<b>租约键</b>
     * {@code sbx:pool:lease:{sandboxId}}（脚本内 GET/SET 均作用其上）——此前误传 ledger 键，
     * 对 HASH 键执行 GET 触发 WRONGTYPE 静默失效（续约永不生效，租约 600s 必过期），
     * 且 Lua 的 {@code SET} 在 Redis 7+ 会先失效脚本前序错误，把 ledger 键覆写成 STRING 污染账本。
     */
    public void renew(String sandboxId) {
        if (sandboxId == null || sandboxId.isBlank()) {
            return;
        }
        try {
            evalLuaKeyed(RENEW_LEASE_LUA, ScriptOutputType.INTEGER,
                new String[]{facade.key(LEASE_PREFIX + sandboxId)},
                new String[]{sandboxId, String.valueOf(pool.idleReturnSeconds())});
        } catch (Exception e) {
            log.debug("[sandbox-pool] lease renew failed (redis unavailable?): sandboxId={}: {}",
                sandboxId, e.getMessage());
        }
    }

    /**
     * resume 账本校正（D2 D17 扩展 + P4 顺序修正）：connect 成功后调用——
     * <ul>
     *   <li>'B'（busy 且 sid==本会话）→ 仅续约放行；</li>
     *   <li>'R'（粘滞且 sid 或 owner==本会话）→ Lua 已原子换绑复活（HDEL i: + HSET b:），
     *       Java 侧 SET 租约（复活即续约①）放行；</li>
     *   <li>其余（被继承给他人/已被回收/校验不过）→ 调用方 <b>close 已 connect 句柄</b> 后抛
     *       SandboxException 触发 SDK P3→P4 降级走借出决策树——不写账本（无幻影 busy）。</li>
     * </ul>
     * Redis 异常 fail-open 放行（校正失败退化为现状纯续约语义，D14 兜底不丢正确性）。
     *
     * @return true=放行（账本已校正/续约），false=降级 P4（调用方须 close 句柄并抛异常）
     */
    public boolean reconcileResume(String sandboxId, String sid, String userKey) {
        try {
            String verdict = evalLuaKeyed(RESUME_RECONCILE_LUA, ScriptOutputType.VALUE,
                new String[]{facade.key(LEDGER_KEY)},
                new String[]{sandboxId,
                    sid != null ? sid : "",
                    userKey != null ? userKey : "",
                    busyJsonOf(sid, userKey)});
            if ("B".equals(verdict) || "R".equals(verdict)) {
                if ("R".equals(verdict)) {
                    recordEvent("resume-revive", sid, sandboxId);
                    log.info("[sandbox-pool] resume revived sticky container: sid={}, userKey={}, sandboxId={}",
                        sid, userKey, sandboxId);
                }
                setLease(sandboxId, sid != null && !sid.isBlank() ? sid : podId);
                return true;
            }
            log.info("[sandbox-pool] resume reconcile downgrade to borrow: sid={}, userKey={}, sandboxId={}, verdict={}",
                sid, userKey, sandboxId, verdict);
            return false;
        } catch (Exception e) {
            log.debug("[sandbox-pool] resume reconcile skipped (redis unavailable?): sandboxId={}: {}",
                sandboxId, e.getMessage());
            return true;   // fail-open：退化为现状纯续约语义
        }
    }

    /**
     * D16-④ 确认入口预检续约（P3 sid 匹配防线版）：{@code s:{sid}} 反查 → 单 key 账本探查 Lua
     * 校验条目内 sid 归属 → 租约键 EXISTS→EXPIRE 同脚本原子判活（缺陷③）。
     * <ul>
     *   <li>{@code s:} 缺失（从未经池借出/fail-open 容器/池刚开启）→ <b>放行</b>（不误伤合法确认——
     *       现状「无 busy 归属即 410」会作废此类会话的确认，为缺陷②误伤面）；</li>
     *   <li>条目 sid 不匹配（陈旧反查指向他人容器）/ 条目已回收 → 410（干净拒绝，绝不放行——
     *       放行后 resume 校正会因 sid 不匹配抛异常触发 P4 中途借新容器丢产物）；</li>
     *   <li>粘滞且 sid 匹配 → Lua 已原子复活回 busy（D17 粘滞窗口内确认续跑，与继承竞态单赢家裁决），
     *       SET 租约放行；</li>
     *   <li>busy 且 sid 匹配 → TOUCH_LEASE_LUA 原子判活续约；返回 0（租约确死）→ 410。</li>
     * </ul>
     * Redis 异常维持 fail-open 放行（不能因 Redis 故障阻断确认——对齐守卫哲学）。
     */
    public void touchSessionOrThrow(String sid) {
        if (sid == null || sid.isBlank()) {
            throw new PoolLeaseGoneException(sid, "no session id for pool touch");
        }
        String verdict;
        try {
            verdict = evalLuaKeyed(TOUCH_PROBE_LUA, ScriptOutputType.VALUE,
                new String[]{facade.key(LEDGER_KEY)}, new String[]{sid});
        } catch (RedisException e) {
            warnFailOpen("confirm touch fail-open (redis unavailable, sid=" + sid + ")");
            return;   // fail-open：放行（Redis 故障期确认可用性优先）
        } catch (Exception e) {
            log.warn("[sandbox-pool] touch ledger probe failed (sid={}): {}", sid, e.getMessage());
            return;   // 不可判定 → 放行（宁可漏收不可误作废）
        }
        if (verdict == null || verdict.isEmpty()) {
            // s: 缺失：会话从未经池借出（fail-open 容器/池刚开启/未知映射）→ 放行（修缺陷②误伤）
            log.debug("[sandbox-pool] confirm touch passed (no pool mapping): sid={}", sid);
            return;
        }
        if ("X".equals(verdict) || "N".equals(verdict)) {
            // 陈旧反查（指向已被他人借走/继承的容器）或容器已回收 → 干净 410，绝不放行（P3 防线）
            throw new PoolLeaseGoneException(sid, "pool ledger stale for session (verdict=" + verdict + ")");
        }
        // 'B:{id}' / 'R:{id}'
        char kind = verdict.charAt(0);
        String target = verdict.length() > 2 ? verdict.substring(2) : "";
        if (kind == 'R') {
            setLease(target, sid);   // 复活：粘滞态租约已死，upsert 重建（复活即续约①）
            recordEvent("touch-revive", sid, target);
            log.info("[sandbox-pool] confirm touch revived sticky container: sid={}, sandboxId={}", sid, target);
            return;
        }
        // kind == 'B'：单 key 原子判活续约（EXISTS→EXPIRE 同脚本，缺陷③窗口关闭）
        try {
            Long ok = evalLuaKeyed(TOUCH_LEASE_LUA, ScriptOutputType.INTEGER,
                new String[]{facade.key(LEASE_PREFIX + target)},
                new String[]{String.valueOf(pool.idleReturnSeconds())});
            if (ok != null && ok == 1L) {
                log.debug("[sandbox-pool] confirm touch renewed lease: sid={}, sandboxId={}", sid, target);
                return;
            }
        } catch (RedisException e) {
            warnFailOpen("confirm touch fail-open on lease probe (sid=" + sid + ")");
            return;   // Redis 抖动：放行（不阻断确认）
        }
        throw new PoolLeaseGoneException(sid, "pool lease gone for container " + target);
    }

    /** 确认入口池租约失活异常（D16-④）：调用方按 410 confirm_expired 处置并自动按拒绝收尾 */
    public static class PoolLeaseGoneException extends RuntimeException {
        public final String sessionId;
        public PoolLeaseGoneException(String sessionId, String reason) {
            super("confirm_expired: pool lease gone for session '" + sessionId + "' (" + reason + ")");
            this.sessionId = sessionId;
        }
    }

    // ---------------------------------------------------------------- 归还（D13：delete() 路由进池）

    /**
     * 归还 = kill + 认领移除 busy + 异步补位（D13：防 SDK 内部/调用方直杀容器导致池容量静默缩水）。
     * Redis 不可达 fail-soft：仍 kill（调用方语义是销毁），补位跳过（扫描自愈）。
     */
    public void release(String sandboxId) {
        recordEvent("release", null, sandboxId);
        log.info("[sandbox-pool] release (kill + rebuild): sandboxId={}", sandboxId);
        boolean claimed = false;
        try {
            claimed = !luaClaimRecycle(sandboxId).isEmpty();
        } catch (Exception e) {
            warnFailOpen("release claim skipped (redis unavailable): " + e.getMessage());
        }
        killQuietly(sandboxId);
        delLease(sandboxId);
        if (claimed) {
            rebuildAsync();
        }
    }

    // ---------------------------------------------------------------- 后台扫描（回收/剪枝/保活/水位）

    /**
     * 启动扫描线程 + 异步预热（集群级水位：多副本同时启动经 Lua cap 合力补齐不超额）。
     *
     * <p>由装配处（AgentScopeConfig）在池开启分支构造完成后<b>显式调用</b>——不实现
     * {@code ApplicationListener}：@Bean 方法在池关闭分支返回 null（NullBean）时，容器按
     * 声明类型把该 bean 名收集进 ApplicationListener 并在 registerListeners 阶段强转，
     * NullBean 会抛 {@code BeanNotOfRequiredTypeException} 使<b>所有业务服务启动即崩</b>
     * （2026-11-05 黑盒重验实测）。池关闭时本类不被装配，无需监听器语义。
     *
     * <p>预热/扫描全部在 daemon 后台线程，不阻塞启动；OpenSandbox Server 不可达只告警
     * （借出时按需创建兜底）。幂等：重复调用只启动一次。
     */
    public void start() {
        startBackgroundExecutors();
        // 预热：异步补齐 minIdle
        var exec = rebuildExecutor;
        if (exec == null) {
            return;
        }
        exec.submit(() -> {
            for (int i = 0; i < pool.minIdle(); i++) {
                if (!rebuildOnce("prewarm")) {
                    break;   // 容量已满/Redis 不可达：停止本轮预热
                }
            }
        });
    }

    /** 启动扫描/补位线程（package-private = 测试显式启动，跳过预热；daemon 线程） */
    void startBackgroundExecutors() {
        if (scanScheduler != null) {
            return;
        }
        scanScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "sandbox-pool-scan");
            t.setDaemon(true);
            return t;
        });
        rebuildExecutor = Executors.newFixedThreadPool(Math.max(1, pool.rebuildConcurrency()), r -> {
            var t = new Thread(r, "sandbox-pool-rebuild");
            t.setDaemon(true);
            return t;
        });
        scanScheduler.scheduleWithFixedDelay(this::scanSafe,
            pool.scanIntervalSeconds(), pool.scanIntervalSeconds(), TimeUnit.SECONDS);
    }

    private void scanSafe() {
        try {
            scan();
        } catch (RedisException e) {
            warnFailOpen("scan skipped (redis unavailable): " + e.getMessage());   // D8 fail-soft：回收跳过
        } catch (Exception e) {
            log.warn("[sandbox-pool] scan failed: {}", e.getMessage());
        }
    }

    /** 单轮扫描（§5.3）：busy 失活回收（D14 双保险 + 认领 + 复核撤销）→ creating 剪枝 → idle 保活 → 水位告警 */
    void scan() {
        Map<String, String> ledger = facade.sync().hgetall(facade.key(LEDGER_KEY));
        int busy = 0;
        int idle = 0;
        for (var en : ledger.entrySet()) {
            if (en.getKey().startsWith("b:")) {
                busy++;
                scanBusyEntry(en.getKey().substring(2), en.getValue());
            } else if (en.getKey().startsWith("i:")) {
                idle++;
                scanIdleEntry(en.getKey().substring(2));
            }
        }
        // creating 剪枝（崩溃 mid-create 自愈，容量不泄漏）
        Long pruned = eval(PRUNE_CREATING_LUA, ScriptOutputType.INTEGER,
            new String[]{String.valueOf(CREATING_PRUNE_SECONDS)});
        if (pruned != null && pruned > 0) {
            log.info("[sandbox-pool] pruned {} stale creating entr{}", pruned, pruned == 1 ? "y" : "ies");
        }
        // 水位告警：busy==maxSize 连续 FULL_WARN_CYCLES 个周期 → 容量该扩信号
        if (busy >= pool.maxSize()) {
            if (fullCycles.incrementAndGet() == FULL_WARN_CYCLES) {
                log.warn("[sandbox-pool] pool at capacity (busy={}/{}) for {} consecutive scans — consider raising SANDBOX_POOL_MAX_SIZE",
                    busy, pool.maxSize(), FULL_WARN_CYCLES);
            }
        } else {
            fullCycles.set(0);
        }
    }

    /** busy 条目判定：租约活 → 跳过；宽限期内 → 跳过；turn_lease 活跃 → 重置租约跳过；否则认领回收 */
    private void scanBusyEntry(String sandboxId, String busyJson) {
        // 1. 租约活：跳过
        if (leaseAlive(sandboxId)) {
            return;
        }
        var rec = parseBusy(busyJson);
        // 2. borrowedAt 宽限规则：借出方在「ledger 入账 → SET 租约」两步间的窗口（崩溃遗留 60s 后进入正常判定）
        if (rec != null && rec.borrowedAt() > 0
                && System.currentTimeMillis() / 1000 - rec.borrowedAt() < BORROW_GRACE_SECONDS) {
            return;
        }
        // 3. D14 双保险：绑定 sid 的 turn_lease 活跃（跨副本可见的"turn 进行中"）→ 重置租约跳过。
        //    不可判定（DB 抖动抛异常）→ 跳过本轮（存储层错误分型纪律：宁延迟不误杀）
        if (turnLeaseStore != null && rec != null && rec.sid() != null && !rec.sid().isBlank()) {
            try {
                if (turnLeaseStore.isHeldStrict(rec.sid())) {
                    setLease(sandboxId, rec.sid());
                    log.debug("[sandbox-pool] turn_lease active, lease reset: sid={}, sandboxId={}",
                        rec.sid(), sandboxId);
                    return;
                }
            } catch (Exception e) {
                log.debug("[sandbox-pool] turn_lease probe unavailable, skip recycle: sid={}: {}",
                    rec.sid(), e.getMessage());
                return;
            }
        }
        // 4. Lua 认领回收（先到先得，多副本并发扫描唯一赢家）
        String claimed;
        try {
            claimed = luaClaimRecycle(sandboxId);
        } catch (RedisException e) {
            throw e;   // 交由 scanSafe 整轮 fail-soft
        }
        if (claimed.isEmpty()) {
            return;   // 已被其他副本认领
        }
        // 4a. 认领后复核 + 撤销：kill 前租约若被 resume 续约重建（判定到 kill 的窗口内有新 turn）→ 还原 busy 与 s: 反查
        if (leaseAlive(sandboxId)) {
            try {
                eval(UNCLAIM_LUA, ScriptOutputType.INTEGER,
                    new String[]{sandboxId, busyJson, rec != null && rec.sid() != null ? rec.sid() : ""});
                log.info("[sandbox-pool] claim reverted (lease renewed during scan): sandboxId={}", sandboxId);
            } catch (RedisException e) {
                warnFailOpen("unclaim failed, container will be killed with stale claim: " + e.getMessage());
            }
            return;
        }
        // 4b. kill + DEL 租约（卫生操作）+ 异步补位；kill 失败（容器已被 Server GC/已死）照常继续
        killQuietly(sandboxId);
        delLease(sandboxId);
        recordEvent("scan-kill", rec != null ? rec.sid() : null, sandboxId);
        log.info("[sandbox-pool] scanned idle-return: kill sandboxId={} (sid={}), snapshot={}",
            sandboxId, rec != null ? rec.sid() : "?", snapshotString());
        rebuildAsync();
    }

    /** idle 保活（§5.4）：getInfo 失效 → 原子认领 + kill + 补位（兜底 Server 空闲 GC） */
    private void scanIdleEntry(String sandboxId) {
        if (probeAlive(sandboxId)) {
            return;
        }
        try {
            String claimed = eval(CLAIM_IDLE_LUA, ScriptOutputType.VALUE, new String[]{sandboxId});
            if (claimed != null && !claimed.isEmpty()) {
                killQuietly(sandboxId);
                recordEvent("idle-reap", null, sandboxId);
                log.warn("[sandbox-pool] idle container dead (Server GC?), reaping: sandboxId={}", sandboxId);
                rebuildAsync();
            }
        } catch (RedisException e) {
            throw e;
        }
    }

    /** 异步补位：Lua cap（集群级 + rebuildConcurrency 经线程池上限裁决）→ 建 → 探测 → 入 idle 池 */
    private void rebuildAsync() {
        var exec = rebuildExecutor;
        if (exec == null || exec.isShutdown()) {
            return;
        }
        exec.submit(() -> rebuildOnce("rebuild"));
    }

    /** 单次补位；返回 false = 未补位（容量满 / Redis 不可达 / 建容器失败——告警后由下轮扫描重试） */
    private boolean rebuildOnce(String reason) {
        try {
            String entry = luaCap();
            if (entry == null || entry.isEmpty()) {
                return false;
            }
            localCreating.put(entry, Boolean.TRUE);
            try {
                Sandbox osb = buildSandbox();
                String id = osb.getInfo().getId();
                if (!probeAlive(id)) {
                    killQuietly(id);
                    recordEvent("rebuild-fail", null, id);
                    log.warn("[sandbox-pool] rebuilt container failed probe, killed: reason={}, sandboxId={}", reason, id);
                    return false;
                }
                eval(PROMOTE_IDLE_LUA, ScriptOutputType.INTEGER,
                    new String[]{entry, id, String.valueOf(System.currentTimeMillis() / 1000)});
                recordEvent(reason, null, id);
                log.info("[sandbox-pool] {} done: sandboxId={}, snapshot={}", reason, id, snapshotString());
                return true;
            } finally {
                // 成功路径已 PROMOTE（HDEL c:）；失败/异常路径释放 creating 名额（重复 HDEL 无害），下轮扫描重试
                localCreating.remove(entry);
                evalQuiet(RELEASE_CREATING_LUA, ScriptOutputType.INTEGER, new String[]{entry});
            }
        } catch (RedisException e) {
            warnFailOpen("rebuild skipped (redis unavailable): " + e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("[sandbox-pool] {} failed: {}", reason, e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- 可观测性

    /** 池状态快照（Debug 端点用）：idle/busy/creating 明细 + waiters + 水位计数 */
    public Map<String, Object> snapshot() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("enabled", true);
        m.put("podId", podId);
        m.put("maxSize", pool.maxSize());
        m.put("waiters", waiters.get());
        m.put("fullCycles", fullCycles.get());
        var idle = new ArrayList<String>();
        var busy = new ArrayList<Map<String, Object>>();
        var creating = new ArrayList<String>();
        try {
            for (var en : facade.sync().hgetall(facade.key(LEDGER_KEY)).entrySet()) {
                if (en.getKey().startsWith("i:")) {
                    idle.add(en.getKey().substring(2));
                } else if (en.getKey().startsWith("b:")) {
                    var rec = parseBusy(en.getValue());
                    var b = new java.util.LinkedHashMap<String, Object>();
                    b.put("sandboxId", en.getKey().substring(2));
                    if (rec != null) {
                        b.put("sid", rec.sid());
                        b.put("podId", rec.podId());
                        b.put("borrowedAt", rec.borrowedAt());
                    }
                    busy.add(b);
                } else if (en.getKey().startsWith("c:")) {
                    creating.add(en.getKey().substring(2));
                }
            }
        } catch (Exception e) {
            m.put("error", String.valueOf(e.getMessage()));
        }
        m.put("idle", idle);
        m.put("busy", busy);
        m.put("creating", creating);
        return m;
    }

    /** 最近流转事件（Debug 端点用；环形缓冲按事件时间倒序，最多 100 条） */
    public List<Map<String, Object>> recentEvents(int limit) {
        synchronized (eventsLock) {
            var all = new ArrayList<>(events);
            java.util.Collections.reverse(all);
            return all.size() > limit ? all.subList(0, limit) : all;
        }
    }

    private void recordEvent(String type, String sid, String sandboxId) {
        var e = new java.util.LinkedHashMap<String, Object>();
        e.put("ts", java.time.Instant.now().toString());
        e.put("event", type);
        e.put("sid", sid);
        e.put("sandboxId", sandboxId);
        e.put("snapshot", snapshotString());
        synchronized (eventsLock) {
            while (events.size() >= 100) {
                events.pollFirst();
            }
            events.addLast(e);
        }
    }

    /** 轻量水位串（日志/事件附注；读失败返回占位，不抛） */
    private String snapshotString() {
        try {
            var counts = new int[]{0, 0, 0};
            for (var f : facade.sync().hkeys(facade.key(LEDGER_KEY))) {
                if (f.startsWith("i:")) counts[0]++;
                else if (f.startsWith("b:")) counts[1]++;
                else if (f.startsWith("c:")) counts[2]++;
            }
            return "idle=" + counts[0] + " busy=" + counts[1] + " creating=" + counts[2]
                + "/" + pool.maxSize() + " waiters=" + waiters.get();
        } catch (Exception e) {
            return "redis-unavailable";
        }
    }

    // ---------------------------------------------------------------- Redis 原语（全部 try 包裹，RedisException 上抛由调用方分型）

    /**
     * ledger 单 key Lua 执行收口（protected = 测试子类覆写脱离真实 Redis）——
     * 委托 {@link #evalLuaKeyed}（KEYS 显式传入）并固定 KEYS[1]=ledger。
     */
    protected <T> T evalLua(String script, ScriptOutputType type, String[] args) {
        return evalLuaKeyed(script, type, new String[]{facade.key(LEDGER_KEY)}, args);
    }

    /**
     * 单 key Lua 执行收口（protected = 测试子类覆写脱离真实 Redis；KEYS 显式传入）。
     * <b>缺陷①修复的根因收口</b>：所有 Lua 的 KEYS[1] 由调用点显式给键——ledger 单 key 脚本传
     * ledger 键、租约键脚本（RENEW/TOUCH_LEASE）传租约键，杜绝「脚本 KEYS[1] 语义与传入键错位」
     * 再发（此前 renew 硬编码 ledger 键导致 WRONGTYPE 静默失效）。单 key 脚本在 cluster 下
     * 天然合法，无 CROSSSLOT 面。
     */
    protected <T> T evalLuaKeyed(String script, ScriptOutputType type, String[] keys, String[] args) {
        return facade.sync().eval(script, type, keys, args);
    }

    private <T> T eval(String script, ScriptOutputType type, String[] args) {
        return evalLua(script, type, args);
    }

    private void evalQuiet(String script, ScriptOutputType type, String[] args) {
        try {
            eval(script, type, args);
        } catch (Exception e) {
            log.debug("[sandbox-pool] evalQuiet failed: {}", e.getMessage());
        }
    }

    private String luaBorrow(String busyJson, String sid) {
        String id = eval(BORROW_LUA, ScriptOutputType.VALUE,
            new String[]{busyJson, sid != null ? sid : ""});
        return id == null ? "" : id;
    }

    private String luaCap() {
        return eval(CAP_LUA, ScriptOutputType.VALUE,
            new String[]{String.valueOf(pool.maxSize()), podId, podId});
    }

    private String luaClaimRecycle(String sandboxId) {
        String id = eval(CLAIM_RECYCLE_LUA, ScriptOutputType.VALUE, new String[]{sandboxId});
        return id == null ? "" : id;
    }

    private boolean leaseAlive(String sandboxId) {
        try {
            Long n = facade.sync().exists(facade.key(LEASE_PREFIX + sandboxId));   // Lettuce exists → Long
            return n != null && n > 0;
        } catch (RedisException e) {
            throw e;
        }
    }

    private void setLease(String sandboxId, String value) {
        facade.sync().setex(facade.key(LEASE_PREFIX + sandboxId),
            pool.idleReturnSeconds(), value != null ? value : podId);
    }

    private void delLease(String sandboxId) {
        try {
            facade.sync().del(facade.key(LEASE_PREFIX + sandboxId));
        } catch (Exception e) {
            log.debug("[sandbox-pool] del lease failed (hygiene op): {}", e.getMessage());
        }
    }

    /** kill 前的 busy 认领（借出探测换新路径用；lambda 化仅为绕开重复 try 块） */
    private void claimBusy(java.util.function.Supplier<String> claim) {
        try {
            claim.get();
        } catch (Exception e) {
            log.debug("[sandbox-pool] claim on probe-fail failed (scan will reconcile): {}", e.getMessage());
        }
    }

    private void warnFailOpen(String message) {
        long now = System.currentTimeMillis();
        if (now - lastFailOpenWarnAt > FAIL_OPEN_WARN_INTERVAL_MS) {
            lastFailOpenWarnAt = now;
            log.warn("[sandbox-pool] {}", message);
        }
    }

    // ---------------------------------------------------------------- OpenSandbox Server 面（connect/建/探/杀）

    /** 建新容器（按需建/预热/补位；protected = 测试子类可覆写脱离真实 Server） */
    protected Sandbox buildSandbox() {
        var builder = Sandbox.builder()
            .connectionConfig(connectionConfig)
            .image(image)
            .timeout(timeout)
            .resource(resource)
            .env(environment);
        if (entrypoint != null && !entrypoint.isEmpty()) {
            builder.entrypoint(entrypoint);
        }
        return builder.build();
    }

    /**
     * 连接既有容器（池借出/回收判定用；容器不存在抛 SandboxApiException 404 链）。
     * protected = 测试子类覆写返回 mock 句柄（单测不触真实 OpenSandbox Server）。
     */
    protected Sandbox connect(String sandboxId) {
        return Sandbox.connector()
            .sandboxId(sandboxId)
            .connectionConfig(connectionConfig)
            .skipHealthCheck(true)
            .connect();
    }

    /** 轻量存活探测（设计 §5.2/§5.3 的 execd ping 等价实现：Server getInfo 一次往返；失败=容器不可用） */
    boolean probeAlive(String sandboxId) {
        try (var osb = connect(sandboxId)) {
            var info = osb.getInfo();
            return info != null && info.getId() != null && !info.getId().isBlank();
        } catch (Exception e) {
            log.debug("[sandbox-pool] probe dead: sandboxId={}: {}", sandboxId, e.getMessage());
            return false;
        }
    }

    /** kill 幂等（已死容器 kill 无副作用；连接失败照常吞——回收路径不因 kill 失败中断） */
    protected void killQuietly(String sandboxId) {
        try (var osb = connect(sandboxId)) {
            osb.kill();
        } catch (Exception e) {
            log.debug("[sandbox-pool] kill failed (likely already dead): sandboxId={}: {}", sandboxId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- busy 记录

    /** busy 条目 JSON：{sid, userKey, podId, borrowedAt(秒)}——sid 为 D14 双保险查询键（设计 D14），userKey 为 D17 配额键 */
    private String busyJsonOf(String sid, String userKey) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("sid", sid != null ? sid : "");
        m.put("userKey", userKey != null ? userKey : "");
        m.put("podId", podId);
        m.put("borrowedAt", System.currentTimeMillis() / 1000);
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"sid\":\"\",\"userKey\":\"\",\"podId\":\"\",\"borrowedAt\":0}";
        }
    }

    private BusyRecord parseBusy(String json) {
        try {
            var n = mapper.readTree(json);
            return new BusyRecord(
                n.path("sid").asText(""),
                n.path("podId").asText(""),
                n.path("borrowedAt").asLong(0));
        } catch (Exception e) {
            return null;
        }
    }

    record BusyRecord(String sid, String podId, long borrowedAt) {}

    // ---------------------------------------------------------------- 生命周期（D12：优雅停机不杀容器）

    /**
     * 优雅停机：关扫描/补位线程 + HDEL 本副本 creating 条目（漏清由 5min 剪枝自愈）。
     * <b>绝不杀任何容器</b>——容器是集群资产（D12），在途 turn 经 resume 在其他/重启后副本续跑。
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        var sched = scanScheduler;
        if (sched != null) {
            sched.shutdownNow();
        }
        var exec = rebuildExecutor;
        if (exec != null) {
            exec.shutdownNow();
        }
        for (var entry : localCreating.keySet()) {
            evalQuiet(RELEASE_CREATING_LUA, ScriptOutputType.INTEGER, new String[]{entry});
        }
        localCreating.clear();
        log.info("[sandbox-pool] shut down (containers left intact — cluster assets, D12)");
    }
}
