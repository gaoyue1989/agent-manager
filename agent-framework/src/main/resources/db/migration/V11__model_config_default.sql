-- =====================================================================
-- V11：默认对话模型（debug 页 Models 可设置）。
--
-- is_default=1 最多一行（应用层单语句原子维护：更新时命中行置 1、其余置 0）；
-- 无默认行 = 系统模型（LLM_* 环境变量）。未显式绑定模型的会话走该默认模型。
--
-- 【幂等性纪律（FW3 基线演练约束，e2e api-core.spec.ts FW3）】FW3 会清空
-- flyway_schema_history 后以 baseline(5) 重放未应用迁移——在"V11 已实际应用"
-- 的现库上本脚本会被再次执行。MySQL 8.0 无 ADD COLUMN IF NOT EXISTS，
-- 因此列/索引均以 INFORMATION_SCHEMA 探测守卫，已应用时整段退化为 no-op；
-- 与 V6 的 INSERT IGNORE、V7/V8 的幂等守卫同构。禁止把本文件改回无条件 DDL。
-- =====================================================================

-- ① is_default 列（已存在则跳过）
SET @ddl_default_col = IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_config'
     AND COLUMN_NAME = 'is_default') = 0,
  'ALTER TABLE model_config ADD COLUMN is_default TINYINT(1) NOT NULL DEFAULT 0 AFTER enabled',
  'SELECT 1');
PREPARE stmt_default_col FROM @ddl_default_col;
EXECUTE stmt_default_col;
DEALLOCATE PREPARE stmt_default_col;

-- ② 默认标记索引（已存在则跳过）
SET @ddl_default_idx = IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_config'
     AND INDEX_NAME = 'idx_model_is_default') = 0,
  'ALTER TABLE model_config ADD KEY idx_model_is_default (is_default)',
  'SELECT 1');
PREPARE stmt_default_idx FROM @ddl_default_idx;
EXECUTE stmt_default_idx;
DEALLOCATE PREPARE stmt_default_idx;
