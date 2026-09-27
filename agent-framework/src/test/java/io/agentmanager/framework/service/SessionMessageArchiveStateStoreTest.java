package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionMessageArchiveStateStore 单测：归档触发面（三写路径/键过滤/fail-soft）、
 * slot 复合键、Msg 序列化形态 round-trip（docs/session-history-archive-design.md §6）。
 */
class SessionMessageArchiveStateStoreTest {

    /** 记录型假件：验证归档不影响 delegate 调用与版本语义透传 */
    private static class RecordingStore implements AgentStateStore {
        final List<State> saved = new ArrayList<>();
        long version = 1;

        @Override
        public void save(String userId, String sessionId, String stateKey, State state) {
            saved.add(state);
        }

        @Override
        public void save(String userId, String sessionId, String stateKey, List<? extends State> states) {
            saved.addAll(states);
        }

        @Override
        public long saveIfVersion(String userId, String sessionId, String stateKey, State state, long expectedVersion) {
            saved.add(state);
            return version++;
        }

        @Override
        public boolean supportsVersioning() {
            return true;
        }

        @Override
        public <T extends State> VersionedState<T> getVersioned(
                String userId, String sessionId, String stateKey, Class<T> type) {
            return new VersionedState<>(null, AgentStateStore.UNVERSIONED);
        }

        @Override
        public <T extends State> Optional<T> get(String userId, String sessionId, String stateKey, Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends State> List<T> getList(String userId, String sessionId, String stateKey, Class<T> type) {
            return List.of();
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return false;
        }

        @Override
        public void delete(String userId, String sessionId) { }

        @Override
        public Set<String> listSessionIds(String userId) {
            return Set.of();
        }
    }

    private RecordingStore delegate;
    private SessionMessageStore archiveStore;
    private SessionMessageArchiveStateStore decorator;

    @SuppressWarnings("unchecked")
    private List<SessionMessageStore.MessageRecord> capturedRecords() {
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(archiveStore).archiveBatch(anyString(), captor.capture());
        return (List<SessionMessageStore.MessageRecord>) captor.getValue();
    }

    // ===== 触发面 =====

    @Test
    void saveShouldArchiveContextAndDelegate() {
        var decorator = newDecorator();
        var state = AgentState.builder().sessionId("sess-1").userId("user-a")
            .addMessage(Msg.builder().id("m1").role(MsgRole.USER).textContent("hello").build())
            .addMessage(Msg.builder().id("m2").role(MsgRole.ASSISTANT).textContent("hi").build())
            .replyId("reply-1")
            .build();

        decorator.save("user-a", "sess-1", "agent_state", state);

        verify(archiveStore).archiveBatch(eq("user-a:sess-1"), anyList());
        var records = capturedRecords();
        assertEquals(2, records.size());
        assertEquals("m1", records.get(0).msgId());
        assertEquals("message", records.get(0).kind());
        assertEquals("USER", records.get(0).role());
        assertNull(records.get(0).replyId(), "reply_id 不盖戳（AgentState.getReplyId() 为会话级，见设计文档 §15）");
        assertTrue(records.get(0).msgJson().contains("hello"));
        assertEquals(1, delegate.saved.size());
    }

    @Test
    void saveIfVersionShouldArchiveAndPassthroughVersion() {
        var decorator = newDecorator();
        var state = AgentState.builder().sessionId("sess-1")
            .addMessage(Msg.builder().id("m1").role(MsgRole.USER).textContent("hello").build())
            .build();

        long returned = decorator.saveIfVersion("u", "sess-1", "agent_state", state, 7L);

        assertEquals(1L, returned);
        assertEquals(1, delegate.saved.size());
        var records = capturedRecords();
        assertEquals(1, records.size());
        assertEquals("m1", records.get(0).msgId());
    }

    @Test
    void nonAgentStateKeyShouldSkipArchiveButStillDelegate() {
        var decorator = newDecorator();
        var state = AgentState.builder().sessionId("sandbox/user/agent/x")
            .addMessage(Msg.builder().id("m1").role(MsgRole.USER).textContent("hello").build())
            .build();

        decorator.save("u", "sandbox/user/agent/x", "sandbox_state", state);

        verify(archiveStore, never()).archiveBatch(anyString(), anyList());
        assertEquals(1, delegate.saved.size());
    }

    @Test
    void blankSessionIdShouldSkipArchiveButStillDelegate() {
        var decorator = newDecorator();
        var state = AgentState.builder().sessionId("sess-1").build();

        decorator.save("u", "", "agent_state", state);

        verify(archiveStore, never()).archiveBatch(anyString(), anyList());
        assertEquals(1, delegate.saved.size());
    }

    @Test
    void archiveFailureShouldNotBreakSave() {
        var decorator = newDecorator();
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
            .when(archiveStore).archiveBatch(anyString(), anyList());
        var state = AgentState.builder().sessionId("sess-1")
            .addMessage(Msg.builder().id("m1").role(MsgRole.USER).textContent("hello").build())
            .build();

        // fail-soft：归档抛异常不影响 agent_state 落库
        assertDoesNotThrow(() -> decorator.save("u", "sess-1", "agent_state", state));
        assertEquals(1, delegate.saved.size());
    }

    @Test
    void summaryMessageShouldBeMarkedCompaction() {
        var decorator = newDecorator();
        var state = AgentState.builder().sessionId("sess-1")
            .addMessage(Msg.builder().id("s1").name("__compaction_summary__")
                .role(MsgRole.USER).textContent("A condensed summary follows: ...").build())
            .build();

        decorator.save("u", "sess-1", "agent_state", state);

        var records = capturedRecords();
        assertEquals(SessionMessageStore.KIND_COMPACTION_SUMMARY, records.get(0).kind());
    }

    @Test
    void slotKeyShouldMatchAgentStateSessionColumn() {
        assertEquals("u1:sess-1", SessionMessageArchiveStateStore.slotKey("u1", "sess-1"));
        // 空白 userId 归一 __anon__（与 MysqlAgentStateStore.normalizeUser 一致）
        assertEquals("__anon__:sess-1", SessionMessageArchiveStateStore.slotKey("", "sess-1"));
        assertEquals("__anon__:sess-1", SessionMessageArchiveStateStore.slotKey(null, "sess-1"));
    }

    @Test
    void toRecordShouldReturnNullForNullMsg() {
        assertNull(SessionMessageArchiveStateStore.toRecord(null));
        
    }

    // ===== 序列化 round-trip：Msg → msg_data JSON → StateDataParser 可解析 =====

    @Test
    void msgSerializationShouldRoundTripThroughStateDataParser() throws Exception {
        var userMsg = Msg.builder().id("m1").role(MsgRole.USER).textContent("hello").build();
        var assistantMsg = Msg.builder().id("m2").role(MsgRole.ASSISTANT)
            .content(ThinkingBlock.builder().thinking("internal").build(),
                TextBlock.builder().text("let me run it").build(),
                new ToolUseBlock("call-1", "echo",
                    Map.of("text", "hi"), null, Map.of(), ToolCallState.FINISHED))
            .build();
        var toolMsg = Msg.builder().id("m3").role(MsgRole.TOOL)
            .content(new ToolResultBlock("call-1", "echo",
                List.of(TextBlock.builder().text("echoed: hi").build()), Map.of(), ToolResultState.SUCCESS))
            .build();

        var r1 = SessionMessageArchiveStateStore.toRecord(userMsg);
        var r2 = SessionMessageArchiveStateStore.toRecord(assistantMsg);
        var r3 = SessionMessageArchiveStateStore.toRecord(toolMsg);
        assertNotNull(r1);
        assertNotNull(r2);
        assertNotNull(r3);

        // 与 agent_state.context[] 同构：包一层 context 供 StateDataParser 消费
        var stateData = "{\"context\":[" + r1.msgJson() + "," + r2.msgJson() + "," + r3.msgJson() + "]}";
        var arr = io.agentmanager.framework.service.StateDataParser.findMessagesArray(stateData);
        assertNotNull(arr);
        assertEquals(3, arr.size());
        assertTrue(arr.get(1).get("content").toString().contains("\"type\":\"tool_use\""),
            "块序列化必须带 type 判别字段");

        var dtos = io.agentmanager.framework.service.StateDataParser.toRoleContentList(arr, 8000);
        assertEquals(2, dtos.size()); // 纯 tool_result 的 TOOL 消息被并入 assistant tool_calls
        assertEquals("user", dtos.get(0).get("role"));
        assertEquals("assistant", dtos.get(1).get("role"));
        @SuppressWarnings("unchecked")
        var calls = (List<Map<String, Object>>) dtos.get(1).get("tool_calls");
        assertNotNull(calls);
        assertEquals("call-1", calls.get(0).get("id"));
        assertEquals("echo", calls.get(0).get("name"));
        // ToolCallState.getValue() 带 @JsonValue → 序列化为小写；DTO state 优先取配对结果态（有结果看结果）
        assertEquals("success", calls.get(0).get("state"));
        assertEquals("echoed: hi", calls.get(0).get("output"));
    }

    private SessionMessageArchiveStateStore newDecorator() {
        delegate = new RecordingStore();
        archiveStore = mock(SessionMessageStore.class);
        return new SessionMessageArchiveStateStore(delegate, archiveStore);
    }
}
