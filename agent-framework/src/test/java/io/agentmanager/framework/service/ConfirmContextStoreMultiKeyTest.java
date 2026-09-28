package io.agentmanager.framework.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.agentscope.core.message.ToolUseBlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConfirmContextStore 多行形态单测（V7 迁移，travel-fulfillment 设计 §5.1/§5.2）：
 * confirm_key 复合键写入、本地行兼容重载、FIFO 升序、local/remote TTL 分档、
 * 远程锚点 JSON 反序列化与兼容旧行为（5 参 PendingConfirm / 覆盖写语义）。
 *
 * <p>mock 风格同 SessionUserStoreTest（mock JDBC 三件套，断言 SQL 形态与参数绑定）。
 */
class ConfirmContextStoreMultiKeyTest {

    private static final Duration LOCAL_TTL = Duration.ofMinutes(30);
    private static final Duration REMOTE_TTL = Duration.ofHours(2);

    private DataSource dataSource;
    private Connection conn;
    private PreparedStatement ps;
    private ConfirmContextStore store;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        store = new ConfirmContextStore(dataSource, LOCAL_TTL, REMOTE_TTL);
    }

    // ===== 写入 =====

    @Test
    void putLocalCompatShouldBindDefaultKeyAndNullAnchor() throws Exception {
        // 存量调用零改动：5 参 put = confirm_key 'local' + remote_task NULL
        store.put("acme__t1", List.of(Map.of("id", "call-1", "name", "t", "input", Map.of())),
            "reply-1", "gw-1", "peer-1");

        var sqlCap = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sqlCap.capture());
        var sql = sqlCap.getValue();
        assertTrue(sql.contains("session_id, confirm_key, tool_calls_json, remote_task"), sql);
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"), "同 (session_id, confirm_key) 覆盖写语义保留: " + sql);

        verify(ps).setString(1, "acme__t1");
        verify(ps).setString(2, ConfirmContextStore.LOCAL_KEY);
        verify(ps).setString(4, null);   // remote_task 锚点：本地行为 NULL
    }

    @Test
    void putRemoteShouldBindTaskKeyAndAnchorJson() throws Exception {
        var anchor = "{\"service\":\"booking\",\"task_id\":\"t-123\",\"tool_calls\":[],\"child_reply_id\":null}";
        store.put("task:t-123", "webui-1",
            List.of(Map.of("id", "call-1", "name", "create_order", "input", Map.of("order_id", "O-1"))),
            null, null, null, anchor);

        verify(ps).setString(1, "webui-1");
        verify(ps).setString(2, "task:t-123");
        verify(ps).setString(4, anchor);
    }

    // ===== 查询形态 =====

    @Test
    void findPendingShouldBeLocalOnlyAndFifoAscending() throws Exception {
        stubEmptyResult();
        // 单参 findPending（父 state 恢复路径专用）：仅 confirm_key='local'，FIFO 取最早行
        store.findPending("acme__t1");

        var sql = capturedSql();
        assertTrue(sql.contains("confirm_key = ?"), "本地恢复路径不得消费远程行: " + sql);
        assertTrue(sql.contains("ORDER BY created_at ASC"), "FIFO 取最早未消费行（设计 §5.2）: " + sql);

        verify(ps).setString(6, ConfirmContextStore.LOCAL_KEY);
        verify(ps).setInt(7, (int) LOCAL_TTL.toSeconds());
    }

    @Test
    void findByKeyShouldBindGivenKeyAndTieredTtl() throws Exception {
        stubEmptyResult();
        store.findPending("webui-1", "task:t-123");

        verify(ps).setString(6, "task:t-123");
        verify(ps).setInt(7, (int) REMOTE_TTL.toSeconds());
    }

    @Test
    void findHeadPendingShouldFilterBothTiersWithoutKeyRestriction() throws Exception {
        stubEmptyResult();
        // history pendingConfirm 数据源：任意 confirm_key 的最早未消费行
        store.findHeadPending("webui-1");

        var sql = capturedSql();
        assertFalse(sql.contains("confirm_key = ?\n                   AND consumed"), sql);
        assertTrue(sql.contains("confirm_key = 'local'"), "本地行 30min TTL 分档: " + sql);
        assertTrue(sql.contains("confirm_key <> 'local'"), "远程行独立 TTL 分档: " + sql);
        assertTrue(sql.contains("ORDER BY created_at ASC"), sql);

        verify(ps).setInt(6, (int) LOCAL_TTL.toSeconds());
        verify(ps).setInt(7, (int) REMOTE_TTL.toSeconds());
    }

    @Test
    void findUnconsumedRemoteShouldRestrictToRemoteRows() throws Exception {
        stubEmptyResult();
        store.findUnconsumedRemote("webui-1");

        var sql = capturedSql();
        assertTrue(sql.contains("confirm_key <> 'local'"), sql);
        assertTrue(sql.contains("ORDER BY created_at ASC"), sql);
    }

    @Test
    void findExpiredRemoteRowsShouldSelectExpiredUnconsumedRemote() throws Exception {
        stubEmptyResult();
        store.findExpiredRemoteRows();

        var sql = capturedSql();
        assertTrue(sql.contains("confirm_key <> 'local'"), sql);
        assertTrue(sql.contains("consumed = 0"), sql);
        assertTrue(sql.contains("created_at < DATE_SUB"), "超 TTL 判定方向: " + sql);
        verify(ps).setInt(1, (int) REMOTE_TTL.toSeconds());
    }

    // ===== 消费与清理 =====

    @Test
    void consumeShouldCasByPrimaryKeyPair() throws Exception {
        // 先建 ResultSet 再打桩：mock 构造不得发生在未完成的 when() 上下文内
        ResultSet row = rowResultSet();
        when(ps.executeQuery()).thenReturn(row);
        var stored = store.consume("webui-1", "task:t-123");

        var sqlCap = ArgumentCaptor.forClass(String.class);
        verify(conn, org.mockito.Mockito.atLeastOnce()).prepareStatement(sqlCap.capture());
        var casSql = sqlCap.getAllValues().get(0);   // 第一段 = CAS UPDATE
        assertTrue(casSql.contains("AND confirm_key = ? AND consumed = 0"),
            "CAS 消费必须按 (session_id, confirm_key) 精确键: " + casSql);
        // UPDATE 与随后的 SELECT 读回各绑一次 confirm_key
        verify(ps, org.mockito.Mockito.times(2)).setString(2, "task:t-123");
        assertEquals("t-123-task", stored.replyId());
    }

    @Test
    void deleteExpiredShouldUseTwoTierCutoffs() throws Exception {
        store.deleteExpired();

        var sql = capturedSql();
        assertTrue(sql.contains("confirm_key = 'local'"), sql);
        assertTrue(sql.contains("confirm_key <> 'local'"), sql);
        verify(ps).setInt(1, (int) LOCAL_TTL.toSeconds());
        verify(ps).setInt(2, (int) REMOTE_TTL.toSeconds());
    }

    @Test
    void deleteShouldRemoveAllKeysOfSession() throws Exception {
        store.delete("webui-1");
        var sql = capturedSql();
        assertTrue(sql.contains("DELETE FROM confirm_context WHERE session_id = ?"), sql);
        assertFalse(sql.toUpperCase().contains("CONFIRM_KEY"), "删会话须清全部 confirm_key 行: " + sql);
    }

    // ===== 行反序列化（含 remote_task 锚点） =====

    @Test
    void headRowShouldCarryKeyAndParsedRemoteAnchor() throws Exception {
        ResultSet rs = remoteRowResultSet();
        when(ps.executeQuery()).thenReturn(rs);
        var row = store.findHeadPending("webui-1").orElseThrow();

        assertEquals("webui-1", row.sessionId(), "消费须用行实际存储键（前缀兼容命中后精确 CAS）");
        assertEquals("task:t-123", row.confirmKey());
        assertTrue(row.isRemote());
        assertEquals("booking", row.remoteTaskField("service"));
        assertEquals("t-123", row.remoteTaskField("task_id"));
        assertNull(row.remoteTaskField("child_reply_id"), "快照不可得的锚点字段为 null");
        assertEquals(1, row.toolCalls().size());
        assertEquals("create_order", row.toolCalls().get(0).getName());
        assertEquals(Map.of("order_id", "O-1"), row.toolCalls().get(0).getInput(),
            "toolInputJson 反序列化为 input Map");
    }

    @Test
    void headRowWithCorruptAnchorShouldDegradeToLocalLikeRow() throws Exception {
        ResultSet rs = remoteRowResultSet("not-json{");
        when(ps.executeQuery()).thenReturn(rs);
        var row = store.findHeadPending("webui-1").orElseThrow();
        assertNull(row.remoteTask(), "损坏锚点 fail-soft 为 null");
        assertFalse(row.isRemote(), "无锚点即不按远程路由（fail-closed 到本地语义）");
    }

    // ===== 兼容旧行为 =====

    @Test
    void pendingConfirmCompatConstructorShouldBehaveAsLocalRow() {
        var row = new ConfirmContextStore.PendingConfirm(
            "reply-1", List.of(ToolUseBlock.builder().id("call-1").name("t")
                .input(Map.of("k", "v")).build()), Instant.now(), null, null);
        assertFalse(row.isRemote());
        assertNull(row.remoteTaskField("task_id"));
        assertEquals(1, row.toolsJson().size());
        assertEquals("call-1", row.toolsJson().get(0).get("tool_call_id"));
    }

    @Test
    void ttlAccessorsShouldExposeBothTiers() {
        assertEquals(LOCAL_TTL, store.ttl());
        assertEquals(REMOTE_TTL, store.remoteTtl());
        var defaulted = new ConfirmContextStore(dataSource, LOCAL_TTL);
        assertEquals(ConfirmContextStore.DEFAULT_REMOTE_TTL, defaulted.remoteTtl(),
            "两参兼容构造默认远程 TTL 24h");
    }

    // ===== 辅助 =====

    private void stubEmptyResult() throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(ps.executeQuery()).thenReturn(rs);
    }

    private String capturedSql() throws Exception {
        var sqlCap = ArgumentCaptor.forClass(String.class);
        verify(conn, org.mockito.Mockito.atLeastOnce()).prepareStatement(sqlCap.capture());
        return sqlCap.getValue();
    }

    /** 消费读取段的完整行（本地行形态） */
    private ResultSet rowResultSet() throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString("tool_calls_json")).thenReturn(
            "[{\"id\":\"call-1\",\"name\":\"create_order\",\"input\":{\"order_id\":\"O-1\"}}]");
        when(rs.getString("remote_task")).thenReturn(null);
        when(rs.getString("reply_id")).thenReturn("t-123-task");
        when(rs.getString("runtime_session_id")).thenReturn(null);
        when(rs.getString("runtime_user_id")).thenReturn(null);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
        return rs;
    }

    /** FIFO 头查询的远程行（含锚点 JSON，可注入损坏形态） */
    private ResultSet remoteRowResultSet(String... anchorOverride) throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("session_id")).thenReturn("webui-1");
        when(rs.getString("confirm_key")).thenReturn("task:t-123");
        when(rs.getString("tool_calls_json")).thenReturn(
            "[{\"id\":\"call-1\",\"name\":\"create_order\",\"input\":{\"order_id\":\"O-1\"}}]");
        when(rs.getString("remote_task")).thenReturn(anchorOverride.length > 0
            ? anchorOverride[0]
            : "{\"service\":\"booking\",\"task_id\":\"t-123\",\"tool_calls\":[],\"child_reply_id\":null}");
        when(rs.getString("reply_id")).thenReturn(null);
        when(rs.getString("runtime_session_id")).thenReturn(null);
        when(rs.getString("runtime_user_id")).thenReturn(null);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
        return rs;
    }
}
