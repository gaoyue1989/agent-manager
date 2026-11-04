package io.agentmanager.framework.sandbox.opensandbox;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;

/**
 * 基于 Redis 的 per-sandbox env 写入租约（issue #230：PUT /sandbox/env 同沙箱并发写互斥）。
 *
 * <p><b>背景</b>：{@code mergeAndWrite} 的合并基于「读沙箱内原文 + 读 sidecar」的快照，
 * 同一沙箱的两个并发 PUT 各自「读旧 → 追加 → mv 发布」，后 mv 者基于旧原文整体覆盖，
 * 先写入方的键从生效文件中丢失（整键丢失）。接口侧按 {@code sandboxId} 加短租约串行化，
 * 把临界区（{@code discoverEnvPath + mergeAndWrite} 全程）排成全序；生效文件本身的
 * {@code mv -f} 原子性保证 execd 读侧不受影响。
 *
 * <p><b>实现</b>：SET NX PX 获租约 + Lua 按 token 续租/比较删除（与
 * {@link RedisSandboxExecutionGuard} 同款互斥语义，另加续租）。键 {@code sbx:env:{sandboxId}}
 * <b>必须</b>经 {@link RedisConnectionFacade#key} 组装——多服务共享 oaf-redis 时裸键会跨服务
 * 互锁（与 {@code sbx:guard} 同源问题）。
 *
 * <p><b>为什么续租（评审 g1）</b>：临界区含 6 次沙箱调用（discover、readOriginal、
 * readManaged、publish 写临时文件、mv、writeManaged）；SDK 1.0.18 把 {@code requestTimeout}
 * （默认 30s）统一设为 connect/read/write/call 超时且默认重试 3 次（{@code RetryPolicy}
 * DEFAULT_MAX_RETRIES=3，429/502/503），单个逻辑调用最坏可达分钟级——任何「静态 TTL 覆盖
 * 临界区最坏耗时」的论证要么失真、要么把崩溃自愈窗口拖到不可接受。改为持有期间按
 * {@code TTL/3} 周期续租后，互斥由「持有者存活即续租」保证，TTL 退化为纯崩溃自愈窗口
 * （持有者被杀后其他写者最长该窗口内 env_update_busy）。
 *
 * <p><b>fail-open</b>：Redis 不可用时放行（对齐守卫哲学——正确性增强不得成为可用性单点），
 * 此时并发窗口回到加锁前状态，但 {@code mergeAndWrite} 的写序修复独立成立（发布失败后
 * 重试仍收敛）。无 Redis 部署经 {@link #noop()} 兜底，行为同 fail-open。续租连续失败至
 * TTL 过期时互斥同样退化，仅记录日志不阻断。
 *
 * <p>等待超时抛 {@code env_update_busy}（503）：同沙箱并发 PUT 低频且等待窗口内按序进入；
 * 耗尽才拒绝，不无限排队。
 */
public class RedisSandboxEnvLock {

    private static final Logger log = LoggerFactory.getLogger(RedisSandboxEnvLock.class);

    /** 释放锁的 Lua：仅当持有 token 匹配才删除（防误删他人租约）；包级可见供单测对齐 */
    static final String RELEASE_LUA =
        "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";

    /**
     * 续租 Lua：仅当持有 token 匹配才延长 TTL——防误续他人租约；对已删除/已过期的键
     * {@code pexpire} 返回 0 且不建键，close 与续租的竞态天然无害。包级可见供单测对齐。
     */
    static final String RENEW_LUA =
        "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('pexpire',KEYS[1],ARGV[2]) else return 0 end";

    /** 裸 key 的固定段（实际 key = facade.key(前缀 + sandboxId)，前缀由门面统一追加） */
    static final String ENV_LOCK_PREFIX = "sbx:env";

    /**
     * 租约 TTL = <b>纯崩溃自愈窗口</b>：持有者存活期间由 {@link #RENEW_LUA} 周期续租，
     * 互斥不依赖 TTL 覆盖临界区耗时（见类注释「为什么续租」）；持有者被杀（不再续租）后，
     * 其他写者最长等本窗口即恢复（期间 env_update_busy）。
     */
    static final long LEASE_TTL_MS = 30_000L;

    /** 续租周期 = TTL/3：单次续租失败有 2 轮重试余量，正常路径 TTL 不会耗尽 */
    static final long RENEW_INTERVAL_MS = LEASE_TTL_MS / 3;

    /** 有限等待上限：超时按 env_update_busy 拒绝（503），调用方稍后重试 */
    static final long WAIT_TIMEOUT_MS = 10_000L;

    private static final long RETRY_INTERVAL_MS = 200;

    private final RedisConnectionFacade facade;
    private final long waitTimeoutMs;
    private final long retryIntervalMs;

    /** 续租调度器（单线程 daemon，惰性创建——noop/未持锁部署零开销） */
    private volatile ScheduledExecutorService renewPool;

    /** fail-open 限流：上次告警时间戳，60s 内不重复刷屏（对齐守卫实现） */
    private volatile long lastFailOpenWarnAt = 0L;

    /** env 写租约句柄：close 释放（幂等；TTL 已过/重复 close 均无害） */
    public interface Lease extends AutoCloseable {

        /** 无锁租约（fail-open / 无 Redis 部署）：close 为 no-op */
        Lease NOOP = () -> {
        };

        @Override
        void close();
    }

    public RedisSandboxEnvLock(RedisConnectionFacade facade) {
        this(facade, WAIT_TIMEOUT_MS, RETRY_INTERVAL_MS);
    }

    /** 单测注入更短等待窗口（生产路径走常量，无配置开关——正确性修复常开） */
    RedisSandboxEnvLock(RedisConnectionFacade facade, long waitTimeoutMs, long retryIntervalMs) {
        this.facade = facade;
        this.waitTimeoutMs = waitTimeoutMs;
        this.retryIntervalMs = retryIntervalMs;
    }

    /** 无 Redis 部署的兜底实例：acquire 恒返回 noop 租约（与 fail-open 同语义） */
    public static RedisSandboxEnvLock noop() {
        return new RedisSandboxEnvLock(null);
    }

    /**
     * 获取 {@code sandboxId} 的写入租约：SET NX PX 成功即持有；被他方占用则按
     * {@code retryIntervalMs} 自旋至等待上限，超时抛 503 {@code env_update_busy}；
     * Redis 异常 fail-open 返回 noop 租约。返回值用 try-with-resources 释放。
     */
    public Lease acquire(String sandboxId) {
        if (facade == null) {
            return Lease.NOOP;
        }
        if (sandboxId == null || sandboxId.isBlank()) {
            // 防御：空 id 会退化为全体共享一把锁，宁可 fail-open 也不引入跨沙箱互斥
            log.warn("[sandbox-env] blank sandboxId for env lock, running unlocked");
            return Lease.NOOP;
        }
        String redisKey = facade.key(ENV_LOCK_PREFIX + ":" + sandboxId);
        String token = UUID.randomUUID().toString();
        var args = SetArgs.Builder.nx().px(LEASE_TTL_MS);
        long deadline = System.currentTimeMillis() + waitTimeoutMs;
        while (true) {
            try {
                String resp = facade.sync().set(redisKey, token, args);
                if ("OK".equals(resp)) {
                    return new EnvLease(redisKey, token);
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw new SandboxEnvException("env_update_busy", 503,
                        "another env update is holding the lease for sandbox " + sandboxId);
                }
                Thread.sleep(retryIntervalMs);
            } catch (RedisException e) {
                failOpen(redisKey, e);
                return Lease.NOOP;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SandboxEnvException("env_update_busy", 503,
                    "interrupted while waiting for env lease of sandbox " + sandboxId);
            }
        }
    }

    private void failOpen(String redisKey, Exception cause) {
        long now = System.currentTimeMillis();
        if (now - lastFailOpenWarnAt > 60_000L) {
            lastFailOpenWarnAt = now;
            log.warn("[sandbox-env] env lock fail-open（Redis 不可用，并发写退化为无锁，写序修复仍生效）: key={}, cause={}",
                redisKey, cause.getMessage());
        }
    }

    /**
     * 按持有 token 续租一轮：token 匹配才 {@code pexpire}。返回键是否仍在本人持有；
     * Redis 异常按 false 处理不抛（调用方在续租线程内，异常不能外溢）。
     * 包级可见供单测。注意：{@code pexpire} 对已删除/已过期的键返回 0 且不建键，
     * 与 close 的竞态天然无害。
     */
    boolean renew(String redisKey, String token) {
        try {
            Object resp = facade.sync().eval(RENEW_LUA, ScriptOutputType.INTEGER,
                new String[]{redisKey}, token, String.valueOf(LEASE_TTL_MS));
            return Long.valueOf(1L).equals(resp);
        } catch (RedisException e) {
            // 续租失败：TTL 内下轮重试；连续失败至过期则互斥退化（fail-open 同级风险），仅记录
            log.info("[sandbox-env] env lease renew failed (will retry): key={}, cause={}",
                redisKey, e.getMessage());
            return false;
        }
    }

    /** 续租调度器惰性创建：只有真实租约需要，noop/fail-open 路径零开销 */
    private ScheduledExecutorService renewPool() {
        var pool = renewPool;
        if (pool == null) {
            synchronized (this) {
                pool = renewPool;
                if (pool == null) {
                    pool = Executors.newSingleThreadScheduledExecutor(r -> {
                        var t = new Thread(r, "sandbox-env-lock-renew");
                        t.setDaemon(true);
                        return t;
                    });
                    renewPool = pool;
                }
            }
        }
        return pool;
    }

    /**
     * 持有句柄：持有期间按 {@code RENEW_INTERVAL_MS} 周期续租（互斥由「存活即续租」保证，
     * 不依赖 TTL 覆盖临界区耗时）；close 先停续租再按 token 比较删除，重复 close / TTL 已过
     * 均幂等无害。
     */
    private final class EnvLease implements Lease {
        private final String redisKey;
        private final String token;
        private final ScheduledFuture<?> renewal;

        private EnvLease(String redisKey, String token) {
            this.redisKey = redisKey;
            this.token = token;
            this.renewal = renewPool().scheduleWithFixedDelay(
                () -> renew(redisKey, token), RENEW_INTERVAL_MS, RENEW_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            // cancel(false)：已启动的一轮续租允许跑完——Lua 原子性下 pexpire 不会复活已删除的键
            renewal.cancel(false);
            try {
                facade.sync().eval(RELEASE_LUA, ScriptOutputType.INTEGER, new String[]{redisKey}, token);
            } catch (RedisException e) {
                // 释放失败无害：不再续租，TTL 到期自愈，仅记录
                log.info("[sandbox-env] env lease release failed (TTL will recover): key={}, cause={}",
                    redisKey, e.getMessage());
            }
        }
    }
}
