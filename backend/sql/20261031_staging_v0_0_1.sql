-- Supplement the frozen room lifecycle and emergency-code upgrades for staging 0.0.1.
-- Execute after schema.sql and 20261008_room_lifecycle.sql. Historical migrations stay frozen.
SET @staging_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_persona_memories' AND column_name='strategy_publication_id')=0, 'ALTER TABLE `ai_persona_memories` ADD COLUMN `strategy_publication_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL', 'SELECT 1');
PREPARE staging_statement FROM @staging_ddl;
EXECUTE staging_statement;
DEALLOCATE PREPARE staging_statement;
SET @staging_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='game_archives' AND column_name='source')=0, 'ALTER TABLE `game_archives` ADD COLUMN `source` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''NORMAL''', 'SELECT 1');
PREPARE staging_statement FROM @staging_ddl;
EXECUTE staging_statement;
DEALLOCATE PREPARE staging_statement;
SET @staging_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rooms' AND column_name='source')=0, 'ALTER TABLE `rooms` ADD COLUMN `source` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT ''NORMAL''', 'SELECT 1');
PREPARE staging_statement FROM @staging_ddl;
EXECUTE staging_statement;
DEALLOCATE PREPARE staging_statement;
SET @staging_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='game_archives' AND index_name='idx_archives_source')=0, 'ALTER TABLE `game_archives` ADD KEY `idx_archives_source` (`source`,`finished_at`)', 'SELECT 1');
PREPARE staging_statement FROM @staging_ddl;
EXECUTE staging_statement;
DEALLOCATE PREPARE staging_statement;
SET @staging_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='rooms' AND index_name='idx_rooms_source_status')=0, 'ALTER TABLE `rooms` ADD KEY `idx_rooms_source_status` (`source`,`status`)', 'SELECT 1');
PREPARE staging_statement FROM @staging_ddl;
EXECUTE staging_statement;
DEALLOCATE PREPARE staging_statement;
