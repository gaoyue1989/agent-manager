package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

/**
 * VikingSessionCommitStateStore 单测（覆盖审计补齐——此前零执行）：AGENT_STATE_KEY 过滤、
 * 新增消息 append、msg_id 幂等去重、非文本跳过、透传与 CAS 写路径。
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
        verify(client, times(1)).appendSessionMessage("u-1", "u-1", "user", "第一问");
        verify(client, times(1)).appendSessionMessage("u-1", "u-1", "assistant", "第一答");

        // 增量 m3：只 append m3
        var state2 = stateWith(List.of(
            msg("m1", MsgRole.USER, "第一问"),
            msg("m2", MsgRole.ASSISTANT, "第一答"),
            msg("m3", MsgRole.USER, "第二问")));
        s.save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state2);
        verify(client, times(1)).appendSessionMessage("u-1", "u-1", "user", "第二问");
        verify(client, times(2)).appendSessionMessage(eq("u-1"), eq("u-1"), eq("user"), anyString());
    }

    @Test
    void skipsNonAgentStateKeyAndBlankSessionAndBlankUser() {
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "x")));
        store().save("u-1", "s-1", "other_state", state);
        store().save("u-1", "  ", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        store().save("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, (State) null);
        verify(client, never()).appendSessionMessage(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void skipsBlankTextAndAnonUser() {
        var state = stateWith(List.of(
            msg("m1", MsgRole.USER, "  "),
            msg("m2", MsgRole.ASSISTANT, "内容")));
        store().save("", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state);
        verify(client, never()).appendSessionMessage(any(), any(), any(), any());
    }

    @Test
    void saveIfVersionAlsoCommitsAndDelegates() {
        var state = stateWith(List.of(msg("m1", MsgRole.USER, "问")));
        when(delegate.saveIfVersion(anyString(), anyString(), anyString(), any(State.class), eq(7L)))
            .thenReturn(8L);
        var out = store().saveIfVersion("u-1", "s-1", SessionMessageArchiveStateStore.AGENT_STATE_KEY, state, 7L);
        assertEquals(8L, out);
        verify(client).appendSessionMessage("u-1", "u-1", "user", "问");
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
    }
}
