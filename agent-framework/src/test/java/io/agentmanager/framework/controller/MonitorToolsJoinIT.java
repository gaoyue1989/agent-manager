package io.agentmanager.framework.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.UUID;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * issue #428 真库回归：/debug/monitor/tools 最近明细的 END 行匹配必须限定同会话
 * （部分供应商 tool_call_id 只在单次响应内唯一，如 call_0 跨会话复用），且取 START
 * 之后最早的 END（同会话跨轮复用同 id 时不串扰、不翻倍）。跑法：
 * MONITOR_TOOLS_JOIN_IT=1 CHECKPOINT_JDBC_URL=... CHECKPOINT_USERNAME=... CHECKPOINT_PASSWORD=...
 * mvn -q test -Dtest=MonitorToolsJoinIT（目标库建议用专用空库，迁移由 TestSchemaMigrator 执行）。
 */
@EnabledIfEnvironmentVariable(named = "MONITOR_TOOLS_JOIN_IT", matches = "1")
class MonitorToolsJoinIT {

    @Test
    void monitorToolsRecentShouldScopeResultJoinBySession() throws Exception {
        var dataSource = new MysqlDataSource();
        dataSource.setURL(System.getenv("CHECKPOINT_JDBC_URL"));
        dataSource.setUser(System.getenv("CHECKPOINT_USERNAME"));
        dataSource.setPassword(System.getenv("CHECKPOINT_PASSWORD"));
        io.agentmanager.framework.support.TestSchemaMigrator.migrate(dataSource);
        var controller = new DebugApiController(
            mock(io.agentmanager.framework.config.AgentManagerProperties.class),
            mock(io.agentmanager.framework.config.OafConfigHolder.class),
            dataSource,
            mock(io.agentmanager.framework.service.LogCollector.class),
            mock(io.agentmanager.framework.config.SandboxConfig.class),
            mock(io.agentmanager.framework.service.SandboxRuntime.class),
            mock(io.agentmanager.framework.service.SkillCatalogService.class),
            mock(io.agentmanager.framework.service.UserSkillService.class),
            mock(io.agentmanager.framework.service.ConfirmContextStore.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        String suffix = UUID.randomUUID().toString();
        String sidA = "join-it-a-" + suffix;
        String sidB = "join-it-b-" + suffix;
        String sidReuse = "join-it-reuse-" + suffix;
        long base = System.currentTimeMillis();
        // 跨会话复用短 id：sess-A / sess-B 各 1 次调用，tool_call_id 均为 call_0
        insertCall(dataSource, sidA, "shell", "call_0", new Timestamp(base - 10_000), "success");
        insertCall(dataSource, sidB, "read_file", "call_0", new Timestamp(base - 5_000), "error");
        // 同会话跨轮复用短 id：两轮均 call_0，第 1 轮 success、第 2 轮 error
        insertCall(dataSource, sidReuse, "shell", "call_0", new Timestamp(base - 4_000), "success");
        insertCall(dataSource, sidReuse, "shell", "call_0", new Timestamp(base - 1_000), "error");
        try {
            var result = mvc.perform(get("/debug/monitor/tools").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andReturn();
            // 共享 IT 库含存量审计行：只断言本用例注入的 join-it- 前缀会话行
            var all = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .path("recent");
            var mine = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            all.forEach(row -> {
                if (row.path("session_id").asText().startsWith("join-it-")) {
                    mine.add(row);
                }
            });
            org.junit.jupiter.api.Assertions.assertEquals(4, mine.size(),
                "4 个调用恰好 4 行（buggy 版仅按 tool_call_id JOIN，跨会话/跨轮复用会翻倍串扰）: " + mine);
            org.junit.jupiter.api.Assertions.assertEquals(sidReuse, mine.get(0).path("session_id").asText());
            org.junit.jupiter.api.Assertions.assertEquals("error", mine.get(0).path("result_state").asText());
            org.junit.jupiter.api.Assertions.assertEquals(sidReuse, mine.get(1).path("session_id").asText());
            org.junit.jupiter.api.Assertions.assertEquals("success", mine.get(1).path("result_state").asText(),
                "同会话跨轮复用同 id：第 1 轮必须配 START 之后最早的 END");
            org.junit.jupiter.api.Assertions.assertEquals(sidB, mine.get(2).path("session_id").asText());
            org.junit.jupiter.api.Assertions.assertEquals("error", mine.get(2).path("result_state").asText());
            org.junit.jupiter.api.Assertions.assertEquals(sidA, mine.get(3).path("session_id").asText());
            org.junit.jupiter.api.Assertions.assertEquals("success", mine.get(3).path("result_state").asText());
        } finally {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM tool_audit_log WHERE session_id IN (?, ?, ?)")) {
                ps.setString(1, sidA);
                ps.setString(2, sidB);
                ps.setString(3, sidReuse);
                ps.executeUpdate();
            }
        }
    }

    /** 按观测中间件写入口径插一对 START/END 行（created_at 精确可控，END 晚 2 秒） */
    private void insertCall(MysqlDataSource dataSource, String sessionId, String toolName,
                            String toolCallId, Timestamp startAt, String resultState) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tool_audit_log (session_id, tool_name, tool_call_id, state, result_state, created_at) "
                    + "VALUES (?, ?, ?, 'TOOL_CALL_START', NULL, ?)")) {
                ps.setString(1, sessionId);
                ps.setString(2, toolName);
                ps.setString(3, toolCallId);
                ps.setTimestamp(4, startAt);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tool_audit_log (session_id, tool_name, tool_call_id, state, result_state, created_at) "
                    + "VALUES (?, ?, ?, 'TOOL_RESULT_END', ?, ?)")) {
                ps.setString(1, sessionId);
                ps.setString(2, toolName);
                ps.setString(3, toolCallId);
                ps.setString(4, resultState);
                ps.setTimestamp(5, new Timestamp(startAt.getTime() + 2_000));
                ps.executeUpdate();
            }
        }
    }
}
