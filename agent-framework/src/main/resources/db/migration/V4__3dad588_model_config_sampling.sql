-- =====================================================================
-- V4：模型采样参数扩展（提交 3dad588，2026-09-27）。
-- model_config 补推理强度与频率惩罚两列（推理引擎方言随 /models 下发）。
-- =====================================================================

ALTER TABLE model_config ADD COLUMN reasoning_effort VARCHAR(16) DEFAULT NULL AFTER enable_thinking;
ALTER TABLE model_config ADD COLUMN frequency_penalty DOUBLE DEFAULT NULL AFTER reasoning_effort;
