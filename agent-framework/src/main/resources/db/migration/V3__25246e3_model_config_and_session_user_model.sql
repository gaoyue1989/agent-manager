-- =====================================================================
-- V3：会话级模型切换 + 托管模型配置（提交 25246e3，2026-09-24）。
--
-- 1) 新建 model_config：托管模型列表（/models 可选集 + 系统模型管标题/压缩）。
-- 2) session_user 补 model 列：会话级模型绑定（"" = 回落系统默认模型）。
-- =====================================================================

CREATE TABLE model_config (
  id              VARCHAR(64) NOT NULL PRIMARY KEY,
  name            VARCHAR(128) NOT NULL,
  provider        VARCHAR(32) NOT NULL DEFAULT 'openai',
  model_id        VARCHAR(128) NOT NULL,
  base_url        VARCHAR(512) NOT NULL,
  api_key         VARCHAR(512) DEFAULT NULL,
  temperature     DOUBLE NOT NULL DEFAULT 0.3,
  max_tokens      INT NOT NULL DEFAULT 16384,
  timeout_seconds INT NOT NULL DEFAULT 120,
  enable_thinking TINYINT(1) NOT NULL DEFAULT 0,
  context_length  INT NOT NULL DEFAULT 0,
  enabled         TINYINT(1) NOT NULL DEFAULT 1,
  created_at      DATETIME(3) NOT NULL,
  updated_at      DATETIME(3) NOT NULL,
  UNIQUE KEY uk_model_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE session_user ADD COLUMN model VARCHAR(128) DEFAULT '' AFTER remark;
