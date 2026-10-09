package io.agentmanager.framework.service.a2ajob;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.lettuce.core.SetArgs;

/**
 * A2A 幂等 Job 的 Redis 说话层（Issue #69 §2.4；沿 RedisEventLog「只做 Redis 的事」分层）。
 *
 * <p>状态机建在单键原子操作上（多副本任意副本可裁决同键并发，无本地态）：
 * <pre>
 *   claim       = SET a2ajob:{key} "claim:&lt;token&gt;" NX EX &lt;租期&gt;   （独占发送权）
 *   complete    = Lua: GET==claim:&lt;token&gt; ? SET &lt;taskId&gt; EX &lt;retention&gt; : 0
 *   release     = Lua: GET==claim:&lt;token&gt; ? DEL : 0                （确定未受理，可立即重试）
 *   extend      = Lua: GET==claim:&lt;token&gt; ? EXPIRE &lt;租期&gt; : 0      （结果未知，禁重发）
 * </pre>
 * Lua 单键脚本在 cluster 模式天然同 slot 安全；claim 前缀承载 token 防「完成态 taskId
 * 与 claim 值碰撞」。运行中 Redis 故障由上层转 503 fail-closed（**无本地降级**——
 * 多副本下本地状态即谎言，Issue §2.4）。
 */
@Component
@ConditionalOnProperty(prefix = "agent.a2a-job", name = "enabled", havingValue = "true")
public class A2aJobRedisStore {

    private static final Logger log = LoggerFactory.getLogger(A2aJobRedisStore.class);

    /** claim 值前缀：claim:&lt;token&gt;（UUID）；taskId 不可能以此开头（防御性双保险在上层） */
    public static final String CLAIM_PREFIX = "claim:";

    private final RedisConnectionFacade facade;

    public A2aJobRedisStore(RedisConnectionFacade facade) {
        this.facade = facade;
    }

    // ---------------------------------------------------------------- key

    /** 幂等键完整形态：&lt;服务前缀&gt;a2ajob:{idempotencyKey}（统一走门面，禁自行拼接） */
    public String key(String idempotencyKey) {
        return facade.key("a2ajob:" + idempotencyKey);
    }

    // ---------------------------------------------------------------- 原语

    /**
     * 抢占式认领（SET NX EX）：返回 null 表示认领成功（本副本获得独占发送权）；
     * 返回当前值表示同键已被占用（claim:* → in-flight 409；taskId → 幂等命中 200）。
     * Redis 异常抛出（上层 fail-closed 503）。
     *
     * <p>NX 失败后 GET 取值存在键恰好过期的竞态窗口（GET 返回 null ≠ 被占用）：
     * 原地重试 NX 至多 3 次，仍无法取得确定态则抛出（上层 fail-closed 503）——
     * 旧形态把 null 当「认领成功」会让双副本并发发送，打破同键恰好一次。
     */
    public String claim(String idempotencyKey, String token, Duration lease) {
        var k = key(idempotencyKey);
        for (int attempt = 0; attempt < 3; attempt++) {
            var ok = facade.sync().set(k, CLAIM_PREFIX + token, SetArgs.Builder.nx().ex(lease.toSeconds()));
            if (ok != null) {
                return null;
            }
            var existing = facade.sync().get(k);
            if (existing != null) {
                return existing;
            }
        }
        throw new IllegalStateException(
            "a2a job claim race persisted for key " + idempotencyKey + " (nx/get loop exhausted)");
    }

    /** 当前值快照（GET；409 后重试 NX 前复查「键已消失」用） */
    public String get(String idempotencyKey) {
        return facade.sync().get(key(idempotencyKey));
    }

    /**
     * 发送成功落映射：GET==claim:&lt;token&gt; 才 SET &lt;taskId&gt; EX &lt;retention&gt;（Lua CAS）。
     * 返回 false = 认领已被接管（租期过期的并发接管），调用方按 409 处理。
     */
    public boolean completeWithTaskId(String idempotencyKey, String token, String taskId, Duration retention) {
        var k = key(idempotencyKey);
        String script = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
              return 1
            end
            return 0
            """;
        Long r = facade.sync().eval(script, io.lettuce.core.ScriptOutputType.INTEGER,
            new String[]{k},
            CLAIM_PREFIX + token, taskId, String.valueOf(retention.toSeconds()));
        return r != null && r == 1;
    }

    /** 确定未受理：GET==claim 才 DEL（可立即重试）；false = 已被接管/已收敛，调用方按 409 */
    public boolean release(String idempotencyKey, String token) {
        var k = key(idempotencyKey);
        String script = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """;
        Long r = facade.sync().eval(script, io.lettuce.core.ScriptOutputType.INTEGER,
            new String[]{k}, CLAIM_PREFIX + token);
        return r != null && r == 1;
    }

    /** 结果未知：GET==claim 才刷新租期（禁重发，接管方等收敛）；false = 已被接管/已收敛 */
    public boolean extendLease(String idempotencyKey, String token, Duration lease) {
        var k = key(idempotencyKey);
        String script = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """;
        Long r = facade.sync().eval(script, io.lettuce.core.ScriptOutputType.INTEGER,
            new String[]{k}, CLAIM_PREFIX + token, String.valueOf(lease.toSeconds()));
        return r != null && r == 1;
    }

}
