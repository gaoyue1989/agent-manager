package io.agentmanager.framework.sandbox.opensandbox;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.agentmanager.framework.service.SessionUserStore;
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
 * <p><b>锁键翻译（2026-10-16）</b>：官方语义要求"锁粒度 = slot 共享粒度 = 真实用户"。
 * 但 Channel 链路（/threads/chat 经 ChatUiChannel 网关）下 SDK 会改写 RuntimeContext——
 * ctx.userId 是网关 peer（=前端 sessionId，见 SessionKeyResolver javadoc），直接用它组键
 * 会让锁退化为"每会话一把"（实测 sbx:guard:user:{sa2-main}/{sb2-sbx} 两锁并存，同用户
 * 两个会话并行复用同一 slot）。本类在组键前经 {@link SessionKeyResolver#realUserId} 反查
 * session_user 表还原真实用户；反查不可用/未命中时回退原值（A2A / invoke 链路 ctx.userId
 * 本就是真实用户，回退保持其行为不变）。注意这修复的是"同用户串行化"；跨用户并行仍会
 * 竞态 harness 单槽文件系统（上游 #2849），由 per-session agent 隔离另行解决。
 *
 * <p><b>实现</b>：SET NX PX + Lua 比较删除（与 turn_lease 同款互斥语义）。同一隔离键
 * （USER → 真实用户）串行进入；租约 TTL 为崩溃自愈兜底（进程被杀时锁到期自动释放）。
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

    /** 反查结果短缓存 TTL：session_user 行在会话生命周期内不变，缓存只为挡自旋重试的查库频率 */
    private static final long LOOKUP_CACHE_TTL_MS = 30_000L;

    /** 反查不到/异常时的负缓存 TTL：避免自旋路径每 200ms 打一次 DB */
    private static final long NEGATIVE_CACHE_TTL_MS = 5_000L;

    private final RedisConnectionFacade facade;
    private final long leaseTtlMs;
    private final SessionUserStore sessionUserStore;

    /** fail-open 限流：上次告警时间戳，60s 内不重复刷屏 */
    private volatile long lastFailOpenWarnAt = 0L;

    /** key.value → 解析结果（真实 userId 或 null=回退原值）+ 时间戳。容量有界（活跃会话数），过期条目惰性清理 */
    private final ConcurrentHashMap<String, CachedLookup> lookupCache = new ConcurrentHashMap<>();

    private record CachedLookup(String realUserId, long at) {}

    /** 反查句柄为 null 时的兼容构造（单测/无 store 部署）：行为与修复前逐字节一致 */
    public RedisSandboxExecutionGuard(RedisConnectionFacade facade, long leaseTtlMs) {
        this(facade, leaseTtlMs, null);
    }

    public RedisSandboxExecutionGuard(RedisConnectionFacade facade, long leaseTtlMs,
                                      SessionUserStore sessionUserStore) {
        this.facade = facade;
        this.leaseTtlMs = leaseTtlMs;
        this.sessionUserStore = sessionUserStore;
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        // 多 Agent 共用 oaf-redis 时 sbx:guard:user:{uid} 会跨服务互锁——agent.redis.prefix
        // 经 facade.key() 统一切分（本类的旧构造器注入 keyPrefix 的职责已并入门面）
        String redisKey = facade.key(GUARD_PREFIX + ":"
            + key.getScope().name().toLowerCase() + ":" + resolveLockValue(key));
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
     * 锁键值翻译：USER 范围经 session_user 反查真实 userId（Channel 链路 key.value 是
     * 网关 peer=前端 sessionId）；SESSION/AGENT/GLOBAL 范围值本就是规范键，原样返回。
     * 反查不可用/未命中回退原值（A2A / invoke 链路行为不变）。结果短缓存挡自旋查库。
     */
    private String resolveLockValue(SandboxIsolationKey key) {
        if (sessionUserStore == null
                || key.getScope() != io.agentscope.harness.agent.IsolationScope.USER) {
            return key.getValue();
        }
        return resolveUserLockValue(key.getValue());
    }

    /** USER 范围的键值翻译（包级可见供单测）：反查命中用真实用户，未命中/异常回退原值 */
    String resolveUserLockValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        long now = System.currentTimeMillis();
        var cached = lookupCache.get(raw);
        if (cached != null && now - cached.at() < (cached.realUserId() != null
                ? LOOKUP_CACHE_TTL_MS : NEGATIVE_CACHE_TTL_MS)) {
            return cached.realUserId() != null ? cached.realUserId() : raw;
        }
        String real = null;
        try {
            real = sessionUserStore.findUserIdBySession(raw);
        } catch (Exception e) {
            // SessionUserStore 内部已 fail-soft，此处兜底防御
            log.debug("Guard lock-value lookup errored for {}: {}", raw, e.getMessage());
        }
        lookupCache.put(raw, new CachedLookup(real, now));
        // 惰性清理：超过正缓存 TTL 2 倍的条目顺手剔除，防长生命周期进程缓慢累积
        if (lookupCache.size() > 1024) {
            lookupCache.entrySet().removeIf(e -> now - e.getValue().at() > 2 * LOOKUP_CACHE_TTL_MS);
        }
        return real != null && !real.isBlank() ? real : raw;
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
