package io.agentmanager.framework.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionUserStore 单测：锁定 updateRemark 不得使用「写目标表同时读它」的自引用子查询
 * （MySQL 1093: You can't specify target table for update in FROM clause）。
 */
class SessionUserStoreTest {

    private DataSource dataSource;
    private Connection conn;
    private PreparedStatement ps;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(mock(Statement.class));
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
    }

    @Test
    void updateRemarkShouldIssuePlainUpdateWithoutSelfReferenceSubquery() throws Exception {
        var store = new SessionUserStore(dataSource);

        assertTrue(store.updateRemark("sid-1", "新标题"));

        var sqlCap = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sqlCap.capture());
        var sql = sqlCap.getValue();
        assertTrue(sql.startsWith("UPDATE session_user SET remark"),
            "updateRemark 应先发 UPDATE，实际: " + sql);
        assertTrue(!sql.toUpperCase().contains("FROM SESSION_USER"),
            "SQL 不得在写 session_user 时读它（MySQL 1093）: " + sql);
    }
}
