-- =====================================================================
-- V5：会话消息轨归档（提交 062e01f，2026-09-27）。
--
-- session_message：记忆压缩前的全量消息归档，history 双源合并视图的
-- 时间线基底（docs/session-history-archive-design.md）。
-- =====================================================================

CREATE TABLE session_message (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(255) NOT NULL,
  msg_id     VARCHAR(128) NOT NULL,
  kind       VARCHAR(32) NOT NULL DEFAULT 'message',
  role       VARCHAR(32) DEFAULT NULL,
  reply_id   VARCHAR(64) DEFAULT NULL,
  msg_data   LONGTEXT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_session_msg (session_id, msg_id),
  KEY idx_session_id (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
