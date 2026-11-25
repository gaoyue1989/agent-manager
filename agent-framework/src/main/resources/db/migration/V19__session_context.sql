CREATE TABLE IF NOT EXISTS session_context (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  session_id    VARCHAR(255) NOT NULL,
  context_key   VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
  title         VARCHAR(255) DEFAULT NULL,
  content       MEDIUMTEXT   NOT NULL,
  content_chars INT          NOT NULL,
  user_id       VARCHAR(255) NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_context (session_id, context_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
