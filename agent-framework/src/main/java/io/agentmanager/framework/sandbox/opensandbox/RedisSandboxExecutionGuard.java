package io.agentmanager.framework.sandbox.opensandbox;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.ScriptOutputType;

/**
 * 基于 Redis 的沙箱并发执行守卫（issue #27：官方文档 §9 对 USER 隔离范围的明确要求）。
 *
 * <p><b>背景</b>：框架装配 {@code IsolationScope.USER}（同用户跨会话共享沙箱 state slot），
 * harness 官方文档明确"USER 范围是顺序复用而非并发共享，多副本/并发场景建议配置
 * {@code SandboxExecutionGuard} 串行化共享 slot"。未配置时同用户并发 agent call 会在
 * harness 侧并发 hydrate 同一容器（固定临时路径互踩）与并发创建容器（端口分配竞态）——
 * 即 #27 观测到的两类故障。
 *
 * <p><b>实现</b>：SET NX PX + Lua 比较删除（与 turn_lease 同款互斥语义）。同一隔离键
 * （USER → userId）串行进入；租约 TTL 为崩溃自愈兜底（进程被杀时锁到期自动释放）。
 *
 * <p><b>fail-open</b>：Redis 不可用时放行并限流告警——守卫是正确性增强，不能反过来
 * 成为对话可用性的单点。官方 noop() 语义即"无锁"。
 */
public class RedisSandboxExecutionGuard implements SandboxExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisSandboxExecutionGuard.class);

    /** 释放锁的 Lua：仅当持有 token 匹配才删除（防误删他人租约） */
    private static final String RELEASE_LUA =
        "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";

    private static final long RETRY_INTERVAL_MS = 200;

    /** 裸 key 的固定段（实际 key = facade.key(前缀 + scope + value)，前缀由门面统一追加） */
    private static final String GUARD_PREFIX = "sbx:guard";

    private final RedisConnectionFacade facade;
    private final long leaseTtlMs;

    /** fail-open 限流：上次告警时间戳，60s 内不重复刷屏 */
    private volatile long lastFailOpenWarnAt = 0L;

    public RedisSandboxExecutionGuard(RedisConnectionFacade facade, long leaseTtlMs) {
        this.facade = facade;
        this.leaseTtlMs = leaseTtlMs;
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        // 多 Agent 共用 oaf-redis 时 sbx:guard:user:{uid} 会跨服务互锁——agent.redis.prefix
        // 经 facade.key() 统一切分（本类的旧构造器注入 keyPrefix 的职责已并入门面）
        String redisKey = facade.key(GUARD_PREFIX + ":"
            + key.getScope().name().toLowerCase() + ":" + key.getValue());
        String token = UUID.randomUUID().toString();
        var args = SetArgs.Builder.nx().px(leaseTtlMs);
        while (true) {
            try {
                String resp = commands().set(redisKey, token, args);
                if ("OK".equals(resp)) {
                    return new RedisLease(redisKey, token);
                }
                // 已被同用户其他 call 持有：等待重试（阻塞语义，与官方 Guard 契约一致）
                Thread.sleep(RETRY_INTERVAL_MS);
            } catch (RedisException e) {
                failOpen(redisKey, e);
                return SandboxLease.noop();
            }
        }
    }

    /**
     * 同步命令视图经门面取（惰性建连 + 建连退避已收拢在门面，与 RedisEventLog 共享一份逻辑）。
     * SET/Lua EVAL 都是单 key 命令，standalone 与 cluster 连接的 sync 视图同源。
     */
    private io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String> commands() {
        return facade.sync();
    }

    private void failOpen(String redisKey, Exception cause) {
        long now = System.currentTimeMillis();
        if (now - lastFailOpenWarnAt > 60_000L) {
            lastFailOpenWarnAt = now;
            log.warn("SandboxExecutionGuard fail-open（Redis 不可用，沙箱并发防护降级为无锁）: key={}, cause={}",
                    redisKey, cause.getMessage());
        }
    }

    /** 持有句柄：close 时按 token 比较删除；重复 close / TTL 已过均幂等无害 */
    private final class RedisLease implements SandboxLease {
        private final String redisKey;
        private final String token;

        private RedisLease(String redisKey, String token) {
            this.redisKey = redisKey;
            this.token = token;
        }

        @Override
        public void close() {
            try {
                commands().eval(RELEASE_LUA, ScriptOutputType.INTEGER, new String[]{redisKey}, token);
            } catch (RedisException e) {
                // 释放失败无害：TTL 到期自愈，仅记录
                log.info("Sandbox guard lease release failed (TTL will recover): key={}, cause={}",
                        redisKey, e.getMessage());
            }
        }
    }
}
