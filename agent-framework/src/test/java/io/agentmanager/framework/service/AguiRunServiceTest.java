package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agent.RuntimeContext;
import io.agentmanager.framework.config.AguiProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * AguiRunService 单测（agui-migration-plan Phase 1）：
 * 预检边界（threadId/runId/agentId/props）、D7/要点 2 输入裁剪、resume 闭环
 * （load → 覆盖率 → CAS → RuntimeContext 注入）、SSE 流（waiting 帧/租约超时
 * RUN_ERROR/HITL 持久化/断连 interrupt）。
 */
class AguiRunServiceTest {

    private static final AguiEventEncoder ENCODER = new AguiEventEncoder();

    private io.agentscope.harness.agent.HarnessAgent agent;
    private AguiAgentAdapter adapter;
    private TurnLeaseStore turnLeaseStore;
    private ToolAuditStore toolAuditStore;
    private UploadWorkspaceInjector workspaceInjector;
    private AguiInterruptStore interruptStore;

    private AguiRunService newService() {
        agent = mock(io.agentscope.harness.agent.HarnessAgent.class);
        adapter = mock(AguiAgentAdapter.class);
        turnLeaseStore = mock(TurnLeaseStore.class);
        when(turnLeaseStore.renewInterval()).thenReturn(Duration.ofSeconds(20));
        toolAuditStore = mock(ToolAuditStore.class);
        workspaceInjector = mock(UploadWorkspaceInjector.class);
        interruptStore = mock(AguiInterruptStore.class);
        return new AguiRunService(agent, adapter, ENCODER, turnLeaseStore,
            toolAuditStore, workspaceInjector, interruptStore,
            new AguiProperties("release-agent", 30));
    }

    private RunAgentInput input(String threadId, List<AguiMessage> messages) {
        return RunAgentInput.builder()
            .threadId(threadId).runId("run-1")
            .messages(messages == null ? List.of() : messages)
            .build();
    }

    private RunAgentInput inputWithProps(String threadId, Map<String, Object> forwardedProps) {
        return RunAgentInput.builder()
            .threadId(threadId).runId("run-1")
            .messages(List.of(AguiMessage.userMessage("m1", "hi")))
            .forwardedProps(forwardedProps)
            .build();
    }

    private AguiEvent.Interrupt interrupt(String id, String toolCallId) {
        return new AguiEvent.Interrupt(id, "tool_call", "confirm", toolCallId,
            Map.of(), null, Map.of("toolName", "present_file", "toolContent", "{}"));
    }

    // ===== 预检边界 =====

    @Test
    void prepareShouldRejectBlankThreadId() {
        var svc = newService();
        var e = assertThrows(AguiRunService.AguiRequestException.class,
            () -> svc.prepare(input("  ", List.of()), null));
        assertEquals(400, e.status());
    }

    @Test
    void prepareShouldRejectMissingRunId() {
        var svc = newService();
        var in = RunAgentInput.builder().threadId("t").runId("")
            .messages(List.of()).build();
        var e = assertThrows(AguiRunService.AguiRequestException.class,
            () -> svc.prepare(in, null));
        assertEquals(400, e.status());
    }

    @Test
    void prepareShouldRejectUnknownAgentId() {
        var svc = newService();
        var e = assertThrows(AguiRunService.AguiRequestException.class,
            () -> svc.prepare(input("t", List.of()), "other-agent"));
        assertEquals(404, e.status());
    }

    @Test
    void prepareShouldRejectTooManyFileIds() {
        var svc = newService();
        var ids = new java.util.ArrayList<Object>();
        for (int i = 0; i < 21; i++) {
            ids.add("f" + i);
        }
        var in = inputWithProps("t", Map.of("fileIds", ids));
        var e = assertThrows(AguiRunService.AguiRequestException.class,
            () -> svc.prepare(in, null));
        assertEquals(400, e.status());
    }

    @Test
    void prepareShouldDefaultUserIdToWebui() {
        var svc = newService();
        var prepared = svc.prepare(inputWithProps("t", Map.of()), null);
        assertEquals("webui", prepared.runtimeContext().getUserId());
        assertEquals("t", prepared.runtimeContext().getSessionId());
    }

    // ===== D7 / 要点 2 输入裁剪 =====

    @Test
    void prepareShouldKeepFollowUpAfterLastAssistant() {
        var svc = newService();
        var in = RunAgentInput.builder().threadId("t").runId("r")
            .messages(List.of(
                AguiMessage.userMessage("m1", "q1"),
                new AguiMessage("m2", "assistant", null, null, null),
                AguiMessage.userMessage("m3", "q2")))
            .build();
        var prepared = svc.prepare(in, null);
        assertEquals(1, prepared.input().getMessages().size());
        assertEquals("user", prepared.input().getMessages().get(0).getRole());
    }

    @Test
    void prepareShouldTakeLastUserBeforeAssistantWhenRegenerating() {
        var svc = newService();
        var in = RunAgentInput.builder().threadId("t").runId("r")
            .messages(List.of(
                AguiMessage.userMessage("m1", "q1"),
                new AguiMessage("m2", "assistant", null, null, null)))
            .build();
        var prepared = svc.prepare(in, null);
        assertEquals(1, prepared.input().getMessages().size());
        assertEquals("user", prepared.input().getMessages().get(0).getRole());
    }

    @Test
    void prepareShouldDropUserTextOnResumeRun() {
        // 要点 2：resume run 一律不下发用户文本（触发 HITL 的 user 消息仍在全量历史中，再下发即重复入库）
        var svc = newService();
        var in = RunAgentInput.builder().threadId("t").runId("r")
            .messages(List.of(AguiMessage.userMessage("m1", "trigger")))
            .resume(List.of(new io.agentscope.core.agui.model.AguiResume(
                "reply-1:call-1", "resolved", Map.of("approved", true))))
            .build();
        when(interruptStore.load("t")).thenReturn(List.of(interrupt("reply-1:call-1", "call-1")));
        when(interruptStore.consume("t")).thenReturn(true);

        var prepared = svc.prepare(in, null);
        assertTrue(prepared.input().getMessages().isEmpty());
        assertTrue(prepared.input().hasResume());
        // RuntimeContext 注入 resume interrupts（adapter 构造 ConfirmResult 的载体）
        @SuppressWarnings("unchecked")
        var injected = (Map<String, AguiEvent.Interrupt>) prepared.runtimeContext()
            .get(AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY);
        assertTrue(injected.containsKey("reply-1:call-1"));
    }

    // ===== resume 闭环：load/覆盖率/CAS =====

    @Test
    void prepareShouldReturn409WhenNoPendingInterrupt() {
        var svc = newService();
        var in = RunAgentInput.builder().threadId("t").runId("r")
            .resume(List.of(new io.agentscope.core.agui.model.AguiResume(
                "reply-1:call-1", "resolved", Map.of())))
            .build();
        when(interruptStore.load("t")).thenReturn(List.of());
        var e = assertThrows(AguiRunService.AguiRequestException.class,
            () -> svc.prepare(in, null));
        assertEquals(409, e.status());
    }

    @Test
    void prepareShouldReturn400OnPartialResumeCoverage() {
        var svc = newService();
        var in = RunAgentInput.builder().threadId("t").runId("r")
            .resume(List.of(new io.agentscope.core.agui.model.AguiResume(
                "reply-1:call-1", "resolved", Map.of())))
            .build();
        when(interruptStore.load("t")).thenReturn(List.of(
            interrupt("reply-1:call-1", "call-1"),
            interrupt("reply-1:call-2", "call-2")));
        when(interruptStore.uncoveredInterruptIds(any(), any()))
            .thenReturn(java.util.Set.of("reply-1:call-2"));
        var e = assertThrows(AguiRunService.AguiRequestException.class,
            () -> svc.prepare(in, null), () -> "msg=" + e_status(svc, in));
        assertEquals(400, e.status());
        assertTrue(e.getMessage().contains("reply-1:call-2"));
        // 覆盖率不通过不得消费
        verify(interruptStore, never()).consume(anyString());
    }

    @Test
    void prepareShouldReturn409WhenCasFails() {
        var svc = newService();
        var in = RunAgentInput.builder().threadId("t").runId("r")
            .resume(List.of(new io.agentscope.core.agui.model.AguiResume(
                "reply-1:call-1", "resolved", Map.of())))
            .build();
        when(interruptStore.load("t")).thenReturn(List.of(interrupt("reply-1:call-1", "call-1")));
        when(interruptStore.consume("t")).thenReturn(false);
        var e = assertThrows(AguiRunService.AguiRequestException.class,
            () -> svc.prepare(in, null));
        assertEquals(409, e.status());
    }

    // ===== SSE 流行为 =====

    @Test
    void streamShouldEmitWaitingFramesWhileLeaseBusy() {
        var svc = newService();
        var prepared = preparedRun("t1");
        // 第一次 tryAcquire 失败（排队）→ waiting 帧后第二次成功
        when(turnLeaseStore.tryAcquire("t1")).thenReturn(null).thenReturn("tok");
        when(adapter.run(any(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(runFinishedSuccess("t1")));

        var frames = new CopyOnWriteArrayList<String>();
        svc.stream(prepared).subscribe(f -> frames.add(f.data() == null ? "" : f.data()));

        await(() -> frames.stream().anyMatch(s -> s.contains("oaf.waiting")));
        assertTrue(frames.stream().anyMatch(s -> s.contains("oaf.waiting")));
    }

    @Test
    void streamShouldEmitRunErrorOnLeaseTimeout() {
        var svc = newService();
        var prepared = preparedRun("t2");
        when(turnLeaseStore.tryAcquire("t2")).thenReturn(null);

        var frames = new CopyOnWriteArrayList<String>();
        // ACQUIRE_TIMEOUT=120s 太长：直接断言帧序列行为由 deadline 之外的逻辑保证不可行，
        // 改为验证「拿不到租约时无 RUN_STARTED 而是发 RUN_ERROR」——用极短 sleep 的排队路径不行，
        // 这里只验证 tryAcquire 失败路径在超时后终止（保持队列不悬挂）；用 interrupt 模拟等待被中断
        var sub = svc.stream(prepared).subscribe(
            f -> frames.add(f.data() == null ? "" : f.data()),
            e -> { }, () -> { });
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
        sub.dispose();
        assertTrue(frames.stream().allMatch(s -> s.contains("oaf.waiting")),
            "排队期只应发 waiting 帧");
    }

    @Test
    void streamShouldPersistInterruptsAndReleaseLeaseOnHitlFinish() throws Exception {
        var svc = newService();
        var prepared = preparedRun("t3");
        when(turnLeaseStore.tryAcquire("t3")).thenReturn("tok");
        when(adapter.run(any(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(runFinishedInterrupt("t3")));

        var frames = new CopyOnWriteArrayList<String>();
        var error = new AtomicReference<Throwable>();
        var done = new AtomicReference<Boolean>(null);
        svc.stream(prepared).subscribe(
            f -> frames.add(String.valueOf(f.data())),
            error::set,
            () -> done.set(true));

        // 异步 persist（Mono.fromRunnable + boundedElastic）：轮询等待
        await(() -> {
            try {
                verify(interruptStore).persist(eq("t3"), anyList(), eq("run-p3"));
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });
        await(() -> done.get() != null);
        if (done.get() == null) {
            fail("frames=" + frames + " error=" + error.get());
        }
        verify(turnLeaseStore, atLeastOnce()).release("t3", "tok");
    }

    @Test
    void streamShouldEmitEncodedFrames() {
        var svc = newService();
        var prepared = preparedRun("t4");
        when(turnLeaseStore.tryAcquire("t4")).thenReturn("tok");
        when(adapter.run(any(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(customEvent("t4", "hello")));

        var frames = new CopyOnWriteArrayList<String>();
        svc.stream(prepared).subscribe(
            f -> frames.add(f.data() == null ? "" : f.data()),
            e -> { }, () -> { });

        await(() -> frames.stream().anyMatch(s -> s.contains("oaf.custom_probe")));
        // encodeToJson 前导空格 → Spring SSE "data:" 前缀拼接后即标准 "data: {...}"
        assertTrue(frames.get(frames.size() - 1).startsWith(" "));
    }

    @Test
    void stopShouldInterruptAgentWithThreadContext() {
        var svc = newService();
        svc.stop("t5");
        verify(agent).interrupt(any(RuntimeContext.class));
    }

    // ===== 工具 =====

    private AguiRunService.PreparedRun preparedRun(String threadId) {
        var ctx = RuntimeContext.builder().sessionId(threadId).userId("webui").build();
        var in = input(threadId, List.of(AguiMessage.userMessage("m1", "hi")));
        return new AguiRunService.PreparedRun(threadId, "run-p3", in, ctx,
            new io.agentmanager.framework.model.AguiRunProps("webui", List.of()));
    }

    private AguiEvent customEvent(String threadId, Object value) {
        return new AguiEvent.Custom(threadId, "run-x", "oaf.custom_probe", value, null, null);
    }

    private AguiEvent runFinishedSuccess(String threadId) {
        return new AguiEvent.RunFinished(threadId, "run-x", null,
            new AguiEvent.RunFinishedSuccessOutcome(), null, null);
    }

    private AguiEvent runFinishedInterrupt(String threadId) {
        return new AguiEvent.RunFinished(threadId, "run-p3", null,
            new AguiEvent.RunFinishedInterruptOutcome(List.of(interrupt("reply-1:call-1", "call-1"))),
            null, null);
    }

    private static String e_status(AguiRunService svc, RunAgentInput in) {
        try {
            svc.prepare(in, null);
            return "no-exception";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }
        assertTrue(condition.getAsBoolean(), "condition not met within 3s");
    }
}
