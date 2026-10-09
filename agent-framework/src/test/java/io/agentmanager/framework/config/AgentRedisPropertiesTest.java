package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code agent.redis} 配置绑定测试。
 *
 * <p><b>为什么不用 {@code new AgentRedisProperties(...)} 直接断言</b>（{@code SandboxConfigTest} 的写法）：
 * 那样测的是「record 的构造器长这样」，**测不出漏写 {@code @DefaultValue}**——而这里的风险恰恰是
 * 「配置节缺失时能不能起来」。{@code application.yml} 不在版本控制内（用户本地有未提交改动），
 * 代码默认值是唯一的保证。本测试走真实的 Binder / {@code @EnableConfigurationProperties} 路径。
 */
class AgentRedisPropertiesTest {

    @EnableConfigurationProperties(AgentRedisProperties.class)
    static class EnableAgentRedis {}

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(EnableAgentRedis.class);

    /**
     * 最关键的一条：整个 {@code agent.redis} 节不存在时，每个叶子都必须落到代码默认值。
     *
     * <p><b>为什么这条测试非有不可（实测的失败模式，不是推测）：</b>漏写 {@code @DefaultValue} 时，
     * 原始类型 {@code int} **不会**让启动失败，而是静默绑定成 {@code 0}。对 {@code maxLenPerStream}
     * 来说那就是 {@code XADD ... MAXLEN ~ 0}——每条刚写进去的事件立刻被裁掉；对
     * {@code commandTimeoutMs} 则是每条命令立刻超时。变异验证：删掉
     * {@code maxLenPerStream} 的 {@code @DefaultValue}，本用例报
     * {@code expected: <250000> but was: <0>}，其余四条仍绿——所以它确实是唯一在守这个的测试。
     */
    @Test
    void everyLeafFallsBackToDefaultWhenSectionAbsent() {
        runner.run(ctx -> {
            var redis = ctx.getBean(AgentRedisProperties.class);
            assertNotNull(redis, "配置节缺失时 bean 仍须绑定成功（叶子全有 @DefaultValue）");
            assertEquals("redis://127.0.0.1:6379", redis.url());
            assertEquals(2000, redis.commandTimeoutMs());
            assertEquals(2000, redis.connectTimeoutMs());
            assertEquals(250000, redis.maxLenPerStream());
        });
    }

    /**
     * 显式配置必须能覆盖——否则运维改不动。
     */
    @Test
    void explicitValuesOverrideDefaults() {
        runner.withPropertyValues(
                "agent.redis.url=redis://oaf-redis.agent-platform.svc.cluster.local:6379/2",
                "agent.redis.command-timeout-ms=500",
                "agent.redis.connect-timeout-ms=750",
                "agent.redis.max-len-per-stream=1000",
                "agent.redis.mode=cluster",
                "agent.redis.cluster-nodes=redis://n1:6379, redis://n2:6379",
                "agent.redis.prefix=ag1")
            .run(ctx -> {
                var redis = ctx.getBean(AgentRedisProperties.class);
                assertEquals("redis://oaf-redis.agent-platform.svc.cluster.local:6379/2", redis.url());
                assertEquals(500, redis.commandTimeoutMs());
                assertEquals(750, redis.connectTimeoutMs());
                assertEquals(1000, redis.maxLenPerStream());
                assertEquals(AgentRedisProperties.Mode.cluster, redis.mode());
                // clusterNodes 经 clusterNodeList() 去空白滤空
                assertEquals(List.of("redis://n1:6379", "redis://n2:6379"), redis.clusterNodeList());
                // prefix 规范化补冒号
                assertEquals("ag1:", redis.normalizedPrefix());
            });
    }

    /**
     * 新增叶子（mode/clusterNodes/prefix）在配置节缺失时必须落到代码默认值——
     * 与四个原始字段同一条纪律（见 everyLeafFallsBackToDefaultWhenSectionAbsent 的失败模式说明）。
     */
    @Test
    void newLeavesFallBackToDefaultsWhenSectionAbsent() {
        runner.run(ctx -> {
            var redis = ctx.getBean(AgentRedisProperties.class);
            assertEquals(AgentRedisProperties.Mode.standalone, redis.mode());
            assertEquals("", redis.clusterNodes());
            assertEquals("", redis.prefix());
            assertTrue(redis.clusterNodeList().isEmpty());
            assertEquals("", redis.normalizedPrefix());
            assertFalse(redis.isCluster());
        });
    }

    /** prefix 非法值必须响亮失败：空白、含 { 、含 } 都会破坏 hash tag 语义或等于没配。 */
    @Test
    void rejectsIllegalPrefix() {
        for (String bad : new String[]{"   ", "ag{o}", "ag}", "{ag"}) {
            var ex = assertThrows(IllegalArgumentException.class,
                () -> new AgentRedisProperties("redis://127.0.0.1:6379", 2000, 2000, 250000,
                    AgentRedisProperties.Mode.standalone, "", bad),
                "非法 prefix 应抛 IllegalArgumentException：" + bad);
            assertNotNull(ex.getMessage());
        }
    }

    /** prefix 规范化：无冒号自动补；已带冒号原样保留；4 参兼容构造器保持空前缀。 */
    @Test
    void normalizesPrefixTrailingColon() {
        assertEquals("ag1:", new AgentRedisProperties("redis://127.0.0.1:6379", 2000, 2000, 250000,
            AgentRedisProperties.Mode.standalone, "", "ag1").normalizedPrefix());
        assertEquals("ag1:", new AgentRedisProperties("redis://127.0.0.1:6379", 2000, 2000, 250000,
            AgentRedisProperties.Mode.standalone, "", "ag1:").normalizedPrefix());
        assertEquals("", new AgentRedisProperties("redis://127.0.0.1:6379", 2000, 2000, 250000)
            .normalizedPrefix());
    }

    /**
     * TTL 只有一个来源：{@code CleanupConfig.sessionRetentionDays}。
     * {@code AgentRedisProperties} **不得**再声明一个 retentionDays——两个 7 迟早会漂移成两个值。
     */
    @Test
    void redisConfigMustNotDeclareItsOwnRetention() {
        for (var component : AgentRedisProperties.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase().contains("retention"),
                "retention 应复用 CleanupConfig.sessionRetentionDays，不能在 AgentRedisProperties 里另起一份："
                    + component.getName());
        }
    }

    /**
     * 配置错的 URL 必须**响亮失败**（与「Redis 暂时不可达」区别对待）：
     * 前者是发布错误，后者必须让服务照常起来、只在用到时降级。
     *
     * <p>四种形态都是运维真会写错的：漏 scheme（直接把 host 填进 URL）、漏主机、
     * 把 http 当成 redis scheme、端口多打一位。断言具体异常类型而不是 {@code Exception}，
     * 否则这条测试会因为任何原因通过（比如构造器 NPE），等于没测。
     */
    @Test
    void facadeBeanFailsFastOnMalformedUrl() {
        var config = new AgentScopeConfig();
        for (String bad : new String[]{
                "not-a-valid-uri",          // URI scheme must not be null
                "redis://",                 // Expected authority
                "http://h:6379",            // Scheme http not supported
                "redis://127.0.0.1:99999"   // Port out of range
        }) {
            var ex = assertThrows(IllegalArgumentException.class,
                () -> config.redisConnectionFacade(
                    new AgentRedisProperties(bad, 2000, 2000, 250000)),
                "非法 URL 应抛 IllegalArgumentException 而不是别的异常：" + bad);
            assertNotNull(ex.getMessage(), "异常须带可诊断的信息：" + bad);
        }
    }

    /** cluster 模式下非法种子节点同样要启动期失败（与 standalone URL 同一纪律）。 */
    @Test
    void facadeBeanFailsFastOnMalformedClusterNode() {
        var config = new AgentScopeConfig();
        assertThrows(IllegalArgumentException.class,
            () -> config.redisConnectionFacade(new AgentRedisProperties(
                "redis://127.0.0.1:6379", 2000, 2000, 250000,
                AgentRedisProperties.Mode.cluster, "redis://127.0.0.1:99999", "")));
    }

    /**
     * 不可达的 Redis **不得**让 bean 创建失败——Lettuce 懒连接，这里只建客户端不建连接。
     * 这条直接对应「Redis 滚动重启不能变成 agent 集群崩溃循环」。
     */
    @Test
    void facadeBeanSucceedsWhenRedisIsUnreachable() {
        var config = new AgentScopeConfig();
        // 127.0.0.1:1 上不会有 Redis；bean 创建**不应该**抛异常，也不应该阻塞到超时
        try (var facade = config.redisConnectionFacade(
                new AgentRedisProperties("redis://127.0.0.1:1", 2000, 2000, 250000))) {
            assertNotNull(facade);
        }
        // cluster 模式同理：种子不可达不影响 bean 创建（拓扑发现惰性发生在 connect 时）
        try (var facade = config.redisConnectionFacade(new AgentRedisProperties(
                "redis://127.0.0.1:1", 2000, 2000, 250000,
                AgentRedisProperties.Mode.cluster, "", ""))) {
            assertNotNull(facade);
            assertTrue(facade.isClusterMode());
        }
    }
}
