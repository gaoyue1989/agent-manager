package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionEventTailer;
import io.agentmanager.framework.service.TurnLeaseStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionStreamControllerStatusTest {

    @Mock private TurnLeaseStore turnLeaseStore;
    @Mock private AgentRuntimeService runtimeService;
    @Mock private SessionEventStore eventStore;
    @Mock private io.agentmanager.framework.service.SessionUserStore sessionUserStore;
    @Mock private io.agentmanager.framework.service.TurnIntentStore turnIntentStore;

    private SessionEventTailer tailer;
    private SessionStreamController controller;

    @BeforeEach
    void setUp() {
        tailer = new SessionEventTailer(eventStore, turnLeaseStore, runtimeService,
            Duration.ofMillis(300));
        // 无映射行（findUserIdBySession → null）= 存量会话，归属校验放行
        when(sessionUserStore.findUserIdBySession(anyString())).thenReturn(null);
        controller = new SessionStreamController(runtimeService, turnLeaseStore, eventStore, tailer,
            sessionUserStore, turnIntentStore);
    }

    @Test
    void interruptedWhenLeaseGoneAndNoTerminalEvent() {
        when(turnLeaseStore.isHeld("sid-1")).thenReturn(false);
        when(eventStore.findLatestStrict("sid-1")).thenReturn(
            new SessionEventStore.EnvelopedEvent(9, "TEXT_BLOCK_DELTA", "{}", "rid-1"));
        when(runtimeService.findPendingConfirm("sid-1")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-1", null).getBody();
        assertEquals("interrupted", body.get("state"));
        assertEquals(9, body.get("latest_event_seq"));
    }

    @Test
    void workingWhenLeaseHeldEvenWithoutLocalSink() {
        when(turnLeaseStore.isHeld("sid-2")).thenReturn(true);
        when(eventStore.findLatestStrict("sid-2")).thenReturn(
            new SessionEventStore.EnvelopedEvent(3, "TEXT_BLOCK_DELTA", "{}", "rid-2"));
        when(runtimeService.findPendingConfirm("sid-2")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-2", null).getBody();
        // 跨副本关键点：本 Pod 没有 sink，也必须报 working
        assertEquals("working", body.get("state"));
    }

    @Test
    void completedWhenLatestIsAgentEnd() {
        when(turnLeaseStore.isHeld("sid-3")).thenReturn(false);
        when(eventStore.findLatestStrict("sid-3")).thenReturn(
            new SessionEventStore.EnvelopedEvent(12, "AGENT_END", "{}", "rid-3"));
        when(runtimeService.findPendingConfirm("sid-3")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-3", null).getBody();
        assertEquals("completed", body.get("state"));
        assertEquals(12, body.get("latest_event_seq"));
    }

    @Test
    void completedWhenLatestIsErrorTerminal() {
        // error 与 AGENT_END 同为 turn 终态：不得落进 idle（否则刷新后前端把
        // 「本回合已失败结束」当成「无事件可恢复」，与对话流的 error 帧语义分叉）
        when(turnLeaseStore.isHeld("sid-err")).thenReturn(false);
        when(eventStore.findLatestStrict("sid-err")).thenReturn(
            new SessionEventStore.EnvelopedEvent(8, "error",
                "{\"type\":\"error\",\"error\":\"boom\"}", "rid-err"));
        when(runtimeService.findPendingConfirm("sid-err")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-err", null).getBody();
        assertEquals("completed", body.get("state"));
        assertEquals(8, body.get("latest_event_seq"));
    }

    @Test
    void workingTurnWithoutEventsDoesNotExposePreviousTurnReplyId() {
        // 租约被持有但最新事件是上一 turn 的终态：下一 turn 尚未产出事件。
        // 不能把旧 replyId 下发——客户端按它过滤续传会永远看不到新 turn 的 delta。
        when(turnLeaseStore.isHeld("sid-next")).thenReturn(true);
        when(eventStore.findLatestStrict("sid-next")).thenReturn(
            new SessionEventStore.EnvelopedEvent(21, "AGENT_END", "{}", "rid-old"));
        when(runtimeService.findPendingConfirm("sid-next")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-next", null).getBody();
        assertEquals("working", body.get("state"));
        assertEquals(21, body.get("latest_event_seq"));
        assertEquals("", body.get("reply_id"),
            "新 turn 尚未产出事件时不得回放上一 turn 的 replyId");
    }

    @Test
    void queuedTurnIntentExposesItsReplyIdAsWorking() {
        // 同会话并发：新请求还在等租约、尚无任何事件；意图行让刷新能拿到本轮 turn id
        when(turnIntentStore.find("sid-queued")).thenReturn(
            new io.agentmanager.framework.service.TurnIntentStore.Intent("rid-queued", false, null));
        when(turnLeaseStore.isHeld("sid-queued")).thenReturn(true);
        when(eventStore.findLatestStrict("sid-queued")).thenReturn(
            new SessionEventStore.EnvelopedEvent(30, "AGENT_END", "{}", "rid-running"));
        when(runtimeService.findPendingConfirm("sid-queued")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-queued", null).getBody();
        assertEquals("working", body.get("state"));
        assertEquals("rid-queued", body.get("reply_id"),
            "排队中的 turn 必须回自己的 replyId，而不是正在跑的上一轮");
        assertEquals("", body.get("error"));
    }

    @Test
    void failedTurnIntentExposesFailureWithoutSubscription() {
        when(turnIntentStore.find("sid-failed")).thenReturn(
            new io.agentmanager.framework.service.TurnIntentStore.Intent(
                "rid-failed", true, "turn_in_progress: queue timeout"));
        when(turnLeaseStore.isHeld("sid-failed")).thenReturn(false);
        when(eventStore.findLatestStrict("sid-failed")).thenReturn(
            new SessionEventStore.EnvelopedEvent(5, "AGENT_END", "{}", "rid-old"));
        when(runtimeService.findPendingConfirm("sid-failed")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-failed", null).getBody();
        assertEquals("failed", body.get("state"));
        assertEquals("rid-failed", body.get("reply_id"));
        assertEquals("turn_in_progress: queue timeout", body.get("error"));
    }

    @Test
    void waitingConfirmTakesPrecedence() {
        when(eventStore.findLatestStrict("sid-4")).thenReturn(
            new SessionEventStore.EnvelopedEvent(5, "permission_ask", "{}", "rid-4"));
        when(runtimeService.findPendingConfirm("sid-4")).thenReturn(Map.of("tools", "[]"));

        Map<String, Object> body = controller.status("sid-4", null).getBody();
        assertEquals("waiting_confirm", body.get("state"));
    }

    @Test
    void idleWhenNoEvents() {
        when(turnLeaseStore.isHeld("sid-5")).thenReturn(false);
        when(eventStore.findLatestStrict("sid-5")).thenReturn(null);
        when(runtimeService.findPendingConfirm("sid-5")).thenReturn(null);

        Map<String, Object> body = controller.status("sid-5", null).getBody();
        assertEquals("idle", body.get("state"));
        assertEquals(0, body.get("latest_event_seq"));
    }

    /**
     * 存储不可用时必须 503，而不是一个 {@code latest_event_seq: 0} 的**成功**响应。
     *
     * <p>这是本端点最重要的失败语义：前端 {@code chat.js:463} 会无条件
     * {@code lastEventId = status.latest_event_seq || 0}，所以一个发出去的 0 会把健康客户端的
     * 游标重置到流首，Redis 一恢复就是整场会话重放。503 让 {@code api.js} 的 {@code get()}
     * 抛错，落到 {@code tryResumeSSE} 的 catch（{@code chat.js:514}）保留游标、跳过恢复。
     */
    @Test
    void serviceUnavailableWhenStoreCannotBeRead() {
        when(runtimeService.findPendingConfirm("sid-6")).thenReturn(null);
        when(eventStore.findLatestStrict("sid-6"))
            .thenThrow(new RuntimeException("redis connection refused"));

        var resp = controller.status("sid-6", null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals("event_store_unavailable", resp.getBody().get("error"));
        // 关键：不能带上任何会被前端当成游标的字段
        assertFalse(resp.getBody().containsKey("latest_event_seq"),
            "503 响应不得携带 latest_event_seq，否则前端仍可能把游标重置为 0");
    }
}
