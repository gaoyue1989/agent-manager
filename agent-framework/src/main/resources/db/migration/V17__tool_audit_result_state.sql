-- 工具调用观测（tool-call-observability-design）：tool_audit_log 增加结果状态列，
-- 供失败率统计与 /debug/monitor/tools 聚合；审计写点统一至 ToolCallObservabilityMiddleware
-- （每调用 2 行：TOOL_CALL_START + TOOL_RESULT_END），计数口径 = TOOL_CALL_START 行。
-- 存量行（controller 侧写入的 4 态行）result_state 保持 NULL，聚合按未知处理。
--
-- 【幂等性纪律】同 V14/V16：FW3 升级路径会在已应用库上重放全部迁移
-- （e2e/api-core.spec.ts），MySQL 8.0 无 ADD COLUMN IF NOT EXISTS，
-- 列用 information_schema.COLUMNS、索引用 information_schema.STATISTICS 守卫，
-- 已应用时各段退化为 no-op。

SET @ddl_result_state = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tool_audit_log'
     AND COLUMN_NAME = 'result_state') = 0,
  'ALTER TABLE tool_audit_log ADD COLUMN result_state VARCHAR(32) DEFAULT NULL AFTER state',
  'SELECT 1');
PREPARE stmt_result_state FROM @ddl_result_state;
EXECUTE stmt_result_state;
DEALLOCATE PREPARE stmt_result_state;

SET @ddl_idx_created = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tool_audit_log'
     AND INDEX_NAME = 'idx_tool_created') = 0,
  'ALTER TABLE tool_audit_log ADD INDEX idx_tool_created (tool_name, created_at)',
  'SELECT 1');
PREPARE stmt_idx_created FROM @ddl_idx_created;
EXECUTE stmt_idx_created;
DEALLOCATE PREPARE stmt_idx_created;

SET @ddl_idx_call = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tool_audit_log'
     AND INDEX_NAME = 'idx_tool_call_id') = 0,
  'ALTER TABLE tool_audit_log ADD INDEX idx_tool_call_id (tool_call_id)',
  'SELECT 1');
PREPARE stmt_idx_call FROM @ddl_idx_call;
EXECUTE stmt_idx_call;
DEALLOCATE PREPARE stmt_idx_call;
