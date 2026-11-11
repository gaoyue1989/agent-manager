package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.mcp.ChatRequestHeaders;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionEventTailer;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.SkillInjectionService;
import io.agentmanager.framework.service.ToolAuditStore;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AG-UI run 入口透传头测试（mcp-chat-header-forward-design，与 chat / confirm 三入口同语义）：
 *
 * <ul>
 *   <li>新对话轮：/agui/runs 捕获的透传头随 InboundMessage.runtimeContext 进 agent turn
 *       （镜像 ChatStreamControllerTest.chatShouldCarryForwardHeadersIntoInboundMessage）</li>
 *   <li>HITL 恢复轮：resume[] 经 resumeWithConfirmEvents 4 参重载把透传头随恢复上下文随行
 *       （镜像 ConfirmControllerTest.confirmStreamShouldCaptureForwardHeadersIntoResumeContext）</li>
 *   <li>零声明服务：capture 空集 → runtimeContext 无 ChatRequestHeaders attribute（行为不变）</li>
 * </ul>
 */
class AguiRunControllerTest {

    private AguiRunController controller;
    private AgentRuntimeService runtimeService;
    private TurnLeaseStore turnLeaseStore;
    private SessionEventStore eventStore;
    private SessionUserStore sessionUserStore;
    private McpToolRegistrar mcpToolRegistrar;
    private HarnessAgent agent;
    private ChatUiChannel chatChannel;

    @BeforeEach
    void setUp() {
        runtimeService = mock(AgentRuntimeService.class);
        turnLeaseStore = mock(TurnLeaseStore.class);
        eventStore = mock(SessionEventStore.class);
        sessionUserStore = mock(SessionUserStore.class);
        mcpToolRegistrar = mock(McpToolRegistrar.class);
        agent = mock(HarnessAgent.class);
        chatChannel = mock(ChatUiChannel.class);

        // dispatchStream(InboundMessage) 路径要求 channelId 非空（InboundMessage 构造校验），
        // mock channel 默认返回 null——打桩为 ChatUiChannel 的 CHANNEL_ID 语义值
        lenient().when(chatChannel.channelId()).thenReturn("chatui");
        // 恢复轮与 TurnLeaseGuard 构造防 NPE（ttl()/renewInterval() 缺了会炸）
        lenient().when(turnLeaseStore.renewInterval()).thenReturn(Duration.ofSeconds(20));
        lenient().when(turnLeaseStore.ttl()).thenReturn(Duration.ofSeconds(60));
        when(runtimeService.findPendingConfirm(anyString())).thenReturn(null);
        // 真实 SessionEventBus 包 mock store：append 返回 1 才会广播（A3：seq=0 不广播）
        when(eventStore.append(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        when(eventStore.findMaxSeq(anyString())).thenReturn(0);
        when(eventStore.findLatest(anyString())).thenReturn(null);
        when(eventStore.queryAfter(anyString(), anyString(), anyInt())).thenReturn(Flux.empty());
        var eventBus = new SessionEventBus(eventStore,
            Duration.ofMillis(100), Duration.ofMinutes(5), 64);

        var skillInjectionService = mock(SkillInjectionService.class);
        // controller 走 2 参重载 injectSkillReferences(text, userId)：恒等注入
        when(skillInjectionService.injectSkillReferences(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        // AgentManagerProperties 9 参口径（AguiConfigTest 同款）：真实 SseConfig 供心跳间隔
        var props = new AgentManagerProperties(
            mock(AgentManagerProperties.LLMConfig.class),
            mock(AgentManagerProperties.ServerConfig.class),
            mock(AgentManagerProperties.CheckpointConfig.class),
            "/test", "",
            mock(AgentManagerProperties.CleanupConfig.class),
            mock(AgentManagerProperties.FileConfig.class),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults());
        var codec = new AguiEventCodec(new io.agentmanager.framework.config.AguiProperties(
            true, true, true, true, false));

        controller = new AguiRunController(runtimeService, turnLeaseStore, eventBus,
            mock(SessionEventTailer.class), sessionUserStore, skillInjectionService,
            mock(ToolAuditStore.class), mcpToolRegistrar, codec, props);
    }

    private static HttpHeaders requestHeadersWithAuth() {
        var requestHeaders = new HttpHeaders();
        requestHeaders.add("Authorization", "Bearer tok-abc");
        requestHeaders.add("Cookie", "sid=secret");
        return requestHeaders;
    }

    @Test
    void runsShouldCarryForwardHeadersIntoInboundMessage() {
        // 新对话轮：配置驱动捕获的透传头随 InboundMessage 的 runtimeContext 进 agent turn
        //（typed attribute），denylist 头不捕获——与 /threads/chat 同款 in-band 随行
        var threadId = "agui-fwd-t1";
        when(mcpToolRegistrar.getForwardHeaderNames()).thenReturn(Set.of("authorization"));
        when(turnLeaseStore.tryAcquire(threadId)).thenReturn("tok-1");
        when(runtimeService.getAgent(threadId)).thenReturn(agent);
        lenient().when(agent.channel(any(ChatUiChannel.class))).thenReturn(chatChannel);
        when(chatChannel.dispatchStream(any(InboundMessage.class)))
            .thenReturn(Flux.just((AgentEvent) new AgentEndEvent("reply-1")));

        var frames = controller.runs(
                new AguiRunController.AguiRunRequest(threadId, null,
                    List.of(new AguiRunController.AguiMessageDTO("m1", "user", "hello")), null),
                null, requestHeadersWithAuth())
            .collectList().block(Duration.ofSeconds(10));

        assertNotNull(frames, "应产出 SSE 帧并正常收流");
        var captor = ArgumentCaptor.forClass(InboundMessage.class);
        verify(chatChannel).dispatchStream(captor.capture());
        var carried = captor.getValue().runtimeContext().get(ChatRequestHeaders.class);
        assertNotNull(carried, "声明的透传头应随 InboundMessage 随行");
        assertEquals(Map.of("authorization", "Bearer tok-abc"), carried.headers(),
            "denylist 头（cookie）不得被捕获");
    }

    @Test
    void resumeRunsShouldPassForwardHeadersToResumeService() {
        // 恢复轮：resume[] → translateResumes → resumeWithConfirmEvents 4 参重载，
        // 捕获的透传头随恢复上下文进 RuntimeContext（恢复轮的 MCP 调用同样按声明注入）
        var threadId = "agui-resume-t1";
        when(mcpToolRegistrar.getForwardHeaderNames()).thenReturn(Set.of("authorization"));
        when(turnLeaseStore.acquire(eq(threadId), any())).thenReturn("tok-2");
        when(runtimeService.resumeWithConfirmEvents(eq(threadId), isNull(), anyList(), any()))
            .thenReturn(Flux.just((AgentEvent) new AgentEndEvent("reply-r")));

        var frames = controller.runs(
                new AguiRunController.AguiRunRequest(threadId, null, null,
                    List.of(new AguiRunController.AguiResumeDTO("run-1:call-1", "resolved",
                        Map.of("approved", true)))),
                null, requestHeadersWithAuth())
            .collectList().block(Duration.ofSeconds(10));

        assertNotNull(frames, "应产出 SSE 帧并正常收流");
        var captor = ArgumentCaptor.forClass(ChatRequestHeaders.class);
        verify(runtimeService).resumeWithConfirmEvents(eq(threadId), isNull(), anyList(), captor.capture());
        assertEquals(Map.of("authorization", "Bearer tok-abc"), captor.getValue().headers(),
            "恢复链路同样捕获透传头（cookie 被 denylist 剔除）");
    }

    @Test
    void runsShouldNotCarryHeadersWhenNothingDeclared() {
        // 零声明服务：capture 空集 → runtimeContext 无 ChatRequestHeaders attribute（行为不变）
        var threadId = "agui-nofwd-t1";
        when(turnLeaseStore.tryAcquire(threadId)).thenReturn("tok-3");
        when(runtimeService.getAgent(threadId)).thenReturn(agent);
        lenient().when(agent.channel(any(ChatUiChannel.class))).thenReturn(chatChannel);
        when(chatChannel.dispatchStream(any(InboundMessage.class)))
            .thenReturn(Flux.just((AgentEvent) new AgentEndEvent("reply-3")));

        controller.runs(
                new AguiRunController.AguiRunRequest(threadId, null,
                    List.of(new AguiRunController.AguiMessageDTO("m1", "user", "hello")), null),
                null, requestHeadersWithAuth())
            .collectList().block(Duration.ofSeconds(10));

        var captor = ArgumentCaptor.forClass(InboundMessage.class);
        verify(chatChannel).dispatchStream(captor.capture());
        assertNull(captor.getValue().runtimeContext().get(ChatRequestHeaders.class),
            "零声明服务不得携带透传头 attribute");
    }

    @Test
    void runsShouldUnregisterLifecycleOnSetupFailure() {
        // 4a34a41 回滚回归钉子（run 形态）：租约到手后 onTurnStart 已注册（§4.4 T3 双信号），
        // run 准备段同步抛（getAgent 异常）走 catch 回滚——漏一行 onTurnEnd 即 activeTurns
        // 条目无 TTL 泄漏，sweep 持续按 turn 注册续期把应回收沙箱多续到超龄
        var threadId = "agui-setupfail-t1";
        var lifecycle = mock(io.agentmanager.framework.sandbox.opensandbox.SandboxLifecycleManager.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "sandboxLifecycle", lifecycle);
        when(turnLeaseStore.tryAcquire(threadId)).thenReturn("tok-f1");
        when(runtimeService.getAgent(threadId))
            .thenThrow(new IllegalStateException("agent gone"));

        var frames = controller.runs(
                new AguiRunController.AguiRunRequest(threadId, null,
                    List.of(new AguiRunController.AguiMessageDTO("m1", "user", "hello")), null),
                null, requestHeadersWithAuth())
            .collectList().block(Duration.ofSeconds(10));

        assertNotNull(frames, "准备失败也应收流（abortSetup 回错误帧）");
        assertTrue(frames.stream().anyMatch(f -> f.data() != null && f.data().contains("turn_setup_failed")),
            "run 准备失败应回 turn_setup_failed 帧: " + frames);
        verify(lifecycle).onTurnStart(threadId);
        verify(lifecycle).onTurnEnd(threadId);
    }

    @Test
    void resumeRunsShouldUnregisterLifecycleOnSetupFailure() {
        // 4a34a41 回滚回归钉子（resume 形态）：恢复段重注册 onTurnStart 后，
        // resumeWithConfirmEvents 同步抛 → catch 回滚注销，与 confirm-stream 同语义
        var threadId = "agui-resumefail-t1";
        var lifecycle = mock(io.agentmanager.framework.sandbox.opensandbox.SandboxLifecycleManager.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "sandboxLifecycle", lifecycle);
        when(turnLeaseStore.acquire(eq(threadId), any())).thenReturn("tok-r1");
        doThrow(new IllegalStateException("confirm consumed"))
            .when(runtimeService).resumeWithConfirmEvents(eq(threadId), isNull(), anyList(), any());

        var frames = controller.runs(
                new AguiRunController.AguiRunRequest(threadId, null, null,
                    List.of(new AguiRunController.AguiResumeDTO("run-1:call-1", "resolved",
                        Map.of("approved", true)))),
                null, requestHeadersWithAuth())
            .collectList().block(Duration.ofSeconds(10));

        assertNotNull(frames, "恢复准备失败也应收流（abortSetup 回错误帧）");
        assertTrue(frames.stream().anyMatch(f -> f.data() != null && f.data().contains("confirm consumed")),
            "resume 准备失败应回错误帧: " + frames);
        verify(lifecycle).onTurnStart(threadId);
        verify(lifecycle).onTurnEnd(threadId);
    }
}
