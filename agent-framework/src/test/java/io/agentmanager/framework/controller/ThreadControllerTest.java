package io.agentmanager.framework.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.LLMLogger;
import io.agentscope.core.message.ToolUseBlock;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Thread 会话 API 测试（O7：会话接口迁 /threads）：列表 + 历史 + LLM 调用记录。
 * （confirm_context 随旧链路退役，pendingConfirm 展示已删——agui-migration-plan Phase 3）
 */
class ThreadControllerTest {

    private DataSource dataSource;
    private LLMLogger llmLogger;
    private ThreadController controller;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        llmLogger = new LLMLogger();
        controller = new ThreadController(dataSource, llmLogger);
    }

    // ---------- list ----------

    @Test
    void listThreadsShouldReturnDeduplicatedSessions() throws Exception {
        var conn = mock(Connection.class);
        var stmt = mock(Statement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("session_id")).thenReturn("acme-test-agent:t1");
        when(rs.getTimestamp("updated_at")).thenReturn(new Timestamp(1000));

        var result = controller.listThreads();
        assertEquals(1, result.size());
        assertEquals("t1", result.get(0).get("thread_id"));
        assertEquals("acme-test-agent:t1", result.get(0).get("session_id"));
    }

    @Test
    void listThreadsShouldReturnEmptyOnError() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("boom"));
        assertEquals(List.of(), controller.listThreads());
    }

    // ---------- thread history ----------

    @Test
    void threadHistoryShouldParseMessages() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("state_data")).thenReturn(
            "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"assistant\",\"content\":\"hi\"}]}");

        var result = controller.threadHistory("acme:t1");
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("hello", messages.get(0).get("content"));
        assertEquals("hi", messages.get(1).get("content"));
    }

    @Test
    void threadHistoryShouldHandlePartsFallback() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("state_data")).thenReturn(
            "{\"nested\":{\"messages\":[{\"role\":\"user\",\"parts\":[{\"text\":\"p1\"}]}]}}");

        var result = controller.threadHistory("acme:t2");
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals("[{\"text\":\"p1\"}]", messages.get(0).get("content"));
    }

    @Test
    void threadHistoryShouldParseAgentScopeContextField() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("state_data")).thenReturn(
            "{\"session_id\":\"s5\",\"summary\":\"\",\"context\":[" +
            "{\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"metadata\":{}}," +
            "{\"role\":\"ASSISTANT\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"reasoning\"}," +
            "{\"type\":\"text\",\"text\":\"hi\"}],\"metadata\":{}}]}");

        var result = controller.threadHistory("acme:s5");
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) result.get("messages");
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("hello", messages.get(0).get("content"));
        assertEquals("assistant", messages.get(1).get("role"));
        assertEquals("hi", messages.get(1).get("content"));
    }

    @Test
    void threadHistoryShouldSkipThinkingBlocks() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("state_data")).thenReturn(
            "{\"context\":[{\"role\":\"ASSISTANT\"," +
            "\"content\":[{\"type\":\"thinking\",\"thinking\":\"internal reasoning\"}]}]}");

        var result = controller.threadHistory("acme:s6");
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) result.get("messages");
        assertTrue(messages.isEmpty());
    }

    @Test
    void threadHistoryShouldReturnEmptyWhenNoRow() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        var result = controller.threadHistory("acme:t3");
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) result.get("messages");
        assertTrue(messages.isEmpty());
    }

    @Test
    void threadHistoryShouldReturnErrorOnException() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("db down"));
        var result = controller.threadHistory("acme:t4");
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) result.get("messages");
        assertTrue(messages.isEmpty());
        assertEquals("db down", result.get("error"));
    }

    // ---------- llm calls ----------

    @Test
    void llmCallsShouldReturnRecords() {
        llmLogger.logCall("s1", Map.of("model", "gpt-4"), Map.of("content", "ok"));
        var result = controller.llmCalls("s1");
        @SuppressWarnings("unchecked")
        var calls = (List<Map<String, Object>>) result.get("calls");
        assertEquals(1, calls.size());
        assertNotNull(calls.get(0).get("call_id"));
        assertEquals("gpt-4", ((Map<?, ?>) calls.get(0).get("request")).get("model"));
    }

    @Test
    void llmCallsShouldReturnEmptyForUnknownThread() {
        var result = controller.llmCalls("unknown");
        @SuppressWarnings("unchecked")
        var calls = (List<Map<String, Object>>) result.get("calls");
        assertTrue(calls.isEmpty());
    }
}