package io.agentmanager.framework.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.LLMLogger;
import io.agentmanager.framework.service.LogCollector;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DebugApiController.class)
class DebugApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentManagerProperties props;

    @MockBean
    private OafConfig oafConfig;

    @MockBean
    private DataSource dataSource;

    @MockBean
    private LLMLogger llmLogger;

    @MockBean
    private LogCollector logCollector;

    private AgentManagerProperties.LLMConfig llmConfig(String key) {
        return new AgentManagerProperties.LLMConfig(
            key, "gpt-4", "http://localhost/v1", "openai", 0.7, 4096, 120);
    }

    // ---------- env config ----------

    @Test
    void envConfigShouldMaskSensitiveValues() throws Exception {
        when(props.llm()).thenReturn(llmConfig("sk-0123456789abcdefghijkl"));
        when(props.server()).thenReturn(new AgentManagerProperties.ServerConfig("0.0.0.0", 8100));
        when(props.checkpoint()).thenReturn(new AgentManagerProperties.CheckpointConfig(
            "jdbc:mysql://127.0.0.1:3307/agent_manager_test", "agent_manager", "Agent@Manager2026", "agent_manager_test"));
        when(props.configDir()).thenReturn("/config");

        mockMvc.perform(get("/debug/config/env"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.llm.api_key").value("sk-0****ijkl"))
            .andExpect(jsonPath("$.llm.model_id").value("gpt-4"))
            .andExpect(jsonPath("$.checkpoint.password").value("Agen****2026"))
            .andExpect(jsonPath("$.config_dir").value("/config"));
    }

    @Test
    void envConfigShouldMaskShortOrBlankSecret() throws Exception {
        when(props.llm()).thenReturn(llmConfig(""));
        var cp = new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost/db", "u", "short", "db");
        when(props.server()).thenReturn(new AgentManagerProperties.ServerConfig("0.0.0.0", 8100));
        when(props.checkpoint()).thenReturn(cp);
        when(props.configDir()).thenReturn("/config");

        mockMvc.perform(get("/debug/config/env"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.llm.api_key").value("(未配置)"))
            .andExpect(jsonPath("$.checkpoint.password").value("****"));
    }

    // ---------- oaf config ----------

    @Test
    void oafConfigShouldReturnAllFields() throws Exception {
        var skills = List.of(new OafConfig.SkillConfig(
            "bash", "local", "1.0.0", true, "Bash skill", List.of("Bash"),
            "", "", java.util.Map.of()));
        var mcp = new OafConfig.McpServerConfig("weather", "weather-service", "1.0.0", "mcp-configs/weather", true);
        var sub = new OafConfig.SubAgentConfig("acme", "helper", "1.0.0", "helper role", List.of("task"), true, "");
        var model = new OafConfig.ModelConfig("openai", "gpt-4", "");
        var runtime = new OafConfig.RuntimeConfig(0.3, 2048, true);

        when(oafConfig.name()).thenReturn("Test");
        when(oafConfig.vendorKey()).thenReturn("acme");
        when(oafConfig.agentKey()).thenReturn("test");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.slug()).thenReturn("acme/test");
        when(oafConfig.description()).thenReturn("desc");
        when(oafConfig.author()).thenReturn("author");
        when(oafConfig.license()).thenReturn("MIT");
        when(oafConfig.tags()).thenReturn(List.of("a", "b"));
        when(oafConfig.tools()).thenReturn(List.of("Read"));
        when(oafConfig.deniedTools()).thenReturn(List.of("Write"));
        when(oafConfig.skills()).thenReturn(skills);
        when(oafConfig.mcpServers()).thenReturn(List.of(sub == null ? null : mcp));
        when(oafConfig.subAgents()).thenReturn(List.of(sub));
        when(oafConfig.model()).thenReturn(model);
        when(oafConfig.runtimeConfig()).thenReturn(runtime);

        mockMvc.perform(get("/debug/config/oaf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Test"))
            .andExpect(jsonPath("$.skills[0].name").value("bash"))
            .andExpect(jsonPath("$.mcpServers[0].server").value("weather-service"))
            .andExpect(jsonPath("$.subAgents[0].agent").value("helper"))
            .andExpect(jsonPath("$.model.provider").value("openai"))
            .andExpect(jsonPath("$.runtimeConfig.requireConfirmation").value(true));
    }

    @Test
    void oafConfigShouldHandleNullModelAndRuntime() throws Exception {
        when(oafConfig.name()).thenReturn("n");
        when(oafConfig.vendorKey()).thenReturn("v");
        when(oafConfig.agentKey()).thenReturn("a");
        when(oafConfig.version()).thenReturn("1");
        when(oafConfig.slug()).thenReturn("v/a");
        when(oafConfig.description()).thenReturn("");
        when(oafConfig.author()).thenReturn("");
        when(oafConfig.license()).thenReturn("");
        when(oafConfig.tags()).thenReturn(List.of());
        when(oafConfig.tools()).thenReturn(List.of());
        when(oafConfig.deniedTools()).thenReturn(List.of());
        when(oafConfig.skills()).thenReturn(List.of());
        when(oafConfig.mcpServers()).thenReturn(List.of());
        when(oafConfig.subAgents()).thenReturn(List.of());
        when(oafConfig.model()).thenReturn(null);
        when(oafConfig.runtimeConfig()).thenReturn(null);

        mockMvc.perform(get("/debug/config/oaf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").isEmpty())
            .andExpect(jsonPath("$.runtimeConfig").isEmpty());
    }

    // ---------- database status ----------

    @Test
    void databaseStatusShouldShowConnectedState() throws Exception {
        var conn = mock(Connection.class);
        var meta = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn("MySQL");
        when(meta.getURL()).thenReturn("jdbc:mysql://127.0.0.1:3307/db");

        var stmt = mock(Statement.class);
        var rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false, true, false);
        when(rs.getLong(1)).thenReturn(5L);

        mockMvc.perform(get("/debug/database/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.database").value("MySQL"))
            .andExpect(jsonPath("$.tables.agent_state.rows").value(5))
            .andExpect(jsonPath("$.tables.agent_fs.rows").value(5));
    }

    @Test
    void databaseStatusShouldReportConnectionError() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("conn refused"));

        mockMvc.perform(get("/debug/database/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(false))
            .andExpect(jsonPath("$.error").value("conn refused"));
    }

    // ---------- threads ----------

    @Test
    void threadsShouldReturnDeduplicatedSessions() throws Exception {
        var conn = mock(Connection.class);
        var stmt = mock(Statement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("session_id")).thenReturn("acme-test-agent:t1");
        when(rs.getTimestamp("updated_at")).thenReturn(new Timestamp(1000));

        mockMvc.perform(get("/debug/threads"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].thread_id").value("t1"))
            .andExpect(jsonPath("$[0].session_id").value("acme-test-agent:t1"))
            .andExpect(jsonPath("$[0].updated_at").isNotEmpty());
    }

    @Test
    void threadsShouldReturnEmptyOnError() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/debug/threads"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$").isEmpty());
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

        mockMvc.perform(get("/debug/threads/s1/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages[0].role").value("user"))
            .andExpect(jsonPath("$.messages[0].content").value("hello"))
            .andExpect(jsonPath("$.messages[1].content").value("hi"));
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

        mockMvc.perform(get("/debug/threads/s2/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages[0].content").value("[{\"text\":\"p1\"}]"));
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

        mockMvc.perform(get("/debug/threads/s5/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages[0].role").value("user"))
            .andExpect(jsonPath("$.messages[0].content").value("hello"))
            .andExpect(jsonPath("$.messages[1].role").value("assistant"))
            .andExpect(jsonPath("$.messages[1].content").value("hi"));
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

        mockMvc.perform(get("/debug/threads/s6/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages").isEmpty());
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

        mockMvc.perform(get("/debug/threads/s3/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages").isEmpty());
    }

    @Test
    void threadHistoryShouldReturnErrorOnException() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(get("/debug/threads/s4/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages").isEmpty())
            .andExpect(jsonPath("$.error").value("db down"));
    }

    // ---------- llm calls ----------

    @Test
    void llmCallsShouldReturnRecords() throws Exception {
        when(llmLogger.getCalls("s1")).thenReturn(List.of(
            new LLMLogger.CallRecord("c1", 1000L, Map.of("model", "gpt-4"), Map.of("content", "ok"))));

        mockMvc.perform(get("/debug/threads/s1/llm-calls"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.calls[0].call_id").value("c1"))
            .andExpect(jsonPath("$.calls[0].request.model").value("gpt-4"));
    }

    @Test
    void llmCallsShouldReturnEmptyForUnknownThread() throws Exception {
        when(llmLogger.getCalls("unknown")).thenReturn(List.of());

        mockMvc.perform(get("/debug/threads/unknown/llm-calls"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.calls").isEmpty());
    }

    // ---------- memory ----------

    @Test
    void memoryShouldGroupByUser() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("item_key")).thenReturn("/MEMORY.md", "/memory/note.txt");
        when(rs.getString("namespace_path")).thenReturn("u\u001Fusers\u001Falice\u001Fagents\u001Fxxx", null);
        when(rs.getString("value_json")).thenReturn("{\"content\":\"hello memory\"}", "{\"content\":\"note\"}");
        when(rs.getLong("updated_at")).thenReturn(1000L, 2000L);

        mockMvc.perform(get("/debug/memory"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.users.alice.files[0].path").value("/MEMORY.md"))
            .andExpect(jsonPath("$.users.alice.memory_md").value("hello memory"));
    }

    @Test
    void memoryShouldHandleNullNamespace() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("namespace_path")).thenReturn(null);

        mockMvc.perform(get("/debug/memory"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.users.unknown").exists());
    }

    @Test
    void memoryShouldHandleInvalidJson() throws Exception {
        var conn = mock(Connection.class);
        var ps = mock(PreparedStatement.class);
        var rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("namespace_path")).thenReturn("u\u001Fusers\u001Falice\u001F0\u001F1");
        when(rs.getString("value_json")).thenReturn("not-json");

        mockMvc.perform(get("/debug/memory"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.users.alice.files[0].size").value(0));
    }

    @Test
    void memoryShouldReturnErrorOnFailure() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("store down"));

        mockMvc.perform(get("/debug/memory"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.users").isMap())
            .andExpect(jsonPath("$.error").value("store down"));
    }

    // ---------- workspace ----------

    @Test
    void workspaceShouldReportMissing() throws Exception {
        when(props.configDir()).thenReturn("/nonexistent/config");

        mockMvc.perform(get("/debug/workspace"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    void workspaceShouldListFiles(@TempDir Path configDir) throws Exception {
        when(props.configDir()).thenReturn(configDir.toAbsolutePath().toString());
        var ws = configDir.resolve(".agentscope/workspace");
        java.nio.file.Files.createDirectories(ws);
        java.nio.file.Files.writeString(ws.resolve("AGENTS.md"), "hi");

        mockMvc.perform(get("/debug/workspace"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exists").value(true))
            .andExpect(jsonPath("$.files[0].path").value("AGENTS.md"));
    }

    // ---------- logs ----------

    @Test
    void logsShouldDelegateAndDefaultLimits() throws Exception {
        when(logCollector.getLogs("all", 100)).thenReturn(List.of("line1"));

        mockMvc.perform(get("/debug/logs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logs[0]").value("line1"))
            .andExpect(jsonPath("$.level").value("all"));
    }

    @Test
    void logsShouldClampLimitWithinBounds() throws Exception {
        // limit 过滤: min(limit,1)=1, max=500
        when(logCollector.getLogs(argThat(l -> l == null || "all".equals(l)), anyInt())).thenReturn(List.of());
        mockMvc.perform(get("/debug/logs").param("limit", "1000"))
            .andExpect(status().isOk());

        org.mockito.Mockito.verify(logCollector).getLogs(anyString(), org.mockito.ArgumentMatchers.eq(500));
    }

    @Test
    void logsShouldFilterByLevel() throws Exception {
        when(logCollector.getLogs("error", 100)).thenReturn(List.of("err1"));
        mockMvc.perform(get("/debug/logs").param("level", "error"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logs[0]").value("err1"));
    }
}