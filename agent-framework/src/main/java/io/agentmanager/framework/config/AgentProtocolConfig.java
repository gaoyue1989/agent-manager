package io.agentmanager.framework.config;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.protocol.AgentProtocolAuthFilter;
import io.agentscope.extensions.agentprotocol.AgentFactory;
import io.agentscope.extensions.agentprotocol.ProtocolTaskRepository;
import io.agentscope.extensions.agentprotocol.WorkspaceProtocolTaskRepository;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

// 嵌套属性 record 的单类型导入：同一包内也需显式导入才能以短名引用
import io.agentmanager.framework.config.AgentManagerProperties.AgentProtocolSettings;

/**
 * Agent Protocol 服务端装配（远程子 agent 面，travel-fulfillment agent-protocol 设计
 * §6.2/§7/§8 member）：所有 OAF 服务获得「可被远程调度」能力，默认关闭、存量零影响。
 *
 * <p>条件装配挂在嵌套类 {@link ProtocolEnabledAssembly}（agent.agent-protocol.enabled=true
 * 才注册协议 beans），外层类常驻——协议关闭时 {@link AgentManagerProperties.AgentProtocolSettings}
 * 仍正常绑定，供 {@code InfoController} 透出 enabled=false 状态。
 *
 * <p>两个 bean override 均利用 SDK 自动配置的 {@code @ConditionalOnMissingBean} 退位机制
 * （AgentProtocolAutoConfiguration 2.0.3，javap 实证）：
 * <ul>
 *   <li>{@code ProtocolTaskRepository}：默认实现落容器本地 FS（重启即丢、副本不可见），
 *       此处传入本服务 HarnessAgent 的 WorkspaceManager，TaskRecord 借 DistributedStore
 *       落 agent_fs（MySQL，跨重启可 resume、跨副本可见，设计 §7；禁指只读 /config）；</li>
 *   <li>{@code AgentFactory}：默认工厂拿 Spring bean 引用，而 OAF reload 的 swapAgent 只换
 *       volatile 引用不换 bean——不覆盖则 reload 后协议任务全部跑在旧 agent 实例（设计 F14）。
 *       此处复刻 {@code A2aAgentRefHolder} 的间接持有模式，每次运行取
 *       {@code agentRuntimeService.getAgent()} 当前值。</li>
 * </ul>
 *
 * <p>fail-fast（设计 §6.2，第二轮 P1-1）×2：① enabled=true 而 authToken 空白 → 启动抛异常；
 * ② SDK 键（agentscope.agent-protocol.enabled）开启而本键未开 → 启动抛异常（见构造器
 * javadoc）。协议端点无内建认证，叠加业务 Ingress 全量路由等于未防护的远程调度入口，
 * 两条路径都不允许静默放行。
 *
 * <p>配置组 {@code AgentProtocolSettings}（agent.agent-protocol.*）定义在
 * {@link AgentManagerProperties} 内，经 {@code @EnableConfigurationProperties} 在此
 * 常驻注册（协议关闭时也正常绑定，供 InfoController 透出 enabled=false 状态）。
 */
@Configuration
@EnableConfigurationProperties(AgentManagerProperties.AgentProtocolSettings.class)
public class AgentProtocolConfig {
    private static final Logger log = LoggerFactory.getLogger(AgentProtocolConfig.class);

    /** SDK 端点注册键（AgentProtocolAutoConfiguration @ConditionalOnProperty，2.0.3 字节码实证） */
    static final String SDK_ENABLED_KEY = "agentscope.agent-protocol.enabled";
    /** 本项目装配键（fail-fast 与 AuthFilter 的装配条件） */
    static final String APP_ENABLED_KEY = "agent.agent-protocol.enabled";

    /**
     * 双开关一致性 fail-fast（设计 §6.2 P1-1 安全不变量）：SDK 端点按
     * {@code agentscope.agent-protocol.enabled} 注册，而认证 filter 与 token fail-fast 按
     * {@code agent.agent-protocol.enabled} 装配。application.yml 让两键同绑
     * AGENT_PROTOCOL_ENABLED 常例一致，但任一直接设置 SDK 命名空间（JVM -D / profile yml /
     * 属性源覆盖，优先级均高于 yml）会得到无 AuthFilter、无 token fail-fast 的 /tasks* 端点
     * ——恰是 §6.2 要封死的未防护远程调度面。「端点存在 ⇒ token 强制」必须结构性成立：
     * SDK 键开启而本键未开启时拒绝启动（反方向不拒——本键开而 SDK 键关属 fail-closed，
     * filter 空挂无害）。本构造在端点 bean 实例化前执行，拒绝发生在对外服务之前。
     */
    public AgentProtocolConfig(Environment environment) {
        if (isEnabled(environment, SDK_ENABLED_KEY) && !isEnabled(environment, APP_ENABLED_KEY)) {
            throw new IllegalStateException(
                "检测到 " + SDK_ENABLED_KEY + "=true 而 " + APP_ENABLED_KEY + "!=true：SDK /tasks* "
                    + "端点将注册但缺少 AgentProtocolAuthFilter 与 token fail-fast 保护（设计 §6.2 "
                    + "未防护远程调度面，不允许启动）。两开关由 AGENT_PROTOCOL_ENABLED 同源驱动，请勿"
                    + "单独覆盖 SDK 命名空间；确需启用请设置 AGENT_PROTOCOL_ENABLED=true 并配置 "
                    + "AGENT_PROTOCOL_AUTH_TOKEN");
        }
    }

    private static boolean isEnabled(Environment environment, String key) {
        return Boolean.parseBoolean(environment.getProperty(key, "false"));
    }

    /**
     * 条件装配：agent.agent-protocol.enabled=true 时注册协议 beans（缺省/显式 false 均不装配，
     * 存量服务零影响）。构造期完成 fail-fast 校验（本类仅在该条件下被实例化）。
     */
    @Configuration
    @ConditionalOnProperty(prefix = "agent.agent-protocol", name = "enabled", havingValue = "true")
    static class ProtocolEnabledAssembly {

        private final AgentProtocolSettings settings;

        ProtocolEnabledAssembly(AgentProtocolSettings settings) {
            requireAuthToken(settings);
            this.settings = settings;
        }

        /** 认证 token fail-fast 校验：enabled=true 时 authToken 必须非空白（设计 §6.2 M1 强制措施） */
        static void requireAuthToken(AgentProtocolSettings settings) {
            if (settings == null || settings.authToken() == null || settings.authToken().isBlank()) {
                throw new IllegalStateException(
                    "AGENT_PROTOCOL_ENABLED=true 时必须配置 AGENT_PROTOCOL_AUTH_TOKEN（服务间认证，"
                        + "设计 docs/design/travel-fulfillment-agent-protocol-design.md §6.2）；"
                        + "未启用远程调度请保持 AGENT_PROTOCOL_ENABLED=false");
            }
        }

        /**
         * AgentFactory：协议任务（提交 + 每次 resume）运行时取当前 agent。
         * 经 AgentRuntimeService 的 volatile 引用间接持有，OAF reload 整包重建后
         * 协议任务自动路由到新 agent（与 A2A 链路同源语义）。
         */
        @Bean
        public AgentFactory agentProtocolAgentFactory(AgentRuntimeService agentRuntimeService) {
            log.info("Agent Protocol enabled: AgentFactory -> agentRuntimeService.getAgent() (reload-safe)");
            return request -> agentRuntimeService.getAgent();
        }

        /**
         * deny_rules 自实现第一环（设计 F16/§6.1：SDK 扩展不消费 context.deny_rules）：
         * 经 SDK 自动配置收集进 AgentProtocolTaskStore，每次协议任务构建 RuntimeContext 时
         * 把父级下传的 DENY 规则挂上任务属性，由常驻的 ProtocolDenyRulesMiddleware 拦截执行。
         */
        @Bean
        public io.agentscope.extensions.agentprotocol.RuntimeContextCustomizer
                protocolDenyRulesContextCustomizer() {
            log.info("Agent Protocol enabled: RuntimeContextCustomizer -> context.deny_rules 动态 DENY");
            return new io.agentmanager.framework.service.protocol.ProtocolDenyRulesContextCustomizer();
        }

        /**
         * TaskRepository bean override：TaskRecord 落 agent_fs（经本服务 HarnessAgent 的
         * WorkspaceManager → DistributedStore）。捕获启动实例的 WorkspaceManager 是安全的：
         * reload 不关闭旧 WorkspaceManager（仅收尾 MCP 连接），且新旧实例指向同一
         * DistributedStore bean（同一 agent_fs 表）。
         */
        @Bean
        public ProtocolTaskRepository agentProtocolTaskRepository(HarnessAgent harnessAgent) {
            var repository = new WorkspaceProtocolTaskRepository(harnessAgent.getWorkspaceManager());
            log.info("Agent Protocol enabled: ProtocolTaskRepository -> agent_fs (WorkspaceProtocolTaskRepository)");
            return repository;
        }

        /**
         * /tasks* 服务间认证过滤器注册（URL 限 /tasks 与 /tasks/*，不影响其他端点）。
         * enabled=false 时本装配类不实例化，filter 整体不存在（存量行为零变化）。
         */
        @Bean
        public FilterRegistrationBean<AgentProtocolAuthFilter> agentProtocolAuthFilter() {
            var filter = new AgentProtocolAuthFilter(settings.authToken());
            var registration = new FilterRegistrationBean<>(filter);
            registration.setName("agentProtocolAuthFilter");
            registration.addUrlPatterns("/tasks", "/tasks/*");
            // 在 MDC/userId 等前置过滤器之后执行；认证失败短路，不进入业务链
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 150);
            return registration;
        }
    }
}
