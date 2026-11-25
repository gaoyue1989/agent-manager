package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

/**
 * VikingSessionCommitStateStore 单测：AGENT_STATE_KEY 过滤、新增消息 append、msg_id 幂等
 * 去重、非文本跳过、透传与 CAS 写路径；issue #418 三语义——delegate 落库先行不阻塞、
 * OV session 键按会话隔离（A2A 不串写）、有界队列满丢弃不堆积。
 */
class VikingSessionCommitStateStoreTest {

    private final AgentStateStore delegate = Mockito.mock(AgentStateStore.class);
    private final VikingMemoryClient client = Mockito.mock(VikingMemoryClient.class);
    /** 反查恒空 → 走 fallback 原 userId（A2A 直连形态）；Channel 形态另有 upsert 数据时生效 */
    private final SessionUserStore sessionUserStore = Mockito.mock(SessionUserStore.class);

    private VikingSessionCommitStateStore store() {
        return new VikingSessionCommitStateStore(delegate, client, sessionUserStore);
    }

    private Msg msg(String id, MsgRole role, String text) {
        return Msg.builderForRole(role).id(id).textContent(text).build();
    }

    private AgentState stateWith(List<Msg> context) {
        var state = Mockito.mock(AgentState.class);
        when(state.getContext()).thenReturn(context);
        return state;
    }

    @Test
    void appendsOnlyNewMessagesThenDedups() {
        var s = store(); // 单实例：去重态在实例内跨 save 生效
        var state = stateWith(List.of(
            msg("m1", MsgRole.USER, "第一问"),
            msg("m2", MsgRole.ASSISTANT, "第一答")));
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        // 同一 state 二次 save：m1/m2 只 append 一次（msg_id 去重）
        verify(client, timeout(2000).times(1)).appendSessionMessage("u-1", "u-1:s-1", "user", "第一问");
        verify(client, timeout(2000).times(1)).appendSessionMessage("u-1", "u-1:s-1", "assistant", "第一答");

        // 增量 m3：只 append m3
        var state2 = stateWith(List.of(
            msg("m1", MsgRole.USER, "第一问"),
            msg("m2", MsgRole.ASSISTANT, "第一答"),
            msg("m3", MsgRole.USER, "第二问")));
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state2);
        verify(client, timeout(2000).times(1)).appendSessionMessage("u-1", "u-1:s-1", "user", "第二问");
        verify(client, timeout(2000).times(2)).appendSessionMessage(eq("u-1"), eq("u-1:s-1"), eq("user"), anyString());
        s.close();
    }

    @Test
    void skipsNonAgentStateKeyAndBlankSessionAndBlankUser() {
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "x")));
        store().save("u-1", "s-1", "other_state", state);
        store().save("u-1", "  ", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        store().save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, (State) null);
        verify(client, after(200).never()).appendSessionMessage(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void skipsBlankTextAndAnonUser() {
        var state = stateWith(List.of(
            msg("m1", MsgRole.USER, "  "),
            msg("m2", MsgRole.ASSISTANT, "内容")));
        store().save("", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        verify(client, after(200).never()).appendSessionMessage(any(), any(), any(), any());
    }

    @Test
    void versioningCapabilityPassesThroughToDelegate() {
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "问")));
        when(delegate.supportsVersioning()).thenReturn(true);
        when(delegate.getVersioned("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, State.class))
            .thenReturn(new io.agentscope.core.state.VersionedState<>(state, 7L));
        var s = store();
        assertTrue(s.supportsVersioning());
        assertEquals(7L, s.getVersioned("u-1", "s-1",
            SessionMessageArchiveStateStore.AGENT_STATE_KEY, State.class).version());
        verify(delegate).getVersioned("u-1", "s-1",
            SessionMessageArchiveStateStore.AGENT_STATE_KEY, State.class);
    }

    @Test
    void versioningCapabilityReflectsDelegateWithoutFalseAdvertising() {
        when(delegate.supportsVersioning()).thenReturn(false);
        assertFalse(store().supportsVersioning());
    }

    @Test
    void fullDecoratorChainPassesVersioningThrough() {
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "问")));
        when(delegate.supportsVersioning()).thenReturn(true);
        when(delegate.getVersioned(anyString(), anyString(), anyString(), any(Class.class)))
            .thenReturn(new io.agentscope.core.state.VersionedState<>(state, 7L));
        var chain = new io.agentmanager.framework.sandbox.opensandbox.AskingContentBackfillStateStore(
            new SessionMessageArchiveStateStore(
                new VikingSessionCommitStateStore(delegate, client, sessionUserStore),
                Mockito.mock(SessionMessageStore.class)));
        assertTrue(chain.supportsVersioning());
        assertEquals(7L, chain.getVersioned("u-1", "s-1",
            SessionMessageArchiveStateStore.AGENT_STATE_KEY, State.class).version());
        verify(delegate).getVersioned("u-1", "s-1",
            SessionMessageArchiveStateStore.AGENT_STATE_KEY, State.class);
    }

    @Test
    void saveIfVersionAlsoCommitsAndDelegates() {
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "问")));
        when(delegate.saveIfVersion(anyString(), anyString(), anyString(), any(State.class), eq(7L)))
            .thenReturn(8L);
        var out = store().saveIfVersion("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state, 7L);
        assertEquals(8L, out);
        verify(client, timeout(2000)).appendSessionMessage("u-1", "u-1:s-1", "user", "问");
    }

    @Test
    void passthroughReadPathsAndFailSoftOnClientError() {
        var s = store();
        when(delegate.get("u-1", "s-1", "k", State.class)).thenReturn(Optional.empty());
        s.get("u-1", "s-1", "k", State.class);
        s.delete("u-1", "s-1");
        s.exists("u-1", "s-1");
        Mockito.verify(delegate).delete("u-1", "s-1");

        // client 抛异常不影响 delegate 落库（fail-soft）
        Mockito.doThrow(new RuntimeException("ov down")).when(client)
            .appendSessionMessage(anyString(), anyString(), anyString(), anyString());
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "q")));
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        verify(delegate).save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        s.close();
    }

    @Test
    void saveMustNotBlockOnSlowOpenViking() throws Exception {
        var gate = new CountDownLatch(1);
        Mockito.doAnswer(inv -> {
            gate.await(2, TimeUnit.SECONDS);
            return null;
        }).when(client).appendSessionMessage(anyString(), anyString(), anyString(), anyString());
        var s = store();
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "问")));
        long start = System.nanoTime();
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        verify(delegate).save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        assertTrue(elapsedMs < 1000,
            "save must return before OpenViking append completes, took " + elapsedMs + "ms");
        gate.countDown();
        verify(client, timeout(2000)).appendSessionMessage("u-1", "u-1:s-1", "user", "问");
        s.close();
    }

    @Test
    void a2aSessionsMustIsolateBySessionKey() {
        var s = store();
        s.save("u-1", "s-A", SessionMessageArchiveStateStore.AGENT_STATE_KEY,
            stateWith(List.of(msg("a1", MsgRole.USER, "会话A问"))));
        s.save("u-1", "s-B", SessionMessageArchiveStateStore.AGENT_STATE_KEY,
            stateWith(List.of(msg("b1", MsgRole.USER, "会话B问"))));
        verify(client, timeout(2000)).appendSessionMessage("u-1", "u-1:s-A", "user", "会话A问");
        verify(client, timeout(2000)).appendSessionMessage("u-1", "u-1:s-B", "user", "会话B问");
        s.close();
    }

    @Test
    void realUserLookupResolvedOncePerBatch() {
        when(sessionUserStore.findUserIdBySession("s-1")).thenReturn("real-u");
        var s = store();
        var state = stateWith(List.of(
            msg("m1", MsgRole.USER, "一"),
            msg("m2", MsgRole.ASSISTANT, "二")));
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        verify(client, timeout(2000)).appendSessionMessage("real-u", "u-1:s-1", "user", "一");
        verify(client, timeout(2000)).appendSessionMessage("real-u", "u-1:s-1", "assistant", "二");
        verify(sessionUserStore, times(1)).findUserIdBySession("s-1");
        verify(sessionUserStore, after(200).never()).findUserIdBySession("u-1");
        s.close();
    }

    @Test
    void realUserLookupFallsBackToUserIdWhenSessionMisses() {
        when(sessionUserStore.findUserIdBySession(anyString())).thenReturn(null);
        var s = store();
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "问")));
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        verify(client, timeout(2000)).appendSessionMessage("u-1", "u-1:s-1", "user", "问");
        verify(sessionUserStore, times(1)).findUserIdBySession("s-1");
        verify(sessionUserStore, after(200).never()).findUserIdBySession("u-1");
        s.close();
    }

    @Test
    void a2aMustNotResolveUserIdAsSessionKey() {
        when(sessionUserStore.findUserIdBySession("alice-private-sess")).thenReturn(null);
        when(sessionUserStore.findUserIdBySession("u-1001")).thenReturn("u-2002");
        var s = store();
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "受害者私密内容")));
        s.save("u-1001", "alice-private-sess", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        verify(client, timeout(2000)).appendSessionMessage(
            "u-1001", "u-1001:alice-private-sess", "user", "受害者私密内容");
        verify(sessionUserStore, after(200).never()).findUserIdBySession("u-1001");
        s.close();
    }

    @Test
    void a2aSessionIdRowWinsOverPoisonedUserIdRow() {
        when(sessionUserStore.findUserIdBySession("alice-private-sess")).thenReturn("u-1001");
        when(sessionUserStore.findUserIdBySession("u-1001")).thenReturn("u-2002");
        var s = store();
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "受害者私密内容")));
        s.save("u-1001", "alice-private-sess", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        verify(client, timeout(2000)).appendSessionMessage(
            "u-1001", "u-1001:alice-private-sess", "user", "受害者私密内容");
        verify(sessionUserStore, after(200).never()).findUserIdBySession("u-1001");
        s.close();
    }

    @Test
    void channelMustResolvePeerRowNotGatewayHashRow() {
        when(sessionUserStore.findUserIdBySession("gw-3f20f08c5499")).thenReturn("u-2002");
        when(sessionUserStore.findUserIdBySession("front-sid")).thenReturn("u-1001");
        var s = store();
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "Channel 内容")));
        s.save("front-sid", "gw-3f20f08c5499", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        verify(client, timeout(2000)).appendSessionMessage(
            "u-1001", "front-sid:gw-3f20f08c5499", "user", "Channel 内容");
        verify(sessionUserStore, after(200).never()).findUserIdBySession("gw-3f20f08c5499");
        s.close();
    }

    @Test
    void queueOverflowDropsWithoutBlocking() throws Exception {
        var gate = new CountDownLatch(1);
        var appended = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            if (appended.incrementAndGet() == 1) {
                gate.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(client).appendSessionMessage(anyString(), anyString(), anyString(), anyString());
        var s = store();
        int total = VikingSessionCommitStateStore.QUEUE_CAPACITY + 20;
        long start = System.nanoTime();
        for (int i = 0; i < total; i++) {
            s.save("u-1", "s-" + i, SessionMessageArchiveStateStore.AGENT_STATE_KEY,
                stateWith(List.of(msg("m" + i, MsgRole.USER, "内容" + i))));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 2000,
            total + " saves must not block on the single slow worker, took " + elapsedMs + "ms");
        gate.countDown();
        verify(client, timeout(5000).times(VikingSessionCommitStateStore.QUEUE_CAPACITY + 1))
            .appendSessionMessage(anyString(), anyString(), anyString(), anyString());
        verify(client, after(200).never())
            .appendSessionMessage(anyString(), anyString(), anyString(), contains("内容" + (total - 1)));
        s.close();
    }

    /** issue #496：seenBySlot 外层 Map 有界（LRU 淘汰，超限重放 append 语义与内层护栏一致） */
    @Test
    void seenSlotsMustStayBounded() {
        var s = store();
        for (int i = 0; i < 1200; i++) {
            s.save("u-1", "s-" + i, SessionMessageArchiveStateStore.AGENT_STATE_KEY,
                stateWith(List.of(msg("m" + i, MsgRole.USER, "内容" + i))));
        }
        assertTrue(s.seenBySlot.size() <= VikingSessionCommitStateStore.SLOT_CAPACITY,
            "seenBySlot 外层 Map 无界增长：实际 " + s.seenBySlot.size());
        s.close();
    }

    /** issue #496：会话删除必须同步清理 slot 去重集（删后重建的会话不得被旧 seen 压制 append） */
    @Test
    void deleteMustEvictSeenSlot() {
        var s = store();
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY,
            stateWith(List.of(msg("m1", MsgRole.USER, "问"))));
        assertTrue(s.seenBySlot.containsKey("u-1:s-1"));
        s.delete("u-1", "s-1");
        assertFalse(s.seenBySlot.containsKey("u-1:s-1"), "会话删除必须清理 slot 去重集");
        s.close();
    }

    /** issue #496：单键 delete（stateKey=agent_state）同样清理 slot 去重集 */
    @Test
    void deleteByStateKeyMustEvictSeenSlot() {
        var s = store();
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY,
            stateWith(List.of(msg("m1", MsgRole.USER, "问"))));
        s.delete("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY);
        assertFalse(s.seenBySlot.containsKey("u-1:s-1"));
        s.close();
    }

    /** issue #496：close 必须排空队列残留消息并等待 worker 终止后再关闭 delegate */
    @Test
    void closeMustDrainPendingCommitsBeforeTerminate() throws Exception {
        var gate = new CountDownLatch(1);
        var appended = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            if (appended.incrementAndGet() == 1) {
                gate.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(client).appendSessionMessage(anyString(), anyString(), anyString(), anyString());
        var s = store();
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY,
            stateWith(List.of(msg("m1", MsgRole.USER, "执行中批次"))));
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY,
            stateWith(List.of(msg("m2", MsgRole.USER, "队列残留批次"))));
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
            gate.countDown();
        });
        long start = System.nanoTime();
        s.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 400,
            "close 必须等待队列残留消息提交完成后再返回，took " + elapsedMs + "ms");
        assertTrue(s.commitWorker.isTerminated(), "close 返回时 commitWorker 必须已终止");
        verify(client, times(2)).appendSessionMessage(eq("u-1"), eq("u-1:s-1"), eq("user"), anyString());
        verify(delegate).close();
    }

    /** issue #496：worker 卡死时 close 必须有界返回（排空上限 3s），不得无限期阻塞关停 */
    @Test
    void closeMustTerminateWithinBoundWhenWorkerStuck() throws Exception {
        var gate = new CountDownLatch(1);
        Mockito.doAnswer(inv -> {
            gate.await(10, TimeUnit.SECONDS);
            return null;
        }).when(client).appendSessionMessage(anyString(), anyString(), anyString(), anyString());
        var s = store();
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY,
            stateWith(List.of(msg("m1", MsgRole.USER, "卡住"))));
        long start = System.nanoTime();
        s.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        gate.countDown();
        assertTrue(elapsedMs < 5000,
            "worker 卡死时 close 必须有界返回（3s 排空上限），took " + elapsedMs + "ms");
        assertTrue(s.commitWorker.isTerminated(), "close 返回时 commitWorker 必须已终止");
    }

    @Test
    void appendBatchShouldStopImmediatelyWhenWorkerThreadIsInterrupted() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Mockito.doAnswer(inv -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            return null;
        }).when(client).appendSessionMessage(anyString(), anyString(), anyString(), anyString());
        var s = store();
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY,
            stateWith(List.of(
                msg("m1", MsgRole.USER, "first"),
                msg("m2", MsgRole.ASSISTANT, "second"),
                msg("m3", MsgRole.USER, "third"))));
        s.close();
        assertEquals(1, calls.get());
    }
}
