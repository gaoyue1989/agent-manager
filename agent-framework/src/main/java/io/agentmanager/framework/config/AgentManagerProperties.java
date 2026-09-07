package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent")
public record AgentManagerProperties(
    LLMConfig llm,
    ServerConfig server,
    CheckpointConfig checkpoint,
    @DefaultValue("/config") String configDir,
    @DefaultValue("") String workspaceDir,
    CleanupConfig cleanup,
    FileConfig file
) {

    /**
     * 工作区基目录：AGENT_WORKSPACE_DIR 优先；未配置时回落到 configDir。
     * 平台部署场景下 configDir 为只读的 OAF 包挂载点，工作区须落在独立可写卷。
     */
    public String resolvedWorkspaceBaseDir() {
        return (workspaceDir == null || workspaceDir.isBlank()) ? configDir : workspaceDir;
    }

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

    /**
     * 文件上传/下载配置（file-upload-download-plan 设计文档）。
     * 环境变量：FILE_* / FILE_STORAGE_*（见 application.yml 绑定）。
     */
    public record FileConfig(
        /** 上传端点开关（false 时 403） */
        @DefaultValue("true") boolean uploadEnabled,
        /** 单文件大小上限（MB） */
        @DefaultValue("20") int uploadMaxMb,
        /** 每 user_key pending 未消费文件数上限（软限制） */
        @DefaultValue("20") int uploadMaxPending,
        /** MIME 白名单（逗号分隔，支持 * 通配） */
        @DefaultValue("image/*,text/plain,text/markdown,text/csv,application/pdf,"
            + "application/vnd.openxmlformats-officedocument.*,application/vnd.ms-*") String uploadAllowedMime,
        /** 图片内联单文件大小上限（MB），超限降级路径提示 */
        @DefaultValue("5") int imageMaxMb,
        /** 图片内联总字节预算（MB），多图叠加超限降级路径提示 */
        @DefaultValue("15") int imageInlineTotalMb,
        /** present_file 工具产出文件大小上限（MB） */
        @DefaultValue("50") int presentMaxMb,
        /** 下载端点开关（false 时 403） */
        @DefaultValue("true") boolean downloadEnabled,
        /** upload 文件保留天数（P2 清理） */
        @DefaultValue("7") int retentionDays,
        /** 存储后端类型：local / s3 */
        @DefaultValue("local") String storageType,
        /** local 后端根目录（K8s 下挂 platform-data PVC subPath files/） */
        @DefaultValue("/data/files") String storageLocalDir,
        /** s3 后端 endpoint（MinIO/Ceph RGW/OSS S3 网关） */
        @DefaultValue("") String storageS3Endpoint,
        /** s3 后端 accessKey（敏感，.env.secrets） */
        @DefaultValue("") String storageS3AccessKey,
        /** s3 后端 secretKey（敏感，.env.secrets） */
        @DefaultValue("") String storageS3SecretKey,
        /** s3 后端 bucket */
        @DefaultValue("agent-files") String storageS3Bucket
    ) {}
}
