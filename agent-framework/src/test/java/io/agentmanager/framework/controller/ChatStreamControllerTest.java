package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.SkillInjectionService;
import io.agentmanager.framework.service.ToolAuditStore;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentmanager.framework.service.UiContextStore;
import io.agentmanager.framework.service.UploadWorkspaceInjector;
import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话入口端点测试（{@code POST /threads/chat}，sessionId 在请求体中，可选）。
 *
 * <p>核心验证：
 * <ul>
 *   <li>事件经 EventBus 广播而非直吐 FluxSink</li>
 *   <li>onCancel 不 dispose agent 管道</li>
 *   <li>sessionId 省略时自动生成 UUID 并首发 session_created</li>
 *   <li>write_file → KV 同步、工具审计等副作用</li>
 * </ul>
 *
 * <p>注意：sessionId 不能包含 Windows 路径非法字符（如 :），
 * 因为 Controller 内部会调用 PathSafe.sanitize 把 : 替换为 _，
 * 导致 Mockito 精确匹配参数失败。这里统一用 - 代替 :。
 */
class ChatStreamControllerTest {

    private static final Pattern SESSION_ID_FIELD =
        Pattern.compile("\"session_id\":\"([^\"]+)\"");

    private ChatStreamController controller;
    private ChatUiChannel chatChannel;
    private AgentRuntimeService runtimeService;
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

        // 默认间隔；个别用例会重新打桩成亚秒值来逼出丢锁，故 lenient
        lenient().when(turnLeaseStore.renewInterval()).thenReturn(Duration.ofSeconds(20));
        // TurnLeaseGuard 构造时就要算「到必须停手」的时长，ttl() 缺了会 NPE
        lenient().when(turnLeaseStore.ttl()).thenReturn(Duration.ofSeconds(60));
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
        controller = new ChatStreamController(chatChannel, runtimeService, turnLeaseStore,
            toolAuditStore, workspaceInjector, sandboxConfig, eventBus, eventStore,
            sessionUserStore, workspaceReader, props, skillInjectionService,
            mock(io.agentmanager.framework.service.FileAssetStore.class));
    }

    /** 等续租线程跑过头一拍（存根返回 LOST → guard 随即置位丢锁） */
    private void awaitRenewCalled() {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            boolean called = mockingDetails(turnLeaseStore).getInvocations().stream()
                .anyMatch(i -> "renew".equals(i.getMethod().getName()));
            if (called) {
                return;
            }
            sleep(5);
        }
        fail("续租线程未在 3s 内被调用");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> collect(String sessionId, String message, String userId) {
        return collect(sessionId, message, userId, null);
    }

    private List<String> collect(String sessionId, String message, String userId, List<String> fileIds) {
        var frames = controller.chat(
                new ChatStreamController.ChatRequest(message, userId, sessionId, fileIds), null)
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
        // endTurn 先 closeSession 再放锁：流一结束 collect() 就返回了，而 release 还在 boundedElastic 线程上，必须等待
        verify(turnLeaseStore, timeout(2000)).release(sessionId, "tok-1");
    }

    @Test
    void chatShouldStopWritingAfterLeaseLost() throws Exception {
        // 本副本丢了该 session 的执行权之后，继续 append 会与新 owner 的 seq 区间重叠
        // ——这正是 C1 要防的事。所以后续事件必须被丢弃，且收尾走 abandonTurn（丢弃缓冲）
        // 而不是 finishTurn（刷缓冲）。
        var sessionId = "test-user-lost";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-lost");
        // 亚秒配置把「丢锁」逼到 20ms 内出现
        when(turnLeaseStore.renewInterval()).thenReturn(Duration.ofMillis(20));
        when(turnLeaseStore.ttl()).thenReturn(Duration.ofMillis(60));
        lenient().when(turnLeaseStore.renew(anyString(), anyString()))
            .thenReturn(TurnLeaseStore.RenewOutcome.LOST);

        // 事件由测试显式编排，而不是让源流按自己的节奏跑：丢锁后 abandonSession 会**提前**
        // 关掉请求流，collect() 随即返回 —— 若源流还在后面慢慢发，断言就变成了空断言
        // （MUT-J/MUT-L 实测正是如此）。multicast sink 是同步投递的，tryEmitNext 返回即处理完。
        var events = Sinks.many().multicast().<AgentEvent>onBackpressureBuffer();
        var subscribed = new CountDownLatch(1);
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(events.asFlux().doOnSubscribe(s -> subscribed.countDown()));

        var framesFuture = CompletableFuture.supplyAsync(() -> collect(sessionId, "hello", "alice"));
        assertTrue(subscribed.await(3, TimeUnit.SECONDS), "controller 未订阅 agent 事件流");
        // 必须等到续租那一拍真的跑过（LOST → 置位）再发事件，否则第一个事件走的是正常路径
        awaitRenewCalled();
        sleep(50);   // 越过「调用被记录」与 markLost 置位之间的窗口

        events.tryEmitNext(new TextBlockDeltaEvent("reply-l", "block-l", "hi"));
        events.tryEmitNext(new AgentEndEvent("reply-l"));
        events.tryEmitComplete();   // 源流结束 → endTurn

        var frames = framesFuture.get(5, TimeUnit.SECONDS);

        assertEquals(1, frames.stream().filter(f -> f != null && f.contains("interrupted")).count(),
            "客户端只应看到一帧 interrupted（后续事件虽仍会进来，但流已关）: " + frames);
        assertTrue(frames.stream().anyMatch(f -> f != null && f.contains("lease_lost")), frames.toString());
        // 用 any() 而非 anyString()：后者不匹配 null，而 emitSynthetic 传 null replyId 时
        // 会把"终态帧被落库"这条漏检
        verify(eventStore, never()).append(any(), any(), any(), any());
        // 两次调用是设计使然：stopIfLeaseLost 先拆一次让客户端立刻收流，
        // 源流跑完后 endTurn 再来一次（幂等空转）
        verify(eventStore, atLeastOnce()).abandonTurn(sessionId);
        verify(eventStore, never()).finishTurn(sessionId);
        // 租约已属于接管者，不得再 DELETE（token 校验虽不会误删，但也不该谎称是自己释放的）
        verify(turnLeaseStore, never()).release(sessionId, "tok-lost");
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
        // endTurn 先 closeSession 再放锁：流一结束 collect() 就返回了，而 release 还在 boundedElastic 线程上，必须等待
        verify(turnLeaseStore, timeout(2000)).release(sessionId, "tok-2");
    }

    @Test
    void chatShouldReleaseLeaseWhenTurnSetupFails() {
        // 构造消息阶段抛异常（如 fileId 失效导致工作区注入失败）时，租约**已经**到手。
        // 续租线程不看本段是否还活着，漏放租约 = 该 session 被永久锁死。
        var sessionId = "test-user-s4";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-4");
        when(workspaceInjector.injectToWorkspace(anyString(), anyString()))
            .thenThrow(new IllegalStateException("file not found"));

        var frames = collect(sessionId, "hello", null, List.of("f-missing"));

        assertTrue(frames.stream().anyMatch(f -> f != null && f.contains("turn_setup_failed")),
            "准备失败应回错误帧: " + frames);
        verify(turnLeaseStore).release(sessionId, "tok-4");
        verify(eventStore).finishTurn(sessionId);
        verify(chatChannel, never()).sendStream(any(ChatUiRequest.class));
    }

    @Test
    void chatShouldReleaseLeaseWhenSendStreamThrowsSynchronously() {
        // sendStream 同步抛异常（如 channel 未就绪）时租约同样已经到手。这一刻执行流还
        // **没有**走到 .subscribe(...)，所以没有任何终态回调会来收尾——不在准备段的 try
        // 里兜住，续租线程就会带着这个 token 一直续期，该 session 再也无法执行。
        var sessionId = "test-user-s5";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-5");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenThrow(new IllegalStateException("channel not ready"));

        // 同步异常若逃到订阅者，说明准备段的 try 没兜住它 —— 显式转成断言失败，
        // 否则这里只会抛出一个看不出跟租约有关的 IllegalStateException
        var frames = new ArrayList<String>();
        try {
            frames.addAll(collect(sessionId, "hello", null));
        } catch (Exception e) {
            fail("sendStream 的同步异常不该逃逸给订阅者，应由准备段捕获并回滚: " + e);
        }

        assertTrue(frames.stream().anyMatch(f -> f != null && f.contains("turn_setup_failed")),
            "sendStream 同步失败应回错误帧: " + frames);
        verify(turnLeaseStore).release(sessionId, "tok-5");
        verify(eventStore, never()).append(any(), any(), any(), any());
    }

    @Test
    void chatShouldReleaseLeaseWhenGuardConstructionFails() {
        // 续租线程还没起、token 却已经在手上：没有别的地方会释放它。
        // token 不释放则该 session 在 TTL 内拿不到租约（观察者也会一直 probe 到 RUNNING）。
        var sessionId = "test-user-s6";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-6");
        when(turnLeaseStore.renewInterval()).thenThrow(new IllegalStateException("bad config"));

        var frames = new ArrayList<String>();
        try {
            frames.addAll(collect(sessionId, "hello", null));
        } catch (Exception e) {
            fail("租约启动失败应被回滚并回错误帧，而不是把异常抛给订阅者: " + e);
        }

        assertTrue(frames.stream().anyMatch(f -> f != null && f.contains("turn_setup_failed")),
            "租约启动失败应回错误帧: " + frames);
        verify(turnLeaseStore).release(sessionId, "tok-6");
        // 连 turn 都没开始，不该动到 EventBus 的会话状态
        verify(eventStore, never()).finishTurn(any());
        verify(eventStore, never()).abandonTurn(any());
        verify(chatChannel, never()).sendStream(any(ChatUiRequest.class));
    }

    @Test
    void chatShouldQueueWithWaitingFramesWhenLeaseBusy() {
        // 缩短等待间隔，避免 15s 等待导致测试超时
        ChatStreamController.WAITING_FRAME_INTERVAL = Duration.ofMillis(50);
        ChatStreamController.ACQUIRE_TIMEOUT = Duration.ofSeconds(2);

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
        ChatStreamController.WAITING_FRAME_INTERVAL = Duration.ofSeconds(15);
        ChatStreamController.ACQUIRE_TIMEOUT = Duration.ofSeconds(120);
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

    // ===== sessionId 可选：自动生成 + session_created =====

    @Test
    void chatShouldGenerateUuidAndEmitSessionCreatedWhenSessionIdAbsent() {
        when(turnLeaseStore.tryAcquire(anyString())).thenReturn("tok-new");
        var end = new AgentEndEvent("reply-new");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just((AgentEvent) end));

        // sessionId 传 null → 应自动生成
        var frames = collect(null, "hello", "alice");

        assertTrue(!frames.isEmpty(), "应有 SSE 帧: " + frames);
        var first = frames.get(0);
        assertTrue(first != null && first.contains("session_created"),
            "首个 SSE 事件应为 session_created: " + frames);

        var matcher = SESSION_ID_FIELD.matcher(first);
        assertTrue(matcher.find(), "session_created 应携带 session_id: " + first);
        var generated = matcher.group(1);
        // UUID.fromString 对非法格式抛 IllegalArgumentException
        assertNotNull(java.util.UUID.fromString(generated),
            "自动生成的 session_id 应为合法 UUID: " + generated);

        // 自动生成的 id 应被用于抢租约
        verify(turnLeaseStore).tryAcquire(generated);
        verify(sessionUserStore).upsert(generated, "alice");
    }

    @Test
    void chatShouldNotEmitSessionCreatedWhenSessionIdProvided() {
        var sessionId = "test-user-s6";
        when(turnLeaseStore.tryAcquire(sessionId)).thenReturn("tok-6");
        var end = new AgentEndEvent("reply-6");
        when(chatChannel.sendStream(any(ChatUiRequest.class)))
            .thenReturn(Flux.just((AgentEvent) end));

        var frames = collect(sessionId, "hello", "alice");

        assertTrue(frames.stream().noneMatch(f -> f != null && f.contains("session_created")),
            "传了 sessionId 不应出现 session_created: " + frames);
        verify(turnLeaseStore).tryAcquire(sessionId);
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
