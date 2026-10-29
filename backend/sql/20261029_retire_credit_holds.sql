-- Retire unused escrow allocations. No balance, temporary lot or call identity changes.
SET @cleanup_drop_columns = (
  SELECT GROUP_CONCAT(CONCAT('DROP COLUMN `', COLUMN_NAME, '`') ORDER BY ORDINAL_POSITION SEPARATOR ', ')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_credit_reservations'
    AND COLUMN_NAME IN ('reserved_temp', 'reserved_permanent', 'temp_portions_json')
);
SET @cleanup_ddl = IF(@cleanup_drop_columns IS NULL, 'SELECT 1',
  CONCAT('ALTER TABLE ai_credit_reservations ', @cleanup_drop_columns));
PREPARE cleanup_statement FROM @cleanup_ddl;
EXECUTE cleanup_statement;
DEALLOCATE PREPARE cleanup_statement;
