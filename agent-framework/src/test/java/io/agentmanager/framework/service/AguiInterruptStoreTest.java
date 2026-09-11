package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;

/**
 * AguiInterruptStore 单测（agui-migration-plan §5.3 / R10）：
 * 纯逻辑（覆盖率校验 / Map 组装）、load 重建完整性校验（缺 metadata 关键字段 fail-fast）、
 * consumed/过期过滤；真实库回环（persist→load→consume）按环境变量 AGUI_IT=1 门控
 * （对齐 S3FileStorageIT 门控模式，离线 CI 默认跳过）。
 */
class AguiInterruptStoreTest {

    private static AguiEvent.Interrupt interrupt(String id, String toolCallId, Map<String, Object> metadata) {
        return new AguiEvent.Interrupt(id, "tool_call", "confirm", toolCallId, Map.of(), null, metadata);
    }

    private static AguiEvent.Interrupt validInterrupt(String id) {
        return interrupt(id, "call-1", Map.of(
            AguiInterruptConstants.METADATA_TOOL_NAME, "present_file",
            AguiInterruptConstants.METADATA_TOOL_CONTENT, "{}",
            AguiInterruptConstants.METADATA_REPLY_ID, "reply-1"));
    }

    // ===== 纯逻辑 =====

    @Test
    void uncoveredShouldReportMissingInterruptIds() {
        var open = List.of(validInterrupt("a:1"), validInterrupt("a:2"));
        var resume = List.<AguiResume>of(new AguiResume("a:1", "resolved", Map.of()));
        assertEquals(Set.of("a:2"), new AguiInterruptStore(mockDS(), Duration10())
            .uncoveredInterruptIds(open, resume));
    }

    @Test
    void uncoveredShouldBeEmptyWhenFullyCovered() {
        var open = List.of(validInterrupt("a:1"));
        var resume = List.<AguiResume>of(
            new AguiResume("a:1", "resolved", Map.of()),
            new AguiResume("a:2", "cancelled", Map.of()));
        assertTrue(new AguiInterruptStore(mockDS(), Duration10())
            .uncoveredInterruptIds(open, resume).isEmpty());
    }

    @Test
    void toInterruptMapShouldKeyById() {
        var map = AguiInterruptStore.toInterruptMap(List.of(validInterrupt("a:1")));
        assertTrue(map.containsKey("a:1"));
    }

    // ===== load 完整性校验（R10 fail-fast）=====

    @Test
    void loadShouldFailFastWhenMetadataMissingToolName() {
        var corrupt = interrupt("a:1", "call-1", Map.of(
            AguiInterruptConstants.METADATA_TOOL_CONTENT, "{}"));
        var json = toJson(List.of(corrupt));
        var store = newStoreWithRow(json, 0, java.sql.Timestamp.from(java.time.Instant.now()));
        var e = assertThrows(AguiInterruptStore.AguiInterruptCorruptedException.class,
            () -> store.load("t1"));
        assertTrue(e.getMessage().contains("toolName"));
    }

    @Test
    void loadShouldReturnEmptyWhenConsumed() {
        var store = newStoreWithRow(toJson(List.of(validInterrupt("a:1"))), 1,
            java.sql.Timestamp.from(java.time.Instant.now()));
        assertTrue(store.load("t1").isEmpty());
    }

    @Test
    void loadShouldReturnEmptyWhenExpired() {
        var stale = java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofHours(1)));
        var store = newStoreWithRow(toJson(List.of(validInterrupt("a:1"))), 0, stale);
        assertTrue(store.load("t1").isEmpty());
    }

    @Test
    void loadShouldRebuildInterruptWithMetadata() {
        var store = newStoreWithRow(toJson(List.of(validInterrupt("a:1"))), 0,
            java.sql.Timestamp.from(java.time.Instant.now()));
        var loaded = store.load("t1");
        assertEquals(1, loaded.size());
        assertEquals("a:1", loaded.get(0).id());
        assertEquals("present_file",
            loaded.get(0).metadata().get(AguiInterruptConstants.METADATA_TOOL_NAME));
    }

    // ===== 真实库回环（AGUI_IT=1 门控；需本地 13307 端口转发）=====

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "AGUI_IT", matches = "1")
    void persistLoadConsumeRoundTripShouldWorkAgainstRealDb() {
        var store = new AguiInterruptStore(realDataSource(), java.time.Duration.ofMinutes(30));
        store.delete("agui-it-thread");
        try {
            store.persist("agui-it-thread", List.of(validInterrupt("a:1")), "run-it");
            var loaded = store.load("agui-it-thread");
            assertEquals(1, loaded.size());
            assertTrue(store.consume("agui-it-thread"));
            // CAS 后不可重复消费
            assertFalse(store.consume("agui-it-thread"));
            assertTrue(store.load("agui-it-thread").isEmpty());
        } finally {
            store.delete("agui-it-thread");
        }
    }

    // ===== 工具 =====

    private static java.time.Duration Duration10() {
        return java.time.Duration.ofMinutes(30);
    }

    private static DataSource mockDS() {
        return Mockito.mock(DataSource.class, Mockito.RETURNS_DEEP_STUBS);
    }

    private static String toJson(List<AguiEvent.Interrupt> interrupts) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(interrupts);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** mock JDBC：返回单行（interrupts_json/consumed/created_at） */
    private AguiInterruptStore newStoreWithRow(String json, int consumed, java.sql.Timestamp createdAt) {
        var ds = mock(DataSource.class, Mockito.RETURNS_DEEP_STUBS);
        try {
            ResultSet rs = ds.getConnection().prepareStatement(anyString()).executeQuery();
            when(rs.next()).thenReturn(true);
            when(rs.getString("interrupts_json")).thenReturn(json);
            when(rs.getInt("consumed")).thenReturn(consumed);
            when(rs.getTimestamp("created_at")).thenReturn(createdAt);
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
        return new AguiInterruptStore(ds, java.time.Duration.ofMinutes(30));
    }

    /** 真实库数据源（AGUI_IT 门控内使用）：JDBC URL 从环境变量注入 */
    private static DataSource realDataSource() {
        var url = System.getenv().getOrDefault("AGUI_IT_JDBC_URL",
            "jdbc:mysql://127.0.0.1:13307/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        var user = System.getenv().getOrDefault("AGUI_IT_JDBC_USER", "oaf");
        var password = System.getenv("AGUI_IT_JDBC_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("AGUI_IT_JDBC_PASSWORD required for AGUI_IT=1");
        }
        var ds = new com.zaxxer.hikari.HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setMaximumPoolSize(2);
        return ds;
    }
}
