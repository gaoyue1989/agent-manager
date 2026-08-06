package io.agentmanager.framework.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;

@Configuration
public class ChannelConfig {

    @Bean
    public ChatUiChannel chatUiChannel(HarnessAgent agent) {
        return agent.channel(ChatUiChannel.create());
    }
}
