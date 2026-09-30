package io.agentmanager.framework.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.RemoteConfirmBridge.RemoteTaskClient;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import io.agentscope.harness.agent.subagent.task.RemoteTaskStatus;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RemoteConfirmBridge 单测（travel-fulfillment 设计 §5，M0 定形态）：
 * 在途任务登记（agent_spawn 结果解析 + 声明清单匹配）、快照轮询落远程行（F15）、
 * 并发防御（§5.3 ERROR 审计照常排队）、决策路由（approve→ALLOW / reject→DENY，
 * 未决策工具 fail-closed）、TTL 超时自动 DENY（§5.4）、终态唤醒 lead 汇总 turn（§4 步骤 7）。
 *
 * <p>不起真实 HTTP：RemoteTaskClient 端口注入 fake（AgentProtocolTaskClient 为 final
 * 具体类，SDK 调用面经接口缝隔离）。
 */
class RemoteConfirmBridgeTest {

    private static final String SID = "webui-1";
    private static final String TASK_ID = "t-123";
    private static final String CONFIRM_KEY = "task:" + TASK_ID;
    private static final String ENDPOINT = "http://booking.agent-platform.svc:8100";

    private ConfirmContextStore store;
    private AgentRuntimeService runtimeService;
    private SessionEventBus eventBus;
    private SessionEventStore eventStore;
    private SessionUserStore sessionUserStore;
    private TurnLeaseStore turnLeaseStore;
    private ToolAuditStore toolAuditStore;
    private FakeTaskClient taskClient;
    private RemoteConfirmBridge bridge;

    /** fake SDK 客户端：记录 resume 决策、可编排状态快照序列（队列优先，耗尽后回落 nextStatus / 终态白名单） */
    static class FakeTaskClient implements RemoteTaskClient {
        RemoteTaskStatus nextStatus;
        final java.util.ArrayDeque<RemoteTaskStatus> statusSequence = new java.util.ArrayDeque<>();
        /** 命中的 taskId 直接返回 success（多任务并发场景按任务区分快照） */
        final java.util.Set<String> terminalTaskIds = new java.util.HashSet<>();
        final List<List<RemoteConfirmDecision>> resumes = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger statusCalls =
            new java.util.concurrent.atomic.AtomicInteger();
        IOException failResumeWith;

        @Override
        public RemoteTaskStatus getStatus(String url, Map<String, String> headers, String taskId) {
            statusCalls.incrementAndGet();
            var next = statusSequence.poll();
            if (next != null) {
                return next;
            }
            if (terminalTaskIds.contains(taskId)) {
                return new RemoteTaskStatus("success", null, List.of());
            }
            return nextStatus;
        }

        @Override
        public void resumeTask(String url, Map<String, String> headers, String taskId,
                               List<RemoteConfirmDecision> decisions) throws IOException {
            if (failResumeWith != null) {
                throw failResumeWith;
            }
            resumes.add(List.copyOf(decisions));
        }
    }

    @BeforeEach
    void setUp() {
        store = mock(ConfirmContextStore.class);
        runtimeService = mock(AgentRuntimeService.class);
        eventStore = mock(SessionEventStore.class);
        when(eventStore.queryAfter(anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(Flux.empty());
        when(eventStore.findLatest(anyString())).thenReturn(null);
        when(eventStore.findMaxSeq(anyString())).thenReturn(0);
        when(eventStore.append(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        eventBus = new SessionEventBus(eventStore);
        sessionUserStore = mock(SessionUserStore.class);
        turnLeaseStore = mock(TurnLeaseStore.class);
        org.mockito.Mockito.lenient().when(turnLeaseStore.renewInterval()).thenReturn(Duration.ofSeconds(20));
        org.mockito.Mockito.lenient().when(turnLeaseStore.ttl()).thenReturn(Duration.ofSeconds(60));
        org.mockito.Mockito.lenient().when(turnLeaseStore.tryAcquire(anyString())).thenReturn("tok-1");
        toolAuditStore = mock(ToolAuditStore.class);
        taskClient = new FakeTaskClient();
        bridge = new RemoteConfirmBridge(store, runtimeService, eventBus, sessionUserStore,
            turnLeaseStore, toolAuditStore, taskClient, 1L /* 1ms 轮询：终态监听测试加速 */);
        // CARD_QUEUED 审计仅在落卡 insert 生效（affected==1，§18.2）时记：默认 stub 为 1
        org.mockito.Mockito.lenient().when(store.put(anyString(), anyString(), anyList(),
            any(), any(), any(), any())).thenReturn(1);
        when(runtimeService.oafConfig()).thenReturn(oafConfigWithBooking());
    }

    /** 声明清单：booking 带远程 endpoint；local-helper 为本地子 agent（无 endpoint） */
    private static OafConfig oafConfigWithBooking() {
        return new OafConfig(
            "trip-lead", "acme", "trip-lead", "1.0.0", "acme/trip-lead",
            "lead", "@acme", "MIT", List.of(), "you are a planner.",
            List.of(), List.of(),
            List.of(new OafConfig.SubAgentConfig("internal", "booking", "1.0.0",
                "订票专员", List.of(), true, ENDPOINT),
                new OafConfig.SubAgentConfig("internal", "local-helper", "1.0.0",
                    "本地助手", List.of(), true, null)),
            List.of(), List.of(),
            new OafConfig.ModelConfig("openai", "gpt-4", ""),
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            new OafConfig.MemoryConfig("editable", Map.of()),
            Map.of());
    }

    private void registerInFlightTask() {
        bridge.onAgentSpawnResult(SID, Map.of("agent_key", "booking"),
            "status: submitted\ntask_id: " + TASK_ID + "\nUse task_output(task_id='" + TASK_ID + "') ...");
    }

    /** store 远程行（消费路由/超时治理的查询返回形态） */
    private static ConfirmContextStore.PendingConfirm remoteRow(String... toolIds) {
        return remoteRowFor(TASK_ID, toolIds);
    }

    /** 指定 task_id 的远程行（confirm_key 与锚点同步派生，供多任务并发场景） */
    private static ConfirmContextStore.PendingConfirm remoteRowFor(String taskId, String... toolIds) {
        var ids = toolIds.length > 0 ? toolIds : new String[]{"call-1"};
        var calls = java.util.Arrays.stream(ids)
            .map(id -> ToolUseBlock.builder().id(id).name("create_order")
                .input(Map.of("order_id", "O-1")).build())
            .toList();
        // Map.of 不允许 null 值：child_reply_id 用 LinkedHashMap 承载 null（快照不可得形态）
        var anchor = new java.util.LinkedHashMap<String, Object>();
        anchor.put("service", "booking");
        anchor.put("task_id", taskId);
        anchor.put("child_reply_id", null);
        return new ConfirmContextStore.PendingConfirm(SID, RemoteConfirmBridge.confirmKeyFor(taskId),
            null, calls, Instant.now(), null, null, anchor);
    }

    // ===== 1. 在途任务登记 =====

    @Test
    void spawnResultShouldRegisterInFlightRemoteTaskByDeclaration() {
        registerInFlightTask();
        assertEquals(1, bridge.inFlightCount(), "booking 有 endpoint 声明 → 登记");
    }

    @Test
    void spawnResultShouldRegisterByAgentIdParam() {
        // demo 实测（2026-09-29 双进程）：SDK 2.0.3 AgentSpawnTool 实际 schema 主键是
        // agent_id（真实模型自然使用该参数名，任务正确路由），漏匹配会跳过登记、
        // 快照轮询（F15 唯一确认源）永不生效——回归钉
        bridge.onAgentSpawnResult(SID, Map.of("agent_id", "booking", "timeout_seconds", 60,
                "task", "订票任务"),
            "status: submitted\ntask_id: " + TASK_ID);
        assertEquals(1, bridge.inFlightCount(), "agent_id 入参应同样命中声明登记");
    }

    @Test
    void spawnResultShouldStripTrailingQuoteFromTaskId() {
        // demo 实测（2026-09-29 双进程）：同步超时升格变体的结果文本 task_id 紧邻 JSON
        // 收尾引号，\S+ 连引号捕获后快照查询恒 404，轮询静默失效——回归钉
        bridge.onAgentSpawnResult(SID, Map.of("agent_id", "booking"),
            "agent_id: booking\\nsession_id: sub-1\\nstatus: timeout\\ntask_id: " + TASK_ID + "\"");
        assertEquals(1, bridge.inFlightCount(), "尾随引号必须剥离后登记");
        assertTrue(bridge.inFlightTaskIds().contains(TASK_ID), "登记的 taskId 不得含引号");
    }

    @Test
    void localSubagentSpawnShouldNotRegister() {
        bridge.onAgentSpawnResult(SID, Map.of("agent_key", "local-helper"), "task_id: t-local");
        assertEquals(0, bridge.inFlightCount(), "无 endpoint 声明 = 本地子 agent，与远程确认无关");
    }

    @Test
    void spawnWithoutTaskIdOrAgentShouldBeIgnored() {
        bridge.onAgentSpawnResult(SID, Map.of("agent_key", "booking"), "同步执行完成，无后台句柄");
        bridge.onAgentSpawnResult(SID, Map.of("message", "hi"), "task_id: t-orphan");
        assertEquals(0, bridge.inFlightCount());
    }

    // ===== 2. 快照轮询 → 远程行（F15） =====

    @Test
    void pollShouldQueueRemoteConfirmRowWithAnchor() throws Exception {
        registerInFlightTask();
        taskClient.nextStatus = new RemoteTaskStatus("awaiting_confirm", null,
            List.of(new RemotePendingConfirm("call-1", "create_order", "{\"order_id\":\"O-1\"}")));
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.empty());
        when(store.findUnconsumedRemote(SID)).thenReturn(List.of());

        bridge.pollOnce();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> toolsCap =
            ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<String> anchorCap = ArgumentCaptor.forClass(String.class);
        verify(store).put(eq(CONFIRM_KEY), eq(SID), toolsCap.capture(), isNull(), isNull(),
            isNull(), anchorCap.capture());

        var tools = toolsCap.getValue();
        assertEquals(1, tools.size());
        assertEquals("call-1", tools.get(0).get("id"));
        assertEquals("create_order", tools.get(0).get("name"));
        assertEquals(Map.of("order_id", "O-1"), tools.get(0).get("input"),
            "toolInputJson 反序列化为 input Map");
        var anchor = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(anchorCap.getValue(), Map.class);
        assertEquals("booking", anchor.get("service"));
        assertEquals(TASK_ID, anchor.get("task_id"));
        // 审计：落卡记录（设计 §10）
        verify(toolAuditStore).record(eq(SID), eq("remote_confirm"), eq(TASK_ID),
            eq("CARD_QUEUED"), anyString());
    }

    @Test
    void pollShouldSkipWhenCardAlreadyQueued() {
        registerInFlightTask();
        taskClient.nextStatus = new RemoteTaskStatus("awaiting_confirm", null, List.of());
        when(store.findPending(SID, CONFIRM_KEY))
            .thenReturn(java.util.Optional.of(remoteRow()));

        bridge.pollOnce();
        verify(store, never()).put(anyString(), anyString(), anyList(), any(), any(), any(), any());
    }

    @Test
    void concurrentRemotePendingShouldAuditErrorAndStillQueue() {
        registerInFlightTask();
        taskClient.nextStatus = new RemoteTaskStatus("awaiting_confirm", null, List.of());
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.empty());
        // 同 session 已有另一远程行未消费（违反写任务串行规约的观测信号）
        when(store.findUnconsumedRemote(SID)).thenReturn(List.of(
            new ConfirmContextStore.PendingConfirm(SID, "task:t-other", null,
                List.of(), Instant.now(), null, null,
                Map.of("service", "approval", "task_id", "t-other"))));

        bridge.pollOnce();
        // 新任务照常排队（不悬挂、不丢弃），同时落 QUEUE_CONFLICT 审计
        verify(store).put(eq(CONFIRM_KEY), eq(SID), anyList(), isNull(), isNull(), isNull(), anyString());
        verify(toolAuditStore).record(eq(SID), eq("remote_confirm"), eq(TASK_ID),
            eq("QUEUE_CONFLICT"), argThat(p -> p.contains("serial_rule_violation")));
    }

    @Test
    void pollShouldDiscardStaleRowWhenTaskTurnsTerminalByItself() {
        registerInFlightTask();
        taskClient.nextStatus = new RemoteTaskStatus("success", null, List.of());
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.of(remoteRow()));

        bridge.pollOnce();
        assertEquals(0, bridge.inFlightCount(), "终态任务清出登记表");
        verify(store).consume(SID, CONFIRM_KEY);
        verify(toolAuditStore).record(eq(SID), eq("remote_confirm"), eq(TASK_ID),
            eq("TASK_TERMINAL"), anyString());
    }

    // ===== 2.1 传输类错误 vs 真实失败（SDK 把 HTTP>=400/404 映射为 error 状态） =====

    /** SDK 2.0.3 传输错误文案形态（字节码实证）：404 → "task not found"、>=400 → "HTTP <code>: <body>" */
    @Test
    void transportErrorHeuristicShouldMatchSdkErrorMessages() {
        assertTrue(RemoteConfirmBridge.isTransportError(
            new RemoteTaskStatus("error", "task not found", List.of())));
        assertTrue(RemoteConfirmBridge.isTransportError(
            new RemoteTaskStatus("error", "HTTP 503: Service Unavailable", List.of())),
            "HTTP 前缀形态（网关 5xx/重启窗口）是传输错误");
        assertFalse(RemoteConfirmBridge.isTransportError(
            new RemoteTaskStatus("error", "member LLM call failed: timeout", List.of())),
            "业务异常文案 = 真实任务失败");
        assertFalse(RemoteConfirmBridge.isTransportError(
            new RemoteTaskStatus("failed", "task not found", List.of())),
            "非 error 状态不参与传输分类");
        assertFalse(RemoteConfirmBridge.isTransportError(
            new RemoteTaskStatus("error", null, List.of())),
            "无 error 文案的 error 状态按真实失败处理（fail-closed）");
        assertFalse(RemoteConfirmBridge.isTransportError(
            new RemoteTaskStatus("awaiting_confirm", null, List.of())));
    }

    /** 瞬时 404/5xx：保留在途登记与未消费卡片，不下发终态治理（§9 子服务不可达分支） */
    @Test
    void pollShouldKeepRegistrationOnTransportError() {
        registerInFlightTask();
        taskClient.nextStatus = new RemoteTaskStatus("error", "HTTP 503: Service Unavailable", List.of());
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.of(remoteRow()));

        bridge.pollOnce();
        bridge.pollOnce();

        assertEquals(1, bridge.inFlightCount(), "传输类错误不清登记");
        verify(store, never()).consume(anyString(), anyString());
        verify(toolAuditStore, never()).record(anyString(), anyString(), anyString(),
            eq("TASK_TERMINAL"), anyString());
    }

    /** 连续超限（默认 60 次）才判失联放弃：清登记 + 审计；已落确认卡留给 TTL 治理 */
    @Test
    void pollShouldGiveUpRegistrationAfterConsecutiveTransportErrors() {
        registerInFlightTask();
        taskClient.nextStatus = new RemoteTaskStatus("error", "task not found", List.of());

        for (int i = 0; i < RemoteConfirmBridge.MAX_TRANSPORT_ERROR_POLLS; i++) {
            bridge.pollOnce();
        }
        assertEquals(0, bridge.inFlightCount(), "连续超限后放弃登记");
        verify(toolAuditStore).record(eq(SID), eq("remote_confirm"), eq(TASK_ID),
            eq("TRANSPORT_ERROR_GAVE_UP"), argThat(p -> p.contains("task not found")));

        // 放弃后登记表为空：后续轮询不再打子服务（早退）
        int calls = taskClient.statusCalls.get();
        bridge.pollOnce();
        assertEquals(calls, taskClient.statusCalls.get());
    }

    /** awaiting_confirm/终态会重置连续计数：间歇性传输错误不累计成放弃 */
    @Test
    void pollShouldResetTransportErrorCountOnAwaitingConfirm() {
        registerInFlightTask();
        taskClient.nextStatus = new RemoteTaskStatus("error", "task not found", List.of());
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.empty());
        when(store.findUnconsumedRemote(SID)).thenReturn(List.of());

        for (int i = 0; i < RemoteConfirmBridge.MAX_TRANSPORT_ERROR_POLLS - 1; i++) {
            bridge.pollOnce();
        }
        // 一次 awaiting 打断连续序列
        taskClient.nextStatus = new RemoteTaskStatus("awaiting_confirm", null, List.of());
        bridge.pollOnce();
        // 再来一段未达上限的传输错误
        taskClient.nextStatus = new RemoteTaskStatus("error", "task not found", List.of());
        for (int i = 0; i < RemoteConfirmBridge.MAX_TRANSPORT_ERROR_POLLS - 1; i++) {
            bridge.pollOnce();
        }

        assertEquals(1, bridge.inFlightCount(), "计数被打断重置，不得放弃登记");
    }

    /** member 写入的业务失败（error 文案非传输形态）= 真实终态：照常终态治理 */
    @Test
    void pollShouldTreatGenuineErrorStatusAsTerminalFailure() {
        registerInFlightTask();
        taskClient.nextStatus = new RemoteTaskStatus("error", "member LLM call failed", List.of());
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.of(remoteRow()));

        bridge.pollOnce();
        assertEquals(0, bridge.inFlightCount(), "真实失败走终态清登记");
        verify(store).consume(SID, CONFIRM_KEY);
        verify(toolAuditStore).record(eq(SID), eq("remote_confirm"), eq(TASK_ID),
            eq("TASK_TERMINAL"), anyString());
    }

    // ===== 3. 决策路由（§5.4） =====

    @Test
    void routeDecisionShouldResumeWithAllowOnApprove() {
        registerInFlightTask();
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.of(remoteRow()));
        var out = bridge.routeDecision(SID, CONFIRM_KEY,
            List.of(Map.of("tool_call_id", "call-1", "confirmed", true)));

        assertEquals("remote", out.get("routed"));
        assertEquals("ALLOW", out.get("decision"));
        assertEquals(TASK_ID, out.get("task_id"));
        verify(store).consume(SID, CONFIRM_KEY);
        assertEquals(1, taskClient.resumes.size());
        var decisions = taskClient.resumes.get(0);
        assertEquals(1, decisions.size());
        assertEquals("call-1", decisions.get(0).getToolCallId());
        assertTrue(decisions.get(0).isApproved(), "approve → ALLOW");
        verify(toolAuditStore).record(eq(SID), eq("remote_confirm"), eq(TASK_ID),
            eq("ALLOW"), anyString());
    }

    @Test
    void routeDecisionShouldDenyUndecidedToolsFailClosed() {
        registerInFlightTask();
        when(store.findPending(SID, CONFIRM_KEY))
            .thenReturn(java.util.Optional.of(remoteRow("call-1", "call-2")));

        bridge.routeDecision(SID, CONFIRM_KEY,
            List.of(Map.of("tool_call_id", "call-1", "confirmed", true)));

        var decisions = taskClient.resumes.get(0);
        assertEquals(2, decisions.size());
        assertTrue(decisions.get(0).isApproved());
        assertFalse(decisions.get(1).isApproved(), "results 未覆盖的挂起工具 fail-closed DENY");
        // 任一 DENY 即 decision=DENY 语义由 ALLOW 判定取反：存在 approved=true → ALLOW；
        // 全 DENY 场景单独断言
    }

    @Test
    void routeDecisionShouldDenyAllOnReject() {
        registerInFlightTask();
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.of(remoteRow()));

        var out = bridge.routeDecision(SID, CONFIRM_KEY,
            List.of(Map.of("tool_call_id", "call-1", "confirmed", false)));
        assertEquals("DENY", out.get("decision"));
        assertFalse(taskClient.resumes.get(0).get(0).isApproved(), "reject → DENY（M0 F17：拒绝路径干净终止）");
    }

    @Test
    void routeDecisionShouldFailWhenRowMissing() {
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.empty());
        assertThrows(AgentRuntimeService.ConfirmContextNotFoundException.class,
            () -> bridge.routeDecision(SID, CONFIRM_KEY, List.of()));
        assertEquals(0, taskClient.resumes.size(), "无行不 resume");
    }

    @Test
    void routeDecisionShouldPropagateAlreadyConsumed() {
        registerInFlightTask();
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.of(remoteRow()));
        when(store.consume(SID, CONFIRM_KEY))
            .thenThrow(new AgentRuntimeService.ConfirmAlreadyConsumedException(SID));
        assertThrows(AgentRuntimeService.ConfirmAlreadyConsumedException.class,
            () -> bridge.routeDecision(SID, CONFIRM_KEY, List.of()));
        assertEquals(0, taskClient.resumes.size(), "CAS 失败不得重复 resume");
    }

    // ===== 4. resume 后终态唤醒（§4 步骤 7） =====

    @Test
    void routeDecisionShouldWakeLeadSummaryTurnOnTerminal() {
        registerInFlightTask();
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.of(remoteRow()));
        // 终态监听：快照轮询立即见 success
        taskClient.nextStatus = new RemoteTaskStatus("success", null, List.of());
        when(runtimeService.invokeStream(anyString(), anyString(), any()))
            .thenReturn(Flux.just(Map.of("type", "done", "token", "汇总完成")));

        bridge.routeDecision(SID, CONFIRM_KEY,
            List.of(Map.of("tool_call_id", "call-1", "confirmed", true)));

        // 唤醒 turn：acquire 租约 → invokeStream → 事件写 durable SSE → 收尾释放
        verify(turnLeaseStore, timeout(3000)).tryAcquire(SID);
        verify(eventStore, timeout(3000)).append(eq(SID), anyString(), eq("done"), anyString());
        verify(eventStore, timeout(3000)).finishTurn(SID);
        verify(turnLeaseStore, timeout(3000)).release(SID, "tok-1");
    }

    @Test
    void wakeShouldSkipWhenLeaseBusy() {
        when(turnLeaseStore.tryAcquire(SID)).thenReturn(null);
        bridge.wakeLead(SID, TASK_ID, "成功完成");
        verify(runtimeService, never()).invokeStream(anyString(), anyString(), any());
        verify(turnLeaseStore, never()).release(anyString(), anyString());
    }

    /** 等待终态监听完成预期轮询数（FakeTaskClient.statusCalls 原子可观测） */
    private static void awaitPolls(FakeTaskClient client, int expectedPolls, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (client.statusCalls.get() < expectedPolls && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(client.statusCalls.get() >= expectedPolls,
            "终态监听未在限时内完成 " + expectedPolls + " 次轮询（实际 "
                + client.statusCalls.get() + "）");
    }

    /** 监听期瞬时 5xx（SDK 映射 error 状态）：非终态，不发「失败：task not found」假汇总 */
    @Test
    void terminalWatchShouldNotWakeLeadOnTransportError() throws Exception {
        registerInFlightTask();
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.of(remoteRow()));
        taskClient.nextStatus = new RemoteTaskStatus("error", "HTTP 502: Bad Gateway", List.of());

        bridge.routeDecision(SID, CONFIRM_KEY,
            List.of(Map.of("tool_call_id", "call-1", "confirmed", true)));

        // 监听跑满 MAX_TERMINAL_POLLS 次有界轮询后放弃（§13.9 降级），全程不唤醒
        awaitPolls(taskClient, RemoteConfirmBridge.MAX_TERMINAL_POLLS, 15_000);
        verify(runtimeService, never()).invokeStream(anyString(), anyString(), any());
        verify(toolAuditStore, never()).record(anyString(), anyString(), anyString(),
            eq("TASK_TERMINAL"), anyString());
    }

    /** 传输错误不终止监听：随后到达的真实失败照常唤醒 lead 汇总 turn */
    @Test
    void terminalWatchShouldWakeOnGenuineFailureAfterTransportErrors() {
        registerInFlightTask();
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.of(remoteRow()));
        taskClient.statusSequence.add(new RemoteTaskStatus("error", "task not found", List.of()));
        taskClient.statusSequence.add(new RemoteTaskStatus("failed", "member LLM exploded", List.of()));
        when(runtimeService.invokeStream(anyString(), anyString(), any()))
            .thenReturn(Flux.just(Map.of("type", "done", "token", "汇总完成")));

        bridge.routeDecision(SID, CONFIRM_KEY,
            List.of(Map.of("tool_call_id", "call-1", "confirmed", true)));

        verify(turnLeaseStore, timeout(3000)).tryAcquire(SID);
        verify(eventStore, timeout(3000)).append(eq(SID), anyString(), eq("done"), anyString());
        verify(turnLeaseStore, timeout(3000)).release(SID, "tok-1");
    }

    /**
     * 并发第二个远程决策的终态监听不被第一个阻塞（wakeExecutor 每任务一线程，§5.2/§5.3）。
     * 判定锚点：第一个任务持续传输错误（有界轮询中、永不唤醒），第二个任务立即 success——
     * 第二个唤醒发生时第一个的轮询必须远未跑满（若共享单线程，第二个唤醒只可能出现在
     * 第一个跑满 MAX_TERMINAL_POLLS 之后）。
     */
    @Test
    void concurrentTerminalWatchesShouldNotSerialize() throws Exception {
        registerInFlightTask();
        bridge.onAgentSpawnResult(SID, Map.of("agent_key", "booking"), "task_id: t-456");
        var secondKey = RemoteConfirmBridge.confirmKeyFor("t-456");
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.of(remoteRow()));
        when(store.findPending(SID, secondKey))
            .thenReturn(java.util.Optional.of(remoteRowFor("t-456")));
        when(runtimeService.invokeStream(anyString(), anyString(), any()))
            .thenReturn(Flux.just(Map.of("type", "done", "token", "ok")));

        taskClient.nextStatus = new RemoteTaskStatus("error", "HTTP 503: overloaded", List.of());
        taskClient.terminalTaskIds.add("t-456");

        bridge.routeDecision(SID, CONFIRM_KEY,
            List.of(Map.of("tool_call_id", "call-1", "confirmed", true)));
        bridge.routeDecision(SID, secondKey,
            List.of(Map.of("tool_call_id", "call-1", "confirmed", true)));

        // 第二个任务的唤醒（唯一 tryAcquire 来源——第一个任务永不终态）须尽快发生
        verify(turnLeaseStore, timeout(5000)).tryAcquire(SID);
        assertTrue(taskClient.statusCalls.get() < RemoteConfirmBridge.MAX_TERMINAL_POLLS,
            "第二个任务唤醒时第一个监听已跑满轮询上限 → 两监听被串行化（statusCalls="
                + taskClient.statusCalls.get() + "）");
    }

    // ===== 5. TTL 超时治理（§5.4 用户不作为分支） =====

    @Test
    void sweepShouldAutoDenyExpiredRemoteRows() {
        registerInFlightTask();
        when(store.findExpiredRemoteRows()).thenReturn(List.of(remoteRow("call-1", "call-2")));

        bridge.sweepExpiredRemote();

        var decisions = taskClient.resumes.get(0);
        assertEquals(2, decisions.size());
        decisions.forEach(d -> assertFalse(d.isApproved(), "超时自动 DENY"));
        verify(store).consume(SID, CONFIRM_KEY);
        verify(toolAuditStore).record(eq(SID), eq("remote_confirm"), eq(TASK_ID),
            eq("DENY"), argThat(p -> p.contains("confirm_timeout")));
    }

    @Test
    void sweepShouldStillConsumeRowWhenResumeFails() {
        when(store.findExpiredRemoteRows()).thenReturn(List.of(remoteRow()));
        // 未登记 + 声明可解析 endpoint → resume 抛 IO（子服务不可达）
        taskClient.failResumeWith = new IOException("connection refused");

        bridge.sweepExpiredRemote();
        verify(store).consume(SID, CONFIRM_KEY);
        verify(toolAuditStore).record(eq(SID), eq("remote_confirm"), eq(TASK_ID),
            eq("TIMEOUT_RESUME_FAILED"), anyString());
    }

    @Test
    void remoteHeadersShouldDefaultToEmptyMap() {
        assertTrue(bridge.remoteHeaders().isEmpty(),
            "AGENT_REMOTE_HEADERS_JSON 未配置时空头（集群内 svc 直连无需认证头）");
    }

    // ===== 6. 幽灵本地行清理（F20） =====

    @Test
    void pollShouldPurgeGhostLocalRowWithChildToolCallIds() throws Exception {
        registerInFlightTask();
        taskClient.nextStatus = new RemoteTaskStatus("awaiting_confirm", null,
            List.of(new RemotePendingConfirm("call-1", "create_order", "{\"order_id\":\"O-1\"}")));
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.empty());
        when(store.findUnconsumedRemote(SID)).thenReturn(List.of());
        // 捕获先于轮询的时序：父侧已把转发的子 ask 落成 local 幽灵行，对撞消费命中
        when(store.consumeGhostLocalRows(eq(SID), eq(List.of("call-1")))).thenReturn(1);

        bridge.pollOnce();

        verify(store).consumeGhostLocalRows(eq(SID), eq(List.of("call-1")));
        verify(toolAuditStore).record(eq(SID), eq("remote_confirm"), eq(TASK_ID),
            eq("GHOST_LOCAL_PURGED"), anyString());
        // 远程行照常落地（唯一有效决策路由）
        verify(store).put(eq(CONFIRM_KEY), eq(SID), anyList(), isNull(), isNull(),
            isNull(), anyString());
    }

    @Test
    void pollShouldNotPurgeWhenNoGhostMatch() throws Exception {
        registerInFlightTask();
        taskClient.nextStatus = new RemoteTaskStatus("awaiting_confirm", null,
            List.of(new RemotePendingConfirm("call-1", "create_order", "{}")));
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.empty());
        when(store.findUnconsumedRemote(SID)).thenReturn(List.of());
        // mock 默认 consumeGhostLocalRows → 0（无幽灵命中）

        bridge.pollOnce();

        // 清理调用照常发生（对撞逻辑在 store 侧返回 0），但不落 GHOST_LOCAL_PURGED 审计，
        // 远程行照常落地
        verify(store).consumeGhostLocalRows(eq(SID), eq(List.of("call-1")));
        verify(toolAuditStore, never()).record(anyString(), anyString(), anyString(),
            eq("GHOST_LOCAL_PURGED"), anyString());
        verify(store).put(eq(CONFIRM_KEY), eq(SID), anyList(), isNull(), isNull(),
            isNull(), anyString());
    }

    // ===== 7. 后台收割兜底（F23 残留） =====

    @Test
    void backgroundTerminalShouldWakeLeadWithHarvestHint() {
        // 未经确认路由的后台任务（同步窗口超时升格）直接到终态：此前只清登记不交付
        registerInFlightTask();
        taskClient.nextStatus = new RemoteTaskStatus("success", null, List.of());
        when(runtimeService.invokeStream(anyString(), anyString(), any()))
            .thenReturn(Flux.just(Map.of("type", "done", "token", "收割完成")));

        bridge.pollOnce();

        verify(turnLeaseStore, timeout(3000)).tryAcquire(SID);
        verify(eventStore, timeout(3000)).append(eq(SID), anyString(), eq("done"), anyString());
        verify(turnLeaseStore, timeout(3000)).release(SID, "tok-1");
        // 收割话术：提示先 task_output 取结果（确定性收割，不靠模型自觉）
        var messageCap = ArgumentCaptor.forClass(String.class);
        verify(runtimeService, timeout(3000)).invokeStream(messageCap.capture(), eq(SID), any());
        assertTrue(messageCap.getValue().contains("task_output(task_id='" + TASK_ID + "')"),
            "后台收割唤醒应提示 task_output 取结果: " + messageCap.getValue());
    }

    @Test
    void wakeShouldBeIdempotentPerTask() {
        when(runtimeService.invokeStream(anyString(), anyString(), any()))
            .thenReturn(Flux.just(Map.of("type", "done", "token", "ok")));

        bridge.wakeLead(SID, TASK_ID, "成功完成");
        bridge.wakeLead(SID, TASK_ID, "成功完成");

        // 决策路径 + 后台路径对同一终态只发一次汇总 turn
        org.mockito.Mockito.verify(runtimeService, org.mockito.Mockito.timeout(3000).times(1))
            .invokeStream(anyString(), eq(SID), any());
    }
}
