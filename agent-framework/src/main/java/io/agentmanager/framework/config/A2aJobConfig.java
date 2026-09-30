package io.agentmanager.framework.config;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.agentmanager.framework.service.a2ajob.A2aJobAuthFilter;

/**
 * A2A 幂等 Job 装配（Issue #69 §2.3；member 侧 `/a2a/jobs`，默认关闭存量零影响）。
 *
 * <p>条件装配挂在嵌套类（{@code agent.a2a-job.enabled=true} 才注册），形态照抄
 * {@code AgentProtocolConfig}：构造期 token fail-fast（enabled=true 而 authToken 空白
 * 拒绝启动）+ Redis 启动自检（Job 路径硬依赖 Redis，fail-fast 优于首请求报错——
 * 运行中故障仍走 503 fail-closed，此处只保证「配了 Job 但 Redis 根本连不上」的部署
 * 错误早暴露）。
 */
@Configuration
@EnableConfigurationProperties(AgentA2aJobProperties.class)
public class A2aJobConfig {
    private static final Logger log = LoggerFactory.getLogger(A2aJobConfig.class);

    /**
     * token fail-fast：enabled=true 而 authToken 空白 → 启动失败（对外无认证的
     * 远程触发面不允许静默放行，同 AgentProtocolConfig.requireAuthToken 语义）。
     */
    static void requireAuthToken(AgentA2aJobProperties props) {
        if (props == null || props.authToken() == null || props.authToken().isBlank()) {
            throw new IllegalStateException(
                "AGENT_A2A_JOB_ENABLED=true 时必须配置 AGENT_A2A_JOB_TOKEN（/a2a/jobs 入口认证，"
                    + "Issue #69 §2.3）；未启用 A2A Job 请保持 AGENT_A2A_JOB_ENABLED=false");
        }
    }

    /**
     * 条件装配：agent.a2a-job.enabled=true 时注册端点与过滤器（缺省/显式 false 均不装配）。
     */
    @Configuration
    @ConditionalOnProperty(prefix = "agent.a2a-job", name = "enabled", havingValue = "true")
    static class A2aJobEnabledAssembly {

        private final AgentA2aJobProperties props;

        A2aJobEnabledAssembly(AgentA2aJobProperties props) {
            requireAuthToken(props);
            this.props = props;
        }

        /**
         * Redis 启动自检：PING 通才算就绪（Job 硬依赖；失败拒绝启动）。自检用独立短连接，
         * 不占用门面连接池；异常时抛出 → bean 创建失败 → 启动失败（fail-fast）。
         */
        @Bean
        public AtomicReference<Boolean> a2aJobRedisSelfCheck(RedisConnectionFacade facade) {
            var result = new AtomicReference<>(Boolean.FALSE);
            try {
                String pong = facade.sync().ping();
                result.set("PONG".equalsIgnoreCase(pong));
            } catch (Exception e) {
                throw new IllegalStateException(
                    "AGENT_A2A_JOB_ENABLED=true 但 Redis 启动自检失败（Job 路径硬依赖 Redis，"
                        + "Issue #69 §2.3）: " + e.getMessage(), e);
            }
            log.info("A2A Job enabled: redis self-check ok (maxConcurrent={}, sendTimeout={}s, retention={}h)",
                props.maxConcurrent(), props.sendTimeoutSeconds(), props.retentionHours());
            return result;
        }

        /** /a2a/jobs* 认证过滤器（URL 限定，不影响其他端点） */
        @Bean
        public FilterRegistrationBean<A2aJobAuthFilter> a2aJobAuthFilter() {
            var filter = new A2aJobAuthFilter(props.authToken());
            var registration = new FilterRegistrationBean<>(filter);
            registration.setName("a2aJobAuthFilter");
            registration.addUrlPatterns("/a2a/jobs", "/a2a/jobs/*");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 160);
            return registration;
        }
    }
}
