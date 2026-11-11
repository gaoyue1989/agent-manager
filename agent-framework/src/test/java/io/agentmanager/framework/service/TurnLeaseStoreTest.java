package io.agentmanager.framework.service;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TurnLeaseStore 单测（本类首个测试——此前只有 mock 层的间接覆盖）。
 *
 * <p>核心是 {@code renew} 的**三态**：把「租约已被接管」与「DB 瞬时故障」区分开。
 * 改动前它返回裸 boolean 且 {@code catch (Exception) → false}，于是
 * {@link TurnLeaseGuard} 把一次连接池抖动当成接管、永久停掉本 turn 的续租——
 * 60s TTL 过后另一个副本接管，而本副本仍在执行仍在写，两侧 seq 区间重叠。
 */
@ExtendWith(MockitoExtension.class)
class TurnLeaseStoreTest {

    @Mock private DataSource dataSource;
    @Mock private java.sql.Connection conn;
    @Mock private java.sql.PreparedStatement renewPs;

    private TurnLeaseStore store;

    @BeforeEach
    void setUp() throws Exception {
        // schema 由 Flyway 迁移管理（db/migration），构造器不再建表：
        // 这里只 stub 业务路径（renew）用到的连接
        lenient().when(dataSource.getConnection()).thenReturn(conn);
        lenient().when(conn.prepareStatement(contains("UPDATE turn_lease"))).thenReturn(renewPs);

        store = new TurnLeaseStore(dataSource, Duration.ofSeconds(60), Duration.ofSeconds(20));
    }

    @Test
    void renewReportsHeldWhenRowUpdated() throws Exception {
        when(renewPs.executeUpdate()).thenReturn(1);
        assertEquals(TurnLeaseStore.RenewOutcome.HELD, store.renew("sid-1", "tok-1"));
    }

    @Test
    void renewReportsLostWhenNoRowMatched() throws Exception {
        when(renewPs.executeUpdate()).thenReturn(0);
        assertEquals(TurnLeaseStore.RenewOutcome.LOST, store.renew("sid-1", "tok-1"),
            "0 行 = 租约已被接管或释放，这是真的丢锁");
    }

    @Test
    void renewReportsErrorOnTransientFailure() throws Exception {
        when(renewPs.executeUpdate())
            .thenThrow(new SQLTransientConnectionException("pool exhausted"));

        assertEquals(TurnLeaseStore.RenewOutcome.ERROR, store.renew("sid-1", "tok-1"),
            "DB 抖动必须报 ERROR 让 guard 重试，而不是被当成接管永久停掉续租");
    }

    @Test
    void renewReportsErrorOnAnySqlException() throws Exception {
        when(renewPs.executeUpdate()).thenThrow(new SQLException("connection reset"));

        assertEquals(TurnLeaseStore.RenewOutcome.ERROR, store.renew("sid-1", "tok-1"));
    }

    @Test
    void exposesTtlForGuardFallbackBound() {
        assertEquals(Duration.ofSeconds(60), store.ttl(),
            "guard 需要 TTL 来判断「故障已持续超过一个租约周期」");
    }

    @Test
    void gateAcquireReturnsUpstreamTurnLease() throws Exception {
        var insertPs = mock(java.sql.PreparedStatement.class);
        var deletePs = mock(java.sql.PreparedStatement.class);
        when(conn.prepareStatement(contains("INSERT INTO turn_lease"))).thenReturn(insertPs);
        when(conn.prepareStatement(contains("DELETE FROM turn_lease WHERE session_id = ? AND token = ?")))
            .thenReturn(deletePs);
        when(insertPs.executeUpdate()).thenReturn(1);

        var lease = store.acquire("sid-1");

        assertInstanceOf(TurnLeaseGuard.class, lease);
        assertInstanceOf(io.agentscope.harness.agent.gateway.TurnLease.class, lease);
        lease.close();
        verify(deletePs).executeUpdate();
    }

    /** 上游 SessionTurnGate 契约：slot 被持有时分布式 gate 用 TurnBusyException 而非排队等待 */
    @Test
    void gateAcquireThrowsTurnBusyWhenSlotHeld() throws Exception {
        var insertPs = mock(java.sql.PreparedStatement.class);
        var deletePs = mock(java.sql.PreparedStatement.class);
        when(conn.prepareStatement(contains("INSERT INTO turn_lease"))).thenReturn(insertPs);
        when(conn.prepareStatement(contains("DELETE FROM turn_lease WHERE session_id = ? AND expires_at")))
            .thenReturn(deletePs);
        when(insertPs.executeUpdate())
            .thenThrow(new java.sql.SQLIntegrityConstraintViolationException("dup pk"));
        when(deletePs.executeUpdate()).thenReturn(0);

        assertThrows(io.agentscope.harness.agent.gateway.TurnBusyException.class,
            () -> store.acquire("sid-1"));
    }

    @Test
    void gateIsRunningMirrorsLeaseState() throws Exception {
        var selectPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(conn.prepareStatement(contains("SELECT 1 FROM turn_lease"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);

        assertTrue(store.isRunning("sid-1"));
        assertFalse(store.isRunning("sid-1"));
    }
}
