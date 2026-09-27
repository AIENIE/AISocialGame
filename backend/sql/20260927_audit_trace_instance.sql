-- Unknown historical instance ownership remains NULL; only an explicit recorded ID is backfilled.
SET @trace_instance_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='ai_decision_traces' AND column_name='instance_id')=0,
  'ALTER TABLE ai_decision_traces ADD COLUMN instance_id VARCHAR(96) NULL AFTER room_id', 'SELECT 1');
PREPARE trace_instance_statement FROM @trace_instance_ddl;
EXECUTE trace_instance_statement;
DEALLOCATE PREPARE trace_instance_statement;
SET @trace_instance_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='ai_decision_traces' AND index_name='idx_ai_trace_instance')=0,
  'CREATE INDEX idx_ai_trace_instance ON ai_decision_traces(instance_id, fallback, id)', 'SELECT 1');
PREPARE trace_instance_statement FROM @trace_instance_ddl;
EXECUTE trace_instance_statement;
DEALLOCATE PREPARE trace_instance_statement;
UPDATE ai_decision_traces SET instance_id = CASE
  WHEN JSON_VALID(quality) THEN CASE
    WHEN JSON_TYPE(JSON_EXTRACT(quality, '$.instanceId')) = 'STRING'
         AND CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(quality, '$.instanceId'))) BETWEEN 1 AND 96
      THEN JSON_UNQUOTE(JSON_EXTRACT(quality, '$.instanceId'))
    ELSE NULL END
  ELSE NULL END
WHERE instance_id IS NULL;
