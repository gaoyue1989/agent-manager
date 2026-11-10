-- =============================================================================
-- agent-framework 全量建表语句（MySQL 8.0）
-- =============================================================================
-- 来源：db/migration/V1..V15 Flyway 迁移（表结构演进一律走 Flyway，启动自动执行、
--       存量库自动 baseline；本文件为快照式全量参考，以迁移脚本为权威）
-- 生成日期：2026-09-28（对应 commit 60dd006）；2026-10-25 增补 V10 turn_intent、
--           V11/V12 model_config.is_default/is_fallback（对应 commit e729a67）；
--           2026-10-27 增补 V13 turn_stop（单轮停止请求）；2026-10-29 增补
--           V14 session_message.trace_id（Monitor 回放 ↔ Jaeger trace，issue #205）；
--           2026-11-09 增补 V15 沙箱生命周期簿记两表 sandbox_user/sandbox_binding
--           （sandbox-pool-opensandbox-design PR-D §4.1，docs/design/ 在仓库根目录）
--
-- 数据库：CHECKPOINT_JDBC_URL 指向的库（默认 agent_manager_test）
--         CHECKPOINT_DB_NAME 可显式指定 agent_state 所在库名（未设时从 JDBC URL 解析）
--
-- 表清单（13 张本工程表 + 2 张 SDK 表）：
--   ① agent_state      SDK MysqlAgentStateStore   —— AgentState（会话状态，权威）
--   ② agent_fs         SDK JdbcStore              —— 分布式文件系统（工作区文件）
--   ③ session_user     会话→用户映射 + 标题(remark) + 会话模型(model)
--   ④ session_message  会话消息轨归档（压缩后历史可查）
--   ⑤ confirm_context  HITL 待确认上下文
--   ⑥ turn_lease       turn 执行权租约（互斥）
--   ⑦ tool_audit_log   工具调用审计日志
--   ⑧ file_asset       上传/交付文件元数据
--   ⑨ kv_sync_key      agent_fs key → user_key 反查表
--   ⑩ model_config     托管模型配置（V11/V12 增默认/备用标记）
--   ⑪ ui_context       MCP Apps 4.7 UI 上下文
--   ⑫ turn_intent      排队 turn 意图（多副本可续传，V10）
--   ⑬ turn_stop        单轮停止请求（跨副本投递，V13）
--   ⑭ sandbox_user     单用户沙箱配额计数锚（binding_count 不变量，V15）
--   ⑮ sandbox_binding  沙箱借出簿记（uk_sandbox/uk_session + PENDING 占额行，V15）
--
-- 非 MySQL：session_event（会话事件）已整体迁至 Redis Streams，不再建表。
-- 未使用：agentscope-extensions-mysql 的 JdbcRemoteSnapshotClient 快照表
--        （snapshot_id / data / created_at），本工程未启用该客户端，不建。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- ① agent_state —— AgentState 存储（SDK 创建，表名可配）
-- 来源：agentscope-extensions-mysql
--       io/agentscope/extensions/mysql/state/MysqlAgentStateStore.java
-- 实例化：AgentScopeConfig.distributedStore(...) 传表名 "agent_state"
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_state (
    session_id VARCHAR(255) NOT NULL,
    state_key  VARCHAR(255) NOT NULL,
    item_index INT          NOT NULL DEFAULT 0,
    state_data LONGTEXT     NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;


-- -----------------------------------------------------------------------------
-- ② agent_fs —— 分布式文件系统（SDK 创建）
-- 来源：agentscope-extensions-mysql
--       io/agentscope/extensions/mysql/store/MysqlJdbcStoreDialect.java
-- 实例化：JdbcStore.builder(dataSource).tableName("agent_fs").initializeSchema(true)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_fs (
    namespace_path VARCHAR(512) NOT NULL,
    item_key       VARCHAR(255) NOT NULL,
    value_json     LONGTEXT     NOT NULL,
    version        BIGINT       NOT NULL,
    updated_at     BIGINT       NOT NULL,
    PRIMARY KEY (namespace_path, item_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- -----------------------------------------------------------------------------
-- ③ session_user —— 会话→用户映射（标题 remark / 会话模型 model）
-- 来源：service/SessionUserStore.java
-- 存量库迁移（代码内幂等 ensureColumn）：
--   ALTER TABLE session_user ADD COLUMN remark VARCHAR(512) DEFAULT '' AFTER user_id;
--   ALTER TABLE session_user ADD COLUMN model  VARCHAR(128) DEFAULT '' AFTER remark;
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS session_user (
    session_id VARCHAR(255) NOT NULL PRIMARY KEY,
    user_id    VARCHAR(255) NOT NULL,
    remark     VARCHAR(512) DEFAULT '',
    model      VARCHAR(128) DEFAULT '',
    created_at DATETIME(3)  NOT NULL,
    updated_at DATETIME(3)  NOT NULL,
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- -----------------------------------------------------------------------------
-- ④ session_message —— 会话消息轨归档（append-only，压缩后历史可查）
-- 来源：service/SessionMessageStore.java
-- 开关：AGENT_HISTORY_ARCHIVE_ENABLED（默认 true；false = 不写不查）
-- 清理：SessionCleanupService 按 updated_at 保留期删除（与 agent_state 同 7 天）
-- 幂等：唯一键 (session_id, msg_id)，重复归档按 mergeMsgJson 合并
-- trace_id：V14 增，OTel traceId（首非空值优先，历史行 NULL），Monitor 回放「消息 ↔ Jaeger trace」入口
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS session_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    msg_id     VARCHAR(128) NOT NULL,
    kind       VARCHAR(32)  NOT NULL DEFAULT 'message',
    role       VARCHAR(32)  DEFAULT NULL,
    reply_id   VARCHAR(64)  DEFAULT NULL,
    trace_id   VARCHAR(32)  DEFAULT NULL,
    msg_data   LONGTEXT     NOT NULL,
    created_at DATETIME(3)  NOT NULL,
    updated_at DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_session_msg (session_id, msg_id),
    KEY idx_session_id (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- -----------------------------------------------------------------------------
-- ⑤ confirm_context —— HITL 待确认上下文
-- 来源：service/ConfirmContextStore.java
-- 语义：permission_ask 时覆盖写入（consumed 重置 0），confirm 后 CAS 置 consumed=1
-- 清理：AGENT_CLEANUP_* confirm TTL 过期清理
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS confirm_context (
    session_id         VARCHAR(255) PRIMARY KEY,
    tool_calls_json    MEDIUMTEXT NOT NULL,
    reply_id           VARCHAR(64),
    runtime_session_id VARCHAR(255),
    runtime_user_id    VARCHAR(255),
    created_at         DATETIME(3) NOT NULL,
    consumed           TINYINT(1)  NOT NULL DEFAULT 0,
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- -----------------------------------------------------------------------------
-- ⑥ turn_lease —— turn 执行权租约（单会话单次流互斥，续租 + 过期接管）
-- 来源：service/TurnLeaseStore.java
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS turn_lease (
    session_id VARCHAR(255) PRIMARY KEY,
    token      CHAR(36)    NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- -----------------------------------------------------------------------------
-- ⑦ tool_audit_log —— 工具调用审计日志（异步批量写入，失败静默降级）
-- 来源：service/ToolAuditStore.java
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tool_audit_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    VARCHAR(255) NOT NULL,
    tool_name     VARCHAR(255) NOT NULL,
    tool_call_id  VARCHAR(64),
    state         VARCHAR(32),
    payload_json  MEDIUMTEXT,
    created_at    DATETIME(3)  NOT NULL,
    KEY idx_session (session_id, id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- -----------------------------------------------------------------------------
-- ⑧ file_asset —— 上传/交付文件元数据（本地 / S3 双后端）
-- 来源：service/FileAssetStore.java
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS file_asset (
    id             VARCHAR(36)  PRIMARY KEY,
    user_key       VARCHAR(255) NOT NULL,
    session_id     VARCHAR(255),
    reply_id       VARCHAR(64),
    file_name      VARCHAR(255) NOT NULL,
    workspace_path VARCHAR(512),
    mime_type      VARCHAR(128) NOT NULL,
    size           BIGINT       NOT NULL,
    storage_type   VARCHAR(16)  NOT NULL,
    storage_key    VARCHAR(512) NOT NULL,
    origin         VARCHAR(16)  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'pending',
    created_at     DATETIME(3)  NOT NULL,
    KEY idx_user_status (user_key, status),
    KEY idx_session (session_id, created_at),
    KEY idx_origin_status (origin, status, created_at),
    KEY idx_reply (reply_id),
    UNIQUE KEY uk_storage (storage_type, storage_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- -----------------------------------------------------------------------------
-- ⑨ kv_sync_key —— agent_fs rel_path → user_key 反查（文件权限/归属用）
-- 来源：service/FileAssetStore.java
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kv_sync_key (
    rel_path   VARCHAR(512) NOT NULL,
    user_key   VARCHAR(255) NOT NULL,
    updated_at DATETIME     NOT NULL,
    PRIMARY KEY (rel_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- -----------------------------------------------------------------------------
-- ⑩ model_config —— 托管模型配置（/models CRUD）
-- 来源：service/ModelConfigStore.java + V3/V4/V11/V12 迁移
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS model_config (
    id                VARCHAR(64)  NOT NULL PRIMARY KEY,
    name              VARCHAR(128) NOT NULL,
    provider          VARCHAR(32)  NOT NULL DEFAULT 'openai',
    model_id          VARCHAR(128) NOT NULL,
    base_url          VARCHAR(512) NOT NULL,
    api_key           VARCHAR(512) DEFAULT NULL,
    temperature       DOUBLE       NOT NULL DEFAULT 0.3,
    max_tokens        INT          NOT NULL DEFAULT 16384,
    timeout_seconds   INT          NOT NULL DEFAULT 120,
    enable_thinking   TINYINT(1)   NOT NULL DEFAULT 0,
    reasoning_effort  VARCHAR(16)  DEFAULT NULL,
    frequency_penalty DOUBLE       DEFAULT NULL,
    context_length    INT          NOT NULL DEFAULT 0,
    enabled           TINYINT(1)   NOT NULL DEFAULT 1,
    is_default        TINYINT(1)   NOT NULL DEFAULT 0,
    is_fallback       TINYINT(1)   NOT NULL DEFAULT 0,
    created_at        DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_model_name (name),
    KEY idx_model_is_default (is_default),
    KEY idx_model_is_fallback (is_fallback)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- -----------------------------------------------------------------------------
-- ⑪ ui_context —— MCP Apps 4.7 静默 UI 上下文（按会话覆盖写）
-- 来源：service/UiContextStore.java
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ui_context (
    session_id         VARCHAR(255) NOT NULL,
    content            MEDIUMTEXT   NULL,
    structured_context JSON         NULL,
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- -----------------------------------------------------------------------------
-- ⑫ turn_intent —— 排队 turn 意图（多副本可续传，V10）
-- 来源：db/migration/V10__turn_intent.sql（SessionIntentStore）
-- POST /threads/chat 抢 turn_lease 前登记本轮 turn id，排队等待期间刷新的客户端
-- 经 GET /status 拿到 turn id 后用 GET /subscribe?replyId= 续传。
-- 生命周期：QUEUED 在租约到手后立即删除；排队超时/建立失败改写 FAILED+error_text；
-- 崩溃遗留行由 expires_at 读时过滤 + SessionCleanupService 每日清理兜底。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS turn_intent (
    session_id  VARCHAR(255)   NOT NULL,
    reply_id    VARCHAR(64)    NOT NULL,
    state       VARCHAR(16)    NOT NULL,
    error_text  VARCHAR(1024)  DEFAULT NULL,
    created_at  DATETIME(3)    NOT NULL,
    expires_at  DATETIME(3)    NOT NULL,
    PRIMARY KEY (session_id),
    KEY idx_turn_intent_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- -----------------------------------------------------------------------------
-- ⑬ turn_stop —— 单轮停止请求（跨副本投递，V13）
-- 来源：db/migration/V13__turn_stop.sql（TurnStopStore）
-- POST /threads/{sessionId}/stop 写行；持有该 turn 的副本每秒全量读取并按
-- reply_id 匹配本地活跃 turn，再调 SDK 按会话 interrupt。reply_id 是栅栏：
-- 新 turn 的 reply_id 不同，陈旧行不会误杀下一轮。
-- 生命周期：turn 收尾即删；崩溃遗留行由 requested_at 读窗（1h）过滤 +
-- SessionCleanupService 每日清理兜底。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS turn_stop (
    session_id   VARCHAR(255)  NOT NULL,
    reply_id     VARCHAR(64)   NOT NULL,
    requested_by VARCHAR(255)  DEFAULT NULL,
    requested_at DATETIME(3)   NOT NULL,
    PRIMARY KEY (session_id),
    KEY idx_turn_stop_reply (reply_id),
    KEY idx_turn_stop_requested (requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- -----------------------------------------------------------------------------
-- ⑭ sandbox_user —— 用户沙箱配额计数锚（V15）
-- 来源：db/migration/V15__sandbox_binding.sql（sandbox-pool-opensandbox-design §4.1，PR-D）。
-- 职责边界：TTL 真相在 OpenSandbox 服务端（expiresAt + 到期 GC），本表仅是
-- LRU 排序缓存 + 单用户配额仲裁锚点；「执行中 turn」事实源仍是 turn_lease
-- （§4.1.1 派生，不冗余）。落 per-service checkpoint 库（{原库}_{k8s 名}），配额天然按服务隔离。
-- 用户配额计数表：占额 CAS 锚点。占额（检查计数 + 占位）必须原子——仅靠绑定表做不到
-- （0 绑定行时 SELECT ... FOR UPDATE 锁不住空集，并发会话各自 count=quota-1 都去 create 突破配额）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sandbox_user (
  user_key       VARCHAR(255) NOT NULL,
  -- 该用户当前绑定数；与 COUNT(sandbox_binding WHERE user_key=?) 事务内同增同减
  -- 不变量（§4.1）：任意时刻 binding_count == COUNT(sandbox_binding.user_key = 本行)
  binding_count  INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- ⑮ sandbox_binding —— 沙箱借出簿记（V15，同上来源）
-- 绑定表：user/session ↔ sandbox 簿记。绑定行不会在会话结束时删除（框架无会话结束信号），
-- 沙箱到期后行仍在，由 acquire 懒剔除（正确性）+ SessionCleanupService 定时清理（容量）双轨回收。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sandbox_binding (
  id         BIGINT NOT NULL AUTO_INCREMENT,
  user_key   VARCHAR(255) NOT NULL,
  session_id VARCHAR(255) NOT NULL,
  sandbox_id VARCHAR(128) NOT NULL,
  -- LRU 排序缓存（服务端绝对 expiresAt 回填，不用本地 now+TTL 推算）；真相在服务端 expiresAt。
  -- PENDING 占额行固定取 NOW() + 10min（= 陈旧 PENDING 回收窗，§4.2 ②，天然豁免 ⑤a 过期行删除）
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sandbox (sandbox_id),          -- 一个沙箱至多一条绑定
  UNIQUE KEY uk_session (session_id),          -- 一个会话至多一条绑定（LRU 复用 = UPDATE 改归属）
  KEY idx_user (user_key, expires_at),         -- 配额校验 + LRU 取最小 expires_at
  KEY idx_expiry (expires_at)                  -- 定时清理扫描（§4.1.2）
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- PENDING 占额不变量（§4.1，评审修订）：占额（sandbox_user CAS）与绑定落库是两个事务，
-- 若占额提交后进程崩溃（create 前或落绑定前），binding_count +1 却无对应绑定行——幽灵占额。
-- 修复：占额事务同时 INSERT 一条 PENDING 绑定行（sandbox_id = 'pending:{uuid}'，
-- session_id 即本会话，expires_at = NOW() + 10min），create 成功后短事务把该行
-- UPDATE 为真实 sandbox_id + 服务端 expiresAt（转正）；create 失败或唯一键冲突
-- → 删除 PENDING 行 + 计数递减（补偿一律按自身 pending:{uuid} 的 sandbox_id 定位，
-- 严禁按 session_id 删——同用户 A2A 链路共享 session_id，会误删他人在途占位）。
-- 此后任意时刻 binding_count == COUNT(binding 行)，⑤ 的重算/懒剔除在锚行锁下恒安全；
-- 崩溃残留的 PENDING 行由懒剔除按行龄回收（created_at < NOW() - 10min）。


-- =============================================================================
-- 部署注意
-- =============================================================================
-- 1. 表结构演进一律走 Flyway（db/migration/V1..V15，启动自动执行；存量库自动
--    baseline，多副本历史表锁互斥，失败即启动失败——纪律见
--    docs/design/db-migration-flyway-design.md）：新建库无需手动执行本文件；
--    **禁止**在 Store 构造器或请求路径手工 DDL，禁止修改已合并的 V 文件
--    （checksum 校验会拒绝启动）。
-- 2. session_message 为新表且默认开启归档，升级后会持续写入并按 7 天保留滚动清理；
--    磁盘/行数敏感可设 AGENT_HISTORY_ARCHIVE_ENABLED=false 关闭。
-- 3. session_event 表已于 2026-09-16 起迁至 Redis Streams（AGENT_REDIS_URL），
--    不再建 MySQL 表；历史遗留的 session_event 表可归档后删除。
-- =============================================================================
