-- Existing rows retain NULL and their original token-accounting receipts.
SET @ai_billing_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='ai_credit_reservations' AND column_name='billing_quote_base64')=0,
  'ALTER TABLE ai_credit_reservations ADD COLUMN billing_quote_base64 LONGTEXT NULL', 'SELECT 1');
PREPARE ai_billing_statement FROM @ai_billing_ddl;
EXECUTE ai_billing_statement;
DEALLOCATE PREPARE ai_billing_statement;
