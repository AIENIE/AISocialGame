-- Resumable, evidence-bound werewolf lessons. Additive only; no games, calls or strategies are created.
CREATE TABLE IF NOT EXISTS lab_training_runs (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  experiment_id VARCHAR(36) NOT NULL, operation_id VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL, goal VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL, stage VARCHAR(24) NOT NULL, reason VARCHAR(128) NULL,
  candidate_strategy_version_id VARCHAR(36) NULL,
  configuration LONGTEXT NOT NULL, assessment LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL, updated_at_epoch_ms BIGINT NOT NULL, version BIGINT NULL,
  UNIQUE KEY uk_lab_training_operation (experiment_id, operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_training_cases (
  id VARCHAR(36) NOT NULL PRIMARY KEY, run_id VARCHAR(36) NOT NULL,
  purpose VARCHAR(24) NOT NULL, source_type VARCHAR(48) NOT NULL, source_group VARCHAR(200) NOT NULL,
  persona_id VARCHAR(64) NOT NULL, participant_id VARCHAR(36) NOT NULL, ordinal INT NOT NULL,
  content_hash VARCHAR(64) NOT NULL, data LONGTEXT NOT NULL, created_at DATETIME(6) NOT NULL,
  KEY idx_lab_training_case_run (run_id, purpose, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_training_tasks (
  id VARCHAR(36) NOT NULL PRIMARY KEY, run_id VARCHAR(36) NOT NULL, case_id VARCHAR(36) NULL,
  task_key VARCHAR(128) NOT NULL, kind VARCHAR(24) NOT NULL, variant VARCHAR(24) NULL,
  status VARCHAR(24) NOT NULL, ordinal INT NOT NULL, reason VARCHAR(128) NULL,
  request_id VARCHAR(128) NULL, attempt INT NOT NULL, paid_retries INT NOT NULL,
  started_at_epoch_ms BIGINT NULL, updated_at_epoch_ms BIGINT NOT NULL,
  not_before_epoch_ms BIGINT NOT NULL, lease_until_epoch_ms BIGINT NOT NULL,
  input LONGTEXT NOT NULL, checkpoint LONGTEXT NOT NULL, result LONGTEXT NOT NULL, version BIGINT NULL,
  UNIQUE KEY uk_lab_training_task_identity (run_id, task_key),
  KEY idx_lab_training_task_run (run_id, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Postconditions: three new tables with immutable case content, operation/task identities and query indexes.
-- Existing experiment, participant, wallet and published-strategy records are unchanged.
