SET @lab_participant_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lab_experiences' AND column_name='participant_id')=0,
  'ALTER TABLE lab_experiences ADD COLUMN participant_id VARCHAR(36) NULL', 'SELECT 1');
PREPARE lab_participant_statement FROM @lab_participant_ddl;
EXECUTE lab_participant_statement;
DEALLOCATE PREPARE lab_participant_statement;

SET @lab_participant_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lab_evaluations' AND column_name='participant_id')=0,
  'ALTER TABLE lab_evaluations ADD COLUMN participant_id VARCHAR(36) NULL', 'SELECT 1');
PREPARE lab_participant_statement FROM @lab_participant_ddl;
EXECUTE lab_participant_statement;
DEALLOCATE PREPARE lab_participant_statement;

UPDATE lab_experiences SET participant_id=agent_id WHERE participant_id IS NULL;
UPDATE lab_evaluations SET participant_id=agent_id WHERE participant_id IS NULL;
ALTER TABLE lab_experiences MODIFY COLUMN participant_id VARCHAR(36) NOT NULL;
ALTER TABLE lab_evaluations MODIFY COLUMN participant_id VARCHAR(36) NOT NULL;

SET @lab_participant_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lab_experiences' AND index_name='uk_lab_participant_version')=0,
  'ALTER TABLE lab_experiences ADD UNIQUE INDEX uk_lab_participant_version (experiment_id,participant_id,sequence_number)', 'SELECT 1');
PREPARE lab_participant_statement FROM @lab_participant_ddl;
EXECUTE lab_participant_statement;
DEALLOCATE PREPARE lab_participant_statement;

SET @lab_participant_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lab_experiences' AND index_name='uk_lab_experience_version')>0,
  'ALTER TABLE lab_experiences DROP INDEX uk_lab_experience_version', 'SELECT 1');
PREPARE lab_participant_statement FROM @lab_participant_ddl;
EXECUTE lab_participant_statement;
DEALLOCATE PREPARE lab_participant_statement;
