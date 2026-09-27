-- Additive, repeatable. Run after 20260919_ai_turn_diagnostics.sql. No history rewrite.
CREATE TABLE IF NOT EXISTS ai_call_usage (
 id VARCHAR(128) NOT NULL PRIMARY KEY,
 source VARCHAR(64), user_id VARCHAR(64), room_id VARCHAR(64), persona_id VARCHAR(64), model_key VARCHAR(128),
 started_epoch_ms BIGINT NOT NULL, completed_epoch_ms BIGINT NULL,
 outcome VARCHAR(32), admitted BOOLEAN NOT NULL DEFAULT FALSE,
 prompt_tokens BIGINT NULL, completion_tokens BIGINT NULL, anomaly BOOLEAN NOT NULL DEFAULT FALSE, anomaly_scopes VARCHAR(128) NULL,
 INDEX idx_call_usage_time (started_epoch_ms)
);
