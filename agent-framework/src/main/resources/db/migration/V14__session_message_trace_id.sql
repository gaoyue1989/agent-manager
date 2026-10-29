-- =====================================================================
-- V14：会话消息轨补充 OTel trace_id（issue #205，2026-10-29）。
--
-- 归档写入发生在 turn 的 OTel span 作用域内（TurnInputArchiveMiddleware /
-- SessionMessageArchiveStateStore），写入时取 Span.current() 的 traceId 落库，
-- 供 Monitor 会话回放直接展示「消息 ↔ Jaeger trace」入口。
--
-- 语义：首个非空值优先（与 reply_id 一致）——后续 turn 重新归档历史消息时
-- 不得把旧消息的 traceId 覆盖成本轮值。历史行无 trace 可推导，保持 NULL，
-- 页面按「无活跃 span」口径显示 "-"。
--
-- 幂等：FW3 升级路径会在已迁移库上重放全部迁移（e2e/api-core.spec.ts），
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，故用 information_schema 守卫。
-- =====================================================================

SET @trace_id_ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE session_message ADD COLUMN trace_id VARCHAR(32) DEFAULT NULL AFTER reply_id',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'session_message'
    AND COLUMN_NAME = 'trace_id'
);
PREPARE trace_id_stmt FROM @trace_id_ddl;
EXECUTE trace_id_stmt;
DEALLOCATE PREPARE trace_id_stmt;
