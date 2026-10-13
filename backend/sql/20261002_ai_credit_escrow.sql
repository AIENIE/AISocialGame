CREATE TABLE IF NOT EXISTS credit_temp_lots (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  project_key VARCHAR(64) NOT NULL,
  remaining BIGINT NOT NULL,
  expires_at DATETIME(6) NULL,
  KEY idx_temp_lot_owner (user_id, project_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_credit_reservations (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  identity_hash VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  project_key VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  budget_id VARCHAR(36) NOT NULL,
  state VARCHAR(32) NOT NULL,
  reserved_temp BIGINT NOT NULL,
  reserved_permanent BIGINT NOT NULL,
  temp_portions_json TEXT NOT NULL,
  updated_at BIGINT NOT NULL,
  result_base64 LONGTEXT NULL,
  UNIQUE KEY uk_ai_credit_identity (identity_hash),
  KEY idx_ai_credit_reconcile (state, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
