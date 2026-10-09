-- =====================================================================
-- V1 基线：e91d1f0（2026-09-20，PR #8 合并点）时的存量库表结构。
--
-- 表结构以现网 SHOW CREATE TABLE 为准（而非当时代码里的 initSchema 文本）：
-- 老表由更早版本的代码创建，未显式声明 COLLATE 的表落成了 server 默认的
-- utf8mb4_0900_ai_ci，与后续新表（显式 unicode_ci）并存——重建必须忠实还原，
-- 否则跨表 JOIN 会因排序规则不一致报错。
--
-- 此版本之前的 DDL 全部由各 Store 构造器手工执行（该模式已废弃，见 Flyway 接入说明）。
-- =====================================================================

-- AgentScope SDK 会话状态表（SDK JDBC store 建表并写入，框架只读）。
-- SDK 侧也用 CREATE TABLE IF NOT EXISTS 自建（agentscope-extensions-mysql），
-- 这里同样带 IF NOT EXISTS：fresh 库上 SDK bean 与 Flyway 谁先初始化都安全，形态与现网一致
CREATE TABLE IF NOT EXISTS agent_state (
  session_id VARCHAR(255) NOT NULL,
  state_key  VARCHAR(255) NOT NULL,
  item_index INT NOT NULL DEFAULT 0,
  state_data LONGTEXT NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version    BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (session_id, state_key, item_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- AgentScope SDK agent_fs KV 表（SDK JDBC store 建表并写入，同上带 IF NOT EXISTS）
CREATE TABLE IF NOT EXISTS agent_fs (
  namespace_path VARCHAR(512) NOT NULL,
  item_key       VARCHAR(255) NOT NULL,
  value_json     LONGTEXT NOT NULL,
  version        BIGINT NOT NULL,
  updated_at     BIGINT NOT NULL,
  PRIMARY KEY (namespace_path, item_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 会话-用户映射（GET /threads 按 userId 过滤的数据源）
CREATE TABLE session_user (
  session_id VARCHAR(255) NOT NULL,
  user_id    VARCHAR(255) NOT NULL,
  remark     VARCHAR(512) DEFAULT '',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (session_id),
  KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- HITL 待确认上下文（confirm_context 未消费待确认，供刷新后重建确认卡片）
CREATE TABLE confirm_context (
  session_id         VARCHAR(255) NOT NULL,
  tool_calls_json    MEDIUMTEXT NOT NULL,
  reply_id           VARCHAR(64) DEFAULT NULL,
  runtime_session_id VARCHAR(255) DEFAULT NULL,
  runtime_user_id    VARCHAR(255) DEFAULT NULL,
  created_at         DATETIME(3) NOT NULL,
  consumed           TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (session_id),
  KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 单 turn 写入租约（多副本互斥）
CREATE TABLE turn_lease (
  session_id VARCHAR(255) NOT NULL,
  token      CHAR(36) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (session_id),
  KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 产出文件登记（present_file/present_url → 前端下载卡片）
CREATE TABLE file_asset (
  id             VARCHAR(36) NOT NULL,
  user_key       VARCHAR(255) NOT NULL,
  session_id     VARCHAR(255) DEFAULT NULL,
  reply_id       VARCHAR(64) DEFAULT NULL,
  file_name      VARCHAR(255) NOT NULL,
  workspace_path VARCHAR(512) DEFAULT NULL,
  mime_type      VARCHAR(128) NOT NULL,
  size           BIGINT NOT NULL,
  storage_type   VARCHAR(16) NOT NULL,
  storage_key    VARCHAR(512) NOT NULL,
  origin         VARCHAR(16) NOT NULL,
  status         VARCHAR(16) NOT NULL DEFAULT 'pending',
  created_at     DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_storage (storage_type, storage_key),
  KEY idx_user_status (user_key, status),
  KEY idx_session (session_id, created_at),
  KEY idx_reply (reply_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- agent_fs KV 同步游标
CREATE TABLE kv_sync_key (
  rel_path   VARCHAR(512) NOT NULL,
  user_key   VARCHAR(255) NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (rel_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 工具调用审计（异步落库）
CREATE TABLE tool_audit_log (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id   VARCHAR(255) NOT NULL,
  tool_name    VARCHAR(255) NOT NULL,
  tool_call_id VARCHAR(64) DEFAULT NULL,
  state        VARCHAR(32) DEFAULT NULL,
  payload_json MEDIUMTEXT,
  created_at   DATETIME(3) NOT NULL,
  KEY idx_session (session_id, id),
  KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 调试页注入上下文（UI 会话快照）
CREATE TABLE ui_context (
  session_id         VARCHAR(255) NOT NULL,
  content            MEDIUMTEXT,
  structured_context JSON DEFAULT NULL,
  updated_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
