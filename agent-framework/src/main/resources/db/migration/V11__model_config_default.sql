-- =====================================================================
-- V11：默认对话模型（debug 页 Models 可设置）。
--
-- is_default=1 最多一行（应用层单语句原子维护：更新时命中行置 1、其余置 0）；
-- 无默认行 = 系统模型（LLM_* 环境变量）。未显式绑定模型的会话走该默认模型。
-- =====================================================================

ALTER TABLE model_config
  ADD COLUMN is_default TINYINT(1) NOT NULL DEFAULT 0 AFTER enabled,
  ADD KEY idx_model_is_default (is_default);
