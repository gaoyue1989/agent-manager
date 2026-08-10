package io.agentmanager.framework.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlTaskStoreTest {

    private DataSource dataSource;
    private MySqlTaskStore store;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        store = new MySqlTaskStore(dataSource);
    }

    private void mockStateData(String stateData, boolean hasRow) throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(hasRow);
        when(rs.getString("state_data")).thenReturn(stateData);
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.valueOf("2026-08-10 12:00:00"));
    }

    private static final String STATE_DATA_WITH_HISTORY = """
        {"session_id":"s1","user_id":"u1","summary":"",
         "context":[
           {"id":"m1","role":"USER","content":[{"type":"text","text":"hello"}],"metadata":{}},
           {"id":"m2","role":"ASSISTANT",
            "content":[{"type":"thinking","thinking":"reasoning"},{"type":"text","text":"hi there"}],
            "metadata":{}}
         ],
         "cur_iter":0,"shutdown_interrupted":false}
        """;

    @Test
    void getShouldBuildTaskWithHistory() throws Exception {
        mockStateData(STATE_DATA_WITH_HISTORY, true);

        Task task = store.get("s1");

        assertNotNull(task);
        assertEquals("s1", task.getId());
        assertEquals("s1", task.getContextId());
        assertEquals(TaskState.COMPLETED, task.getStatus().state());
        List<Message> history = task.getHistory();
        assertEquals(2, history.size());
        assertEquals(Message.Role.USER, history.get(0).getRole());
        assertEquals("hello", ((TextPart) history.get(0).getParts().get(0)).getText());
        assertEquals(Message.Role.AGENT, history.get(1).getRole());
        // thinking 块被跳过，仅 text 块
        assertEquals(1, history.get(1).getParts().size());
        assertEquals("hi there", ((TextPart) history.get(1).getParts().get(0)).getText());
    }

    @Test
    void getShouldReturnNullWhenNotFound() throws Exception {
        mockStateData(null, false);

        assertNull(store.get("nonexistent"));
    }

    @Test
    void getShouldInferCanceledState() throws Exception {
        mockStateData("""
            {"session_id":"s2","context":[],"cur_iter":3,"shutdown_interrupted":true}
            """, true);

        Task task = store.get("s2");

        assertNotNull(task);
        assertEquals(TaskState.CANCELED, task.getStatus().state());
    }

    @Test
    void getShouldInferWorkingState() throws Exception {
        mockStateData("""
            {"session_id":"s3","context":[],"cur_iter":5,"shutdown_interrupted":false}
            """, true);

        Task task = store.get("s3");

        assertNotNull(task);
        assertEquals(TaskState.WORKING, task.getStatus().state());
    }

    @Test
    void getShouldReturnEmptyHistoryForEmptyContext() throws Exception {
        mockStateData("""
            {"session_id":"s4","context":[],"cur_iter":0,"shutdown_interrupted":false}
            """, true);

        Task task = store.get("s4");

        assertNotNull(task);
        assertTrue(task.getHistory().isEmpty());
    }

    @Test
    void getShouldReturnNullOnDbError() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("db down"));

        assertNull(store.get("s5"));
    }

    @Test
    void saveShouldBeNoOp() {
        var task = new Task("t1", "t1", new io.a2a.spec.TaskStatus(TaskState.COMPLETED),
            List.of(), List.of(), java.util.Map.of());
        // 不抛异常即可（save 为 no-op）
        assertDoesNotThrow(() -> store.save(task));
    }

    @Test
    void deleteShouldBeNoOp() {
        assertDoesNotThrow(() -> store.delete("t1"));
    }

    @Test
    void getShouldQueryAgentStateTable() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        store.get("s6");

        verify(conn).prepareStatement(org.mockito.ArgumentMatchers.contains("agent_state"));
        verify(ps).setString(1, "s6");
    }
}
