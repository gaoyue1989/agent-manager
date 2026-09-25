package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;

import org.junit.jupiter.api.Test;

class ChannelConfigTest {

    private final ChannelConfig config = new ChannelConfig();

    @Test
    void chatUiChannelShouldCreateChannel() {
        var agent = mock(HarnessAgent.class);
        var channel = ChatUiChannel.create();
        when(agent.channel(any(ChatUiChannel.class))).thenReturn(channel);

        assertSame(channel, config.chatUiChannel(agent));
    }

    @Test
    void providerShouldFollowCurrentAgentAfterReload() {
        var runtimeService = mock(AgentRuntimeService.class);
        var oldAgent = mock(HarnessAgent.class);
        var newAgent = mock(HarnessAgent.class);
        var oldChannel = ChatUiChannel.create();
        var newChannel = ChatUiChannel.create();
        when(runtimeService.getAgent()).thenReturn(oldAgent, newAgent);
        when(oldAgent.channel(any(ChatUiChannel.class))).thenReturn(oldChannel);
        when(newAgent.channel(any(ChatUiChannel.class))).thenReturn(newChannel);

        var provider = config.chatUiChannelProvider(runtimeService);

        assertSame(oldChannel, provider.current());
        assertSame(newChannel, provider.current());
        verify(oldAgent).channel(any(ChatUiChannel.class));
        verify(newAgent).channel(any(ChatUiChannel.class));
    }
}