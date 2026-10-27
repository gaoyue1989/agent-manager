-- 单轮停止请求（POST /threads/{sessionId}/stop → 属主副本轮询消费）
--
-- 为什么落共享存储：停止请求可能落到任意副本，而 SDK 的 interrupt 标志是
-- per-session 的**运行时**状态（InterruptControl 不序列化），只有真正持有该
-- turn 的副本能生效。请求方写行、属主轮询读行，跨副本无需粘性路由。
--
-- reply_id 是栅栏：一轮 turn 的 reply_id 全局唯一，陈旧停止行不可能误杀下一轮。
-- 生命周期：属主 turn 收尾时删除（含正常结束/中断/排队取消）；崩溃遗留行由
-- requested_at 读时过滤 + SessionCleanupService 每日清理兜底。
CREATE TABLE IF NOT EXISTS turn_stop (
  session_id   VARCHAR(255) NOT NULL,
  reply_id     VARCHAR(64)  NOT NULL,
  requested_by VARCHAR(255) DEFAULT NULL,
  requested_at DATETIME(3)  NOT NULL,
  PRIMARY KEY (session_id),
  KEY idx_turn_stop_reply (reply_id),
  KEY idx_turn_stop_requested (requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
