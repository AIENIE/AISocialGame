-- AISocialGame rules v2. Additive, repeatable MySQL migration; run before enabling v2.
-- Existing games and legacy memory content are preserved. No experience is auto-approved.

SET @v2_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rooms' AND column_name = 'host_user_id') = 0,
  'ALTER TABLE `rooms` ADD COLUMN `host_user_id` VARCHAR(36) NULL', 'SELECT 1');
PREPARE v2_statement FROM @v2_ddl;
EXECUTE v2_statement;
DEALLOCATE PREPARE v2_statement;

SET @v2_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rooms' AND column_name = 'private_config') = 0,
  'ALTER TABLE `rooms` ADD COLUMN `private_config` LONGTEXT NULL', 'SELECT 1');
PREPARE v2_statement FROM @v2_ddl;
EXECUTE v2_statement;
DEALLOCATE PREPARE v2_statement;

SET @v2_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'game_states' AND column_name = 'version') = 0,
  'ALTER TABLE `game_states` ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE v2_statement FROM @v2_ddl;
EXECUTE v2_statement;
DEALLOCATE PREPARE v2_statement;

SET @v2_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_persona_memories' AND column_name = 'approved_summary') = 0,
  'ALTER TABLE `ai_persona_memories` ADD COLUMN `approved_summary` LONGTEXT NULL', 'SELECT 1');
PREPARE v2_statement FROM @v2_ddl;
EXECUTE v2_statement;
DEALLOCATE PREPARE v2_statement;

SET @v2_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_persona_memories' AND column_name = 'review_status') = 0,
  'ALTER TABLE `ai_persona_memories` ADD COLUMN `review_status` VARCHAR(24) NULL DEFAULT ''PENDING''', 'SELECT 1');
PREPARE v2_statement FROM @v2_ddl;
EXECUTE v2_statement;
DEALLOCATE PREPARE v2_statement;

-- Backfill explicit host authority from the existing host seat. Invalid legacy JSON is ignored.
UPDATE `rooms` r
JOIN JSON_TABLE(IF(JSON_VALID(r.seats), r.seats, '[]'), '$[*]' COLUMNS (
  player_id VARCHAR(36) PATH '$.playerId', is_host BOOLEAN PATH '$.host'
)) s ON s.is_host = TRUE
SET r.host_user_id = s.player_id
WHERE r.host_user_id IS NULL;

CREATE TABLE IF NOT EXISTS `ai_turn_jobs` (
  `id` VARCHAR(64) NOT NULL,
  `room_id` VARCHAR(64) NOT NULL,
  `instance_id` VARCHAR(96) NOT NULL,
  `actor_id` VARCHAR(64) NOT NULL,
  `kind` VARCHAR(40) NOT NULL,
  `turn_key` VARCHAR(256) NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  `observation` LONGTEXT NULL,
  `created_at` DATETIME NULL,
  `started_at` DATETIME NULL,
  `completed_at` DATETIME NULL,
  `version` BIGINT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_turn_job_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `ai_call_budgets` (
  `id` VARCHAR(96) NOT NULL,
  `consumed` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

\n