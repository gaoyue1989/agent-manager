package io.agentmanager.framework.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.LLMLogger;
import io.agentmanager.framework.service.LogCollector;

/**
 * 调试数据端点：为 /debug 调试页面提供配置、数据库、Thread、记忆、工作区、日志等信息。
 */
@RestController
@RequestMapping("/debug")
public class DebugApiController {
    private static final Logger log = LoggerFactory.getLogger(DebugApiController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentManagerProperties props;
    private final OafConfig oafConfig;
    private final DataSource dataSource;
    private final LLMLogger llmLogger;
    private final LogCollector logCollector;
    private final SandboxConfig sandboxConfig;

    public DebugApiController(
        AgentManagerProperties props,
        OafConfig oafConfig,
        DataSource dataSource,
        LLMLogger llmLogger,
        LogCollector logCollector,
        SandboxConfig sandboxConfig
    ) {
        this.props = props;
        this.oafConfig = oafConfig;
        this.dataSource = dataSource;
        this.llmLogger = llmLogger;
        this.logCollector = logCollector;
        this.sandboxConfig = sandboxConfig;
    }

    /** 环境变量配置（敏感信息脱敏） */
    @GetMapping("/config/env")
    public Map<String, Object> envConfig() {
        var llm = props.llm();
        var server = props.server();
        var cp = props.checkpoint();
        return Map.of(
            "llm", Map.of(
                "api_key", maskSecret(llm.apiKey()),
                "model_id", llm.modelId(),
                "base_url", llm.baseUrl(),
                "provider", llm.provider(),
                "temperature", llm.temperature(),
                "max_tokens", llm.maxTokens(),
                "timeout", llm.timeout()
            ),
            "server", Map.of("host", server.host(), "port", server.port()),
            "checkpoint", Map.of(
                "jdbc_url", cp.jdbcUrl(),
                "username", cp.username(),
                "password", maskSecret(cp.password())
            ),
            "config_dir", props.configDir()
        );
    }

    /** OAF 配置（AGENTS.md frontmatter） */
    @GetMapping("/config/oaf")
    public Map<String, Object> oafConfig() {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", oafConfig.name());
        result.put("vendorKey", oafConfig.vendorKey());
        result.put("agentKey", oafConfig.agentKey());
        result.put("version", oafConfig.version());
        result.put("slug", oafConfig.slug());
        result.put("description", oafConfig.description());
        result.put("author", oafConfig.author());
        result.put("license", oafConfig.license());
        result.put("tags", oafConfig.tags());
        result.put("tools", oafConfig.tools());
        result.put("deniedTools", oafConfig.deniedTools());
        result.put("skills", oafConfig.skills().stream().map(s -> Map.<String, Object>of(
            "name", s.name(), "source", s.source(), "version", s.version(),
            "required", s.required(),
            "description", s.description() != null ? s.description() : "")).toList());
        result.put("mcpServers", oafConfig.mcpServers().stream().map(m -> Map.<String, Object>of(
            "vendor", m.vendor(), "server", m.server(), "version", m.version(),
            "required", m.required())).toList());
        result.put("subAgents", oafConfig.subAgents().stream().map(a -> Map.<String, Object>of(
            "agent", a.agent(), "role", a.role(), "required", a.required(),
            "delegations", a.delegations())).toList());
        result.put("model", oafConfig.model() == null ? null : Map.of(
            "provider", oafConfig.model().provider(), "name", oafConfig.model().name()));
        result.put("runtimeConfig", oafConfig.runtimeConfig() == null ? null : Map.of(
            "temperature", oafConfig.runtimeConfig().temperature(),
            "maxTokens", oafConfig.runtimeConfig().maxTokens(),
            "requireConfirmation", oafConfig.runtimeConfig().requireConfirmation()));
        return result;
    }

    /** 数据库连接状态 + 表统计 + 连接池 */
    @GetMapping("/database/status")
    public Map<String, Object> databaseStatus() {
        try (var conn = dataSource.getConnection()) {
            var meta = conn.getMetaData();
            return Map.of(
                "connected", true,
                "database", meta.getDatabaseProductName(),
                "url", maskUrl(meta.getURL()),
                "tables", tableStats(conn),
                "connection_pool", poolStats()
            );
        } catch (Exception e) {
            return Map.of("connected", false, "error", e.getMessage());
        }
    }

    /** Thread 列表：agent_state 表 session_id 去重（真实会话来源） */
    @GetMapping("/threads")
    public List<Map<String, Object>> threads() {
        var result = new ArrayList<Map<String, Object>>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                 "SELECT session_id, MAX(updated_at) AS updated_at FROM agent_state GROUP BY session_id ORDER BY updated_at DESC")) {
            while (rs.next()) {
                var sid = rs.getString("session_id");
                result.add(Map.of(
                    "session_id", sid,
                    "thread_id", extractThreadId(sid),
                    "updated_at", rs.getTimestamp("updated_at") != null
                        ? rs.getTimestamp("updated_at").toString() : ""
                ));
            }
        } catch (Exception e) {
            log.warn("List threads failed: {}", e.getMessage());
        }
        return result;
    }

    /** Thread 历史消息：尽力从 agent_state.state_data 解析 */
    @GetMapping("/threads/{sessionId}/history")
    public Map<String, Object> threadHistory(@PathVariable String sessionId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT state_data FROM agent_state WHERE session_id = ? "
                     + "OR session_id LIKE CONCAT('%:', ?) ORDER BY item_index DESC LIMIT 1")) {
            stmt.setString(1, sessionId);
            // 兼容 agent_state 行 session_id 带 userId 前缀的格式（如 debug-user:debug-user:msq91wz3）
            stmt.setString(2, sessionId);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                return Map.of("session_id", sessionId, "messages", List.of());
            }
            var stateData = rs.getString("state_data");
            return Map.of("session_id", sessionId, "messages", extractMessages(stateData));
        } catch (Exception e) {
            return Map.of("session_id", sessionId, "messages", List.of(), "error", e.getMessage());
        }
    }

    /** LLM 调用记录（LLMLogger） */
    @GetMapping("/threads/{sessionId}/llm-calls")
    public Map<String, Object> llmCalls(@PathVariable String sessionId) {
        var calls = llmLogger.getCalls(sessionId).stream().map(c -> Map.<String, Object>of(
            "call_id", c.callId(),
            "timestamp", c.timestamp(),
            "request", c.request(),
            "response", c.response()
        )).toList();
        return Map.of("session_id", sessionId, "calls", calls);
    }

    /** 记忆内容：从 agent_fs 读取 MEMORY.md + memory/ 文件，按用户分组 */
    @GetMapping("/memory")
    public Map<String, Object> memory() {
        var users = new LinkedHashMap<String, Map<String, Object>>();
        try (var conn = dataSource.getConnection()) {
            // agent_fs 结构: namespace_path(0x1F 分段) + item_key(文件路径) + value_json({content,...})
            // 兼容两种 key 格式：
            //   框架 RemoteFilesystem 写入：item_key 带前导 "/"（如 /MEMORY.md），namespace 含 memory 段
            //   沙箱回写（WorkspaceSyncService）：item_key 无前导 "/"（如 MEMORY.md），namespace 为顶层 userId
            try (var stmt = conn.prepareStatement(
                "SELECT namespace_path, item_key, value_json, updated_at FROM agent_fs "
                    + "WHERE item_key = '/MEMORY.md' OR item_key = 'MEMORY.md' "
                    + "OR item_key LIKE 'memory/%' OR item_key LIKE '/memory/%' "
                    + "ORDER BY namespace_path, item_key")) {
                var rs = stmt.executeQuery();
                while (rs.next()) {
                    var ns = rs.getString("namespace_path");
                    var itemKey = rs.getString("item_key");
                    var valueJson = rs.getString("value_json");
                    var updated = rs.getLong("updated_at");
                    var userKey = extractUserFromNamespace(ns);
                    var user = users.computeIfAbsent(userKey, k -> {
                        var m = new LinkedHashMap<String, Object>();
                        m.put("namespace", ns);
                        m.put("files", new ArrayList<Map<String, Object>>());
                        return m;
                    });
                    // 提取文件内容（value_json 中的 content 字段）
                    var content = extractJsonContent(valueJson);
                    var file = new LinkedHashMap<String, Object>();
                    file.put("path", itemKey);
                    file.put("size", content != null ? content.length() : 0);
                    file.put("content", content != null ? content : "");
                    file.put("updated_at", updated > 0
                        ? new java.sql.Timestamp(updated).toString() : "");
                    @SuppressWarnings("unchecked")
                    var files = (List<Map<String, Object>>) user.get("files");
                    files.add(file);
                    if ("/MEMORY.md".equals(itemKey)) {
                        user.put("memory_md", content != null ? content : "");
                    }
                }
            }
            return Map.of("users", users);
        } catch (Exception e) {
            log.warn("Read memory failed: {}", e.getMessage());
            return Map.of("users", users, "error", e.getMessage());
        }
    }

    /**
     * 从 namespace_path 提取用户标识：
     * - 框架格式：segments 中 "users" 后的第一段（如 agents/Debug Test Agent/users/debug-user/root）
     * - 沙箱回写格式：顶层 userId（namespace = List.of(userId)，JdbcStore 编码为 join + 尾随 0x1F，
     *   如 "debug-user\u001F"）→ 取第一个非空段，避免 0x1F 控制字符泄漏到页面
     */
    private String extractUserFromNamespace(String ns) {
        if (ns == null) return "unknown";
        var segments = ns.split("\u001F");
        for (var i = 0; i < segments.length - 1; i++) {
            if ("users".equals(segments[i])) {
                return segments[i + 1];
            }
        }
        for (var seg : segments) {
            if (seg != null && !seg.isBlank()) {
                return seg;
            }
        }
        return "unknown";
    }

    /** 解析 value_json 中的 content 字段（可能为 null） */
    private String extractJsonContent(String valueJson) {
        if (valueJson == null || valueJson.isBlank()) return null;
        try {
            return MAPPER.readTree(valueJson).path("content").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 沙箱配置（沙箱模式时展示，便于排查） */
    @GetMapping("/sandbox")
    public Map<String, Object> sandbox() {
        var m = new LinkedHashMap<String, Object>();
        m.put("enabled", sandboxConfig.enabled());
        m.put("image", sandboxConfig.image());
        m.put("timeout_minutes", sandboxConfig.timeoutMinutes());
        m.put("memory_mb", sandboxConfig.memoryMb());
        m.put("cpu_count", sandboxConfig.cpuCount());
        m.put("server_url", sandboxConfig.opensandbox().serverUrl());
        m.put("api_key_configured", sandboxConfig.opensandbox().apiKey() != null
            && !sandboxConfig.opensandbox().apiKey().isBlank());
        return m;
    }

    /**
     * 工作区文件列表（本地 .agentscope/workspace 目录）。
     * 沙箱模式下该目录为静态模板层（投影源），运行时文件（MEMORY.md/memory/）在 KV 中。
     */
    @GetMapping("/workspace")
    public Map<String, Object> workspace() {
        var base = Path.of(props.configDir()).resolve(".agentscope").resolve("workspace");
        if (!Files.exists(base)) {
            return Map.of("exists", false, "path", base.toString(), "files", List.of(),
                "sandbox_mode", sandboxConfig.enabled());
        }
        return Map.of("exists", true, "path", base.toString(), "files", listWorkspaceFiles(base, base),
            "sandbox_mode", sandboxConfig.enabled());
    }

    /** 系统日志（内存 Appender，最近 500 条） */
    @GetMapping("/logs")
    public Map<String, Object> logs(
        @RequestParam(defaultValue = "all") String level,
        @RequestParam(defaultValue = "100") int limit
    ) {
        var max = Math.min(Math.max(limit, 1), 500);
        var logs = logCollector.getLogs(level, max);
        return Map.of("logs", logs, "level", level, "total", logs.size());
    }

    // ---------- 内部工具方法 ----------

    private Map<String, Object> tableStats(Connection conn) {
        var result = new LinkedHashMap<String, Object>();
        for (var table : List.of("agent_state", "agent_fs")) {
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                result.put(table, Map.of("rows", rs.getLong(1)));
            } catch (Exception e) {
                result.put(table, Map.of("error", e.getMessage()));
            }
        }
        return result;
    }

    private Map<String, Object> poolStats() {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            return Map.of("type", dataSource.getClass().getSimpleName());
        }
        try {
            var mx = hikari.getHikariPoolMXBean();
            return Map.of(
                "active", mx == null ? -1 : mx.getActiveConnections(),
                "idle", mx == null ? -1 : mx.getIdleConnections(),
                "total", mx == null ? -1 : mx.getTotalConnections(),
                "max", hikari.getMaximumPoolSize()
            );
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    /** session_id 格式: "{slug}:{threadId}"，取最后一个冒号后的部分作为展示 id */
    private String extractThreadId(String sessionId) {
        var idx = sessionId.lastIndexOf(':');
        if (idx >= 0 && idx < sessionId.length() - 1) {
            return sessionId.substring(idx + 1);
        }
        return sessionId;
    }

    /** BFS 查找 state_data JSON 中的 context/messages 数组，尽力提取 {role, content} */
    private List<Map<String, Object>> extractMessages(String stateData) {
        try {
            var messages = io.agentmanager.framework.service.StateDataParser.findMessagesArray(stateData);
            return io.agentmanager.framework.service.StateDataParser.toRoleContentList(messages);
        } catch (Exception e) {
            log.warn("Extract messages failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> listWorkspaceFiles(Path root, Path current) {
        var files = new ArrayList<Map<String, Object>>();
        try (var stream = Files.walk(current)) {
            for (var p : stream.filter(Files::isRegularFile).toList()) {
                try {
                    files.add(Map.of(
                        "path", root.relativize(p).toString(),
                        "size", Files.size(p),
                        "modified", Files.getLastModifiedTime(p).toString()
                    ));
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            log.warn("Workspace scan failed: {}", e.getMessage());
        }
        return files;
    }

    private String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return "(未配置)";
        }
        if (secret.length() <= 8) {
            return "****";
        }
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }

    /** JDBC URL 脱敏：隐藏 URL 中可能携带的密码参数 */
    private String maskUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("(?i)(password=)[^&;]*", "$1***");
    }
}
