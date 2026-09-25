package io.agentmanager.framework.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;

@Configuration
public class ChannelConfig {

    /** 启动期 Channel：保留兼容旧注入点；动态对话入口使用下方 provider。 */
    @Bean
    public ChatUiChannel chatUiChannel(HarnessAgent agent) {
        return agent.channel(ChatUiChannel.create());
    }

    /**
     * 当前 Agent 的 Channel 工厂：每轮对话从 AgentRuntimeService 当前引用创建，
     * 避免 OAF reload 后 ChatStreamController 继续复用启动期 Agent 的旧 Channel。
     */
    @Bean
    public ChatUiChannelProvider chatUiChannelProvider(AgentRuntimeService runtimeService) {
        return () -> {
            var agent = runtimeService.getAgent();
            return agent == null ? null : agent.channel(ChatUiChannel.create());
        };
    }
}
