CREATE TABLE IF NOT EXISTS lab_agents (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  name VARCHAR(64) NOT NULL, persona_id VARCHAR(64) NOT NULL, model_selection LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL, version BIGINT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_experiments (
  id VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL, reason VARCHAR(128) NULL, budget_points BIGINT NOT NULL,
  next_sequence INT NOT NULL, review_cursor INT NOT NULL, configuration LONGTEXT NOT NULL,
  created_by VARCHAR(128) NOT NULL, created_at DATETIME(6) NOT NULL, version BIGINT NULL,
  KEY idx_lab_experiments_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_experiences (
  id VARCHAR(36) NOT NULL PRIMARY KEY, experiment_id VARCHAR(36) NOT NULL,
  agent_id VARCHAR(36) NOT NULL, sequence_number INT NOT NULL, summary VARCHAR(1200) NOT NULL,
  previous_id VARCHAR(36) NULL, evidence LONGTEXT NOT NULL, created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_lab_experience_version (experiment_id, agent_id, sequence_number),
  KEY idx_lab_experiences_page (experiment_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_matches (
  id VARCHAR(36) NOT NULL PRIMARY KEY, experiment_id VARCHAR(36) NOT NULL,
  sequence_number INT NOT NULL, room_id VARCHAR(36) NOT NULL, archive_id VARCHAR(96) NULL,
  evaluation_id VARCHAR(36) NULL, variant VARCHAR(16) NULL, status VARCHAR(32) NOT NULL,
  reason VARCHAR(128) NULL, random_seed BIGINT NOT NULL, starts_at_epoch_ms BIGINT NOT NULL,
  review_lease_until BIGINT NULL, snapshot LONGTEXT NOT NULL, created_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL, version BIGINT NULL,
  UNIQUE KEY uk_lab_match_sequence (experiment_id, sequence_number),
  UNIQUE KEY uk_lab_match_room (room_id), KEY idx_lab_matches_active (status, experiment_id),
  KEY idx_lab_matches_evaluation (evaluation_id, sequence_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_enrollments (
  id VARCHAR(36) NOT NULL PRIMARY KEY, experiment_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL, display_name VARCHAR(64) NOT NULL, avatar VARCHAR(512) NULL,
  status VARCHAR(24) NOT NULL, match_id VARCHAR(36) NULL, confirm_until BIGINT NULL,
  joined_at DATETIME(6) NOT NULL, version BIGINT NULL,
  UNIQUE KEY uk_lab_enrollment_user (experiment_id, user_id),
  KEY idx_lab_enrollment_queue (experiment_id, status, joined_at), KEY idx_lab_enrollment_match (match_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_evaluations (
  id VARCHAR(36) NOT NULL PRIMARY KEY, experiment_id VARCHAR(36) NOT NULL,
  agent_id VARCHAR(36) NOT NULL, pairs INT NOT NULL, next_game INT NOT NULL, random_seed BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL, configuration LONGTEXT NOT NULL, created_at DATETIME(6) NOT NULL,
  version BIGINT NULL, KEY idx_lab_evaluations_experiment (experiment_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @lab_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rooms' AND column_name='source')=0,
  'ALTER TABLE rooms ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT ''NORMAL''', 'SELECT 1');
PREPARE lab_statement FROM @lab_ddl;
EXECUTE lab_statement;
DEALLOCATE PREPARE lab_statement;

SET @lab_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='game_archives' AND column_name='source')=0,
  'ALTER TABLE game_archives ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT ''NORMAL''', 'SELECT 1');
PREPARE lab_statement FROM @lab_ddl;
EXECUTE lab_statement;
DEALLOCATE PREPARE lab_statement;

SET @lab_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_credit_reservations' AND column_name='lab_experiment_id')=0,
  'ALTER TABLE ai_credit_reservations ADD COLUMN lab_experiment_id VARCHAR(36) NULL', 'SELECT 1');
PREPARE lab_statement FROM @lab_ddl;
EXECUTE lab_statement;
DEALLOCATE PREPARE lab_statement;

SET @lab_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_credit_reservations' AND column_name='lab_match_id')=0,
  'ALTER TABLE ai_credit_reservations ADD COLUMN lab_match_id VARCHAR(36) NULL', 'SELECT 1');
PREPARE lab_statement FROM @lab_ddl;
EXECUTE lab_statement;
DEALLOCATE PREPARE lab_statement;

SET @lab_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_credit_reservations' AND column_name='lab_purpose')=0,
  'ALTER TABLE ai_credit_reservations ADD COLUMN lab_purpose VARCHAR(24) NULL', 'SELECT 1');
PREPARE lab_statement FROM @lab_ddl;
EXECUTE lab_statement;
DEALLOCATE PREPARE lab_statement;

SET @lab_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_credit_reservations' AND column_name='billed_points')=0,
  'ALTER TABLE ai_credit_reservations ADD COLUMN billed_points BIGINT NULL', 'SELECT 1');
PREPARE lab_statement FROM @lab_ddl;
EXECUTE lab_statement;
DEALLOCATE PREPARE lab_statement;

SET @lab_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_credit_reservations' AND column_name='billed_cost')=0,
  'ALTER TABLE ai_credit_reservations ADD COLUMN billed_cost DECIMAL(24,6) NULL', 'SELECT 1');
PREPARE lab_statement FROM @lab_ddl;
EXECUTE lab_statement;
DEALLOCATE PREPARE lab_statement;

SET @lab_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='rooms' AND index_name='idx_rooms_source_status')=0,
  'ALTER TABLE rooms ADD INDEX idx_rooms_source_status (source,status)', 'SELECT 1');
PREPARE lab_statement FROM @lab_ddl;
EXECUTE lab_statement;
DEALLOCATE PREPARE lab_statement;

SET @lab_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='game_archives' AND index_name='idx_archives_source')=0,
  'ALTER TABLE game_archives ADD INDEX idx_archives_source (source,finished_at)', 'SELECT 1');
PREPARE lab_statement FROM @lab_ddl;
EXECUTE lab_statement;
DEALLOCATE PREPARE lab_statement;

SET @lab_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='ai_credit_reservations' AND index_name='idx_lab_credit_experiment')=0,
  'ALTER TABLE ai_credit_reservations ADD INDEX idx_lab_credit_experiment (lab_experiment_id,state)', 'SELECT 1');
PREPARE lab_statement FROM @lab_ddl;
EXECUTE lab_statement;
DEALLOCATE PREPARE lab_statement;
