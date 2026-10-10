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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.SandboxRuntime;
import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.service.LogCollector;
import io.agentmanager.framework.service.SkillCatalogService;
import io.agentmanager.framework.service.UserSkillService;

/**
 * 调试数据端点：为 /debug 调试页面提供配置、数据库、Thread、记忆、工作区、日志等信息。
 */
@RestController
@RequestMapping("/debug")
public class DebugApiController {
    private static final Logger log = LoggerFactory.getLogger(DebugApiController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 允许 tableStats 查询的表名白名单（防 SQL 注入） */
    private static final java.util.Set<String> STATS_TABLES = java.util.Set.of("agent_state", "agent_fs", "file_asset");
    /** 运营监控：跨用户会话列表返回上限（防大库拖垮调试页） */
    private static final int MONITOR_SESSION_MAX_LIMIT = 500;
    /** 运营监控「只看异常」时的扫描上限：异常标记需在内存判定，避免无界扫描 */
    private static final int MONITOR_PROBLEM_SCAN_LIMIT = 2000;

    /**
     * 会话 ↔ agent_state 关联条件（与 {@code ThreadController.AGENT_STATE_JOIN} 同口径）：
     * 覆盖老 Channel 形态 {@code "{peer}:{key}"} 与规范形态 {@code "{userId}:{sid}"}，
     * 取冒号前后两段等值匹配。session_id 无冒号时 SUBSTRING_INDEX 返回原值，精确匹配同被覆盖。
     */
    private static final String AGENT_STATE_JOIN_MONITOR =
        "ON SUBSTRING_INDEX(a.session_id, ':', 1) = su.session_id "
            + "OR SUBSTRING_INDEX(a.session_id, ':', -1) = su.session_id ";

    /**
     * 工具失败判据：归档消息 JSON 中 {@code tool_result} 块的终态（success/error/interrupted/denied）。
     * 唯一键（session_id, msg_id）保证同一消息不重复归档，故命中行数即失败工具结果数。
     * 属启发式（工具输出若恰好内嵌同形子串会误判），运营监控可接受。
     */
    private static final String TOOL_ERROR_PREDICATE =
        "msg_data LIKE '%\"state\":\"error\"%' OR msg_data LIKE '%\"state\":\"denied\"%' "
            + "OR msg_data LIKE '%\"state\":\"interrupted\"%'";

    /**
     * 待确认会话：confirm_context 未消费且未过期（TTL 按 confirm_key 分档：本地 30min / 远程 24h，
     * 与 {@code ConfirmContextStore.findHeadPending} 同口径）。两个 {@code ?} 依次为 local / remote 秒数。
     */
    private static final String PENDING_CONFIRM_SQL =
        "SELECT DISTINCT session_id FROM confirm_context WHERE consumed = 0 AND ("
            + "(confirm_key = 'local' AND created_at > DATE_SUB(NOW(3), INTERVAL ? SECOND)) "
            + "OR (confirm_key <> 'local' AND created_at > DATE_SUB(NOW(3), INTERVAL ? SECOND)))";

    private final AgentManagerProperties props;
    private final OafConfigHolder oafConfigHolder;
    private final DataSource dataSource;
    private final LogCollector logCollector;
    private final SandboxConfig sandboxConfig;
    private final SandboxRuntime sandboxRuntime;
    private final SkillCatalogService skillCatalog;
    private final UserSkillService userSkillService;
    /** HITL 待确认上下文（运营监控判定「待确认会话」的 TTL 口径来源） */
    private final io.agentmanager.framework.service.ConfirmContextStore confirmContextStore;

    /** 备用模型 id（LLM_FALLBACK_MODEL_ID，引用 model_config 托管模型）；空 = 未启用 */
    @Value("${agent.llm.fallback-model-id:}")
    private String fallbackModelId;

    public DebugApiController(
        AgentManagerProperties props,
        OafConfigHolder oafConfigHolder,
        DataSource dataSource,
        LogCollector logCollector,
        SandboxConfig sandboxConfig,
        SandboxRuntime sandboxRuntime,
        SkillCatalogService skillCatalog,
        UserSkillService userSkillService,
        io.agentmanager.framework.service.ConfirmContextStore confirmContextStore
    ) {
        this.props = props;
        this.oafConfigHolder = oafConfigHolder;
        this.dataSource = dataSource;
        this.logCollector = logCollector;
        this.sandboxConfig = sandboxConfig;
        this.sandboxRuntime = sandboxRuntime;
        this.skillCatalog = skillCatalog;
        this.userSkillService = userSkillService;
        this.confirmContextStore = confirmContextStore;
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
                "timeout", llm.timeout(),
                "fallback_model_id", fallbackModelId == null ? "" : fallbackModelId
            ),
            "server", Map.of("host", server.host(), "port", server.port()),
            "checkpoint", Map.of(
                "jdbc_url", cp.jdbcUrl(),
                "username", cp.username(),
                "password", maskSecret(cp.password())
            ),
            "config_dir", props.resolvedConfigDir(),
            "storage_dir", props.file().resolvedStorageLocalDir()
        );
    }

    /** OAF 配置（AGENTS.md frontmatter） */
    @GetMapping("/config/oaf")
    public Map<String, Object> oafConfig() {
        var oafConfig = oafConfigHolder.get();
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
        // 动态技能目录（frontmatter 声明 ∪ 目录实际内容，冲突以目录为准）
        result.put("skills", skillCatalog.list());
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

    /**
     * 运营监控：系统使用概览（跨用户汇总），供调试页 Monitor 面板顶部指标卡展示。
     *
     * <p>会话/用户维度取自 {@code session_user}（会话首次发起即 upsert，与 agent_state 解耦）；
     * 消息维度取自 {@code session_message} 归档轨——user 计为「提问」、assistant 计为「回答」，
     * 归档开关关闭（AGENT_HISTORY_ARCHIVE_ENABLED=false）时消息类指标为 0（不报错）。
     * 另含异常会话指标：{@code sessions_with_error}（工具失败）、{@code sessions_pending_confirm}
     * （HITL 待确认）与两者并集 {@code sessions_with_problem}（判据见 {@link #loadMonitorProblems}）。
     */
    @GetMapping("/monitor/overview")
    public Map<String, Object> monitorOverview() {
        var result = new LinkedHashMap<String, Object>();
        try (var conn = dataSource.getConnection()) {
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery(
                     "SELECT COUNT(*) AS total_sessions, COUNT(DISTINCT user_id) AS total_users, "
                         + "SUM(created_at >= CURDATE()) AS sessions_today, "
                         + "SUM(updated_at >= CURDATE()) AS active_sessions_today "
                         + "FROM session_user")) {
                if (rs.next()) {
                    result.put("total_sessions", rs.getLong("total_sessions"));
                    result.put("total_users", rs.getLong("total_users"));
                    result.put("sessions_today", rs.getLong("sessions_today"));
                    result.put("active_sessions_today", rs.getLong("active_sessions_today"));
                }
            }
            long questions = 0;
            long answers = 0;
            long totalMessages = 0;
            long messagesToday = 0;
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery(
                     "SELECT role, COUNT(*) AS c, SUM(created_at >= CURDATE()) AS today "
                         + "FROM session_message GROUP BY role")) {
                while (rs.next()) {
                    var role = rs.getString("role");
                    var c = rs.getLong("c");
                    totalMessages += c;
                    messagesToday += rs.getLong("today");
                    if ("user".equals(role)) {
                        questions += c;
                    } else if ("assistant".equals(role)) {
                        answers += c;
                    }
                }
            }
            result.put("total_messages", totalMessages);
            result.put("total_questions", questions);
            result.put("total_answers", answers);
            result.put("messages_today", messagesToday);
            // 问题会话：工具失败（error/denied/interrupted）或 HITL 待确认
            var problems = loadMonitorProblems(conn);
            result.put("sessions_with_error", (long) problems.errorCounts().size());
            result.put("sessions_pending_confirm", (long) problems.pendingSessions().size());
            result.put("sessions_with_problem", (long) problems.problemSessionCount());
            result.put("connected", true);
            return result;
        } catch (Exception e) {
            log.warn("monitor overview failed: {}", e.getMessage());
            return Map.of("connected", false, "error", e.getMessage());
        }
    }

    /**
     * 运营监控：跨用户会话列表（可按 user / 标题关键词 / 时间范围 / 仅异常过滤）。
     *
     * <p>数据源与 {@code GET /threads} 一致——{@code session_user} 驱动，LEFT JOIN
     * {@code agent_state} 取活跃时间（双形态 slot 匹配见 {@link #AGENT_STATE_JOIN_MONITOR}）；
     * 额外按会话合并归档消息数（{@code message_count}）与问题标记（{@code error_count} /
     * {@code has_error} / {@code pending_confirm}，见 {@link #loadMonitorProblems}）。
     * 时间过滤作用于 {@code session_user.updated_at}（每次 chat/confirm 刷新），
     * 展示的 {@code updated_at} 取 agent_state 与 created_at 的较大者。
     *
     * <p>分页：{@code onlyProblem=true} 时先在内存按问题标记过滤（异常数通常远小于总量，
     * 扫描上限 {@value #MONITOR_PROBLEM_SCAN_LIMIT}），随后统一在内存分页，保证
     * {@code offset}/{@code limit}/{@code hasMore} 与过滤后的集合一致。
     *
     * @param onlyProblem 仅返回存在工具失败或 HITL 待确认的会话
     * @param limit       返回上限（默认 100，钳制在 [1, {@value #MONITOR_SESSION_MAX_LIMIT}]）
     * @param offset      偏移（分页）
     */
    @GetMapping("/monitor/sessions")
    public Map<String, Object> monitorSessions(
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "onlyProblem", required = false, defaultValue = "false") boolean onlyProblem,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset) {
        var cappedLimit = Math.min(Math.max(limit, 1), MONITOR_SESSION_MAX_LIMIT);
        var safeOffset = Math.max(offset, 0);
        var rows = new ArrayList<Map<String, Object>>();
        try (var conn = dataSource.getConnection()) {
            // 问题标记 + 每会话消息数（单次 GROUP BY 聚合，见 loadMonitorProblems）
            var problems = loadMonitorProblems(conn);

            var sql = new StringBuilder(
                "SELECT su.session_id, su.user_id, su.remark, su.model, su.created_at, "
                    + "MAX(a.updated_at) AS updated_at "
                    + "FROM session_user su LEFT JOIN agent_state a "
                    + AGENT_STATE_JOIN_MONITOR
                    + "WHERE 1=1 ");
            var params = new ArrayList<String>();
            if (userId != null && !userId.isBlank()) {
                sql.append("AND su.user_id = ? ");
                params.add(userId.trim());
            }
            if (keyword != null && !keyword.isBlank()) {
                sql.append("AND (su.remark LIKE ? OR su.session_id LIKE ?) ");
                var kw = "%" + keyword.trim() + "%";
                params.add(kw);
                params.add(kw);
            }
            if (from != null && !from.isBlank()) {
                sql.append("AND su.updated_at >= ? ");
                params.add(from.trim());
            }
            if (to != null && !to.isBlank()) {
                sql.append("AND su.updated_at <= ? ");
                params.add(to.trim());
            }
            sql.append("GROUP BY su.session_id, su.user_id, su.remark, su.model, su.created_at ");
            sql.append("ORDER BY COALESCE(MAX(a.updated_at), su.created_at) DESC ");
            sql.append("LIMIT ?");

            // 扫描窗口：仅异常需放宽到问题扫描上限后内存过滤；否则取到目标页 +1 即可
            int scanLimit = onlyProblem ? MONITOR_PROBLEM_SCAN_LIMIT : (safeOffset + cappedLimit + 1);

            try (var ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                for (var p : params) {
                    ps.setString(idx++, p);
                }
                ps.setInt(idx, scanLimit);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        var sid = rs.getString("session_id");
                        var updatedAt = rs.getTimestamp("updated_at");
                        var createdAt = rs.getTimestamp("created_at");
                        var errorCount = problems.errorCounts().getOrDefault(sid, 0L);
                        var pending = problems.pendingSessions().contains(sid);
                        var m = new LinkedHashMap<String, Object>();
                        m.put("session_id", sid);
                        m.put("user_id", rs.getString("user_id"));
                        var remark = rs.getString("remark");
                        m.put("title", remark != null ? remark : "");
                        var model = rs.getString("model");
                        m.put("model", model != null ? model : "");
                        m.put("created_at", createdAt != null ? createdAt.toString() : "");
                        m.put("updated_at", updatedAt != null ? updatedAt.toString()
                            : (createdAt != null ? createdAt.toString() : ""));
                        m.put("message_count", problems.messageCounts().getOrDefault(sid, 0L));
                        m.put("error_count", errorCount);
                        m.put("has_error", errorCount > 0);
                        m.put("pending_confirm", pending);
                        rows.add(m);
                    }
                }
            }

            if (onlyProblem) {
                rows.removeIf(r -> !((Boolean) r.get("has_error") || (Boolean) r.get("pending_confirm")));
            }
            int total = rows.size();
            int fromIdx = Math.min(safeOffset, total);
            int toIdx = Math.min(fromIdx + cappedLimit, total);
            var page = new ArrayList<>(rows.subList(fromIdx, toIdx));
            boolean hasMore = toIdx < total;

            var body = new LinkedHashMap<String, Object>();
            body.put("sessions", page);
            body.put("count", page.size());
            body.put("total", total);
            body.put("hasMore", hasMore);
            body.put("limit", cappedLimit);
            body.put("offset", safeOffset);
            body.put("onlyProblem", onlyProblem);
            return body;
        } catch (Exception e) {
            log.warn("monitor sessions failed: {}", e.getMessage());
            return Map.of("sessions", rows, "count", rows.size(), "hasMore", false, "error", e.getMessage());
        }
    }

    /** 运营监控问题标记：每会话消息/工具失败计数 + 待确认会话集合 */
    private record MonitorProblems(java.util.Map<String, Long> messageCounts,
                                   java.util.Map<String, Long> errorCounts,
                                   java.util.Set<String> pendingSessions) {
        /** 并集会话数：出现工具失败或有 HITL 待确认的会话总数 */
        int problemSessionCount() {
            var union = new java.util.HashSet<>(errorCounts.keySet());
            union.addAll(pendingSessions);
            return union.size();
        }
    }

    /**
     * 加载问题标记（两次查询，均为单遍扫描，避免逐行子查询）：
     * <ul>
     *   <li>会话消息聚合：一次 {@code GROUP BY} 同时得出每会话归档消息总数（{@code message_count}）
     *       与工具失败数（{@code error_count}，判据见 {@link #TOOL_ERROR_PREDICATE}）</li>
     *   <li>HITL 待确认：{@code confirm_context} 未消费且未过期（{@link #PENDING_CONFIRM_SQL}），
     *       TTL 口径取自 {@link io.agentmanager.framework.service.ConfirmContextStore}</li>
     * </ul>
     */
    private MonitorProblems loadMonitorProblems(Connection conn) throws java.sql.SQLException {
        var messageCounts = new java.util.HashMap<String, Long>();
        var errorCounts = new java.util.HashMap<String, Long>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                 "SELECT SUBSTRING_INDEX(session_id, ':', -1) AS sid, COUNT(*) AS total, "
                     + "SUM(CASE WHEN (" + TOOL_ERROR_PREDICATE + ") THEN 1 ELSE 0 END) AS errors "
                     + "FROM session_message GROUP BY sid")) {
            while (rs.next()) {
                var sid = rs.getString("sid");
                messageCounts.put(sid, rs.getLong("total"));
                var errors = rs.getLong("errors");
                if (errors > 0) {
                    errorCounts.put(sid, errors);
                }
            }
        }
        var pending = new java.util.HashSet<String>();
        try (var ps = conn.prepareStatement(PENDING_CONFIRM_SQL)) {
            ps.setLong(1, confirmContextStore.ttl().toSeconds());
            ps.setLong(2, confirmContextStore.remoteTtl().toSeconds());
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    pending.add(rs.getString("session_id"));
                }
            }
        }
        return new MonitorProblems(messageCounts, errorCounts, pending);
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
     * 存在个人技能覆盖（L4，{@code agents/{agent}/users/{uid}/skills}）的用户索引，
     * 供调试页“用户技能”面板下拉/清单使用。明细读写见 {@code /skills/users/{userId}}。
     *
     * <p>索引触顶截断时带 {@code truncated=true}（不静默返回子集）；
     * 查询失败（DB/SQL 不可用）→ 500：不把「索引查不到」降级成 200 + 空列表，
     * 否则调试页会在 DB 抖动时静默显示「0 user(s)」（与 {@code /debug/memory} 的
     * {@code error} 字段口径不同：那里返回的是单次读取结果，这里是“有没有用户”的存在性判定）。
     */
    @GetMapping("/user-skills")
    public ResponseEntity<Map<String, Object>> userSkills() {
        UserSkillService.UserSkillIndex index;
        try {
            index = userSkillService.listUsers();
        } catch (Exception e) {
            log.warn("Debug user skill index failed: {}", e.getMessage());
            var error = new LinkedHashMap<String, Object>();
            error.put("error", "index_failed");
            error.put("message", "用户技能索引读取失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("count", index.users().size());
        body.put("users", index.users());
        if (index.truncated()) {
            body.put("truncated", true);
        }
        return ResponseEntity.ok(body);
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
        m.put("enabled", sandboxRuntime.enabled());
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
        var base = Path.of(props.resolvedConfigDir()).resolve(".agentscope").resolve("workspace");
        if (!Files.exists(base)) {
            return Map.of("exists", false, "path", base.toString(), "files", List.of(),
                "sandbox_mode", sandboxRuntime.enabled());
        }
        return Map.of("exists", true, "path", base.toString(), "files", listWorkspaceFiles(base, base),
            "sandbox_mode", sandboxRuntime.enabled());
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
        for (var table : List.of("agent_state", "agent_fs", "file_asset")) {
            if (!STATS_TABLES.contains(table)) {
                throw new IllegalArgumentException("Table not in stats whitelist: " + table);
            }
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
                } catch (IOException e) {
                    log.debug("Skipping file during workspace scan: {}", e.getMessage());
                }
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
