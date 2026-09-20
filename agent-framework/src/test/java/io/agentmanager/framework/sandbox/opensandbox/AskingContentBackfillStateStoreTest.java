package io.agentmanager.framework.sandbox.opensandbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AskingContentBackfillStateStore 测试：持久化前把 ASKING tool_use 块的 content
 * 回填为 input 的 JSON 字符串（写侧治本），版本化方法全量透传。
 */
class AskingContentBackfillStateStoreTest {

    private final AgentStateStore delegate = mock(AgentStateStore.class);
    private final AskingContentBackfillStateStore store = new AskingContentBackfillStateStore(delegate);

    /** 构造 SDK 挂起形态的 AgentState：最后一条 assistant 消息含 ASKING 块（content 缺失） */
    private AgentState suspendedState(String content) {
        var assistantMsg = Msg.builder()
            .name("assistant").role(MsgRole.ASSISTANT)
            .content(new ToolUseBlock("call-1", "publish_service",
                new LinkedHashMap<>(Map.of("packageId", 166)), content, null, ToolCallState.ASKING))
            .build();
        return AgentState.builder()
            .sessionId("s1").userId("u1")
            .context(new ArrayList<>(List.of(
                Msg.builder().name("user").role(MsgRole.USER).textContent("发布服务").build(),
                assistantMsg)))
            .build();
    }

    @Test
    void saveShouldBackfillAskingContentFromInput() {
        var state = suspendedState(null);

        store.save("s1", "u1", "agent_state", state);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<State>) (Class<?>) State.class);
        verify(delegate).save(eq("s1"), eq("u1"), eq("agent_state"), captor.capture());
        var saved = (AgentState) captor.getValue();
        var savedBlock = (ToolUseBlock) saved.getContext().get(1).getContent().get(0);
        assertEquals("{\"packageId\":166}", savedBlock.getContent(),
            "持久化前 content 必须补为 input 的 JSON 字符串");
        assertEquals(Map.of("packageId", 166), savedBlock.getInput(), "input 不动");
        assertEquals(ToolCallState.ASKING, savedBlock.getState(), "state 不动（仍是挂起态）");
    }

    /** content 已有值（SDK 实际落 "{}" 的形态）不覆盖原值 */
    @Test
    void saveShouldNotOverwriteExistingContent() {
        var state = suspendedState("{}");

        store.save("s1", "u1", "agent_state", state);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<State>) (Class<?>) State.class);
        verify(delegate).save(eq("s1"), eq("u1"), eq("agent_state"), captor.capture());
        var saved = (AgentState) captor.getValue();
        var savedBlock = (ToolUseBlock) saved.getContext().get(1).getContent().get(0);
        assertEquals("{}", savedBlock.getContent(), "已有 content 不被覆盖");
    }

    /** 非 AgentState 直接透传 */
    @Test
    void saveShouldPassThroughNonAgentState() {
        var other = mock(State.class);

        store.save("s1", "u1", "agent_state", other);

        verify(delegate).save("s1", "u1", "agent_state", other);
    }

    /** ReActAgent 走 saveIfVersion（CAS）时同样必须补丁，且委托给 delegate 而非接口 default */
    @Test
    void saveIfVersionShouldBackfillAndDelegate() {
        var state = suspendedState(null);

        store.saveIfVersion("s1", "u1", "agent_state", state, 7L);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<State>) (Class<?>) State.class);
        verify(delegate).saveIfVersion(eq("s1"), eq("u1"), eq("agent_state"), captor.capture(), eq(7L));
        var saved = (AgentState) captor.getValue();
        var savedBlock = (ToolUseBlock) saved.getContext().get(1).getContent().get(0);
        assertEquals("{\"packageId\":166}", savedBlock.getContent(), "版本化保存同样补 content");
        verify(delegate, never()).save(anyString(), anyString(), anyString(), any(State.class));
    }

    /** 版本化语义透传（supportsVersioning=false 会改变 ReActAgent 并发写路径） */
    @Test
    void versionedSemanticsShouldDelegateToWrappedStore() {
        when(delegate.supportsVersioning()).thenReturn(true);
        var versioned = new io.agentscope.core.state.VersionedState<>(suspendedState("{}"), 3L);
        when(delegate.getVersioned("s1", "u1", "agent_state", AgentState.class)).thenReturn(versioned);

        assertTrue(store.supportsVersioning());
        assertEquals(3L, store.getVersioned("s1", "u1", "agent_state", AgentState.class).version());

        when(delegate.getList("s1", "u1", "k", AgentState.class)).thenReturn(List.<AgentState>of());
        when(delegate.get("s1", "u1", "k", AgentState.class)).thenReturn(Optional.empty());
        when(delegate.exists("s1", "k")).thenReturn(true);
        when(delegate.listSessionIds("k")).thenReturn(Set.of("s1"));

        assertTrue(store.getList("s1", "u1", "k", AgentState.class).isEmpty());
        assertTrue(store.get("s1", "u1", "k", AgentState.class).isEmpty());
        assertTrue(store.exists("s1", "k"));
        assertEquals(Set.of("s1"), store.listSessionIds("k"));

        store.delete("s1", "k");
        verify(delegate).delete("s1", "k");
    }

    /** 批量 save 路径同样补丁 */
    @Test
    void saveListShouldBackfillEachAgentState() {
        var state = suspendedState(null);

        store.save("s1", "u1", "agent_state", List.of(state));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<? extends State>>) (Class<?>) List.class);
        verify(delegate).save(eq("s1"), eq("u1"), eq("agent_state"), captor.capture());
        var saved = (AgentState) captor.getValue().get(0);
        var savedBlock = (ToolUseBlock) saved.getContext().get(1).getContent().get(0);
        assertEquals("{\"packageId\":166}", savedBlock.getContent());
    }

    /** 非 assistant 消息与无 ASKING 块的 state 原样透传（不动 context） */
    @Test
    void saveShouldSkipStatesWithoutAskingBlocks() {
        var state = AgentState.builder()
            .sessionId("s1").userId("u1")
            .context(List.of(Msg.builder().name("user").role(MsgRole.USER)
                .textContent("普通消息").build()))
            .build();

        store.save("s1", "u1", "agent_state", state);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<State>) (Class<?>) State.class);
        verify(delegate).save(eq("s1"), eq("u1"), eq("agent_state"), captor.capture());
        assertEquals(1, ((AgentState) captor.getValue()).getContext().size());
    }
}
