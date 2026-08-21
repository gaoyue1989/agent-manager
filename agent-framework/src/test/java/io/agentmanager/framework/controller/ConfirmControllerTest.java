package io.agentmanager.framework.controller;

import java.time.Duration;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HITL 确认端点测试（hitl-permission-plan.md 6.3 / 12.5）：
 * /threads/{sid}/confirm 同步恢复 + 404/409 错误码；confirm-stream 预处理 + 租约。
 *
 * <p>无状态架构：确认上下文落库 ConfirmContextStore（mock 验证委托），确认恢复为新执行段需 acquire 租约。
 */
class ConfirmControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mvc;
    private HarnessAgent agent;
    private AgentRuntimeService runtimeService;
    private ConfirmContextStore confirmContextStore;
    private TurnLeaseStore turnLeaseStore;

    @BeforeEach
    void setUp() throws Exception {
        agent = mock(HarnessAgent.class);
        confirmContextStore = mock(ConfirmContextStore.class);
        turnLeaseStore = mock(TurnLeaseStore.class);
        when(turnLeaseStore.renewInterval()).thenReturn(Duration.ofSeconds(20));
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
        mvc = MockMvcBuilders.standaloneSetup(new ConfirmController(runtimeService, turnLeaseStore)).build();

        // 先注入确认上下文：模拟前端收到 permission_ask（invokeStream 链路 storeConfirmContext 落库）
        var ask = new io.agentscope.core.event.RequireUserConfirmEvent(
            "evt-1", "src-1", "reply-1",
            List.of(ToolUseBlock.builder().id("call-1").name("get_weather")
                .input(Map.of("city", "beijing")).build()));
        when(agent.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
            .thenReturn(reactor.core.publisher.Flux.just(ask));
        runtimeService.invokeStream("query weather", "t1", "alice").collectList().block();
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
    void confirmStreamShouldReturnErrorSseFrameWhenContextMissing() {
        doThrow(new AgentRuntimeService.ConfirmContextNotFoundException("nope"))
            .when(confirmContextStore).checkAvailable(anyString());
        var controller = new ConfirmController(runtimeService, turnLeaseStore);
        var frame = controller.confirmStream("nope", new ConfirmController.ConfirmRequest(List.of()))
            .blockFirst();
        assertNotNull(frame, "should emit error SSE frame");
        assertTrue(frame.data().contains("confirm_context_not_found"),
            "expected error SSE frame, got: " + frame.data());
    }

    @Test
    void confirmStreamShouldAcquireLeaseAndEmitEvents() {
        stubPendingStoreRow();
        when(agent.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
            .thenReturn(reactor.core.publisher.Flux.just(
                (io.agentscope.core.event.AgentEvent) new io.agentscope.core.event.AgentEndEvent("reply-2")));
        when(turnLeaseStore.tryAcquire("t1")).thenReturn("tok-c1");
        var controller = new ConfirmController(runtimeService, turnLeaseStore);
        var frame = controller.confirmStream("t1", new ConfirmController.ConfirmRequest(List.of(
            Map.of("tool_call_id", "call-1", "confirmed", true)))).blockFirst();
        assertNotNull(frame, "should emit SSE frame");
    }
}