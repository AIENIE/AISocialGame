-- Shared werewolf strategies and immutable administrator publication receipts.
-- Additive only. Existing participant experiences and approved text are unchanged.
CREATE TABLE IF NOT EXISTS lab_strategy_versions (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  experiment_id VARCHAR(36) NOT NULL, sequence_number INT NOT NULL,
  previous_id VARCHAR(36) NULL, game_id VARCHAR(64) NOT NULL, request_id VARCHAR(128) NOT NULL,
  content LONGTEXT NOT NULL, compatibility LONGTEXT NOT NULL, evidence LONGTEXT NOT NULL,
  content_hash VARCHAR(64) NOT NULL, created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_lab_strategy_sequence (experiment_id, sequence_number),
  UNIQUE KEY uk_lab_strategy_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS strategy_publications (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  operation_id VARCHAR(36) NOT NULL, request_hash VARCHAR(64) NOT NULL,
  action VARCHAR(16) NOT NULL, game_id VARCHAR(64) NOT NULL,
  strategy_version_id VARCHAR(36) NULL, mode VARCHAR(16) NOT NULL, created_by VARCHAR(128) NOT NULL,
  parameters LONGTEXT NOT NULL, previous_bindings LONGTEXT NOT NULL, result LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_strategy_publication_operation (operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @strategy_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_persona_memories' AND column_name='strategy_publication_id')=0,
  'ALTER TABLE ai_persona_memories ADD COLUMN strategy_publication_id VARCHAR(36) NULL', 'SELECT 1');
PREPARE strategy_statement FROM @strategy_ddl;
EXECUTE strategy_statement;
DEALLOCATE PREPARE strategy_statement;

-- Postconditions: both tables exist; the persona pointer is nullable VARCHAR(36).
-- No backfill, deletion, model call, or publication is performed by this migration.
