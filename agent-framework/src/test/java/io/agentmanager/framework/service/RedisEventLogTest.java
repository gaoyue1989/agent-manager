package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentRedisProperties;
import io.agentmanager.framework.redis.RedisConnectionFacade;

/**
 * {@link RedisEventLog} key 组装离线单测（cluster hash tag × 服务前缀）。
 *
 * <p>为什么这些断言可以离线跑：{@code RedisConnectionFacade} 惰性建连——{@code create} 只解析
 * 配置（不可达种子不算配置错），{@code key()} / {@code isClusterMode()} 都是纯字符串/布尔运算。
 * 同款先例见 {@code ProtocolRedisEventBusTest}（同样用不可达种子建门面再断言 key）。
 *
 * <p>为什么值得单列：cluster 模式下 events/replies 两键必须同 slot（appendBatch 的
 * 「ZADD 索引先于 XADD 数据」管道顺序保证只在同节点成立）；而 standalone 必须逐字节保持旧
 * key（存量数据零迁移、滚动升级时活跃会话回放不中断）。两条互斥约束各钉一个方向。
 */
class RedisEventLogTest {

    /** 不可达种子：只用于拿门面（不建连），不会真的发起连接 */
    private static final String URL = "redis://127.0.0.1:6399";

    private static RedisEventLog logWith(AgentRedisProperties props) {
        var facade = RedisConnectionFacade.create(props);
        return new RedisEventLog(facade, props);
    }

    private static AgentRedisProperties props(AgentRedisProperties.Mode mode, String prefix) {
        return new AgentRedisProperties(URL, 2000, 2000, 250_000, mode, "", prefix);
    }

    /** cluster 无前缀：sid 包 hash tag，两键同 slot */
    @Test
    void clusterModeShouldWrapSessionIdInHashTag() {
        var log = logWith(props(AgentRedisProperties.Mode.cluster, ""));

        assertEquals("sess:{s1}:events", log.eventsKey("s1"));
        assertEquals("sess:{s1}:replies", log.repliesKey("s1"));
    }

    /** cluster + 服务前缀：hash tag 位于前缀之后、slot 计算不被前缀割裂 */
    @Test
    void clusterModeWithPrefixShouldKeepHashTagAfterPrefix() {
        var log = logWith(props(AgentRedisProperties.Mode.cluster, "fw-a"));

        assertEquals("fw-a:sess:{s1}:events", log.eventsKey("s1"));
        assertEquals("fw-a:sess:{s1}:replies", log.repliesKey("s1"));
    }

    /** standalone（存量部署形态）：逐字节保持无 hash tag 的旧 key，防 hash tag 泄漏 */
    @Test
    void standaloneModeShouldKeepLegacyKeyShape() {
        var log = logWith(props(AgentRedisProperties.Mode.standalone, ""));

        assertEquals("sess:s1:events", log.eventsKey("s1"));
        assertEquals("sess:s1:replies", log.repliesKey("s1"));
    }

    /** standalone + 前缀：只加前缀，不引入 hash tag */
    @Test
    void standaloneModeWithPrefixShouldOnlyPrependPrefix() {
        var log = logWith(props(AgentRedisProperties.Mode.standalone, "fw-a"));

        assertEquals("fw-a:sess:s1:events", log.eventsKey("s1"));
        assertEquals("fw-a:sess:s1:replies", log.repliesKey("s1"));
    }
}