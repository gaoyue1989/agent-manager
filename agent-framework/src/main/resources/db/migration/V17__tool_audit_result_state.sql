-- 工具调用观测（tool-call-observability-design）：tool_audit_log 增加结果状态列，
-- 供失败率统计与 /debug/monitor/tools 聚合；审计写点统一至 ToolCallObservabilityMiddleware
-- （每调用 2 行：TOOL_CALL_START + TOOL_RESULT_END），计数口径 = TOOL_CALL_START 行。
-- 存量行（controller 侧写入的 4 态行）result_state 保持 NULL，聚合按未知处理。
ALTER TABLE tool_audit_log
  ADD COLUMN result_state VARCHAR(32) DEFAULT NULL AFTER state,
  ADD INDEX idx_tool_created (tool_name, created_at),
  ADD INDEX idx_tool_call_id (tool_call_id);
