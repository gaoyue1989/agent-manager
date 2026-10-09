package io.agentmanager.framework.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.subagent.task.RemoteTaskStatus;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import io.agentmanager.framework.model.OafConfig;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RemoteConfirmBridge 多副本治理单测（travel-fulfillment 设计 §18.2，G1/G3/G4）：
 * 登记持久化、registry 重建回填、claimWake 跨副本唤醒幂等（认领失败跳过/无行兜底）、
 * sweep CAS 消费前置（治理权唯一）、endpoint 解析持久快照退回。
 * 桥以「registry 注入」形态构造（对照：RemoteConfirmBridgeTest 的 null registry = v1.4 行为）。
 */
class RemoteConfirmBridgeMultiReplicaTest {

    private static final String SID = "webui-1";
    private static final String TASK_ID = "t-123";
    private static final String CONFIRM_KEY = "task:" + TASK_ID;
    private static final String ENDPOINT = "http://booking.agent-platform.svc:8100";
    private static final String REGISTRY_ENDPOINT = "http://booking-svc.agent-platform.svc:8100";

    private ConfirmContextStore store;
    private AgentRuntimeService runtimeService;
    private SessionEventBus eventBus;
    private SessionEventStore eventStore;
    private SessionUserStore sessionUserStore;
    private TurnLeaseStore turnLeaseStore;
    private ToolAuditStore toolAuditStore;
    private RemoteTaskRegistryStore registryStore;
    private RecordingClient taskClient;
    private RemoteConfirmBridge bridge;

    /** URL/事件序捕获扩展（非 mock：断言走 {@link #events} 顺序与 {@link #resumeUrls} 落点） */
    static class RecordingClient extends RemoteConfirmBridgeTest.FakeTaskClient {
        final List<String> statusUrls = new ArrayList<>();
        final List<String> resumeUrls = new ArrayList<>();
        /** 与 store.consume 共用的全局事件序（顺序断言用） */
        final List<String> events = new ArrayList<>();

        @Override
        public RemoteTaskStatus getStatus(String url, Map<String, String> headers, String taskId) {
            statusUrls.add(url);
            return super.getStatus(url, headers, taskId);
        }

        @Override
        public void resumeTask(String url, Map<String, String> headers, String taskId,
                               List<RemoteConfirmDecision> decisions) throws java.io.IOException {
            resumeUrls.add(url);
            events.add("resume:" + taskId);
            super.resumeTask(url, headers, taskId, decisions);
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
        registryStore = mock(RemoteTaskRegistryStore.class);
        taskClient = new RecordingClient();
        bridge = new RemoteConfirmBridge(store, runtimeService, eventBus, sessionUserStore,
            turnLeaseStore, toolAuditStore, taskClient, 1L, registryStore);
        when(runtimeService.oafConfig()).thenReturn(oafConfigWithBooking());
        when(runtimeService.invokeStream(anyString(), anyString(), any()))
            .thenReturn(Flux.just(Map.of("type", "done", "token", "汇总完成")));
    }

    /** 声明清单：booking 带远程 endpoint（同 RemoteConfirmBridgeTest 口径） */
    private static OafConfig oafConfigWithBooking() {
        return new OafConfig(
            "trip-lead", "acme", "trip-lead", "1.0.0", "acme/trip-lead",
            "lead", "@acme", "MIT", List.of(), "you are a planner.",
            List.of(), List.of(),
            List.of(new OafConfig.SubAgentConfig("internal", "booking", "1.0.0",
                "订票专员", List.of(), true, ENDPOINT)),
            List.of(), List.of(), null, null, null, null);
    }

    /** store 远程行（anchor: service=booking, task_id=TASK_ID） */
    private static ConfirmContextStore.PendingConfirm remoteRow() {
        var calls = List.of(ToolUseBlock.builder().id("call-1").name("create_order")
            .input(Map.of("order_id", "O-1")).build());
        var anchor = new java.util.LinkedHashMap<String, Object>();
        anchor.put("service", "booking");
        anchor.put("task_id", TASK_ID);
        anchor.put("child_reply_id", null);
        return new ConfirmContextStore.PendingConfirm(SID, CONFIRM_KEY,
            null, calls, Instant.now(), null, null, anchor);
    }

    /** awaiting_confirm 快照（快照轮询返回形态） */
    private static RemoteTaskStatus awaiting() {
        return new RemoteTaskStatus("awaiting_confirm", null,
            List.of(new RemotePendingConfirm("call-1", "create_order", "{\"order_id\":\"O-1\"}")));
    }

    // ===== G1：登记持久化与重建 =====

    @Test
    void spawnShouldPersistRegistryRowWithEndpointSnapshot() {
        bridge.onAgentSpawnResult(SID, Map.of("agent_key", "booking"), "task_id: " + TASK_ID);
        verify(registryStore).register(SID, TASK_ID, "booking", ENDPOINT);
    }

    @Test
    void rebuildShouldBackfillInFlightFromRegistryAndResumePolling() {
        // 模拟「spawn 发生在另一副本」：本进程 inFlight 为空，registry 有行
        when(registryStore.findInFlight(500)).thenReturn(List.of(
            new RemoteTaskRegistryStore.RegistryRow(SID, TASK_ID, "booking", REGISTRY_ENDPOINT, "IN_FLIGHT")));
        taskClient.nextStatus = awaiting();

        bridge.rebuildFromRegistry();
        assertTrue(bridge.inFlightTaskIds().contains(TASK_ID), "重建应回填内存登记表");

        bridge.pollOnce();
        // 跨副本重建的登记照常落卡（含 remote_task 锚点 JSON）
        verify(store).put(eq(CONFIRM_KEY), eq(SID), anyList(), isNull(), isNull(), isNull(),
            contains(TASK_ID));
    }

    @Test
    void rebuildShouldNotClobberSpawnSnapshotForLiveEntries() {
        bridge.onAgentSpawnResult(SID, Map.of("agent_key", "booking"), "task_id: " + TASK_ID);
        when(registryStore.findInFlight(500)).thenReturn(List.of(
            new RemoteTaskRegistryStore.RegistryRow(SID, TASK_ID, "booking", REGISTRY_ENDPOINT, "IN_FLIGHT")));

        bridge.rebuildFromRegistry();
        taskClient.nextStatus = awaiting();
        bridge.pollOnce();

        // 已在内存登记表的行不被重建覆盖：轮询仍走 spawn 时的声明匹配 endpoint
        assertEquals(List.of(ENDPOINT), taskClient.statusUrls);
    }

    // ===== G4：跨副本唤醒幂等 =====

    @Test
    void wakeClaimLostShouldSkipSummaryTurn() {
        when(registryStore.tryClaimWake(SID, TASK_ID))
            .thenReturn(RemoteTaskRegistryStore.WakeClaim.CONTENTED);

        bridge.wakeLead(SID, TASK_ID, "成功完成");

        verify(runtimeService, never()).invokeStream(anyString(), anyString(), any());
        verify(turnLeaseStore, never()).tryAcquire(anyString());
    }

    @Test
    void wakeClaimWonShouldMarkTerminalAndDriveSummaryTurn() {
        when(registryStore.tryClaimWake(SID, TASK_ID))
            .thenReturn(RemoteTaskRegistryStore.WakeClaim.CLAIMED);

        bridge.wakeLead(SID, TASK_ID, "成功完成");

        // 认领即收口（tryClaimWake 单语句 IN_FLIGHT→TERMINAL，无独立 markTerminal 步）
        verify(registryStore, never()).markTerminal(anyString(), anyString(), eq(false));
        verify(turnLeaseStore, timeout(3000)).tryAcquire(SID);
        verify(eventStore, timeout(3000)).append(eq(SID), anyString(), eq("done"), anyString());
    }

    @Test
    void wakeWithoutRegistryRowShouldFallBackToInMemoryGuard() {
        // 历史/异常路径：registry 无行 → v1.4 进程内守卫（第二次唤醒被吞）
        when(registryStore.tryClaimWake(SID, TASK_ID))
            .thenReturn(RemoteTaskRegistryStore.WakeClaim.ABSENT);

        bridge.wakeLead(SID, TASK_ID, "成功完成");
        bridge.wakeLead(SID, TASK_ID, "成功完成");

        verify(runtimeService, times(1)).invokeStream(anyString(), eq(SID), any());
    }

    @Test
    void wakeDeferredWhenRegistryUnavailableShouldNotDriveTurn() {
        // DB 抖动 ≠ 无行/被抢（错误分型）：跳过本轮、rebuild 周期重拾——
        // 若无痕唤醒，DB 恢复后 rebuild 会对同一任务二次汇总 turn
        when(registryStore.tryClaimWake(SID, TASK_ID))
            .thenReturn(RemoteTaskRegistryStore.WakeClaim.UNAVAILABLE);

        bridge.wakeLead(SID, TASK_ID, "成功完成");

        verify(runtimeService, never()).invokeStream(anyString(), anyString(), any());
        verify(turnLeaseStore, never()).tryAcquire(anyString());
    }

    // ===== G3：sweep CAS 消费前置 =====

    @Test
    void sweepShouldResumeOnlyWhenConsumeWins() {
        var row = remoteRow();
        when(store.findExpiredRemoteRows()).thenReturn(List.of(row));
        when(registryStore.findEndpoint(SID, TASK_ID)).thenReturn(java.util.Optional.of(REGISTRY_ENDPOINT));
        // consume 与 resume 的先后经共享事件序断言（store 是 mock、client 是真对象，两侧统一记账）
        when(store.consume(SID, CONFIRM_KEY)).thenAnswer(inv -> {
            taskClient.events.add("consume");
            return new ConfirmContextStore.StoredRow(List.of(), null, Instant.now(), null, null);
        });

        bridge.sweepExpiredRemote();

        assertEquals(List.of("consume", "resume:" + TASK_ID), taskClient.events,
            "CAS 消费必须先于 DENY resume（治理权唯一）");
        assertEquals(List.of(REGISTRY_ENDPOINT), taskClient.resumeUrls);
        verify(toolAuditStore).record(eq(SID), eq("remote_confirm"), eq(TASK_ID),
            eq("DENY"), contains("confirm_timeout"));
    }

    @Test
    void sweepShouldSkipWhenConsumeLost() {
        var row = remoteRow();
        when(store.findExpiredRemoteRows()).thenReturn(List.of(row));
        when(store.consume(SID, CONFIRM_KEY))
            .thenThrow(new AgentRuntimeService.ConfirmAlreadyConsumedException(SID));

        bridge.sweepExpiredRemote();

        assertTrue(taskClient.resumeUrls.isEmpty(), "治理权在别处时不得 resume");
        verify(toolAuditStore, never()).record(anyString(), anyString(), anyString(),
            eq("DENY"), anyString());
    }

    @Test
    void sweepShouldKeepRowConsumedWhenResumeFails() {
        var row = remoteRow();
        when(store.findExpiredRemoteRows()).thenReturn(List.of(row));
        when(registryStore.findEndpoint(SID, TASK_ID)).thenReturn(java.util.Optional.of(REGISTRY_ENDPOINT));
        taskClient.failResumeWith = new java.io.IOException("member down");

        bridge.sweepExpiredRemote();

        // 与 routeDecision「resume 失败不回滚消费」语义一致：行保持收口，审计可查
        verify(store).consume(SID, CONFIRM_KEY);
        verify(toolAuditStore).record(eq(SID), eq("remote_confirm"), eq(TASK_ID),
            eq("TIMEOUT_RESUME_FAILED"), anyString());
    }

    // ===== G1 残端：传输超限放弃时收口 registry 行 =====

    @Test
    void transportGiveUpShouldMarkRegistryRowGivenUp() {
        bridge.onAgentSpawnResult(SID, Map.of("agent_key", "booking"), "task_id: " + TASK_ID);
        taskClient.nextStatus = new RemoteTaskStatus("error", "task not found", List.of());

        for (int i = 0; i < RemoteConfirmBridge.MAX_TRANSPORT_ERROR_POLLS; i++) {
            bridge.pollOnce();
        }

        verify(registryStore).markTerminal(SID, TASK_ID, true);
    }

    // ===== 确认路由的持久快照退回 =====

    @Test
    void routeDecisionShouldResolveEndpointFromRegistryWhenInFlightEmpty() {
        // 模拟 spawn 副本已亡：inFlight 无登记，registry 持久快照命中
        when(store.findPending(SID, CONFIRM_KEY)).thenReturn(java.util.Optional.of(remoteRow()));
        when(registryStore.findEndpoint(SID, TASK_ID)).thenReturn(java.util.Optional.of(REGISTRY_ENDPOINT));
        when(store.consume(SID, CONFIRM_KEY)).thenReturn(new ConfirmContextStore.StoredRow(
            List.of(), null, Instant.now(), null, null));

        var out = bridge.routeDecision(SID, CONFIRM_KEY,
            List.of(Map.of("tool_call_id", "call-1", "confirmed", true)));

        assertEquals("remote", out.get("routed"));
        // resume 经 registry 快照 endpoint 下发（而非声明清单重解析）
        assertEquals(List.of(REGISTRY_ENDPOINT), taskClient.resumeUrls);
    }
}
