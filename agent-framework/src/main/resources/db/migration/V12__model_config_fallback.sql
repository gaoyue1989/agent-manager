-- =====================================================================
-- V12：可配置备用模型（debug 页 Models 可设置）。
--
-- is_fallback=1 最多一行（应用层单语句原子维护：更新时命中行置 1、其余置 0）；
-- 无备用行 = 回退 LLM_FALLBACK_MODEL_ID；两者都没有 = 不启用备用。
-- 备用模型只允许托管模型（不支持 system 作为备用）。
--
-- 【幂等性纪律（FW3 基线演练约束，e2e api-core.spec.ts FW3）】FW3 会清空
-- flyway_schema_history 后以 baseline(5) 重放未应用迁移——在"V12 已实际应用"
-- 的现库上本脚本会被再次执行。MySQL 8.0 无 ADD COLUMN IF NOT EXISTS，
-- 因此列/索引均以 INFORMATION_SCHEMA 探测守卫，已应用时整段退化为 no-op；
-- 与 V6 的 INSERT IGNORE、V7/V8 的幂等守卫同构。禁止把本文件改回无条件 DDL。
-- =====================================================================

-- ① is_fallback 列（已存在则跳过）
SET @ddl_fallback_col = IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_config'
     AND COLUMN_NAME = 'is_fallback') = 0,
  'ALTER TABLE model_config ADD COLUMN is_fallback TINYINT(1) NOT NULL DEFAULT 0 AFTER is_default',
  'SELECT 1');
PREPARE stmt_fallback_col FROM @ddl_fallback_col;
EXECUTE stmt_fallback_col;
DEALLOCATE PREPARE stmt_fallback_col;

-- ② 备用标记索引（已存在则跳过）
SET @ddl_fallback_idx = IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_config'
     AND INDEX_NAME = 'idx_model_is_fallback') = 0,
  'ALTER TABLE model_config ADD KEY idx_model_is_fallback (is_fallback)',
  'SELECT 1');
PREPARE stmt_fallback_idx FROM @ddl_fallback_idx;
EXECUTE stmt_fallback_idx;
DEALLOCATE PREPARE stmt_fallback_idx;
