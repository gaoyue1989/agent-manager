package io.agentmanager.framework.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StreamControllerTest {

    private StreamController controller;
    private ChatUiChannel chatChannel;

    @BeforeEach
    void setUp() {
        chatChannel = mock(ChatUiChannel.class);
        controller = new StreamController(chatChannel);
    }

    @Test
    void chatStreamShouldReturnSseEvents() throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(chatChannel.sendStream(any(SendOptions.class), anyString()))
            .thenReturn(Flux.empty());

        mockMvc.perform(get("/chat/stream")
                .param("message", "")
                .param("userId", "alice"))
            .andExpect(status().isOk());
    }

    @Test
    void chatStreamShouldForwardThinkingBlockDelta() {
        var delta = new ThinkingBlockDeltaEvent("reply-1", "think-1", "thinking");
        when(chatChannel.sendStream(any(SendOptions.class), anyString()))
            .thenReturn(Flux.just(delta));

        var body = controller.chatStream("hello", "alice", null, null)
            .blockLast().data();

        assertTrue(body.contains("THINKING_BLOCK_DELTA"));
        assertTrue(body.contains("\"delta\":\"thinking\""));
    }

    @Test
    void chatStreamShouldForwardToolCallDelta() {
        var delta = new ToolCallDeltaEvent("reply-1", "tc-1", "read_file", "{\"path\":");
        when(chatChannel.sendStream(any(SendOptions.class), anyString()))
            .thenReturn(Flux.just(delta));

        var body = controller.chatStream("hello", "alice", null, null)
            .blockLast().data();

        assertTrue(body.contains("TOOL_CALL_DELTA"));
        assertTrue(body.contains("\"toolCallId\":\"tc-1\""));
        assertTrue(body.contains("\"delta\":\"{\\\"path\\\":\""));
    }

    @Test
    void chatStreamShouldForwardToolResultTextDelta() {
        var delta = new ToolResultTextDeltaEvent("reply-1", "tc-1", "read_file", "file content");
        when(chatChannel.sendStream(any(SendOptions.class), anyString()))
            .thenReturn(Flux.just(delta));

        var body = controller.chatStream("hello", "alice", null, null)
            .blockLast().data();

        assertTrue(body.contains("TOOL_RESULT_TEXT_DELTA"));
        assertTrue(body.contains("\"toolCallId\":\"tc-1\""));
        assertTrue(body.contains("\"delta\":\"file content\""));
    }

    @Test
    void chatStreamShouldForwardModelCallUsage() {
        var usage = new io.agentscope.core.model.ChatUsage(100, 50, 150, 1.2);
        var end = new ModelCallEndEvent("reply-1", usage);
        when(chatChannel.sendStream(any(SendOptions.class), anyString()))
            .thenReturn(Flux.just(end));

        var body = controller.chatStream("hello", "alice", null, null)
            .blockLast().data();

        assertTrue(body.contains("MODEL_CALL_END"));
        assertTrue(body.contains("\"inputTokens\":100"));
        assertTrue(body.contains("\"outputTokens\":50"));
    }
}
