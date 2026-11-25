package io.agentmanager.framework.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.UUID;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * issue #428/#505 真库回归：/debug/monitor/tools 最近明细的 END 行匹配必须限定同会话
 * （部分供应商 tool_call_id 只在单次响应内唯一，如 call_0 跨会话复用），且取 START
 * 之后最早的 END（同会话跨轮复用同 id 时不串扰、不翻倍）；#505 进一步要求 END 只在
 * 自身调用序列内匹配（e2.id &gt; s.id 且早于同会话同 id 的下一次 TOOL_CALL_START）——
 * 同毫秒复用 call_0 不吞前一轮终态、被中断调用不偷下一次调用的终态。跑法：
 * MONITOR_TOOLS_JOIN_IT=1 CHECKPOINT_JDBC_URL=... CHECKPOINT_USERNAME=... CHECKPOINT_PASSWORD=...
 * mvn -q test -Dtest=MonitorToolsJoinIT（目标库建议用专用空库，迁移由 TestSchemaMigrator 执行）。
 */
@EnabledIfEnvironmentVariable(named = "MONITOR_TOOLS_JOIN_IT", matches = "1")
class MonitorToolsJoinIT {

    @Test
    void monitorToolsRecentShouldScopeResultJoinBySession() throws Exception {
        var dataSource = mysqlDataSource();
        io.agentmanager.framework.support.TestSchemaMigrator.migrate(dataSource);
        var mvc = MockMvcBuilders.standaloneSetup(newController(dataSource)).build();

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
            var all = fetchRecent(mvc);
            // 共享 IT 库含存量审计行：只断言本用例注入的 join-it- 前缀会话行
            var mine = rowsOf(all, sidA, sidB, sidReuse);
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
            deleteSessions(dataSource, sidA, sidB, sidReuse);
        }
    }

    @Test
    void monitorToolsRecentShouldScopeResultJoinWithinOwnCallSequence() throws Exception {
        var dataSource = mysqlDataSource();
        io.agentmanager.framework.support.TestSchemaMigrator.migrate(dataSource);
        var mvc = MockMvcBuilders.standaloneSetup(newController(dataSource)).build();

        String suffix = UUID.randomUUID().toString();
        String sidMs = "join-it-ms-" + suffix;
        String sidInterrupt = "join-it-itr-" + suffix;
        long base = System.currentTimeMillis();
        Timestamp same = new Timestamp(base);
        // 同毫秒相邻复用 call_0：后一次 START 只允许配自身序列内的 END（e2.id > s.id）
        insertRow(dataSource, sidMs, "shell", "call_0", "TOOL_CALL_START", null, same);
        insertRow(dataSource, sidMs, "shell", "call_0", "TOOL_RESULT_END", "error", same);
        insertRow(dataSource, sidMs, "read_file", "call_0", "TOOL_CALL_START", null, same);
        insertRow(dataSource, sidMs, "read_file", "call_0", "TOOL_RESULT_END", "success", same);
        // 第 1 轮中断（START 无 END）、第 2 轮复用 call_0 成功：
        // 被中断调用不得越过下一次 TOOL_CALL_START 偷走第 2 轮的 success
        insertRow(dataSource, sidInterrupt, "shell", "call_0", "TOOL_CALL_START", null, new Timestamp(base + 1_000));
        insertRow(dataSource, sidInterrupt, "shell", "call_0", "TOOL_CALL_START", null, new Timestamp(base + 2_000));
        insertRow(dataSource, sidInterrupt, "shell", "call_0", "TOOL_RESULT_END", "success", new Timestamp(base + 3_000));
        try {
            var mine = rowsOf(fetchRecent(mvc), sidMs, sidInterrupt);
            org.junit.jupiter.api.Assertions.assertEquals(4, mine.size(), "4 个 START 恰好 4 行: " + mine);
            var readRow = mine.stream()
                .filter(r -> "read_file".equals(r.path("tool_name").asText())).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals("success", readRow.path("result_state").asText(),
                "同毫秒复用 call_0：read_file 必须配自身序列的 success，而非前一次调用的 error");
            var shellMsRow = mine.stream()
                .filter(r -> sidMs.equals(r.path("session_id").asText())
                    && "shell".equals(r.path("tool_name").asText())).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals("error", shellMsRow.path("result_state").asText());
            var ends = mine.stream()
                .filter(r -> sidInterrupt.equals(r.path("session_id").asText())
                    && !r.path("result_state").isNull()).toList();
            org.junit.jupiter.api.Assertions.assertEquals(1, ends.size(),
                "第 2 轮成功行唯一: " + mine);
            org.junit.jupiter.api.Assertions.assertEquals(new Timestamp(base + 2_000).toString(),
                ends.get(0).path("created_at").asText(), "success 必须归属第 2 轮 START");
            var endsForInterrupted = mine.stream()
                .filter(r -> sidInterrupt.equals(r.path("session_id").asText())
                    && r.path("result_state").isNull()).toList();
            org.junit.jupiter.api.Assertions.assertEquals(1, endsForInterrupted.size(),
                "被中断的第 1 轮必须保持无终态: " + mine);
            org.junit.jupiter.api.Assertions.assertEquals(new Timestamp(base + 1_000).toString(),
                endsForInterrupted.get(0).path("created_at").asText());
        } finally {
            deleteSessions(dataSource, sidMs, sidInterrupt);
        }
    }

    private MysqlDataSource mysqlDataSource() {
        var dataSource = new MysqlDataSource();
        dataSource.setURL(System.getenv("CHECKPOINT_JDBC_URL"));
        dataSource.setUser(System.getenv("CHECKPOINT_USERNAME"));
        dataSource.setPassword(System.getenv("CHECKPOINT_PASSWORD"));
        return dataSource;
    }

    private DebugApiController newController(MysqlDataSource dataSource) {
        return new DebugApiController(
            mock(io.agentmanager.framework.config.AgentManagerProperties.class),
            mock(io.agentmanager.framework.config.OafConfigHolder.class),
            dataSource,
            mock(io.agentmanager.framework.service.LogCollector.class),
            mock(io.agentmanager.framework.config.SandboxConfig.class),
            mock(io.agentmanager.framework.service.SandboxRuntime.class),
            mock(io.agentmanager.framework.service.SkillCatalogService.class),
            mock(io.agentmanager.framework.service.UserSkillService.class),
            mock(io.agentmanager.framework.service.ConfirmContextStore.class));
    }

    private com.fasterxml.jackson.databind.JsonNode fetchRecent(
        org.springframework.test.web.servlet.MockMvc mvc) throws Exception {
        var result = mvc.perform(get("/debug/monitor/tools").param("limit", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(true))
            .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(result.getResponse().getContentAsString())
            .path("recent");
    }

    private ArrayList<com.fasterxml.jackson.databind.JsonNode> rowsOf(
        com.fasterxml.jackson.databind.JsonNode recent, String... sessionIds) {
        var mine = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        recent.forEach(row -> {
            for (String sid : sessionIds) {
                if (sid.equals(row.path("session_id").asText())) {
                    mine.add(row);
                    break;
                }
            }
        });
        return mine;
    }

    /** 按观测中间件写入口径插单行审计（created_at 精确可控） */
    private void insertRow(MysqlDataSource dataSource, String sessionId, String toolName,
                           String toolCallId, String state, String resultState, Timestamp createdAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO tool_audit_log (session_id, tool_name, tool_call_id, state, result_state, created_at) "
                     + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, toolName);
            ps.setString(3, toolCallId);
            ps.setString(4, state);
            ps.setString(5, resultState);
            ps.setTimestamp(6, createdAt);
            ps.executeUpdate();
        }
    }

    /** 按观测中间件写入口径插一对 START/END 行（created_at 精确可控，END 晚 2 秒） */
    private void insertCall(MysqlDataSource dataSource, String sessionId, String toolName,
                            String toolCallId, Timestamp startAt, String resultState) throws Exception {
        insertRow(dataSource, sessionId, toolName, toolCallId, "TOOL_CALL_START", null, startAt);
        insertRow(dataSource, sessionId, toolName, toolCallId, "TOOL_RESULT_END", resultState,
            new Timestamp(startAt.getTime() + 2_000));
    }

    private void deleteSessions(MysqlDataSource dataSource, String... sessionIds) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM tool_audit_log WHERE session_id IN (?, ?, ?)")) {
            for (int i = 0; i < 3; i++) {
                ps.setString(i + 1, i < sessionIds.length ? sessionIds[i] : "");
            }
            ps.executeUpdate();
        }
    }
}
