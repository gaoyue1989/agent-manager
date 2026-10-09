package io.agentmanager.framework.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import io.agentmanager.framework.controller.A2aJobController;
import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.agentmanager.framework.service.a2ajob.A2aJobRedisStore;
import io.agentmanager.framework.service.a2ajob.A2aJobService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A2aJob 装配链测试（CR P1-3，Issue #69 §6：ApplicationContextRunner 验证真实装配）：
 * enabled=false → Controller/Service/Store/Filter 全部不存在（P0-1 守卫：端点面缺省关闭）；
 * enabled=true + facade mock PING 通 → filter 注册 + 自检通过；token 空白 / 并发超限 → 启动失败。
 * Controller/Service/Store 的 @ConditionalOnProperty 注解经 disabled 分支 + e2e 存量实例 404 双守卫。
 */
class A2aJobAssemblyTest {

    /** facade mock：PING 返回 PONG（自检通过）；key 原样透传 */
    private static RedisConnectionFacade fakeFacade() {
        var f = mock(RedisConnectionFacade.class);
        when(f.sync()).thenReturn(mock(io.lettuce.core.cluster.api.sync.RedisClusterCommands.class));
        org.mockito.Mockito.lenient().when(f.sync().ping()).thenReturn("PONG");
        org.mockito.Mockito.lenient().when(f.key(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return f;
    }

    /** facade mock：PING 抛异常（Redis 不可达 / 认证失败）——自检必须 fail-fast */
    private static RedisConnectionFacade brokenFacade() {
        var f = mock(RedisConnectionFacade.class);
        when(f.sync()).thenReturn(mock(io.lettuce.core.cluster.api.sync.RedisClusterCommands.class));
        org.mockito.Mockito.lenient().when(f.sync().ping())
            .thenThrow(new IllegalStateException("Connection refused"));
        org.mockito.Mockito.lenient().when(f.key(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return f;
    }

    /** 全链 supplier 组装（withBean(Class) 自动装配在隔离上下文构造器歧义，显式 new 保依赖） */
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(A2aJobConfig.class))
        // enabled=true 时 A2aJobEnabledAssembly 自检需要 RedisConnectionFacade（PING）
        .withBean("facade", RedisConnectionFacade.class, A2aJobAssemblyTest::fakeFacade)
        .withBean("ctrl", A2aJobController.class,
            () -> new A2aJobController(new A2aJobService(new A2aJobRedisStore(fakeFacade()),
                new AgentA2aJobProperties(true, "tok", 300, 24, 8), "8100")))
        .withPropertyValues("server.port=8100");

    @Test
    void disabledShouldRegisterNoAssemblyBeans() {
        runner.run(ctx -> {
            // 条件装配四件全关（端点 bean 此处为 supplier 直注册，条件关闭的是 config/filter/自检；
            // Controller 类级注解的关闭形态由 e2e J 组对存量实例断言 404）
            assertThat(ctx).doesNotHaveBean("a2aJobAuthFilter");
            assertThat(ctx).doesNotHaveBean(A2aJobConfig.A2aJobEnabledAssembly.class);
        });
    }

    @Test
    void enabledShouldRegisterFilterWithUrlGuard() {
        runner.withPropertyValues(
                "agent.a2a-job.enabled=true",
                "agent.a2a-job.auth-token=tok").run(ctx -> {
            var fr = ctx.getBean("a2aJobAuthFilter", FilterRegistrationBean.class);
            assertThat(fr.getUrlPatterns()).containsExactlyInAnyOrder("/a2a/jobs", "/a2a/jobs/*");
            assertThat(ctx.getBeansOfType(A2aJobConfig.A2aJobEnabledAssembly.class)).isNotEmpty();
        });
    }

    @Test
    void enabledWithoutTokenShouldFailStartup() {
        runner.withPropertyValues("agent.a2a-job.enabled=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            // BeanCreationException 只报「构造失败」，fail-fast 文案在 cause 链里（沿 AgentProtocolConfigTest 口径）
            var cause = ctx.getStartupFailure();
            while (cause != null && !(cause instanceof IllegalStateException)) {
                cause = cause.getCause();
            }
            assertThat(cause).hasMessageContaining("AGENT_A2A_JOB_TOKEN");
        });
    }

    @Test
    void enabledWithOverLimitConcurrencyShouldFailStartup() {
        runner.withPropertyValues(
                "agent.a2a-job.enabled=true",
                "agent.a2a-job.auth-token=tok",
                "agent.a2a-job.max-concurrent=150").run(ctx -> {
            assertThat(ctx).hasFailed();
            var cause = ctx.getStartupFailure();
            while (cause != null && !(cause instanceof IllegalStateException)) {
                cause = cause.getCause();
            }
            assertThat(cause).hasMessageContaining("MAX_CONCURRENT");
        });
    }

    /**
     * enabled=true 但 PING 抛异常（Redis 根本连不上 / 认证失败）→ 启动失败。
     * Job 路径硬依赖 Redis，配置错误必须在启动期暴露（Issue #69 §2.3 的 fail-fast 承诺），
     * 而不是等首请求 503。
     */
    @Test
    void enabledWithUnreachableRedisShouldFailStartup() {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(A2aJobConfig.class))
            .withBean("facade", RedisConnectionFacade.class, A2aJobAssemblyTest::brokenFacade)
            .withPropertyValues(
                "server.port=8100",
                "agent.a2a-job.enabled=true",
                "agent.a2a-job.auth-token=tok")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                var cause = ctx.getStartupFailure();
                while (cause != null && !(cause instanceof IllegalStateException)) {
                    cause = cause.getCause();
                }
                assertThat(cause).hasMessageContaining("Redis 启动自检失败");
            });
    }
}
