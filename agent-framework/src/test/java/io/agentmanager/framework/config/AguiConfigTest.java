package io.agentmanager.framework.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.agentmanager.framework.controller.AguiEventCodec;
import io.agentmanager.framework.controller.AguiRunController;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionEventTailer;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.SkillInjectionService;
import io.agentmanager.framework.service.ToolAuditStore;
import io.agentmanager.framework.service.TurnLeaseStore;

/**
 * /agui 默认关闭的装配守卫（agui-native-parity-design §10，env-up.sh：关闭时 /agui/* 404）。
 *
 * <p>{@link AguiConfig} 与 {@link AguiRunController} 均为
 * {@code @ConditionalOnProperty(name="agent.agui.enabled", havingValue="true")}，application.yml
 * 默认 {@code ${AGENT_AGUI_ENABLED:false}}——生产默认部署不得装配该输出面（约 746 行协议端点）。
 * 若有人移除任一处条件注解或新增无守卫的 agui 端点，此处变红。
 *
 * <p>施工口径：AguiRunController 在真应用走组件扫描，ApplicationContextRunner 里必须以
 * {@code withUserConfiguration(AguiRunController.class)} 注册，类级条件才会被求值——
 * withBean(supplier) 直注册会绕过条件（A2aJobAssemblyTest disabledShouldRegisterNoAssemblyBeans
 * 注释自证）。禁用态条件先行拒绝控制器，依赖 bean 不参与装配，两用例共享同一 runner。
 */
class AguiConfigTest {

    /** 控制器 10 参构造的依赖打桩（全部非 final public class，Mockito 可 mock）；
     *  AguiEventCodec 由开启态 AguiConfig 自产，AgentManagerProperties 按既有测试口径 9 参构造 */
    @Configuration
    static class ControllerDeps {
        @Bean
        AgentManagerProperties agentManagerProperties() {
            return new AgentManagerProperties(
                mock(AgentManagerProperties.LLMConfig.class),
                mock(AgentManagerProperties.ServerConfig.class),
                mock(AgentManagerProperties.CheckpointConfig.class),
                "/test", "",
                mock(AgentManagerProperties.CleanupConfig.class),
                mock(AgentManagerProperties.FileConfig.class),
                new AgentManagerProperties.SseConfig(20, 5, 256, 300),
                AgentManagerProperties.HarnessConfig.defaults());
        }

        @Bean AgentRuntimeService runtimeService() { return mock(AgentRuntimeService.class); }
        @Bean TurnLeaseStore turnLeaseStore() { return mock(TurnLeaseStore.class); }
        @Bean SessionEventBus eventBus() { return mock(SessionEventBus.class); }
        @Bean SessionEventTailer tailer() { return mock(SessionEventTailer.class); }
        @Bean SessionUserStore sessionUserStore() { return mock(SessionUserStore.class); }
        @Bean SkillInjectionService skillInjectionService() { return mock(SkillInjectionService.class); }
        @Bean ToolAuditStore toolAuditStore() { return mock(ToolAuditStore.class); }
        @Bean McpToolRegistrar mcpToolRegistrar() { return mock(McpToolRegistrar.class); }
    }

    /** AguiConfig 为自动配置候选 + 控制器按组件扫描同形态注册（类级条件被上下文求值） */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AguiConfig.class))
        .withUserConfiguration(ControllerDeps.class, AguiRunController.class);

    /** 核心守卫：缺省（AGENT_AGUI_ENABLED 未设）→ 控制器/codec/properties 零装配 */
    @Test
    void disabledShouldAssembleNothing() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(AguiRunController.class);
            assertThat(ctx).doesNotHaveBean(AguiEventCodec.class);
            assertThat(ctx).doesNotHaveBean(AguiProperties.class);
        });
    }

    /** 反向守卫：显式开启 → 控制器与 codec/properties 全部装配（条件只挡关闭态，不挡功能） */
    @Test
    void enabledShouldAssembleCodecAndController() {
        runner.withPropertyValues("agent.agui.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(AguiRunController.class);
            assertThat(ctx).hasSingleBean(AguiEventCodec.class);
            assertThat(ctx).hasSingleBean(AguiProperties.class);
        });
    }
}
