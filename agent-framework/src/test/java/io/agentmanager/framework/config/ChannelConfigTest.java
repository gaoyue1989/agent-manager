package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
}