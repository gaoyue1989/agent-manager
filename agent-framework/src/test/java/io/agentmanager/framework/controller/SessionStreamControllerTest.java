package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionEventBus.TurnStatus;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.SkillInjectionService;
import io.agentmanager.framework.service.ToolAuditStore;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentmanager.framework.service.UiContextStore;
import io.agentmanager.framework.service.UploadWorkspaceInjector;
import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话单次流端点测试（durable-sse-plan 改造版）。
 *
 * <p>核心变化验证：
 * <ul>
 *   <li>事件经 EventBus 广播而非直吐 FluxSink</li>
 *   <li>onCancel 不 dispose agent 管道</li>
 *   <li>新增 GET /subscribe 和 GET /status 端点</li>
 * </ul>
 *
 * <p>注意：sessionId 不能包含 Windows 路径非法字符（如 :），
 * 因为 Controller 内部会调用 PathSafe.sanitize 把 : 替换为 _，
 * 导致 Mockito 精确匹配参数失败。这里统一用 - 代替 :。
 */
class SessionStreamControllerTest {

    private SessionStreamController controller;
    private ChatUiChannel chatChannel;
    private AgentRuntimeService runtimeService;
    private McpToolRegistrar mcpToolRegistrar;
    private TurnLeaseStore turnLeaseStore;
    private ToolAuditStore toolAuditStore;
    private UploadWorkspaceInjector workspaceInjector;
    private SandboxConfig sandboxConfig;
    private SessionEventBus eventBus;
    private SessionEventStore eventStore;
    private SessionUserStore sessionUserStore;
    private WorkspaceReader workspaceReader;
    private AgentManagerProperties props;

    @BeforeEach
    void setUp() {
        chatChannel = mock(ChatUiChannel.class);
        runtimeService = mock(AgentRuntimeService.class);
        mcpToolRegistrar = mock(McpToolRegistrar.class);
        turnLeaseStore = mock(TurnLeaseStore.class);
        toolAuditStore = mock(ToolAuditStore.class);
        workspaceInjector = mock(UploadWorkspaceInjector.class);
        sandboxConfig = mock(SandboxConfig.class);
        eventStore = mock(SessionEventStore.class);
        workspaceReader = mock(WorkspaceReader.class);
        props = mock(AgentManagerProperties.class);
        sessionUserStore = mock(SessionUserStore.class);
        eventBus = new SessionEventBus(eventStore,
            Duration.ofMillis(100), Duration.ofMinutes(5), 64);

        when(turnLeaseStore.renewInterval()).thenReturn(Duration.ofSeconds(20));
        when(sandboxConfig.enabled()).thenReturn(false);
        when(props.file()).thenReturn(FileControllerTest.testProps().file());
        when(runtimeService.findPendingConfirm(anyString())).thenReturn(null);
        when(workspaceInjector.buildContentBlocks(any(), any(), any())).thenAnswer(inv -> {
            var msg = (String) inv.getArgument(1);
            var blocks = new ArrayList<io.agentscope.core.message.ContentBlock>();
            if (msg != null && !msg.isBlank()) {
                blocks.add(io.agentscope.core.message.TextBlock.builder().text(msg).build());
            }
            return blocks;
        });
        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(eventStore.findMaxSeq(anyString())).thenReturn(0);
        when(eventStore.findLatest(anyString())).thenReturn(null);
        when(eventStore.queryAfter(anyString(), anyString(), anyInt())).thenReturn(Flux.empty());
        when(turnLeaseStore.isHeld(anyString())).thenReturn(false);

        var skillInjectionService = mock(SkillInjectionService.class);
        when(skillInjectionService.injectSkillReferences(any())).thenAnswer(inv -> inv.getArgument(0));
        controller = new SessionStreamController(chatChannel, runtimeService, mcpToolRegistrar,
            turnLeaseStore, toolAuditStore, workspaceInjector, sandboxConfig, eventBus, eventStore,
            sessionUserStore, workspaceReader, props, skillInjectionService);
    }

    private List<String> collect(String sid, String message, String userId) {
        var frames = controller.chat(sid, new SessionStreamController.ChatRequest(message, userId, null, null), null)
            .collectList().block(Duration.ofSeconds(10));
        return frames == null ? List.of() : frames.stream().map(f -> f.data()).toList();
    }

    private List<String> collect(String sid, String message, String userId, List<String> fileIds) {
        var frames = controller.chat(sid, new SessionStreamController.ChatRequest(message, userId, null, fileIds), null)
            .collectList().block(Duration.ofSeconds(10));
        return frames == null ? List.of() : frames.stream().map(f -> f.data()).toList();
    }

    @Test
    void chatShouldEmitEventsViaEventBus() {
        var sessionId = "test-user-s1";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-1");
        var delta = new TextBlockDeltaEvent("reply-1", "block-1", "Hi");
        var end = new AgentEndEvent("reply-1");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just((AgentEvent) delta, (AgentEvent) end));

        var frames = collect(sessionId, "hello", "alice");
        assertTrue(frames.stream().anyMatch(f -> f != null && f.contains("TEXT_BLOCK_DELTA")),
            "应通过 EventBus 输出: " + frames);

        verify(turnLeaseStore).tryAcquire(sessionId);
        verify(turnLeaseStore).release(sessionId, "tok-1");
    }

    @Test
    void chatShouldReleaseLeaseOnNaturalComplete() {
        var sessionId = "test-user-s2";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-2");
        var end = new AgentEndEvent("reply-2");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just((AgentEvent) end));

        var frames = collect(sessionId, "hello", null);
        assertNotNull(frames);
        verify(turnLeaseStore).release(sessionId, "tok-2");
    }

    @Test
    void chatShouldQueueWithWaitingFramesWhenLeaseBusy() {
        // 缩短等待间隔，避免 15s 等待导致测试超时
        SessionStreamController.WAITING_FRAME_INTERVAL = Duration.ofMillis(50);
        SessionStreamController.ACQUIRE_TIMEOUT = Duration.ofSeconds(2);

        var sessionId = "test-user-s3";
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(turnLeaseStore.tryAcquire(sessionId)).thenAnswer(inv -> {
            var n = calls.incrementAndGet();
            return n == 1 ? null : "tok-3";
        });
        var end = new AgentEndEvent("reply-3");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just((AgentEvent) end));

        var frames = collect(sessionId, "hello", null);
        assertTrue(frames.stream().anyMatch(f -> f != null && f.contains("waiting")),
            "排队期间应发 waiting 帧: " + frames);

        // 恢复默认值
        SessionStreamController.WAITING_FRAME_INTERVAL = Duration.ofSeconds(15);
        SessionStreamController.ACQUIRE_TIMEOUT = Duration.ofSeconds(120);
    }

    @Test
    void chatShouldRejectBlankMessage() {
        var frames = collect("test-user-s4", "", "alice");
        assertTrue(frames.stream().anyMatch(f -> f != null && f.contains("message or fileIds is required")),
            "空消息应返回 error 帧: " + frames);
    }

    @Test
    void chatShouldAttachSessionKeyToUserMessage() {
        var sessionId = "test-user-s5";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-5");
        var end = new AgentEndEvent("reply-5");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just((AgentEvent) end));

        var captor = org.mockito.ArgumentCaptor.forClass(ChatUiRequest.class);
        collect(sessionId, "hi", "alice");
        verify(chatChannel).sendStream(captor.capture());
        var messages = captor.getValue().messages();
        assertEquals(1, messages.size());
        assertEquals(MsgRole.USER, messages.get(0).getRole());
        assertEquals("hi", messages.get(0).getTextContent());
        assertEquals(sessionId,
            messages.get(0).getMetadata().get(UiContextStore.METADATA_SESSION_KEY));
    }

    // ===== GET /subscribe 测试 =====

    @Test
    void subscribeShouldReplayHistoryAndCloseForCompletedSession() {
        var sessionId = "test-user-sub1";
        // 模拟一个已完成的 session：有 AGENT_END 事件
        when(eventStore.findLatest(sessionId)).thenReturn(
            new SessionEventStore.EnvelopedEvent(5, "AGENT_END", "{}", "rid-sub"));
        when(eventStore.queryAfter(eq(sessionId), anyString(), anyInt()))
            .thenReturn(Flux.just(
                new SessionEventStore.EnvelopedEvent(5, "AGENT_END", "{\"type\":\"AGENT_END\"}", "rid-sub")));

        when(eventStore.findMaxSeq(sessionId)).thenReturn(5);

        var frames = controller.subscribe(sessionId, 0, "rid-sub")
            .collectList().block(Duration.ofSeconds(5));

        assertNotNull(frames);
        var data = frames.stream().map(f -> f.data()).toList();
        assertTrue(data.stream().anyMatch(d -> d != null && d.contains("AGENT_END")),
            "应回放历史: " + data);
        assertTrue(data.stream().anyMatch(d -> d != null && d.contains("done")),
            "已完成 session 应追加 done 帧: " + data);
    }

    // ===== GET /status 测试 =====

    @Test
    void statusShouldReturnWorkingWhenLeaseHeld() {
        var sessionId = "test-user-st1";
        when(turnLeaseStore.isHeld(sessionId)).thenReturn(true);
        when(eventStore.findMaxSeq(sessionId)).thenReturn(10);
        when(eventStore.findLatest(sessionId)).thenReturn(
            new SessionEventStore.EnvelopedEvent(10, "TEXT_BLOCK_DELTA", "{}", "rid-st1"));

        var result = controller.status(sessionId);
        assertEquals("working", result.get("state"));
        assertEquals(10, result.get("latest_event_seq"));
    }

    @Test
    void statusShouldReturnCompletedWhenAgentEnded() {
        var sessionId = "test-user-st2";
        when(turnLeaseStore.isHeld(sessionId)).thenReturn(false);
        when(eventStore.findMaxSeq(sessionId)).thenReturn(20);
        when(eventStore.findLatest(sessionId)).thenReturn(
            new SessionEventStore.EnvelopedEvent(20, "AGENT_END", "{}", "rid-st2"));
        when(runtimeService.findPendingConfirm(sessionId)).thenReturn(null);

        var result = controller.status(sessionId);
        assertEquals("completed", result.get("state"));
    }

    @Test
    void statusShouldReturnIdleWhenNoEvents() {
        var sessionId = "test-user-st3";
        when(turnLeaseStore.isHeld(sessionId)).thenReturn(false);
        when(eventStore.findMaxSeq(sessionId)).thenReturn(0);
        when(runtimeService.findPendingConfirm(sessionId)).thenReturn(null);

        var result = controller.status(sessionId);
        assertEquals("idle", result.get("state"));
    }

    @Test
    void statusShouldReturnWaitingConfirmWhenPending() {
        var sessionId = "test-user-st4";
        when(turnLeaseStore.isHeld(sessionId)).thenReturn(false);
        when(eventStore.findMaxSeq(sessionId)).thenReturn(15);
        when(eventStore.findLatest(sessionId)).thenReturn(
            new SessionEventStore.EnvelopedEvent(15, "permission_ask", "{}", "rid-st4"));
        when(runtimeService.findPendingConfirm(sessionId)).thenReturn(
            java.util.Map.of("reply_id", "rid-st4", "tools", "[]"));

        var result = controller.status(sessionId);
        assertEquals("waiting_confirm", result.get("state"));
    }

    // ===== 原有 MCP / present_file 测试保留 =====

    @Test
    void serializerShouldIncludeUiMetadataOnToolCallStart() {
        var tc = new io.agentscope.core.event.ToolCallStartEvent("reply-u", "call-u", "get_weather");
        String json = AgentEventSseSerializer.payload(tc, "ui://weather/mcp-app.html", "weather");
        assertTrue(json.contains("\"ui\""), "TOOL_CALL_START 应携带 ui 字段: " + json);
        assertTrue(json.contains("\"resourceUri\":\"ui://weather/mcp-app.html\""), "应携带 resourceUri: " + json);
        assertTrue(json.contains("\"server\":\"weather\""), "应携带 server: " + json);
    }

    @Test
    void serializerShouldNotIncludeUiWhenNull() {
        var tc = new io.agentscope.core.event.ToolCallStartEvent("reply-u", "call-u", "echo");
        String json = AgentEventSseSerializer.payload(tc, null, null);
        assertTrue(!json.contains("\"ui\""), "无 UI 工具不应携带 ui 字段: " + json);
        assertTrue(json.contains("\"toolName\":\"echo\""), "原词表字段保持: " + json);
    }

    @Test
    void chatShouldAuditToolStartEvents() {
        var sessionId = "test-user-audit1";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-a1");
        var end = new AgentEndEvent("r-a1");
        var tcStart = new io.agentscope.core.event.ToolCallStartEvent("r-a1", "c-a1", "get_weather");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just((AgentEvent) tcStart, (AgentEvent) end));

        collect(sessionId, "go", null);
        verify(toolAuditStore).record(eq(sessionId), eq("get_weather"), eq("c-a1"),
            eq("TOOL_CALL_START"), anyString());
    }

    // ===== write_file → KV sync 测试 =====

    @Test
    void chatShouldSyncWriteFileToKvWhenSandboxDisabled() {
        var sessionId = "test-user-wf1";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-wf1");
        when(sandboxConfig.enabled()).thenReturn(false);

        // 模拟 write_file 工具调用流程：ToolCallStart → ToolCallDelta(输入参数) → ToolCallEnd
        var replyId = "r-wf1";
        var callId = "c-wf1";
        var tcStart = new io.agentscope.core.event.ToolCallStartEvent(replyId, callId, "write_file");
        // ToolCallDelta 携带 write_file 的输入 JSON（path + content）
        var inputJson = "{\"path\":\"outputs/report.md\",\"content\":\"# Hello\\nWorld\"}";
        var tcDelta = new io.agentscope.core.event.ToolCallDeltaEvent(replyId, callId, "write_file", inputJson);
        var tcEnd = new io.agentscope.core.event.ToolCallEndEvent(replyId, callId, "write_file");
        var agentEnd = new AgentEndEvent(replyId);

        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just(
                (AgentEvent) tcStart,
                (AgentEvent) tcDelta,
                (AgentEvent) tcEnd,
                (AgentEvent) agentEnd));

        when(workspaceReader.writeWorkspaceFile(anyString(), eq("outputs/report.md"), eq("# Hello\nWorld")))
            .thenReturn(true);

        collect(sessionId, "write a report", "alice");

        // 验证 writeWorkspaceFile 以 userId（非 sessionId）为 key 同步 KV
        verify(workspaceReader).writeWorkspaceFile(eq("alice"), eq("outputs/report.md"), eq("# Hello\nWorld"));
    }

    @Test
    void chatShouldNotSyncWriteFileToKvWhenSandboxEnabled() {
        var sessionId = "test-user-wf2";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-wf2");
        when(sandboxConfig.enabled()).thenReturn(true);

        var replyId = "r-wf2";
        var callId = "c-wf2";
        var tcStart = new io.agentscope.core.event.ToolCallStartEvent(replyId, callId, "write_file");
        var inputJson = "{\"path\":\"secret.txt\",\"content\":\"data\"}";
        var tcDelta = new io.agentscope.core.event.ToolCallDeltaEvent(replyId, callId, "write_file", inputJson);
        var tcEnd = new io.agentscope.core.event.ToolCallEndEvent(replyId, callId, "write_file");
        var agentEnd = new AgentEndEvent(replyId);

        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just(
                (AgentEvent) tcStart,
                (AgentEvent) tcDelta,
                (AgentEvent) tcEnd,
                (AgentEvent) agentEnd));

        collect(sessionId, "write secret", "bob");

        // 沙箱模式下不应同步 KV
        verify(workspaceReader, org.mockito.Mockito.never())
            .writeWorkspaceFile(anyString(), anyString(), anyString());
    }

    @Test
    void chatShouldSkipKvSyncWhenWriteFileInputMissingPath() {
        var sessionId = "test-user-wf3";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-wf3");
        when(sandboxConfig.enabled()).thenReturn(false);

        var replyId = "r-wf3";
        var callId = "c-wf3";
        var tcStart = new io.agentscope.core.event.ToolCallStartEvent(replyId, callId, "write_file");
        // 缺少 path 字段
        var inputJson = "{\"content\":\"data\"}";
        var tcDelta = new io.agentscope.core.event.ToolCallDeltaEvent(replyId, callId, "write_file", inputJson);
        var tcEnd = new io.agentscope.core.event.ToolCallEndEvent(replyId, callId, "write_file");
        var agentEnd = new AgentEndEvent(replyId);

        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just(
                (AgentEvent) tcStart,
                (AgentEvent) tcDelta,
                (AgentEvent) tcEnd,
                (AgentEvent) agentEnd));

        collect(sessionId, "write no path", "alice");

        // 缺少 path，不应同步
        verify(workspaceReader, org.mockito.Mockito.never())
            .writeWorkspaceFile(anyString(), anyString(), anyString());
    }

    @Test
    void chatShouldStripWorkspacePrefixFromWriteFilePath() {
        var sessionId = "test-user-wf4";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-wf4");
        when(sandboxConfig.enabled()).thenReturn(false);

        var replyId = "r-wf4";
        var callId = "c-wf4";
        var tcStart = new io.agentscope.core.event.ToolCallStartEvent(replyId, callId, "write_file");
        // path 带有 /workspace/ 前缀
        var inputJson = "{\"path\":\"/workspace/outputs/deep/report.txt\",\"content\":\"deep content\"}";
        var tcDelta = new io.agentscope.core.event.ToolCallDeltaEvent(replyId, callId, "write_file", inputJson);
        var tcEnd = new io.agentscope.core.event.ToolCallEndEvent(replyId, callId, "write_file");
        var agentEnd = new AgentEndEvent(replyId);

        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just(
                (AgentEvent) tcStart,
                (AgentEvent) tcDelta,
                (AgentEvent) tcEnd,
                (AgentEvent) agentEnd));

        when(workspaceReader.writeWorkspaceFile(anyString(), eq("outputs/deep/report.txt"), eq("deep content")))
            .thenReturn(true);

        collect(sessionId, "write deep", "alice");

        verify(workspaceReader).writeWorkspaceFile(eq("alice"), eq("outputs/deep/report.txt"), eq("deep content"));
    }

    @Test
    void chatShouldUseUserIdNotSessionIdAsKvKeyForWriteFile() {
        var sessionId = "test-user-wf5";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-wf5");
        when(sandboxConfig.enabled()).thenReturn(false);

        var replyId = "r-wf5";
        var callId = "c-wf5";
        var tcStart = new io.agentscope.core.event.ToolCallStartEvent(replyId, callId, "write_file");
        var inputJson = "{\"path\":\"memo.txt\",\"content\":\"note\"}";
        var tcDelta = new io.agentscope.core.event.ToolCallDeltaEvent(replyId, callId, "write_file", inputJson);
        var tcEnd = new io.agentscope.core.event.ToolCallEndEvent(replyId, callId, "write_file");
        var agentEnd = new AgentEndEvent(replyId);

        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just(
                (AgentEvent) tcStart,
                (AgentEvent) tcDelta,
                (AgentEvent) tcEnd,
                (AgentEvent) agentEnd));

        when(workspaceReader.writeWorkspaceFile(eq("bob"), eq("memo.txt"), eq("note")))
            .thenReturn(true);

        // userId="bob"，sessionId="test-user-wf5"——验证 KV key 是 "bob" 而非 sessionId
        collect(sessionId, "write memo", "bob");

        verify(workspaceReader).writeWorkspaceFile(eq("bob"), eq("memo.txt"), eq("note"));
        // 确保没有用 sessionId 作为 key
        verify(workspaceReader, org.mockito.Mockito.never())
            .writeWorkspaceFile(eq(sessionId), anyString(), anyString());
    }

    @Test
    void chatShouldDefaultToDebugUserWhenUserIdIsNullForWriteFile() {
        var sessionId = "test-user-wf6";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-wf6");
        when(sandboxConfig.enabled()).thenReturn(false);

        var replyId = "r-wf6";
        var callId = "c-wf6";
        var tcStart = new io.agentscope.core.event.ToolCallStartEvent(replyId, callId, "write_file");
        var inputJson = "{\"path\":\"note.txt\",\"content\":\"hi\"}";
        var tcDelta = new io.agentscope.core.event.ToolCallDeltaEvent(replyId, callId, "write_file", inputJson);
        var tcEnd = new io.agentscope.core.event.ToolCallEndEvent(replyId, callId, "write_file");
        var agentEnd = new AgentEndEvent(replyId);

        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just(
                (AgentEvent) tcStart,
                (AgentEvent) tcDelta,
                (AgentEvent) tcEnd,
                (AgentEvent) agentEnd));

        when(workspaceReader.writeWorkspaceFile(eq("debug-user"), eq("note.txt"), eq("hi")))
            .thenReturn(true);

        // userId=null → 降级为 "debug-user"
        collect(sessionId, "write note", null);

        verify(workspaceReader).writeWorkspaceFile(eq("debug-user"), eq("note.txt"), eq("hi"));
    }
}
