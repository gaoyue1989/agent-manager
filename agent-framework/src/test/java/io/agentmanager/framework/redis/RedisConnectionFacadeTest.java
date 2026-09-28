package io.agentmanager.framework.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentRedisProperties;

/**
 * {@link RedisConnectionFacade} 离线单测：key 组装（前缀 × 模式）与「配置错早失败 /
 * 不可达不影响创建」两条装配纪律。真实连接行为由 {@code RedisEventLogIT} 覆盖
 * （门面在 standalone 路径上与生产装配同一代码）。
 */
class RedisConnectionFacadeTest {

    private static final String URL = "redis://127.0.0.1:6379";

    @Test
    void keyWithoutPrefixIsByteIdenticalToLegacy() {
        try (var facade = RedisConnectionFacade.create(
                new AgentRedisProperties(URL, 2000, 2000, 250_000))) {
            // 前缀为空时必须与旧硬编码 key 逐字节一致（存量部署 / e2e 依赖）
            assertEquals("sess:s1:events", facade.key("sess:s1:events"));
            assertEquals("sbx:guard:user:alice", facade.key("sbx:guard:user:alice"));
            assertFalse(facade.isClusterMode());
            assertFalse(facade.isOpen(), "未建连时 isOpen 必须为 false，且不触发建连");
        }
    }

    @Test
    void keyWithPrefixPrependsNormalizedPrefix() {
        try (var facade = RedisConnectionFacade.create(new AgentRedisProperties(
                URL, 2000, 2000, 250_000,
                AgentRedisProperties.Mode.standalone, "", "fw-a"))) {
            // prefix 自动补冒号后拼接（fw-a → fw-a:）
            assertEquals("fw-a:sess:s1:events", facade.key("sess:s1:events"));
            assertEquals("fw-a:sbx:guard:user:alice", facade.key("sbx:guard:user:alice"));
        }
    }

    @Test
    void clusterModeFlagsPropagateToKeyConsumers() {
        try (var facade = RedisConnectionFacade.create(new AgentRedisProperties(
                URL, 2000, 2000, 250_000,
                AgentRedisProperties.Mode.cluster, "redis://127.0.0.1:6380,redis://127.0.0.1:6381", ""))) {
            // 创建不失败（种子不可达不算配置错）；mode 供 RedisEventLog 决定是否加 hash tag
            assertTrue(facade.isClusterMode());
        }
    }

    @Test
    void invalidClusterSeedFailsAtCreate() {
        // 配置错（URI 非法）必须在创建期响亮失败，与「不可达」区别对待
        assertThrows(IllegalArgumentException.class, () -> RedisConnectionFacade.create(
            new AgentRedisProperties(URL, 2000, 2000, 250_000,
                AgentRedisProperties.Mode.cluster, "redis://127.0.0.1:99999", "")));
    }

    @Test
    void clusterNodesFallsBackToUrlWhenAbsent() {
        var props = new AgentRedisProperties(URL, 2000, 2000, 250_000,
            AgentRedisProperties.Mode.cluster, "  , , ", "");
        // 全空白/逗号 = 等效未配置，clusterNodeList 应返回空（门面回落 url 单种子）
        assertTrue(props.clusterNodeList().isEmpty());
    }
}
