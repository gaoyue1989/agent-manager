package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent")
public record AgentManagerProperties(
    LLMConfig llm,
    ServerConfig server,
    CheckpointConfig checkpoint,
    @DefaultValue("/config") String configDir,
    @DefaultValue("") String workspaceDir,
    CleanupConfig cleanup,
    FileConfig file,
    SseConfig sse,
    HarnessConfig harness,
    AgentProtocolSettings agentProtocol
) {

    /**
     * 显式绑定构造器：record 新增 agentProtocol 组件后出现两个构造器（下方保留一个
     * 兼容旧按位构造的兼容构造器），Spring Boot 的属性 Binder 在多构造器时要求以
     * {@code @ConstructorBinding} 指定绑定入口，缺注解会在启动期绑定失败。
     */
    @ConstructorBinding
    public AgentManagerProperties {
    }

    /**
     * 兼容旧 9 参构造器（不参与属性绑定，仅供既有测试按位置传参构造，
     * 与 HistoryConfig javadoc 记载的「加组件波及全部按位构造测试」问题同源）：
     * 协议配置缺省为默认值（enabled=false，存量行为零变化）。
     */
    public AgentManagerProperties(
        LLMConfig llm,
        ServerConfig server,
        CheckpointConfig checkpoint,
        String configDir,
        String workspaceDir,
        CleanupConfig cleanup,
        FileConfig file,
        SseConfig sse,
        HarnessConfig harness
    ) {
        this(llm, server, checkpoint, configDir, workspaceDir, cleanup, file, sse, harness,
            AgentProtocolSettings.defaults());
    }

    /**
     * 工作区基目录：AGENT_WORKSPACE_DIR 优先；未配置时回落到 configDir。
     * 平台部署场景下 configDir 为只读的 OAF 包挂载点，工作区须落在独立可写卷。
     */
    public String resolvedWorkspaceBaseDir() {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return workspaceDir;
        }
        return resolvedConfigDir();
    }

    /** 解析后的配置目录：显式配置优先；未配置则按 OS 选默认值（Linux /config；Windows %LOCALAPPDATA%/agent-framework/config） */
    public String resolvedConfigDir() {
        if (configDir != null && !configDir.isBlank()) {
            return configDir;
        }
        // 未配置时按 OS 选默认路径
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            var localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return localAppData + "\\agent-framework\\config";
            }
            var userProfile = System.getenv("USERPROFILE");
            if (userProfile != null && !userProfile.isBlank()) {
                return userProfile + "\\AppData\\Local\\agent-framework\\config";
            }
            return "C:\\agent-framework\\config";
        }
        return "/config";
    }

    public record LLMConfig(
        @DefaultValue("") String apiKey,
        @DefaultValue("") String modelId,
        @DefaultValue("") String baseUrl,
        @DefaultValue("openai") String provider,
        @DefaultValue("0.7") double temperature,
        @DefaultValue("4096") int maxTokens,
        @DefaultValue("120") int timeout,
        @DefaultValue("true") boolean enableThinking,
        @DefaultValue("0") int contextLength,
        /** 推理强度（reasoning_effort）；空 = 不下发，由端点默认行为决定（方言映射见 ChatModelFactory） */
        @DefaultValue("") String reasoningEffort,
        /** 频率惩罚；null = 不下发。绑定空串时 Spring 转为 null（env 未配置即不下发） */
        Double frequencyPenalty
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
        @DefaultValue(FileConfig.DEFAULT_UPLOAD_ALLOWED_MIME) String uploadAllowedMime,
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
        @DefaultValue("agent-files") String storageS3Bucket,
        /** present_url 外部交付物 URL 前缀白名单（逗号分隔，防 SSRF；空=禁用外部交付） */
        @DefaultValue("") String externalUrlPrefixes
    ) {

        /** 解析外部交付 URL 白名单为列表（去空白、去尾斜杠、忽略空项） */
        public java.util.List<String> externalUrlPrefixList() {
            if (externalUrlPrefixes == null || externalUrlPrefixes.isBlank()) {
                return java.util.List.of();
            }
            return java.util.Arrays.stream(externalUrlPrefixes.split(","))
                .map(String::trim)
                .map(s -> s.replaceAll("/+$", ""))
                .filter(s -> !s.isEmpty())
                .toList();
        }
        /**
         * 默认 MIME 白名单：图片/文本/PDF/Office 之外放行 zip（OAF 配置包经 📎 上传后注入
         * 工作区，助手凭路径调 upload_package 发布）；x-zip-compressed 兼容 Windows 浏览器。
         * 与 application.yml 的 FILE_UPLOAD_ALLOWED_MIME env 默认值保持同步。
         */
        public static final String DEFAULT_UPLOAD_ALLOWED_MIME =
            "image/*,text/plain,text/markdown,text/csv,application/pdf,"
                + "application/vnd.openxmlformats-officedocument.*,application/vnd.ms-*,"
                + "application/zip,application/x-zip-compressed";

        /** 解析后的存储目录：显式配置优先；未配置则按 OS 选默认值 */
        public String resolvedStorageLocalDir() {
            if (storageLocalDir != null && !storageLocalDir.isBlank()) {
                return storageLocalDir;
            }
            // 跨平台默认值
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                var localAppData = System.getenv("LOCALAPPDATA");
                if (localAppData != null && !localAppData.isBlank()) {
                    return localAppData + "\\agent-framework\\files";
                }
                var userProfile = System.getenv("USERPROFILE");
                if (userProfile != null && !userProfile.isBlank()) {
                    return userProfile + "\\AppData\\Local\\agent-framework\\files";
                }
                return "C:\\agent-framework\\files";
            }
            return "/data/files";
        }
    }

    /**
     * Harness 运行时配置：ReAct 推理、LLM HTTP 超时、Memory、Compaction、HikariCP 连接池。
     * 环境变量前缀：AGENT_*（如 AGENT_REACT_MAX_ITERS），绑定见 application.yml agent.harness.* 节。
     * 默认值与参数化前的硬编码值一致，仅暴露可调性、不改变现有行为。
     */
    public record HarnessConfig(
        // ReAct 推理
        /** ReAct 推理最大轮次（SDK 默认 10，长流程需放宽） */
        @DefaultValue("20") int maxIters,
        // LLM API HTTP 超时（秒）
        /** LLM API 连接超时（秒） */
        @DefaultValue("30") int httpConnectTimeoutSeconds,
        /** LLM API 读超时（秒，长推理场景需更长） */
        @DefaultValue("180") int httpReadTimeoutSeconds,
        /** LLM API 写超时（秒） */
        @DefaultValue("30") int httpWriteTimeoutSeconds,
        // Memory
        /** 记忆总开关（AGENT_MEMORY_ENABLED）：false 时完全不装配记忆（hooks + 工具 + 沙箱门控） */
        @DefaultValue("true") boolean memoryEnabled,
        /** 记忆刷写节流间隔（分钟） */
        @DefaultValue("10") int memoryFlushThrottleMinutes,
        /** 记忆整合最大 token 数 */
        @DefaultValue("8000") int memoryConsolidationMaxTokens,
        /** 记忆整合最小间隔（分钟） */
        @DefaultValue("60") int memoryConsolidationMinGapMinutes,
        // Compaction
        /** 触发压缩的消息数阈值 */
        @DefaultValue("30") int compactionTriggerMessages,
        /** 压缩后保留的消息数 */
        @DefaultValue("10") int compactionKeepMessages,
        /** 压缩前先刷写记忆 */
        @DefaultValue("true") boolean compactionFlushBeforeCompact,
        /** 压缩前先卸载大工具结果 */
        @DefaultValue("true") boolean compactionOffloadBeforeCompact,
        // HikariCP
        /** DB 连接池最大连接数 */
        @DefaultValue("10") int dbPoolMaxSize,
        /** DB 连接池最小空闲连接 */
        @DefaultValue("2") int dbPoolMinIdle,
        /** DB 连接超时（毫秒） */
        @DefaultValue("30000") long dbPoolConnectionTimeoutMs,
        /** DB 空闲超时（毫秒） */
        @DefaultValue("600000") long dbPoolIdleTimeoutMs,
        /** DB 连接最大生命周期（毫秒） */
        @DefaultValue("1800000") long dbPoolMaxLifetimeMs
    ) {
        /** 代码默认值兜底：配置节缺失（如测试直接构造 props）时使用 */
        public static HarnessConfig defaults() {
            return new HarnessConfig(20, 30, 180, 30, true, 10, 8000, 60,
                30, 10, true, true, 10, 2, 30000L, 600000L, 1800000L);
        }
    }

    /**
     * SSE 可靠传输配置（durable-sse-plan §3.3）。
     * 环境变量前缀：AGENT_SSE_*（如 AGENT_SSE_HEARTBEAT_SECONDS）
     */
    public record SseConfig(
            /** 心跳间隔（秒），默认 20。防止 Nginx/CDN 60s 读超时 */
            @DefaultValue("20") int heartbeatSeconds,
            /** EventBus Sinks 过期清理延迟（分钟），默认 5 */
            @DefaultValue("5") int sinksEvictionMinutes,
            /** EventBus Sinks 缓冲区大小，默认 256 */
            @DefaultValue("256") int sinksBufferSize,
            /** 观察者游标轮询间隔（毫秒），默认 300 */
            @DefaultValue("300") int tailPollMs
    ) {}

    /**
     * Agent Protocol（远程子 agent 服务端）配置组，绑定 agent.agent-protocol.*
     * （环境变量 AGENT_PROTOCOL_* / AGENT_REMOTE_*，见 travel-fulfillment
     * agent-protocol 设计 §6.2/§7/§8 member）。
     *
     * <p>自带 {@code @ConfigurationProperties} 注解：本 record 经 AgentProtocolConfig 的
     * {@code @EnableConfigurationProperties} 独立注册（协议关闭时也绑定，供状态透出），
     * 注册器要求被注册类型自身携带该注解。
     *
     * <p>默认 enabled=false：协议端点（/tasks*）不注册、AgentProtocolAuthFilter 不装配，
     * 存量服务零影响（验收断言 8）。
     *
     * <p>remoteConfirmTtlHours / remotePollSeconds / remoteHeadersJson 为 lead 端与
     * RemoteConfirmBridge（桥组件，后续里程碑）预留的语义槽位，member 侧当前只透出与落库。
     */
    @ConfigurationProperties(prefix = "agent.agent-protocol")
    public record AgentProtocolSettings(
        /** 协议总开关（AGENT_PROTOCOL_ENABLED），默认关闭 */
        @DefaultValue("false") boolean enabled,
        /** 服务间认证 token（AGENT_PROTOCOL_AUTH_TOKEN）：enabled=true 时必填，缺失启动即失败（fail-fast） */
        @DefaultValue("") String authToken,
        /** 本地 FS TaskStore 路径（AGENT_PROTOCOL_TASK_STORE）；agent_fs bean override 生效时不使用（§7 退化路径） */
        @DefaultValue("") String taskStore,
        /** 终态 TaskRecord 保留天数（AGENT_PROTOCOL_TASK_RETENTION_DAYS），默认 7 */
        @DefaultValue("7") int retentionDays,
        /** 远程确认挂起独立 TTL 小时数（AGENT_REMOTE_CONFIRM_TTL_HOURS，lead/Bridge 用），默认 24 */
        @DefaultValue("24") int remoteConfirmTtlHours,
        /** 远程任务快照轮询周期秒（AGENT_REMOTE_POLL_SECONDS，lead/Bridge 用），默认 5 */
        @DefaultValue("5") int remotePollSeconds,
        /** 远程子 agent 声明 headers JSON（AGENT_REMOTE_HEADERS_JSON，lead 用），member 侧不消费 */
        @DefaultValue("") String remoteHeadersJson
    ) {
        /** 代码默认值兜底：配置节缺失或兼容构造器（测试直接构造）时使用 */
        public static AgentProtocolSettings defaults() {
            return new AgentProtocolSettings(false, "", "", 7, 24, 5, "");
        }
    }
}
