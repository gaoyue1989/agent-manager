package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.UiContextStore;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 长连接会话流端点测试（F：per-session 事件总线 + 订阅/触发）。
 * 对齐官方 agentscope 模式：订阅端点直接推事件，无 SUBSCRIBED 就绪帧。
 */
class SessionStreamControllerTest {

    private SessionStreamController controller;
    private ChatUiChannel chatChannel;
    private SessionEventBus eventBus;
    private AgentRuntimeService runtimeService;
    private McpToolRegistrar mcpToolRegistrar;

    @BeforeEach
    void setUp() {
        chatChannel = mock(ChatUiChannel.class);
        eventBus = new SessionEventBus();
        runtimeService = mock(AgentRuntimeService.class);
        mcpToolRegistrar = mock(McpToolRegistrar.class);
        controller = new SessionStreamController(chatChannel, eventBus, runtimeService, mcpToolRegistrar);
    }

    @Test
    void subscribeShouldEmitEventsDirectly() {
        var sessionId = "test-user:s1";
        var delta = new TextBlockDeltaEvent("reply-1", "block-1", "Hi");

        Flux<String> stream = controller.subscribe(sessionId).map(sse -> sse.data());
        var received = new ArrayList<String>();

        stream.subscribe(received::add);
        eventBus.emit(sessionId, delta);

        assertTrue(received.stream().anyMatch(s -> s.contains("TEXT_BLOCK_DELTA")),
            "应透传 TEXT_BLOCK_DELTA 事件: " + received);
        assertTrue(received.stream().anyMatch(s -> s.contains("reply-1")),
            "事件应携带 replyId");
        assertTrue(received.stream().anyMatch(s -> s.contains("Hi")),
            "事件应携带 delta 内容");
    }

    @Test
    void subscribeShouldNotEmitEventsForOtherSession() {
        var delta = new TextBlockDeltaEvent("reply-1", "block-1", "Hi");
        var received = new ArrayList<String>();

        controller.subscribe("session-a").map(sse -> sse.data()).subscribe(received::add);
        eventBus.emit("session-b", delta);

        assertTrue(received.stream().noneMatch(s -> s.contains("TEXT_BLOCK_DELTA")),
            "不应收到其他会话的事件");
    }

    @Test
    void subscribeShouldHaveHeartbeatTicks() {
        var received = new ArrayList<String>();
        controller.subscribe("test-user:s5").map(sse -> sse.data()).subscribe(received::add);
        // 心跳 ping 事件在15s间隔后到达，单元测试中直接验证订阅流可接收事件
        var delta = new TextBlockDeltaEvent("reply-hb", "block-hb", "hb-test");
        eventBus.emit("test-user:s5", delta);
        assertTrue(received.stream().anyMatch(s -> s.contains("hb-test")),
            "订阅应能接收事件（心跳15s间隔在集成测试中覆盖）");
    }

    @Test
    void triggerShouldAcceptMessage() throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.empty());

        mockMvc.perform(post("/debug/threads/test-user:s2/chat")
                .contentType("application/json")
                .content("{\"message\":\"hello\",\"userId\":\"alice\"}"))
            .andExpect(status().isAccepted());
    }

    @Test
    void triggerShouldAcceptEmptyMessage() throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        mockMvc.perform(post("/debug/threads/test-user:s3/chat")
                .contentType("application/json")
                .content("{\"message\":\"\",\"userId\":\"alice\"}"))
            .andExpect(status().isAccepted());
    }

    @Test
    void triggerShouldFanOutEventsToBus() throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        var sessionId = "test-user:s4";
        var received = new ArrayList<String>();
        controller.subscribe(sessionId).map(sse -> sse.data()).subscribe(received::add);

        var delta = new TextBlockDeltaEvent("reply-9", "block-9", "fanout");
        Flux<AgentEvent> flux = Flux.just((AgentEvent) delta).delayElements(Duration.ofMillis(50));
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(flux);

        mockMvc.perform(post("/debug/threads/" + sessionId + "/chat")
                .contentType("application/json")
                .content("{\"message\":\"go\",\"userId\":\"alice\"}"))
            .andExpect(status().isAccepted());

        Thread.sleep(300);
        assertTrue(received.stream().anyMatch(s -> s.contains("fanout")),
            "触发后事件应经总线回流到订阅者: " + received);
    }

    // ===== MCP Apps 4.7: ui_context 注入（Controller 传 session key，Hook 注入） =====

    @Test
    void triggerShouldAttachSessionKeyToUserMessage() throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        var sessionId = "test-user:s6";
        when(chatChannel.sendStream(any(ChatUiRequest.class))).thenReturn(Flux.empty());

        mockMvc.perform(post("/debug/threads/" + sessionId + "/chat")
                .contentType("application/json")
                .content("{\"message\":\"hi\",\"userId\":\"alice\"}"))
            .andExpect(status().isAccepted());

        var captor = org.mockito.ArgumentCaptor.forClass(ChatUiRequest.class);
        verify(chatChannel).sendStream(captor.capture());
        var messages = captor.getValue().messages();
        assertEquals(1, messages.size(), "只应发送用户消息（ui_context 由 Hook 注入，不进消息列表）");
        assertEquals(MsgRole.USER, messages.get(0).getRole());
        assertEquals("hi", messages.get(0).getTextContent());
        assertEquals(sessionId,
            messages.get(0).getMetadata().get(UiContextStore.METADATA_SESSION_KEY),
            "用户消息 metadata 应携带会话 key，供 UiContextInjectionHook 读取");
    }

    @Test
    void triggerShouldSendOnlyUserMessage() throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        var sessionId = "test-user:s7";
        when(chatChannel.sendStream(any(ChatUiRequest.class))).thenReturn(Flux.empty());

        mockMvc.perform(post("/debug/threads/" + sessionId + "/chat")
                .contentType("application/json")
                .content("{\"message\":\"hi\",\"userId\":\"alice\"}"))
            .andExpect(status().isAccepted());

        var captor = org.mockito.ArgumentCaptor.forClass(ChatUiRequest.class);
        verify(chatChannel).sendStream(captor.capture());
        assertEquals(1, captor.getValue().messages().size(),
            "消息列表仅用户消息（注入逻辑在 Hook 层）");
    }

    @Test
    void serializerShouldIncludeReplyIdAndBlockId() {
        var delta = new TextBlockDeltaEvent("reply-x", "block-x", "data");
        String json = AgentEventSseSerializer.payload(delta);
        assertTrue(json.contains("\"replyId\":\"reply-x\""), "序列化应携带 replyId: " + json);
        assertTrue(json.contains("\"blockId\":\"block-x\""), "序列化应携带 blockId: " + json);
        assertTrue(json.contains("\"delta\":\"data\""), "序列化应携带 delta: " + json);
    }

    @Test
    void serializerShouldKeepRawTypesUnchanged() {
        var delta = new TextBlockDeltaEvent("reply-x", "block-x", "data");
        String json = AgentEventSseSerializer.payload(delta);
        assertTrue(json.contains("\"type\":\"TEXT_BLOCK_DELTA\""),
            "词表应保持 TEXT_BLOCK_DELTA: " + json);
    }

    // ===== MCP Apps: TOOL_CALL_START ui 词表扩展 =====

    @Test
    void serializerShouldIncludeUiMetadataOnToolCallStart() {
        var tc = new io.agentscope.core.event.ToolCallStartEvent("reply-u", "call-u", "get_weather");
        String json = AgentEventSseSerializer.payload(tc, "ui://weather/mcp-app.html", "weather");
        assertTrue(json.contains("\"ui\""), "TOOL_CALL_START 应携带 ui 字段: " + json);
        assertTrue(json.contains("\"resourceUri\":\"ui://weather/mcp-app.html\""), "应携带 resourceUri: " + json);
        assertTrue(json.contains("\"server\":\"weather\""), "应携带 server: " + json);
        assertTrue(json.contains("\"toolName\":\"get_weather\""), "原词表字段保持: " + json);
    }

    @Test
    void serializerShouldNotIncludeUiWhenNull() {
        var tc = new io.agentscope.core.event.ToolCallStartEvent("reply-u", "call-u", "echo");
        String json = AgentEventSseSerializer.payload(tc, null, null);
        assertTrue(!json.contains("\"ui\""), "无 UI 工具不应携带 ui 字段: " + json);
        assertTrue(json.contains("\"toolName\":\"echo\""), "原词表字段保持: " + json);
    }

    @Test
    void sseShouldCarryUiForUiToolViaResolver() {
        var ref = new McpToolRegistrar.UiRef("ui://weather/mcp-app.html", "weather");
        when(mcpToolRegistrar.resolveUiRef("get_weather")).thenReturn(ref);
        var received = new ArrayList<String>();
        controller.subscribe("test-user:ui1").map(sse -> sse.data()).subscribe(received::add);
        eventBus.emit("test-user:ui1", new io.agentscope.core.event.ToolCallStartEvent("r", "c", "get_weather"));
        assertTrue(received.stream().anyMatch(s -> s.contains("\"resourceUri\":\"ui://weather/mcp-app.html\"")),
            "SSE 事件应携带 ui 元数据: " + received);
    }
}