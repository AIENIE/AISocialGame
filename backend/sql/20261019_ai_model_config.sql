CREATE TABLE IF NOT EXISTS ai_model_catalog (
  id BIGINT NOT NULL PRIMARY KEY,
  models_json LONGTEXT NOT NULL,
  synced_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO ai_model_catalog (id, models_json, version)
SELECT 1, '[]', 0 WHERE NOT EXISTS (SELECT 1 FROM ai_model_catalog WHERE id = 1);

CREATE TABLE IF NOT EXISTS ai_function_model_configs (
  function_code VARCHAR(40) NOT NULL PRIMARY KEY,
  model_key VARCHAR(128) NULL,
  thinking_mode VARCHAR(16) NOT NULL,
  thinking_effort VARCHAR(16) NULL,
  updated_at DATETIME(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
