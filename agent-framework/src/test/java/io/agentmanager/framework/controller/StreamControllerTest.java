package io.agentmanager.framework.controller;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StreamController.class)
class StreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatUiChannel chatChannel;

    @Test
    void chatStreamShouldReturnSseEvents() throws Exception {
        var delta = new TextBlockDeltaEvent("reply-1", "block-1", "Hello ");
        when(chatChannel.sendStream(any(SendOptions.class), anyString()))
            .thenReturn(Flux.just(delta));

        mockMvc.perform(get("/chat/stream")
                .param("message", "hello")
                .param("userId", "alice"))
            .andExpect(status().isOk());
    }

    @Test
    void chatStreamShouldHandleEmptyMessage() throws Exception {
        when(chatChannel.sendStream(any(SendOptions.class), anyString()))
            .thenReturn(Flux.empty());

        mockMvc.perform(get("/chat/stream")
                .param("message", "")
                .param("userId", "alice"))
            .andExpect(status().isOk());
    }
}
