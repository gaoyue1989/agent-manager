-- =====================================================================
-- V16：confirm_context 增加 skills_json（issue #264）。
--
-- 背景：HITL confirm 恢复轮此前不携带挂起轮的 skills 白名单，恢复消息只有
-- confirm_results → SkillFilterMiddleware 读不到 metadata.skills → 白名单静默
-- 失效为全部技能。本列由 storeConfirmContext 写入原轮名单，buildResumeMsg
-- 在恢复消息 metadata 中回填，恢复段沿用同一能力边界。
--
-- 语义：NULL = 挂起轮未声明技能名单（不设置白名单）；'[]' = 显式无技能。
-- 远程确认行（confirm_key='task:*'）恒为 NULL（远程链路没有本轮技能名单）。
--
-- 【幂等性纪律】同 V7：MySQL 8.0 无 ADD COLUMN IF NOT EXISTS，使用
-- INFORMATION_SCHEMA 探测守卫；在已实际应用本迁移的库上重放为 no-op。
-- =====================================================================

SET @ddl_sk = IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'confirm_context'
     AND COLUMN_NAME = 'skills_json') = 0,
  'ALTER TABLE confirm_context ADD COLUMN skills_json MEDIUMTEXT NULL AFTER tool_calls_json',
  'SELECT 1');
PREPARE stmt_sk FROM @ddl_sk;
EXECUTE stmt_sk;
DEALLOCATE PREPARE stmt_sk;
