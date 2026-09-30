package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.LLMLogger;
import io.agentmanager.framework.service.protocol.AgentProtocolAuthFilter;
import io.agentscope.extensions.agentprotocol.AgentFactory;
import io.agentscope.extensions.agentprotocol.AgentProtocolAutoConfiguration;
import io.agentscope.extensions.agentprotocol.AgentProtocolTaskStore;
import io.agentscope.extensions.agentprotocol.AgentRequest;
import io.agentscope.extensions.agentprotocol.ProtocolTaskRepository;
import io.agentscope.extensions.agentprotocol.WorkspaceProtocolTaskRepository;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent Protocol 装配测试：fail-fast 校验、bean override 生效（SDK 默认实现退位）、
 * enabled=false 零装配、AgentProtocolSettings 绑定默认值。
 */
class AgentProtocolConfigTest {

    /** 模拟当前 agent：WorkspaceManager 可探测（TaskRepository override 落库面） */
    private static HarnessAgent harnessAgentMock() {
        var agent = mock(HarnessAgent.class);
        when(agent.getWorkspaceManager()).thenReturn(mock(WorkspaceManager.class));
        return agent;
    }

    /** 真实 AgentRuntimeService（构造仅做赋值），agent 引用经构造注入 */
    private static AgentRuntimeService runtimeService(HarnessAgent agent) {
        return new AgentRuntimeService(mock(io.agentmanager.framework.model.OafConfig.class),
            agent, List.of(), new LLMLogger(), mock(ConfirmContextStore.class));
    }

    /** 提供模拟 HarnessAgent 与真实 AgentRuntimeService 的最小用户配置 */
    @Configuration
    static class TestBeans {
        @Bean
        HarnessAgent harnessAgent() {
            return harnessAgentMock();
        }

        @Bean
        AgentRuntimeService agentRuntimeService(HarnessAgent harnessAgent) {
            return runtimeService(harnessAgent);
        }
    }

    @EnableConfigurationProperties(AgentManagerProperties.AgentProtocolSettings.class)
    static class EnableSettings {}

    @EnableConfigurationProperties(AgentManagerProperties.class)
    static class EnableAgentManagerProperties {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TestBeans.class, AgentProtocolConfig.class);

    // ---------------- fail-fast ----------------

    /** enabled=true 而 authToken 空白 → 装配类构造即抛（设计 §6.2 fail-fast，启动失败） */
    @Test
    void enabledWithoutAuthTokenShouldFailFast() {
        var blank = new AgentManagerProperties.AgentProtocolSettings(true, " ", "", 7, 24, 5, "", true, 120, "redis");
        var ex = assertThrows(IllegalStateException.class,
            () -> new AgentProtocolConfig.ProtocolEnabledAssembly(blank));
        assertTrue(ex.getMessage().contains("AGENT_PROTOCOL_AUTH_TOKEN"),
            "报错应指向缺失的 token 环境变量: " + ex.getMessage());
    }

    /** enabled=true 且未配置 token 时整体装配失败（fail-fast 经 Spring 启动路径生效） */
    @Test
    void enabledContextWithoutTokenShouldFailToStart() {
        runner.withPropertyValues(
                "agent.agent-protocol.enabled=true",
                "agentscope.agent-protocol.enabled=true")
            .run(ctx -> {
                assertTrue(ctx.getStartupFailure() != null, "缺 token 必须启动失败");
                // BeanCreationException 只报「构造失败」，我们的 fail-fast 文案在 cause 链里
                var cause = ctx.getStartupFailure();
                while (cause != null && !(cause instanceof IllegalStateException)) {
                    cause = cause.getCause();
                }
                assertTrue(cause != null && cause.getMessage().contains("AGENT_PROTOCOL_AUTH_TOKEN"),
                    "fail-fast 异常应指向缺失的 token 环境变量");
            });
    }

    // ---------------- 双开关一致性 fail-fast（§6.2 端点存在 ⇒ token 强制） ----------------

    /**
     * SDK 键（agentscope.agent-protocol.enabled）开启而本键未开：SDK /tasks* 端点将
     * 无 AuthFilter 注册 → 启动期拒绝（外层 AgentProtocolConfig 构造期守卫）。
     */
    @Test
    void sdkSwitchEnabledWithoutAppSwitchShouldFailToStart() {
        runner.withPropertyValues("agentscope.agent-protocol.enabled=true")
            .run(ctx -> {
                assertTrue(ctx.getStartupFailure() != null, "SDK 端点无认证保护必须启动失败");
                var cause = ctx.getStartupFailure();
                while (cause != null && !(cause instanceof IllegalStateException)) {
                    cause = cause.getCause();
                }
                assertTrue(cause != null
                        && cause.getMessage().contains("agentscope.agent-protocol.enabled"),
                    "报错应指出 SDK 命名空间开关与 agent.agent-protocol.enabled 不一致: "
                        + (cause == null ? null : cause.getMessage()));
            });
    }

    /** 两键同开（常例，AGENT_PROTOCOL_ENABLED 双落点）：守卫放行 */
    @Test
    void dualSwitchGuardShouldPassWhenBothEnabled() {
        var env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty(AgentProtocolConfig.SDK_ENABLED_KEY, "true");
        env.setProperty(AgentProtocolConfig.APP_ENABLED_KEY, "true");
        assertDoesNotThrow(() -> new AgentProtocolConfig(env));
    }

    /** 两键均关（存量默认态）：守卫放行 */
    @Test
    void dualSwitchGuardShouldPassWhenBothAbsent() {
        assertDoesNotThrow(() -> new AgentProtocolConfig(
            new org.springframework.mock.env.MockEnvironment()));
    }

    /** 仅本键开启：fail-closed 方向（SDK 端点未注册，filter 空挂无害），不拒绝 */
    @Test
    void dualSwitchGuardShouldPassWhenOnlyAppSwitchEnabled() {
        var env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty(AgentProtocolConfig.APP_ENABLED_KEY, "true");
        assertDoesNotThrow(() -> new AgentProtocolConfig(env));
    }

    // ---------------- bean override 生效 ----------------

    /**
     * 自定义 AgentFactory / ProtocolTaskRepository 注册后，SDK 默认实现退位
     * （AgentProtocolAutoConfiguration 的 @ConditionalOnMissingBean，javap 实证）：
     * 容器内各只剩一个 bean，且行为指向本服务的运行时。
     */
    @Test
    void beanOverrideShouldReplaceSdkDefaultsWhenEnabled() {
        runner.withConfiguration(AutoConfigurations.of(AgentProtocolAutoConfiguration.class))
            .withPropertyValues(
                "agent.agent-protocol.enabled=true",
                "agent.agent-protocol.auth-token=test-token",
                "agentscope.agent-protocol.enabled=true")
            .run(ctx -> {
                assertFalse(ctx.getStartupFailure() != null, () -> "启动失败: " + ctx.getStartupFailure());

                // AgentFactory：全容器唯一，且 create 取 agentRuntimeService.getAgent() 当前值
                var factories = ctx.getBeansOfType(AgentFactory.class);
                assertEquals(1, factories.size(), "SDK 默认工厂必须退位，容器只保留我们的工厂");
                var runtime = ctx.getBean(AgentRuntimeService.class);
                assertSame(runtime.getAgent(), factories.values().iterator().next()
                    .create(new AgentRequest("t-1", "a-1", "hi", "u-1", "p-1", false, Map.of())));

                // ProtocolTaskRepository：全容器唯一，且包住当前 agent 的 WorkspaceManager（agent_fs 落库面）
                var repositories = ctx.getBeansOfType(ProtocolTaskRepository.class);
                assertEquals(1, repositories.size(), "SDK 默认本地 FS 仓库必须退位");
                var repository = repositories.values().iterator().next();
                assertInstanceOf(WorkspaceProtocolTaskRepository.class, repository);
                var agent = ctx.getBean(HarnessAgent.class);
                assertSame(agent.getWorkspaceManager(),
                    ((WorkspaceProtocolTaskRepository) repository).workspaceManager());

                // /tasks* 认证过滤器注册面：URL 精确覆盖端点全集（/tasks 与 /tasks/*）
                var authRegistration = ctx.getBeansOfType(FilterRegistrationBean.class).values().stream()
                    .filter(r -> r.getFilter() instanceof AgentProtocolAuthFilter)
                    .findFirst().orElseThrow(() -> new AssertionError("认证过滤器未注册"));
                assertEquals(java.util.Set.of("/tasks", "/tasks/*"), authRegistration.getUrlPatterns());
            });
    }

    /** 工厂语义：reload（swapAgent）后 create 应取到新 agent（F14 的回归锚点） */
    @Test
    void agentFactoryShouldFollowRuntimeServiceCurrentAgent() {
        runner.withPropertyValues(
                "agent.agent-protocol.enabled=true",
                "agent.agent-protocol.auth-token=test-token")
            .run(ctx -> {
                assertFalse(ctx.getStartupFailure() != null, () -> "启动失败: " + ctx.getStartupFailure());
                var factory = ctx.getBean(AgentFactory.class);
                var runtime = ctx.getBean(AgentRuntimeService.class);
                var before = factory.create(new AgentRequest("t", "a", "in", "u", "p", false, Map.of()));
                var next = harnessAgentMock();
                runtime.swapAgent(next);
                assertSame(next, factory.create(new AgentRequest("t", "a", "in", "u", "p", false, Map.of())),
                    "reload 后协议任务必须跑在新 agent 上");
                assertNotSame(before, next);
            });
    }

    // ---------------- enabled=false 零装配 ----------------

    /** 协议关闭（默认态）：协议 beans 与认证过滤器一个都不注册（存量零影响） */
    @Test
    void disabledShouldAssembleNothing() {
        runner.withConfiguration(AutoConfigurations.of(AgentProtocolAutoConfiguration.class))
            .run(ctx -> {
                assertFalse(ctx.getStartupFailure() != null, () -> "启动失败: " + ctx.getStartupFailure());
                assertTrue(ctx.getBeansOfType(AgentFactory.class).isEmpty());
                assertTrue(ctx.getBeansOfType(ProtocolTaskRepository.class).isEmpty());
                assertTrue(ctx.getBeansOfType(FilterRegistrationBean.class).isEmpty());
                // SDK 自动配置同样因 agentscope.agent-protocol.enabled 缺省 false 而不装配
                assertTrue(ctx.getBeansOfType(AgentProtocolTaskStore.class).isEmpty());
            });
    }

    // ---------------- 配置默认值（AgentManagerProperties 新配置组） ----------------

    /** 配置节缺失：全部叶子落代码默认值（enabled=false 保证存量零影响） */
    @Test
    void settingsShouldFallBackToDefaultsWhenSectionAbsent() {
        new ApplicationContextRunner()
            .withUserConfiguration(EnableSettings.class)
            .run(ctx -> {
                var settings = ctx.getBean(AgentManagerProperties.AgentProtocolSettings.class);
                assertFalse(settings.enabled());
                assertEquals("", settings.authToken());
                assertEquals("", settings.taskStore());
                assertEquals(7, settings.retentionDays());
                assertEquals(24, settings.remoteConfirmTtlHours());
                assertEquals(5, settings.remotePollSeconds());
                assertEquals("", settings.remoteHeadersJson());
            });
    }

    /** 显式配置必须能覆盖（运维改得动） */
    @Test
    void settingsShouldAcceptExplicitValues() {
        new ApplicationContextRunner()
            .withUserConfiguration(EnableSettings.class)
            .withPropertyValues(
                "agent.agent-protocol.enabled=true",
                "agent.agent-protocol.auth-token=tok",
                "agent.agent-protocol.task-store=/tmp/tasks",
                "agent.agent-protocol.retention-days=3",
                "agent.agent-protocol.remote-confirm-ttl-hours=48",
                "agent.agent-protocol.remote-poll-seconds=10",
                "agent.agent-protocol.remote-headers-json={\"X-Token\":\"v\"}")
            .run(ctx -> {
                var settings = ctx.getBean(AgentManagerProperties.AgentProtocolSettings.class);
                assertTrue(settings.enabled());
                assertEquals("tok", settings.authToken());
                assertEquals("/tmp/tasks", settings.taskStore());
                assertEquals(3, settings.retentionDays());
                assertEquals(48, settings.remoteConfirmTtlHours());
                assertEquals(10, settings.remotePollSeconds());
                assertEquals("{\"X-Token\":\"v\"}", settings.remoteHeadersJson());
            });
    }

    /**
     * AgentManagerProperties 兼容构造器（旧 9 参按位构造）：协议组缺省为默认值——
     * 保住仓库既有测试的按位构造写法不因新增组件而变编译错误。
     */
    @Test
    void agentManagerPropertiesLegacyConstructorShouldDefaultProtocolGroup() {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("", "", "", "openai", 0.7, 4096, 120,
                false, 0, "", null),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://127.0.0.1:3307/db",
                "u", "p", ""),
            "/config", "", null, null, null, null);
        assertEquals(AgentManagerProperties.AgentProtocolSettings.defaults(), props.agentProtocol());
        assertFalse(props.agentProtocol().enabled());
    }

    /** 完整绑定路径：AgentManagerProperties.agentProtocol 经 Binder 正常落到新组件 */
    @Test
    void agentManagerPropertiesShouldBindAgentProtocolGroup() {
        new ApplicationContextRunner()
            .withUserConfiguration(EnableAgentManagerProperties.class)
            .withPropertyValues(
                "agent.agent-protocol.enabled=true",
                "agent.agent-protocol.auth-token=tok",
                "agent.agent-protocol.retention-days=14")
            .run(ctx -> {
                assertFalse(ctx.getStartupFailure() != null, () -> "启动失败: " + ctx.getStartupFailure());
                var props = ctx.getBean(AgentManagerProperties.class);
                assertTrue(props.agentProtocol().enabled());
                assertEquals("tok", props.agentProtocol().authToken());
                assertEquals(14, props.agentProtocol().retentionDays());
            });
    }
}
