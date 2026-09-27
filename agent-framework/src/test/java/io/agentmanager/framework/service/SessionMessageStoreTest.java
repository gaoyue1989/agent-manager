package io.agentmanager.framework.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionMessageStore 单测：合并规则（文本取长/状态取新）+ SQL 形态锁定。
 * 设计文档：docs/session-history-archive-design.md §6.4 / §7.1。
 */
class SessionMessageStoreTest {

    private DataSource dataSource;
    private Connection conn;
    private Statement stmt;
    private PreparedStatement ps;
    private ResultSet rs;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        conn = mock(Connection.class);
        stmt = mock(Statement.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(stmt.executeUpdate(anyString())).thenReturn(0);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        when(ps.executeUpdate()).thenReturn(1);
    }

    // ===== mergeMsgJson：文本取长、状态取新 =====

    private static String msgJson(String id, String text) {
        return "{\"id\":\"" + id + "\",\"role\":\"ASSISTANT\",\"content\":["
            + "{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    @Test
    void mergeShouldTakeNewWhenPayloadGrows() {
        // 流式扩展：新版更长 → 整体取新
        var merged = SessionMessageStore.mergeMsgJson(msgJson("m1", "hi"), msgJson("m1", "hi there"));
        assertTrue(merged.contains("hi there"));
        assertTrue(!merged.contains("\"text\":\"hi\"}"));
    }

    @Test
    void mergeShouldTakeNewWhenOnlyStateChanges() {
        // 状态迁移（tool_use ASKING→FINISHED）：载荷不变 → 整体取新，终态可见
        var oldJson = "{\"id\":\"m1\",\"role\":\"ASSISTANT\",\"content\":["
            + "{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"echo\",\"input\":{\"a\":1},"
            + "\"state\":\"ASKING\"}]}";
        var newJson = "{\"id\":\"m1\",\"role\":\"ASSISTANT\",\"content\":["
            + "{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"echo\",\"input\":{\"a\":1},"
            + "\"state\":\"FINISHED\"}]}";
        var merged = SessionMessageStore.mergeMsgJson(oldJson, newJson);
        assertTrue(merged.contains("\"state\":\"FINISHED\""));
    }

    @Test
    void mergeShouldKeepOldTextAndNewStateWhenPruned() {
        // 裁剪收缩：新版 output 是预览 → 旧全文保底 + 新状态覆盖
        var fullText = "x".repeat(100);
        var oldJson = "{\"id\":\"m1\",\"role\":\"TOOL\",\"content\":["
            + "{\"type\":\"tool_result\",\"id\":\"call-1\",\"name\":\"echo\","
            + "\"output\":[{\"type\":\"text\",\"text\":\"" + fullText + "\"}],"
            + "\"state\":\"SUCCESS\"}],\"metadata\":{\"gen\":\"1\"}}";
        var newJson = "{\"id\":\"m1\",\"role\":\"TOOL\",\"content\":["
            + "{\"type\":\"tool_result\",\"id\":\"call-1\",\"name\":\"echo\","
            + "\"output\":[{\"type\":\"text\",\"text\":\"xx…(truncated)\"}],"
            + "\"state\":\"ERROR\"}],\"metadata\":{\"gen\":\"2\"}}";
        var merged = SessionMessageStore.mergeMsgJson(oldJson, newJson);
        assertTrue(merged.contains(fullText), "收缩版不得覆盖旧全文");
        assertTrue(merged.contains("\"state\":\"ERROR\""), "块状态须取新");
        assertTrue(merged.contains("\"gen\":\"2\""), "顶层 metadata 须取新");
    }

    @Test
    void mergeShouldOverlayToolUseContentMirrorWhenPruned() {
        // 收缩版的 tool_use.content（input 镜像回填）随新，input 文本保持旧
        var oldJson = "{\"id\":\"m1\",\"role\":\"ASSISTANT\",\"content\":["
            + "{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"echo\","
            + "\"input\":{\"cmd\":\"" + "y".repeat(60) + "\"},\"content\":\"{old-full}\","
            + "\"state\":\"ASKING\"}]}";
        var newJson = "{\"id\":\"m1\",\"role\":\"ASSISTANT\",\"content\":["
            + "{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"echo\","
            + "\"input\":{\"cmd\":\"yy\"},\"content\":\"{new}\",\"state\":\"FINISHED\"}]}";
        var merged = SessionMessageStore.mergeMsgJson(oldJson, newJson);
        assertTrue(merged.contains("{new}"), "tool_use.content 镜像随新");
        assertTrue(merged.contains("\"state\":\"FINISHED\""));
        assertTrue(merged.contains("yyyy"), "input 文本保底");
    }

    @Test
    void mergeShouldHandleNullAndBrokenJson() {
        assertEquals("new", SessionMessageStore.mergeMsgJson(null, "new"));
        assertEquals("old", SessionMessageStore.mergeMsgJson("old", null));
        // 旧版损坏 → 宁新勿丢
        assertEquals("{\"id\":\"m1\"}", SessionMessageStore.mergeMsgJson("not-json{", "{\"id\":\"m1\"}"));
        // 新版损坏 → 保旧（无法解析新版本时不能丢旧文本）
        assertEquals("not-json{", SessionMessageStore.mergeMsgJson("{\"id\":\"m1\"}", "not-json{"));
        assertNull(SessionMessageStore.mergeMsgJson(null, null));
    }

    @Test
    void mergeShouldHandleToolResultOutputArrayShape() {
        // ToolResultBlock.getOutput() 是嵌套块数组：计数须穿透数组，否则收缩检测失效
        var oldJson = "{\"id\":\"m1\",\"role\":\"TOOL\",\"content\":["
            + "{\"type\":\"tool_result\",\"id\":\"call-1\",\"name\":\"echo\","
            + "\"output\":[{\"type\":\"text\",\"text\":\"" + "z".repeat(80) + "\"}],\"state\":\"SUCCESS\"}]}";
        var newJson = "{\"id\":\"m1\",\"role\":\"TOOL\",\"content\":["
            + "{\"type\":\"tool_result\",\"id\":\"call-1\",\"name\":\"echo\","
            + "\"output\":[{\"type\":\"text\",\"text\":\"zz\"}],\"state\":\"SUCCESS\"}]}";
        var merged = SessionMessageStore.mergeMsgJson(oldJson, newJson);
        assertTrue(merged.contains("zzzz"), "嵌套 output 数组的收缩须被识别并保底");
    }

    // ===== SQL 形态锁定 =====

    @Test
    void initSchemaShouldCreateTableWithUniqueKey() throws Exception {
        new SessionMessageStore(dataSource); // 构造即 initSchema
        var sqlCap = ArgumentCaptor.forClass(String.class);
        verify(stmt).executeUpdate(sqlCap.capture());
        var ddl = sqlCap.getValue();
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS session_message"));
        assertTrue(ddl.contains("UNIQUE KEY uk_session_msg (session_id, msg_id)"), "幂等唯一键");
        assertTrue(ddl.contains("KEY idx_session_id (session_id, id)"));
    }

    @Test
    void findPageShouldUseFiveFormSessionPredicate() throws Exception {
        // 与 AgentStateReader.loadFragments 同款 5 形 LIKE（评审 R8：勿收窄为 2 形）
        new SessionMessageStore(dataSource).findPage("sid-1", 99L, 10);
        var sqlCap = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sqlCap.capture());
        var sql = sqlCap.getValue();
        assertTrue(sql.contains("session_id = ?"));
        assertTrue(sql.contains("LIKE CONCAT(?, ':%')"));
        assertTrue(sql.contains("LIKE CONCAT(?, '__%')"));
        assertTrue(sql.contains("LIKE CONCAT('%:', ?)"));
        assertTrue(sql.contains("LIKE CONCAT('%__', ?)"));
        assertTrue(sql.contains("AND id < ?"));
        assertTrue(sql.endsWith("ORDER BY id DESC LIMIT ?"));
    }

    @Test
    void findAllMsgIdsShouldUseFiveFormSessionPredicate() throws Exception {
        new SessionMessageStore(dataSource).findAllMsgIds("sid-1");
        var sqlCap = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sqlCap.capture());
        var sql = sqlCap.getValue();
        assertTrue(sql.startsWith("SELECT DISTINCT msg_id FROM session_message"));
        assertTrue(sql.contains("LIKE CONCAT('%:', ?)"));
    }

    @Test
    void archiveBatchShouldUpsertWithReplyIdKeepFirst() throws Exception {
        var store = new SessionMessageStore(dataSource);
        store.archiveBatch("u:s1", List.of(
            new SessionMessageStore.MessageRecord("m1", "message", "USER", "reply-1",
                "{\"id\":\"m1\",\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}")));
        var sqlCap = ArgumentCaptor.forClass(String.class);
        // 读旧值（SELECT）+ 批量 upsert（INSERT）两条语句
        verify(conn, times(2)).prepareStatement(sqlCap.capture());
        var sql = sqlCap.getAllValues().get(1);
        assertTrue(sql.contains("INSERT INTO session_message"));
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(sql.contains("reply_id = IF(reply_id IS NULL OR reply_id = '', VALUES(reply_id), reply_id)"),
            "reply_id 保持首个非空盖戳");
        // 读旧值（SELECT）与批量 upsert（INSERT）共用 ps mock：sessionKey→参数1、msg_id→参数2 各绑定两次
        verify(ps, times(2)).setString(1, "u:s1");
        verify(ps, times(2)).setString(2, "m1");
        verify(ps).setString(3, "message");
        verify(ps).setString(4, "USER");
        verify(ps).setString(5, "reply-1");
    }

    @Test
    void archiveBatchShouldTolerateStoreErrors() throws Exception {
        when(conn.prepareStatement(anyString())).thenThrow(new RuntimeException("db down"));
        var store = new SessionMessageStore(dataSource);
        // fail-soft：不抛出（fail-soft 契约，绝不影响对话主链路）
        store.archiveBatch("u:s1", List.of(
            new SessionMessageStore.MessageRecord("m1", "message", "USER", null, "{\"id\":\"m1\"}")));
    }

    @Test
    void deleteBeforeShouldUseUpdatedAtCutoff() throws Exception {
        var store = new SessionMessageStore(dataSource);
        store.deleteBefore(Instant.now());
        var sqlCap = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sqlCap.capture());
        assertTrue(sqlCap.getValue().contains("DELETE FROM session_message WHERE updated_at < ?"));
    }

    @Test
    void deleteBySessionShouldUseFiveFormPredicate() throws Exception {
        var store = new SessionMessageStore(dataSource);
        store.deleteBySession("sid-1");
        var sqlCap = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sqlCap.capture());
        var sql = sqlCap.getValue();
        assertTrue(sql.startsWith("DELETE FROM session_message"));
        assertTrue(sql.contains("LIKE CONCAT('%:', ?)"));
    }
}
