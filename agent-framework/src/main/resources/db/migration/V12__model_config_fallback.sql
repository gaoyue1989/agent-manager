-- =====================================================================
-- V12：可配置备用模型（debug 页 Models 可设置）。
--
-- is_fallback=1 最多一行（应用层单语句原子维护：更新时命中行置 1、其余置 0）；
-- 无默认行 = 回退 LLM_FALLBACK_MODEL_ID；两者都没有 = 不启用备用。
-- 备用模型只允许托管模型（不支持 system 作为备用）。
-- =====================================================================

ALTER TABLE model_config
  ADD COLUMN is_fallback TINYINT(1) NOT NULL DEFAULT 0 AFTER is_default,
  ADD KEY idx_model_is_fallback (is_fallback);
