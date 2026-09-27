-- Additive and repeatable; prepare only during M1-R4. No legacy rows are rewritten.
SET @diagnostics_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ai_turn_jobs' AND column_name = 'diagnostics') = 0,
  'ALTER TABLE `ai_turn_jobs` ADD COLUMN `diagnostics` LONGTEXT NULL', 'SELECT 1');
PREPARE diagnostics_statement FROM @diagnostics_ddl;
EXECUTE diagnostics_statement;
DEALLOCATE PREPARE diagnostics_statement;
