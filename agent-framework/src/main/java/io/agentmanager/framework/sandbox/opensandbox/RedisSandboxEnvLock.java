package io.agentmanager.framework.sandbox.opensandbox;

import java.util.UUID;

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
 * 把临界区（{@code readOriginal} → {@code mv}）排成全序；生效文件本身的 {@code mv -f}
 * 原子性保证 execd 读侧不受影响。
 *
 * <p><b>实现</b>：SET NX PX + Lua 比较删除（与 {@link RedisSandboxExecutionGuard} 同款互斥
 * 语义，见其类注释）。键 {@code sbx:env:{sandboxId}} <b>必须</b>经
 * {@link RedisConnectionFacade#key} 组装——多服务共享 oaf-redis 时裸键会跨服务互锁
 * （与 {@code sbx:guard} 同源问题）。
 *
 * <p><b>fail-open</b>：Redis 不可用时放行（对齐守卫哲学——正确性增强不得成为可用性单点），
 * 此时并发窗口回到加锁前状态，但 {@code mergeAndWrite} 的写序修复独立成立（发布失败后
 * 重试仍收敛）。无 Redis 部署经 {@link #noop()} 兜底，行为同 fail-open。
 *
 * <p>等待超时抛 {@code env_update_busy}（503）：同沙箱并发 PUT 低频且临界区为亚秒级
 * 沙箱 API 往返，等待窗口内按序进入；耗尽才拒绝，不无限排队。
 */
public class RedisSandboxEnvLock {

    private static final Logger log = LoggerFactory.getLogger(RedisSandboxEnvLock.class);

    /** 释放锁的 Lua：仅当持有 token 匹配才删除（防误删他人租约）；包级可见供单测对齐 */
    static final String RELEASE_LUA =
        "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";

    /** 裸 key 的固定段（实际 key = facade.key(前缀 + sandboxId)，前缀由门面统一追加） */
    static final String ENV_LOCK_PREFIX = "sbx:env";

    /**
     * 租约 TTL（崩溃自愈）：<b>必须大于临界区最坏耗时</b>——discover + 2 读 + 2 写 + mv
     * 共 5 次沙箱 API 往返（内网单次数秒级，15s 覆盖最坏情形）。TTL 过短则持有者未完成
     * 即过期、退化为无锁竞态；TTL 仅兜底持有者崩溃，正常路径由 close 即时释放。
     */
    static final long LEASE_TTL_MS = 15_000L;

    /** 有限等待上限：超时按 env_update_busy 拒绝（503），调用方稍后重试 */
    static final long WAIT_TIMEOUT_MS = 10_000L;

    private static final long RETRY_INTERVAL_MS = 200;

    private final RedisConnectionFacade facade;
    private final long waitTimeoutMs;
    private final long retryIntervalMs;

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

    /** 持有句柄：close 时按 token 比较删除；重复 close / TTL 已过均幂等无害 */
    private final class EnvLease implements Lease {
        private final String redisKey;
        private final String token;

        private EnvLease(String redisKey, String token) {
            this.redisKey = redisKey;
            this.token = token;
        }

        @Override
        public void close() {
            try {
                facade.sync().eval(RELEASE_LUA, ScriptOutputType.INTEGER, new String[]{redisKey}, token);
            } catch (RedisException e) {
                // 释放失败无害：TTL 到期自愈，仅记录
                log.info("[sandbox-env] env lease release failed (TTL will recover): key={}, cause={}",
                    redisKey, e.getMessage());
            }
        }
    }
}
