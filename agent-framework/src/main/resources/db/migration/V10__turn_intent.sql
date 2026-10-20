-- 排队 turn 意图（多副本可续传）：POST /threads/chat 在抢 turn_lease 前登记本轮
-- 即将使用的 turn id（reply_id 信封 id），使「排队等待期间刷新页面」的客户端能经
-- GET /status 拿到本轮 turn id，再用 GET /subscribe?replyId= 续传；排队超时/建立失败
-- 时改写为 FAILED 并带错误文本，刷新后仍能看见失败原因。
--
-- 生命周期：QUEUED 在租约到手后立即删除（此后以 session_event 的事件 replyId 为准）；
-- 崩溃遗留行由 expires_at 读时过滤 + SessionCleanupService 每日清理兜底。
CREATE TABLE IF NOT EXISTS turn_intent (
  session_id  VARCHAR(255) NOT NULL,
  reply_id    VARCHAR(64)  NOT NULL,
  state       VARCHAR(16)  NOT NULL,
  error_text  VARCHAR(1024) DEFAULT NULL,
  created_at  DATETIME(3)  NOT NULL,
  expires_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (session_id),
  KEY idx_turn_intent_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
