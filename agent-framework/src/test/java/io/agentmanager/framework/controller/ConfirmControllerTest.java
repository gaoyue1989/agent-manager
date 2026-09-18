package io.agentmanager.framework.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HITL 确认端点测试（hitl-permission-plan.md 6.3 / 12.5）：
 * /threads/{sid}/confirm 同步恢复 + 404/409 错误码；confirm-stream DURABLE_SSE + 租约。
 *
 * <p>DURABLE_SSE 架构：confirm-stream 事件经 EventBus 持久化 + 广播，断连后可重连续传。
 */
class ConfirmControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mvc;
    private HarnessAgent agent;
    private AgentRuntimeService runtimeService;
    private ConfirmContextStore confirmContextStore;
    private TurnLeaseStore turnLeaseStore;
    private SessionEventBus eventBus;
    private SessionEventStore eventStore;
    private SessionUserStore sessionUserStore;
    private final io.agentmanager.framework.service.McpToolRegistrar mcpToolRegistrar =
        org.mockito.Mockito.mock(io.agentmanager.framework.service.McpToolRegistrar.class);

    @BeforeEach
    void setUp() throws Exception {
        agent = mock(HarnessAgent.class);
        confirmContextStore = mock(ConfirmContextStore.class);
        turnLeaseStore = mock(TurnLeaseStore.class);
        // 默认间隔；个别用例会重新打桩成亚秒值来逼出丢锁，故 lenient
        lenient().when(turnLeaseStore.renewInterval()).thenReturn(Duration.ofSeconds(20));
        // TurnLeaseGuard 构造时就要算「到必须停手」的时长，ttl() 缺了会 NPE
        lenient().when(turnLeaseStore.ttl()).thenReturn(Duration.ofSeconds(60));
        // 同步 confirm 现在也抢租约（与 confirm-stream 对齐）：默认允许抢到，
        // 个别用例可重新打桩返回 null 以验证 409 turn_in_progress
        lenient().when(turnLeaseStore.tryAcquire(anyString())).thenReturn("tok-1");

        // SessionEventBus 依赖 SessionEventStore（mock）
        eventStore = mock(SessionEventStore.class);
        when(eventStore.queryAfter(anyString(), any(), anyInt())).thenReturn(reactor.core.publisher.Flux.empty());
        when(eventStore.findLatest(anyString())).thenReturn(null);
        when(eventStore.findMaxSeq(anyString())).thenReturn(0);
        eventBus = new SessionEventBus(eventStore);

        sessionUserStore = mock(SessionUserStore.class);

        runtimeService = new AgentRuntimeService(
            new io.agentmanager.framework.model.OafConfig(
                "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
                "Test agent", "@acme", "MIT",
                List.of("test"), "you are a helper.",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                new io.agentmanager.framework.model.OafConfig.ModelConfig("openai", "gpt-4", ""),
                new io.agentmanager.framework.model.OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
                new io.agentmanager.framework.model.OafConfig.MemoryConfig("editable", Map.of()),
                Map.of()),
            agent, List.of(), new io.agentmanager.framework.service.LLMLogger(), confirmContextStore);
        mvc = MockMvcBuilders.standaloneSetup(new ConfirmController(runtimeService, turnLeaseStore, eventBus, sessionUserStore, mcpToolRegistrar)).build();

        // 先注入确认上下文：模拟前端收到 permission_ask（invokeStream 链路 storeConfirmContext 落库）
        var ask = new io.agentscope.core.event.RequireUserConfirmEvent(
            "evt-1", "src-1", "reply-1",
            List.of(ToolUseBlock.builder().id("call-1").name("get_weather")
                .input(Map.of("city", "beijing")).build()));
        when(agent.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
            .thenReturn(reactor.core.publisher.Flux.just(ask));
        runtimeService.invokeStream("query weather", "t1", "alice").collectList().block();
    }

    /** 等续租线程跑过头一拍（存根返回 LOST → guard 随即置位丢锁） */
    private void awaitRenewCalled() {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            boolean called = org.mockito.Mockito.mockingDetails(turnLeaseStore).getInvocations().stream()
                .anyMatch(i -> "renew".equals(i.getMethod().getName()));
            if (called) {
                return;
            }
            sleep(5);
        }
        throw new AssertionError("续租线程未在 3s 内被调用");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stubPendingStoreRow() {
        when(confirmContextStore.consume(anyString())).thenReturn(new ConfirmContextStore.StoredRow(
            List.of(ToolUseBlock.builder().id("call-1").name("get_weather")
                .input(Map.of("city", "beijing")).build()),
            "reply-1", Instant.now(), null, null));
    }

    @Test
    void confirmShouldResumeAndReturnFinalReply() throws Exception {
        stubPendingStoreRow();
        var msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn("weather done");
        when(agent.call(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
            .thenReturn(Mono.just(msg));

        mvc.perform(post("/threads/t1/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("results", List.of(
                    Map.of("tool_call_id", "call-1", "confirmed", true, "accept_rule", false))))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.response").value("weather done"))
            .andExpect(jsonPath("$.thread_id").value("t1"));
    }

    @Test
    void confirmShouldReturn404WhenContextMissing() throws Exception {
        when(confirmContextStore.consume(anyString()))
            .thenThrow(new AgentRuntimeService.ConfirmContextNotFoundException("nope"));
        mvc.perform(post("/threads/nope/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("results", List.of()))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("confirm_context_not_found"));
    }

    @Test
    void confirmShouldReturn409OnDuplicateConfirm() throws Exception {
        stubPendingStoreRow();
        when(confirmContextStore.consume(anyString()))
            .thenReturn(new ConfirmContextStore.StoredRow(
                List.of(ToolUseBlock.builder().id("call-1").name("get_weather")
                    .input(Map.of("city", "beijing")).build()),
                "reply-1", Instant.now(), null, null))
            .thenThrow(new AgentRuntimeService.ConfirmAlreadyConsumedException("t1"));
        var msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn("done");
        when(agent.call(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
            .thenReturn(Mono.just(msg));
        var body = MAPPER.writeValueAsString(Map.of("results", List.of(
            Map.of("tool_call_id", "call-1", "confirmed", true))));

        mvc.perform(post("/threads/t1/confirm").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        mvc.perform(post("/threads/t1/confirm").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("confirm_already_consumed"));
    }

    @Test
    void confirmShouldReturn409WhenTurnLeaseUnavailable() throws Exception {
        // 多副本/并发：该会话有执行段在进行中，同步 confirm 必须拒绝恢复执行
        // （否则两个执行段会同时写同一份 AgentState）
        when(turnLeaseStore.tryAcquire(anyString())).thenReturn(null);
        stubPendingStoreRow();
        when(confirmContextStore.consume(anyString()))
            .thenReturn(new ConfirmContextStore.StoredRow(
                List.of(ToolUseBlock.builder().id("call-1").name("get_weather")
                    .input(Map.of("city", "beijing")).build()),
                "reply-1", Instant.now(), null, null));

        mvc.perform(post("/threads/t1/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("results", List.of(
                    Map.of("tool_call_id", "call-1", "confirmed", true))))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("turn_in_progress"));

        // 未抢到租约就不得恢复执行
        verify(agent, never()).call(anyList(), any(io.agentscope.core.agent.RuntimeContext.class));
    }

    @Test
    void confirmShouldReleaseTurnLeaseAfterExecution() throws Exception {
        // 执行段结束必须释放租约，否则该会话被锁到 TTL 过期（默认 60s）
        stubPendingStoreRow();
        when(confirmContextStore.consume(anyString()))
            .thenReturn(new ConfirmContextStore.StoredRow(
                List.of(ToolUseBlock.builder().id("call-1").name("get_weather")
                    .input(Map.of("city", "beijing")).build()),
                "reply-1", Instant.now(), null, null));
        var msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn("weather done");
        when(agent.call(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
            .thenReturn(Mono.just(msg));

        mvc.perform(post("/threads/t1/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("results", List.of(
                    Map.of("tool_call_id", "call-1", "confirmed", true))))))
            .andExpect(status().isOk());

        verify(turnLeaseStore).release(eq("t1"), eq("tok-1"));
    }

    @Test
    void confirmStreamShouldReturnErrorSseFrameWhenContextMissing() {
        doThrow(new AgentRuntimeService.ConfirmContextNotFoundException("nope"))
            .when(confirmContextStore).checkAvailable(anyString());
        var controller = new ConfirmController(runtimeService, turnLeaseStore, eventBus, sessionUserStore, mcpToolRegistrar);
        var frame = controller.confirmStream("nope", new ConfirmController.ConfirmRequest(List.of()))
            .blockFirst();
        assertNotNull(frame, "should emit error SSE frame");
        assertTrue(frame.data().contains("confirm_context_not_found"),
            "expected error SSE frame, got: " + frame.data());
    }

    @Test
    void confirmStreamShouldStopWritingAfterLeaseLost() throws Exception {
        stubPendingStoreRow();
        when(turnLeaseStore.tryAcquire("t1")).thenReturn("tok-lost");
        // 亚秒配置把「丢锁」逼到 20ms 内出现，早于下面 50ms 才到达的事件
        when(turnLeaseStore.renewInterval()).thenReturn(Duration.ofMillis(20));
        when(turnLeaseStore.ttl()).thenReturn(Duration.ofMillis(60));
        lenient().when(turnLeaseStore.renew(anyString(), anyString()))
            .thenReturn(TurnLeaseStore.RenewOutcome.LOST);
        // 事件由测试显式编排：丢锁后 abandonSession 会**提前**关掉请求流，block() 随即返回，
        // 若源流还在后面慢慢发，断言就变成空断言。multicast sink 同步投递，tryEmitNext 返回即处理完。
        var events = reactor.core.publisher.Sinks.many().multicast()
            .<io.agentscope.core.event.AgentEvent>onBackpressureBuffer();
        var subscribed = new java.util.concurrent.CountDownLatch(1);
        when(agent.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
            .thenReturn(events.asFlux().doOnSubscribe(s -> subscribed.countDown()));

        var controller = new ConfirmController(runtimeService, turnLeaseStore, eventBus, sessionUserStore, mcpToolRegistrar);
        var framesFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
            controller.confirmStream("t1", new ConfirmController.ConfirmRequest(List.of(
                    Map.of("tool_call_id", "call-1", "confirmed", true))))
                .collectList().block(Duration.ofSeconds(10)));
        assertTrue(subscribed.await(3, java.util.concurrent.TimeUnit.SECONDS), "未订阅 agent 事件流");
        awaitRenewCalled();   // 等丢锁真的置位，否则第一个事件走正常路径
        sleep(50);

        events.tryEmitNext(new io.agentscope.core.event.TextBlockDeltaEvent("reply-2", "block-2", "hi"));
        events.tryEmitNext(new io.agentscope.core.event.AgentEndEvent("reply-2"));
        events.tryEmitComplete();

        var frames = framesFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertNotNull(frames);
        assertEquals(1, frames.stream().filter(f -> f.data() != null && f.data().contains("interrupted")).count(),
            "终态帧只应发一帧: " + frames);
        assertTrue(frames.stream().anyMatch(f -> f.data() != null && f.data().contains("lease_lost")),
            frames.toString());
        // 用 any() 而非 anyString()：后者不匹配 null，会漏检「终态帧被落库」
        verify(eventStore, never()).append(any(), any(), any(), any());
        // 两次调用是设计使然（先拆流、后幂等空转）
        verify(eventStore, atLeastOnce()).abandonTurn("t1");
        verify(eventStore, never()).finishTurn("t1");
    }

    @Test
    void confirmStreamShouldAcquireLeaseAndEmitEvents() {
        stubPendingStoreRow();
        when(agent.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
            .thenReturn(reactor.core.publisher.Flux.just(
                (io.agentscope.core.event.AgentEvent) new io.agentscope.core.event.AgentEndEvent("reply-2")));
        when(turnLeaseStore.tryAcquire("t1")).thenReturn("tok-c1");
        var controller = new ConfirmController(runtimeService, turnLeaseStore, eventBus, sessionUserStore, mcpToolRegistrar);
        var frame = controller.confirmStream("t1", new ConfirmController.ConfirmRequest(List.of(
            Map.of("tool_call_id", "call-1", "confirmed", true)))).blockFirst();
        assertNotNull(frame, "should emit SSE frame");
    }
}
