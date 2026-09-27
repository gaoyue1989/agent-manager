package io.agentmanager.framework.controller;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.AgentStateReader;
import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.LLMLogger;
import io.agentmanager.framework.service.ModelCatalog;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionMessageStore;
import io.agentmanager.framework.service.SessionUserStore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * history 归档双源合并单测（docs/session-history-archive-design.md §7）：
 * 合并顺序、压缩分隔条、origin 标注、分页游标、includeArchived=false 与存量会话回退、
 * 归档故障 fail-soft、reply_id 归档盖戳优先。
 */
class ThreadControllerHistoryMergeTest {

    private static final Timestamp TS = Timestamp.valueOf("2026-09-27 10:00:00");

    private DataSourceHolder ds;
    private ConfirmContextStore confirmContextStore;
    private SessionEventStore sessionEventStore;
    private AgentStateReader agentStateReader;
    private SessionMessageStore sessionMessageStore;
    private ThreadController controller;

    /** 简化 DB mock 载体（generatedFiles 等读库路径统一抛错走 catch） */
    private record DataSourceHolder(javax.sql.DataSource dataSource) {}

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = mock(javax.sql.DataSource.class);
        when(dataSource.getConnection()).thenThrow(new RuntimeException("skip db paths"));
        ds = new DataSourceHolder(dataSource);
        confirmContextStore = mock(ConfirmContextStore.class);
        when(confirmContextStore.findPending(anyString())).thenReturn(java.util.Optional.empty());
        sessionEventStore = mock(SessionEventStore.class);
        agentStateReader = mock(AgentStateReader.class);
        sessionMessageStore = mock(SessionMessageStore.class);

        controller = new ThreadController(dataSource, new LLMLogger(), confirmContextStore,
            mock(SessionUserStore.class), sessionEventStore, agentStateReader,
            mock(ModelCatalog.class), 8000, true, sessionMessageStore);
    }

    private static SessionMessageStore.ArchivedRow row(long id, String msgId, String kind,
                                                       String role, String replyId, String msgJson) {
        return new SessionMessageStore.ArchivedRow(id, msgId, kind, role, replyId, msgJson, TS);
    }

    private static final String MSG_M1 =
        "{\"id\":\"m1\",\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}";
    private static final String MSG_M2_SUMMARY =
        "{\"id\":\"m2\",\"name\":\"__compaction_summary__\",\"role\":\"USER\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"summary text\"}]}";
    private static final String MSG_M3 =
        "{\"id\":\"m3\",\"role\":\"ASSISTANT\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}";
    /** state：m3 已归档（应被跳过）+ m4 未归档尾部（应合入基底末尾） */
    private static final String STATE_DATA = "{\"session_id\":\"s1\",\"context\":["
        + "{\"id\":\"m3\",\"role\":\"ASSISTANT\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]},"
        + "{\"id\":\"m4\",\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"tail\"}]}]}";

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> messagesOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("messages");
    }

    // ===== 合并视图 =====

    @Test
    void mergedViewShouldRenderArchiveTimelineWithCompactionDivider() {
        when(agentStateReader.loadStateData("s1")).thenReturn(STATE_DATA);
        // 全会话归档集：m1/m2/m3 已归档（state 节点被跳过），m4 未归档（合入基底末尾）
        when(sessionMessageStore.findAllMsgIds("s1")).thenReturn(java.util.Set.of("m1", "m2", "m3"));
        when(sessionMessageStore.findPage(eq("s1"), eq(null), anyInt())).thenReturn(List.of(
            row(1, "m1", "message", "USER", null, MSG_M1),
            row(2, "m2", "compaction_summary", "USER", null, MSG_M2_SUMMARY),
            row(3, "m3", "message", "ASSISTANT", null, MSG_M3)));
        when(sessionEventStore.findReplyIds("s1")).thenReturn(List.of("r-turn-1"));

        var result = controller.threadHistory("s1", true, 200, null);

        var messages = messagesOf(result);
        assertEquals(4, messages.size());
        // 归档行按 id 升序为基底
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("hello", messages.get(0).get("content"));
        assertEquals("archive", messages.get(0).get("origin"));
        // reply_id 唯一来源是 Redis 顺序回填（仅 assistant；归档不盖戳，见设计文档 §15）
        assertNull(messages.get(0).get("reply_id"));
        // 摘要消息 → 压缩分隔条合成项（不是普通消息）
        assertEquals("compaction", messages.get(1).get("role"));
        assertEquals("compaction_summary", messages.get(1).get("type"));
        assertEquals("summary text", messages.get(1).get("content"));
        assertEquals("m2", messages.get(1).get("msg_id"));
        assertEquals(TS.toString(), messages.get(1).get("created_at"));
        // m3 归档行权威（state 中同 id 节点被全会话归档集跳过）
        assertEquals("assistant", messages.get(2).get("role"));
        assertEquals("archive", messages.get(2).get("origin"));
        assertEquals("r-turn-1", messages.get(2).get("reply_id"));
        // m4 未归档尾部合入基底末尾
        assertEquals("tail", messages.get(3).get("content"));
        assertEquals("state", messages.get(3).get("origin"));
        assertNull(messages.get(3).get("reply_id"));
        assertFalse((Boolean) result.get("hasMore"));
        assertNull(result.get("nextBeforeId"));
        verify(sessionEventStore).findReplyIds("s1");
    }

    @Test
    void paginationShouldComputeHasMoreAndNextBeforeId() {
        when(agentStateReader.loadStateData("s1")).thenReturn(STATE_DATA);
        when(sessionMessageStore.findAllMsgIds("s1")).thenReturn(java.util.Set.of("m1", "m2", "m3"));
        when(sessionMessageStore.findPage(eq("s1"), eq(null), anyInt())).thenReturn(List.of(
            row(1, "m1", "message", "USER", null, MSG_M1),
            row(2, "m2", "compaction_summary", "USER", null, MSG_M2_SUMMARY),
            row(3, "m3", "message", "ASSISTANT", null, MSG_M3)));
        when(sessionEventStore.findReplyIds("s1")).thenReturn(List.of("r-turn-1"));

        // limit=2 → findPage 收到 limit+1=3；升序返回中最旧一条属于下一页
        var result = controller.threadHistory("s1", true, 2, null);

        verify(sessionMessageStore).findPage(eq("s1"), eq(null), eq(3));
        var messages = messagesOf(result);
        // 首页（beforeId=null）合入 state 独有尾部 m4；已归档节点经全会话归档集去重不重复
        assertEquals(3, messages.size());
        assertEquals("summary text", messages.get(0).get("content"));
        assertEquals("hi", messages.get(1).get("content"));
        assertEquals("tail", messages.get(2).get("content"));
        assertTrue((Boolean) result.get("hasMore"));
        assertEquals(2L, ((Number) result.get("nextBeforeId")).longValue());
    }

    @Test
    void deepPageShouldOnlyUseArchiveRows() {
        when(agentStateReader.loadStateData("s1")).thenReturn(STATE_DATA);
        when(sessionMessageStore.findPage(eq("s1"), eq(2L), anyInt())).thenReturn(List.of(
            row(1, "m1", "message", "USER", "r1", MSG_M1)));

        var result = controller.threadHistory("s1", true, 200, 2L);

        // 翻页不再合入 state 独有消息（m4），避免跨页重复；全会话归档集也无需查询
        var messages = messagesOf(result);
        assertEquals(1, messages.size());
        assertEquals("hello", messages.get(0).get("content"));
        verify(sessionMessageStore, never()).findAllMsgIds(anyString());
    }

    // ===== 回退路径 =====

    @Test
    void includeArchivedFalseShouldFallbackToStateOnly() {
        when(agentStateReader.loadStateData("s1")).thenReturn(STATE_DATA);

        var result = controller.threadHistory("s1", false, 200, null);

        verify(sessionMessageStore, never()).findPage(anyString(), any(), anyInt());
        verify(sessionMessageStore, never()).findAllMsgIds(anyString());
        var messages = messagesOf(result);
        assertEquals(2, messages.size());
        assertEquals("hi", messages.get(0).get("content"));
        assertEquals("tail", messages.get(1).get("content"));
        assertFalse((Boolean) result.get("hasMore"));
        assertNull(result.get("nextBeforeId"));
    }

    @Test
    void legacySessionWithoutArchiveRowsShouldBehaveAsBefore() {
        when(agentStateReader.loadStateData("s1")).thenReturn(
            "{\"session_id\":\"s1\",\"context\":["
                + "{\"id\":\"m1\",\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]},"
                + "{\"id\":\"m2\",\"role\":\"ASSISTANT\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}]}");
        when(sessionMessageStore.findPage(eq("s1"), eq(null), anyInt())).thenReturn(List.of());
        when(sessionMessageStore.findAllMsgIds("s1")).thenReturn(java.util.Set.of());
        when(sessionEventStore.findReplyIds("s1")).thenReturn(List.of("r1", "r2"));

        var result = controller.threadHistory("s1", true, 200, null);

        // 存量会话（无归档行）：消息来自 state、origin=state、reply_id 走既有顺序猜测兜底
        var messages = messagesOf(result);
        assertEquals(2, messages.size());
        assertEquals("state", messages.get(0).get("origin"));
        assertEquals("state", messages.get(1).get("origin"));
        assertEquals("r1", messages.get(1).get("reply_id"));
        assertFalse((Boolean) result.get("hasMore"));
    }

    @Test
    void archiveFailureShouldFallbackToStateOnly() {
        when(agentStateReader.loadStateData("s1")).thenReturn(STATE_DATA);
        when(sessionMessageStore.findPage(anyString(), any(), anyInt()))
            .thenThrow(new RuntimeException("db down"));

        var result = controller.threadHistory("s1", true, 200, null);

        // fail-soft：归档查询故障降级为仅 agent_state
        var messages = messagesOf(result);
        assertEquals(2, messages.size());
        assertEquals("hi", messages.get(0).get("content"));
        assertFalse((Boolean) result.get("hasMore"));
    }
}
