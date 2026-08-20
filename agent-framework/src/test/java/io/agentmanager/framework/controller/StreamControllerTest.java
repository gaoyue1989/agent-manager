package io.agentmanager.framework.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.UiContextStore;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StreamControllerTest {

    private StreamController controller;
    private ChatUiChannel chatChannel;
    private McpToolRegistrar mcpToolRegistrar;

    @BeforeEach
    void setUp() {
        chatChannel = mock(ChatUiChannel.class);
        mcpToolRegistrar = mock(McpToolRegistrar.class);
        controller = new StreamController(chatChannel, mcpToolRegistrar);
    }

    @Test
    void chatStreamShouldReturnSseEvents() throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        var delta = new TextBlockDeltaEvent("reply-1", "block-1", "Hello ");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just(delta));

        mockMvc.perform(get("/chat/stream")
                .param("message", "hello")
                .param("userId", "alice"))
            .andExpect(status().isOk());
    }

    @Test
    void chatStreamShouldHandleEmptyMessage() throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.empty());

        mockMvc.perform(get("/chat/stream")
                .param("message", "")
                .param("userId", "alice"))
            .andExpect(status().isOk());
    }

    @Test
    void chatStreamShouldForwardThinkingBlockDelta() {
        var delta = new ThinkingBlockDeltaEvent("reply-1", "think-1", "thinking");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just(delta));

        var body = controller.chatStream("hello", "alice", null, null)
            .blockLast().data();

        assertTrue(body.contains("THINKING_BLOCK_DELTA"));
        assertTrue(body.contains("\"delta\":\"thinking\""));
    }

    @Test
    void chatStreamShouldForwardToolCallDelta() {
        var delta = new ToolCallDeltaEvent("reply-1", "tc-1", "read_file", "{\"path\":");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
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
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
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
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just(end));

        var body = controller.chatStream("hello", "alice", null, null)
            .blockLast().data();

        assertTrue(body.contains("MODEL_CALL_END"));
        assertTrue(body.contains("\"inputTokens\":100"));
        assertTrue(body.contains("\"outputTokens\":50"));
    }

    // ===== MCP Apps 4.7: ui_context 注入（Controller 传 session key，Hook 注入） =====

    @Test
    void chatStreamShouldAttachSessionKeyToUserMessage() {
        var delta = new TextBlockDeltaEvent("reply-1", "block-1", "ok");
        when(chatChannel.sendStream(any(ChatUiRequest.class))).thenReturn(Flux.just(delta));

        controller.chatStream("hello", "alice", "test-user:s1", null).blockLast();

        var captor = org.mockito.ArgumentCaptor.forClass(ChatUiRequest.class);
        verify(chatChannel).sendStream(captor.capture());
        var messages = captor.getValue().messages();
        assertEquals(1, messages.size(), "消息列表仅用户消息（注入逻辑在 Hook 层）");
        assertEquals(MsgRole.USER, messages.get(0).getRole());
        assertEquals("test-user:s1",
            messages.get(0).getMetadata().get(UiContextStore.METADATA_SESSION_KEY),
            "用户消息 metadata 应携带会话 key，供 UiContextInjectionHook 读取");
        assertEquals("test-user:s1", captor.getValue().peerId());
    }

    @Test
    void chatStreamShouldSkipMetadataWithoutSessionId() {
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just(new TextBlockDeltaEvent("r", "b", "ok")));

        controller.chatStream("hello", "alice", null, null).blockLast();

        var captor = org.mockito.ArgumentCaptor.forClass(ChatUiRequest.class);
        verify(chatChannel).sendStream(captor.capture());
        var messages = captor.getValue().messages();
        assertEquals(1, messages.size(), "无 sessionId 时不传会话 key");
        assertEquals(null,
            messages.get(0).getMetadata().get(UiContextStore.METADATA_SESSION_KEY),
            "无 sessionId 时 metadata 不含会话 key");
        assertEquals("alice", captor.getValue().peerId(), "peerId 回落 userId");
    }
}
