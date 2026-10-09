-- =====================================================================
-- V2：agui_interrupt 表（AG-UI HITL interrupt 元数据，跨副本恢复）。
--
-- 来源：AG-UI 分支建表提交 2cd55f9（作者 2026-09-11，合并部署晚于 e91d1f0 基线点）。
-- 现网已存在该表；此迁移保证全新库重建后与现网一致。
-- 表结构按现网 SHOW CREATE TABLE 固化，COMMENT 取干净中文
-- （现网行的 COMMENT 为历史建库会话字符集产物，乱码不影响语义）。
-- =====================================================================

CREATE TABLE agui_interrupt (
  thread_id       VARCHAR(255) NOT NULL COMMENT 'AG-UI threadId',
  interrupts_json MEDIUMTEXT NOT NULL COMMENT '[{interruptId,toolCallId,toolName,toolInput,toolContent,replyId}]',
  run_id          VARCHAR(128) DEFAULT NULL COMMENT '产生 interrupt 的 runId',
  consumed        TINYINT NOT NULL DEFAULT 0 COMMENT 'CAS 消费标记 0->1',
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AG-UI HITL interrupt 元数据（跨副本恢复）';
