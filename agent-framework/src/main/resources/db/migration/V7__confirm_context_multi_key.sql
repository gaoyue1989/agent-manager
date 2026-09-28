-- =====================================================================
-- V7：confirm_context 多行化（travel-fulfillment RemoteConfirmBridge，设计 §5.1）。
--
-- 背景：confirm_context 原为同 session 单行覆盖式（PK = session_id）。
-- 远程确认桥以 confirm_key = 'task:{task_id}' 落远程确认行后，若维持单行
-- 覆盖语义，并发第二个远程挂起会覆盖第一个，先到任务永久悬挂（评审 P1-2）。
--
-- 演进内容：
--   * 新增 confirm_key 列：本地 HITL 固定 'local'（同 session 覆盖语义不变，零回归）；
--     远程确认行 = 'task:{task_id}'（同 session 多行 FIFO 排队）。
--   * 存量回填：ADD COLUMN ... NOT NULL DEFAULT 'local' 使全部存量行即刻为 'local'
--     （下方 UPDATE 为显式幂等兜底，正常情况下 0 行受影响）。
--   * 主键演进为 (session_id, confirm_key)：同 session 本地行维持覆盖写，
--     远程行按任务独立成行。
--   * 新增 remote_task 列：远程行锚点 JSON（{service, task_id, tool_calls,
--     child_reply_id}），本地行为 NULL——消费路由凭"行含 remote_task"判定
--     不走父 state 恢复、改经 Bridge 调 /resume 路由（设计 §5.4/§5.5）。
--     远程行独立 TTL（默认 24h）由 ConfirmContextStore 按 confirm_key 分档判定，
--     本迁移不承载 TTL 值。
-- =====================================================================

ALTER TABLE confirm_context
  ADD COLUMN confirm_key VARCHAR(191) NOT NULL DEFAULT 'local' AFTER session_id,
  ADD COLUMN remote_task MEDIUMTEXT NULL AFTER tool_calls_json;

-- 存量回填（幂等兜底；ADD COLUMN DEFAULT 已使存量行 = 'local'，此句正常 0 行受影响）
UPDATE confirm_context SET confirm_key = 'local'
WHERE confirm_key IS NULL OR confirm_key = '';

ALTER TABLE confirm_context DROP PRIMARY KEY, ADD PRIMARY KEY (session_id, confirm_key);
