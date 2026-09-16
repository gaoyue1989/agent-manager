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
import org.springframework.http.ResponseEntity;

import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.LLMLogger;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentscope.core.message.ToolUseBlock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Thread API tests: list + history + detail + delete + patch + LLM calls.
 */
class ThreadControllerTest {

    private DataSource dataSource;
    private LLMLogger llmLogger;
    private ConfirmContextStore confirmContextStore;
    private SessionUserStore sessionUserStore;
    private SessionEventStore sessionEventStore;
    private ThreadController controller;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        llmLogger = new LLMLogger();
        confirmContextStore = mock(ConfirmContextStore.class);
        sessionUserStore = mock(SessionUserStore.class);
        sessionEventStore = mock(SessionEventStore.class);
        controller = new ThreadController(dataSource, llmLogger, confirmContextStore, sessionUserStore, sessionEventStore);
    }

    /** 让 dataSource.getConnection() 第一次调用抛异常（被 ensureRemarkColumn catch 住），
     *  第二次调用返回业务 conn。
     *  这样避免 mock DatabaseMetaData.getColumns() 这个 final 方法。 */
    private void setupDataSourceWithEnsureRemarkSkipped(Connection businessConn) throws Exception {
        when(dataSource.getConnection())
            .thenThrow(new RuntimeException("skip ensureRemarkColumn"))
            .thenReturn(businessConn);
    }

    // ========== listThreads (no userId filter) ==========

    @Test
    void listThreadsWithoutFilterShouldReturnAllSessionsWithUserId() throws Exception {
        var conn = mock(Connection.class);
        var stmt = mock(Statement.class);
        var rs = mock(ResultSet.class);
        setupDataSourceWithEnsureRemarkSkipped(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("session_id")).thenReturn("acme-test__t1", "acme-test__t2");
        when(rs.getString("user_id")).thenReturn("user1", "user2");
        when(rs.getString("remark")).thenReturn("title1", null);
        when(rs.getTimestamp("updated_at")).thenReturn(new Timestamp(1000), new Timestamp(2000));

        var result = controller.listThreads(null, null);
        assertEquals(2, result.size());
        assertEquals("t1", result.get(0).get("thread_id"));
        assertEquals("user1", result.get(0).get("user_id"));
        assertEquals("title1", result.get(0).get("title"));
        assertEquals("user2", result.get(1).get("user_id"));
        assertEquals("", result.get(1).get("title")); // null remark -> ""
    }

    @Test
    void listThreadsShouldReturnEmptyOnError() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("boom"));
        assertEquals(List.of(), controller.listThreads(null, null));
    }

    // ========== listThreads (with userId filter, LEFT JOIN) ==========

    @Test
    void listThreadsWithUserIdFilterShouldUseLeftJoin() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        setupDataSourceWithEnsureRemarkSkipped(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("session_id")).thenReturn("acme__s1", "acme__s2");
        when(rs.getString("remark")).thenReturn("chat1", null);
        when(rs.getTimestamp("updated_at")).thenReturn(new Timestamp(2000), null);

        var result = controller.listThreads("alice", null);
        // verify SQL uses LEFT JOIN with LIKE matching (agent_state.session_id = slotId format)
        verify(conn).prepareStatement("SELECT su.session_id, su.remark, MAX(a.updated_at) AS updated_at "
            + "FROM session_user su LEFT JOIN agent_state a "
            + "ON a.session_id = su.session_id OR a.session_id LIKE CONCAT(su.session_id, ':%') "
            + "WHERE su.user_id = ? "
            + "GROUP BY su.session_id, su.remark "
            + "ORDER BY COALESCE(MAX(a.updated_at), su.created_at) DESC");
        verify(ps).setString(1, "alice");

        assertEquals(2, result.size());
        assertEquals("alice", result.get(0).get("user_id"));
        assertEquals("chat1", result.get(0).get("title"));
        // Timestamp.toString() 按 JVM 默认时区格式化，期望值同步计算，避免 CI（UTC）与本地（+08）断言漂移
        assertEquals(new Timestamp(2000).toString(), result.get(0).get("updated_at"));
        // agent_state no record -> updated_at should be empty string
        assertEquals("", result.get(1).get("updated_at"));
    }

    @Test
    void listThreadsWithUserIdFilterShouldIncludeOrphanedSessions() throws Exception {
        // LEFT JOIN already covers sessions without agent_state
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        setupDataSourceWithEnsureRemarkSkipped(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("session_id")).thenReturn("acme__old-session");
        when(rs.getString("remark")).thenReturn(null);
        when(rs.getTimestamp("updated_at")).thenReturn(null); // agent_state no record

        var result = controller.listThreads("bob", null);
        assertEquals(1, result.size());
        assertEquals("acme__old-session", result.get(0).get("session_id"));
        assertEquals("bob", result.get(0).get("user_id"));
        assertEquals("", result.get(0).get("updated_at"));
    }

    @Test
    void listThreadsWithUserIdFilterShouldReturnEmptyWhenNoMatchingSessions() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        setupDataSourceWithEnsureRemarkSkipped(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        var result = controller.listThreads("nobody", null);
        assertTrue(result.isEmpty());
    }

    @Test
    void listThreadsShouldPreferHeaderUserIdOverParam() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        setupDataSourceWithEnsureRemarkSkipped(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        // Header userId should override param userId
        controller.listThreads("param-user", "header-user");
        verify(ps).setString(1, "header-user");
    }

    // ========== getThread (single detail) ==========

    @Test
    void getThreadShouldReturnSessionMetaAndMessages() throws Exception {
        when(sessionUserStore.findUserIdBySession("acme__s1")).thenReturn("alice");

        // getThread: meta (agent_state) + generatedFiles (throw) + loadMessages (throw)
        var conn = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(conn)
            .thenThrow(new RuntimeException("skip gf"))
            .thenThrow(new RuntimeException("skip lm"));

        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getTimestamp("updated_at")).thenReturn(new Timestamp(3000));

        var result = controller.getThread("acme__s1");
        assertEquals("acme__s1", result.get("session_id"));
        assertEquals("alice", result.get("user_id"));
        assertEquals(new Timestamp(3000).toString(), result.get("updated_at"));
        assertNotNull(result.get("messages"));
    }

    @Test
    void getThreadShouldReturnEmptyUpdatedAtWhenNoAgentState() throws Exception {
        when(sessionUserStore.findUserIdBySession("acme__s2")).thenReturn(null);

        var conn = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(conn)
            .thenThrow(new RuntimeException("skip gf"))
            .thenThrow(new RuntimeException("skip lm"));

        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        var result = controller.getThread("acme__s2");
        assertEquals("", result.get("updated_at"));
    }

    // ========== deleteThread ==========

    @Test
    void deleteThreadShouldCascadeDelete() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        when(ps.executeQuery()).thenReturn(mock(ResultSet.class));

        var response = controller.deleteThread("acme__s1");
        assertEquals(200, response.getStatusCode().value());
        var body = response.getBody();
        assertNotNull(body);
        assertEquals("acme__s1", body.get("session_id"));
        assertEquals(true, body.get("deleted"));
        assertTrue((int) body.get("rows_affected") > 0);

        // verify confirmContextStore.delete was called
        verify(confirmContextStore).delete("acme__s1");
    }

    @Test
    void deleteThreadShouldHandleDbError() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("db down"));

        var response = controller.deleteThread("acme__s1");
        var body = response.getBody();
        assertNotNull(body);
        assertEquals("acme__s1", body.get("session_id"));
    }

    // ========== patchThread (rename) ==========

    @Test
    void patchThreadShouldUpdateTitle() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        setupDataSourceWithEnsureRemarkSkipped(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);

        var body = new ThreadController.PatchRequest("New Title");
        var response = controller.patchThread("acme__s1", body);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("New Title", response.getBody().get("title"));
    }

    @Test
    void patchThreadWithBlankTitleShouldNotUpdate() throws Exception {
        var body = new ThreadController.PatchRequest("");
        var response = controller.patchThread("acme__s1", body);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("", response.getBody().get("title"));
    }

    @Test
    void patchThreadWithNullTitleShouldNotUpdate() throws Exception {
        var body = new ThreadController.PatchRequest(null);
        var response = controller.patchThread("acme__s1", body);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("", response.getBody().get("title"));
    }

    // ========== threadHistory ==========

    @Test
    void threadHistoryShouldParseMessages() throws Exception {
        var conn = mock(Connection.class);
        // generatedFiles throws (caught), loadMessages succeeds
        when(dataSource.getConnection())
            .thenThrow(new RuntimeException("skip generatedFiles"))
            .thenReturn(conn);

        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
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
        when(dataSource.getConnection())
            .thenThrow(new RuntimeException("skip generatedFiles"))
            .thenReturn(conn);

        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
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
        when(dataSource.getConnection())
            .thenThrow(new RuntimeException("skip generatedFiles"))
            .thenReturn(conn);

        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
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
        when(dataSource.getConnection())
            .thenThrow(new RuntimeException("skip generatedFiles"))
            .thenReturn(conn);

        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
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
        when(dataSource.getConnection())
            .thenThrow(new RuntimeException("skip generatedFiles"))
            .thenReturn(conn);

        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false); // no messages

        var result = controller.threadHistory("acme:t3");
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) result.get("messages");
        assertTrue(messages.isEmpty());
    }

    @Test
    void threadHistoryShouldReturnEmptyOnException() throws Exception {
        // generatedFiles throws, loadMessages also throws -> empty messages
        when(dataSource.getConnection()).thenThrow(new RuntimeException("db down"));
        var result = controller.threadHistory("acme:t4");
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) result.get("messages");
        assertTrue(messages.isEmpty());
    }

    @Test
    void threadHistoryShouldAttachPendingConfirm() throws Exception {
        var pending = new ConfirmContextStore.PendingConfirm("reply-9", List.of(
            ToolUseBlock.builder().id("call-9").name("get_weather")
                .input(java.util.Map.of("city", "beijing")).build()),
            java.time.Instant.now());
        when(confirmContextStore.findPending(anyString())).thenReturn(Optional.of(pending));

        // Skip generatedFiles and loadMessages by throwing
        when(dataSource.getConnection()).thenThrow(new RuntimeException("skip both"));

        var result = controller.threadHistory("acme:mt1");
        assertNotNull(result.get("pendingConfirm"));
        assertTrue(result.get("pendingConfirm").toString().contains("call-9"));
    }

    @Test
    void threadHistoryShouldReturnNullPendingConfirmWhenNone() throws Exception {
        when(confirmContextStore.findPending(anyString())).thenReturn(Optional.empty());
        when(dataSource.getConnection()).thenThrow(new RuntimeException("skip both"));

        var result = controller.threadHistory("acme:mt2");
        assertNull(result.get("pendingConfirm"));
    }

    // ========== extractThreadId ==========

    @Test
    void extractThreadIdShouldHandleDoubleUnderscoreFormat() throws Exception {
        var conn = mock(Connection.class);
        var stmt = mock(Statement.class);
        var rs = mock(ResultSet.class);
        setupDataSourceWithEnsureRemarkSkipped(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("session_id")).thenReturn("acme-test-agent__my-thread-123");
        when(rs.getString("user_id")).thenReturn("u1");
        when(rs.getString("remark")).thenReturn(null);
        when(rs.getTimestamp("updated_at")).thenReturn(new Timestamp(1000));

        var result = controller.listThreads(null, null);
        assertEquals("my-thread-123", result.get(0).get("thread_id"));
    }

    @Test
    void extractThreadIdShouldReturnFullIdWhenNoDoubleUnderscore() throws Exception {
        var conn = mock(Connection.class);
        var stmt = mock(Statement.class);
        var rs = mock(ResultSet.class);
        setupDataSourceWithEnsureRemarkSkipped(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("session_id")).thenReturn("simple-id");
        when(rs.getString("user_id")).thenReturn("u1");
        when(rs.getString("remark")).thenReturn(null);
        when(rs.getTimestamp("updated_at")).thenReturn(new Timestamp(1000));

        var result = controller.listThreads(null, null);
        assertEquals("simple-id", result.get(0).get("thread_id"));
    }

    // ========== llm calls ==========

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
