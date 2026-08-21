package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent")
public record AgentManagerProperties(
    LLMConfig llm,
    ServerConfig server,
    CheckpointConfig checkpoint,
    @DefaultValue("/config") String configDir,
    CleanupConfig cleanup
) {
    public record LLMConfig(
        @DefaultValue("") String apiKey,
        @DefaultValue("") String modelId,
        @DefaultValue("") String baseUrl,
        @DefaultValue("openai") String provider,
        @DefaultValue("0.7") double temperature,
        @DefaultValue("4096") int maxTokens,
        @DefaultValue("120") int timeout
    ) {}

    public record ServerConfig(
        @DefaultValue("0.0.0.0") String host,
        @DefaultValue("8100") int port
    ) {}

    public record CheckpointConfig(
        @DefaultValue("jdbc:mysql://127.0.0.1:3307/agent_manager_test") String jdbcUrl,
        @DefaultValue("agent_manager") String username,
        @DefaultValue("Agent@Manager2026") String password,
        @DefaultValue("") String dbName
    ) {
        /**
         * 实际使用的数据库名：显式配置 CHECKPOINT_DB_NAME 时优先；
         * 未配置则从 CHECKPOINT_JDBC_URL 自动解析（去 query 参数，取最后一个 '/' 之后），
         * 保证 agent_state 与 agent_fs 始终落在同一数据库。
         */
        public String resolvedDbName() {
            if (dbName != null && !dbName.isBlank()) {
                return dbName;
            }
            var url = jdbcUrl;
            int q = url.indexOf('?');
            if (q != -1) {
                url = url.substring(0, q);
            }
            int scheme = url.indexOf("://");
            int slash = url.lastIndexOf('/');
            // 仅当 '/' 出现在协议之后 (host:port/db 结构) 才视为库名
            if (scheme != -1 && slash > scheme + 2 && slash < url.length() - 1) {
                return url.substring(slash + 1);
            }
            return "agent_manager_test";
        }
    }

    /**
     * 清理与租约配置（无状态单次流架构，见 stateless-single-stream-plan O2/O3）。
     * 环境变量前缀：AGENT_CLEANUP_*（如 AGENT_CLEANUP_CONFIRM_TTL_MINUTES）
     */
    public record CleanupConfig(
        /** confirm_context 有效时长（分钟），默认 30 */
        @DefaultValue("30") int confirmTtlMinutes,
        /** turn_lease 租约 TTL（秒），默认 60 */
        @DefaultValue("60") int turnLeaseTtlSeconds,
        /** turn 续租间隔（秒），默认 20 */
        @DefaultValue("20") int turnLeaseRenewSeconds,
        /** 工具审计日志保留天数，默认 30 */
        @DefaultValue("30") int auditRetentionDays,
        /** agent_state/agent_fs 会话记录保留天数，默认 7 */
        @DefaultValue("7") int sessionRetentionDays
    ) {}
}
