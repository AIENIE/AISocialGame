-- AISocialGame 全量表结构（v2）

CREATE TABLE IF NOT EXISTS `users` (
  `id` CHAR(36) NOT NULL,
  `external_user_id` BIGINT NULL,
  `username` VARCHAR(64) NULL,
  `email` VARCHAR(191) NOT NULL,
  `password` VARCHAR(255) NOT NULL,
  `session_id` VARCHAR(128) NULL,
  `access_token` VARCHAR(2048) NULL,
  `nickname` VARCHAR(64) NOT NULL,
  `avatar` VARCHAR(255) NULL,
  `coins` INT NOT NULL DEFAULT 0,
  `level` INT NOT NULL DEFAULT 1,
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_email` (`email`),
  UNIQUE KEY `uk_users_username` (`username`),
  UNIQUE KEY `uk_users_external_user_id` (`external_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rooms` (
  `id` CHAR(36) NOT NULL,
  `room_code` VARCHAR(6) NOT NULL,
  `waiting_since` DATETIME NULL,
  `expired_at` DATETIME NULL,
  `game_id` VARCHAR(64) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `max_players` INT NOT NULL,
  `is_private` TINYINT(1) NOT NULL DEFAULT 0,
  `password` VARCHAR(255) NULL,
  `comm_mode` VARCHAR(64) NULL,
  `config` LONGTEXT NULL,
  `host_user_id` VARCHAR(36) NULL,
  `private_config` LONGTEXT NULL,
  `seats` LONGTEXT NULL,
  `seat_count` INT NOT NULL DEFAULT 0,
  `version` BIGINT NULL,
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rooms_code` (`room_code`),
  KEY `idx_rooms_discovery` (`game_id`,`is_private`,`status`,`created_at`),
  KEY `idx_rooms_expiration` (`status`,`waiting_since`),
  KEY `idx_rooms_game` (`game_id`),
  KEY `idx_rooms_status` (`status`),
  KEY `idx_rooms_game_status_created` (`game_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `game_states` (
  `room_id` VARCHAR(64) NOT NULL,
  `game_id` VARCHAR(64) NOT NULL,
  `phase` VARCHAR(64) NOT NULL,
  `round_number` INT NOT NULL DEFAULT 1,
  `current_seat` INT NULL,
  `players` LONGTEXT NULL,
  `logs` LONGTEXT NULL,
  `data` LONGTEXT NULL,
  `version` BIGINT NOT NULL DEFAULT 0,
  `phase_ends_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  `created_at` DATETIME NULL,
  PRIMARY KEY (`room_id`),
  KEY `idx_state_game` (`game_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `community_posts` (
  `id` CHAR(36) NOT NULL,
  `author_name` VARCHAR(64) NOT NULL,
  `author_id` VARCHAR(36) NULL,
  `avatar` VARCHAR(255) NULL,
  `content` VARCHAR(1024) NOT NULL,
  `tags` LONGTEXT NULL,
  `likes` INT NOT NULL DEFAULT 0,
  `comments` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_posts_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `player_stats` (
  `id` VARCHAR(100) NOT NULL,
  `player_id` VARCHAR(36) NOT NULL,
  `game_id` VARCHAR(32) NOT NULL,
  `display_name` VARCHAR(64) NOT NULL,
  `avatar` VARCHAR(255) NULL,
  `games_played` INT NOT NULL DEFAULT 0,
  `wins` INT NOT NULL DEFAULT 0,
  `score` INT NOT NULL DEFAULT 0,
  `updated_at` DATETIME NULL,
  `created_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_stats_player` (`player_id`),
  KEY `idx_stats_game` (`game_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_persona_memories` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `persona_id` VARCHAR(64) NOT NULL,
  `game_id` VARCHAR(64) NOT NULL,
  `role_key` VARCHAR(64) NOT NULL,
  `memory_summary` LONGTEXT NULL,
  `strategy_notes` LONGTEXT NULL,
  `mistake_notes` LONGTEXT NULL,
  `speech_patterns` LONGTEXT NULL,
  `games_played` INT NOT NULL DEFAULT 0,
  `approved_summary` LONGTEXT NULL,
  `review_status` VARCHAR(24) NULL DEFAULT 'PENDING',
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_persona_memory_scope` (`persona_id`, `game_id`, `role_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_decision_traces` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `room_id` VARCHAR(64) NULL,
  `instance_id` VARCHAR(96) NULL,
  `game_id` VARCHAR(64) NOT NULL,
  `phase` VARCHAR(64) NULL,
  `round_number` INT NOT NULL DEFAULT 1,
  `action` VARCHAR(64) NOT NULL,
  `actor_player_id` VARCHAR(64) NULL,
  `persona_id` VARCHAR(64) NULL,
  `role_key` VARCHAR(64) NULL,
  `model_key` VARCHAR(128) NULL,
  `prompt_tokens` BIGINT NOT NULL DEFAULT 0,
  `completion_tokens` BIGINT NOT NULL DEFAULT 0,
  `latency_ms` BIGINT NOT NULL DEFAULT 0,
  `fallback` TINYINT(1) NOT NULL DEFAULT 0,
  `valid_decision` TINYINT(1) NOT NULL DEFAULT 1,
  `confidence` DOUBLE NULL,
  `target_player_id` VARCHAR(64) NULL,
  `night_action` VARCHAR(64) NULL,
  `reason` VARCHAR(512) NULL,
  `output_summary` VARCHAR(512) NULL,
  `input_summary` VARCHAR(1024) NULL,
  `belief_snapshot` LONGTEXT NULL,
  `memory_snapshot` LONGTEXT NULL,
  `quality` LONGTEXT NULL,
  `raw_output` LONGTEXT NULL,
  `created_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_trace_room_id` (`room_id`, `id`),
  KEY `idx_ai_trace_instance` (`instance_id`, `fallback`, `id`),
  KEY `idx_ai_trace_game_action` (`game_id`, `action`, `id`),
  KEY `idx_ai_trace_persona` (`persona_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_safety_events` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `source` VARCHAR(64) NOT NULL,
  `action` VARCHAR(32) NOT NULL,
  `severity` VARCHAR(32) NOT NULL,
  `category` VARCHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `room_id` VARCHAR(64) NULL,
  `game_id` VARCHAR(64) NULL,
  `user_id` VARCHAR(64) NULL,
  `player_id` VARCHAR(64) NULL,
  `persona_id` VARCHAR(64) NULL,
  `model_key` VARCHAR(128) NULL,
  `trace_id` VARCHAR(64) NULL,
  `content_summary` VARCHAR(512) NULL,
  `sanitized_content` VARCHAR(512) NULL,
  `reason` VARCHAR(255) NULL,
  `metadata` LONGTEXT NULL,
  `acknowledged_by` VARCHAR(64) NULL,
  `acknowledged_at` DATETIME NULL,
  `closed_by` VARCHAR(64) NULL,
  `closed_at` DATETIME NULL,
  `close_reason` VARCHAR(255) NULL,
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_safety_event_status_severity_id` (`status`, `severity`, `id`),
  KEY `idx_ai_safety_event_source_created` (`source`, `created_at`),
  KEY `idx_ai_safety_event_room` (`room_id`, `id`),
  KEY `idx_ai_safety_event_user` (`user_id`, `id`),
  KEY `idx_ai_safety_event_persona` (`persona_id`, `id`),
  KEY `idx_ai_safety_event_model` (`model_key`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_safety_controls` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `scope` VARCHAR(32) NOT NULL,
  `target_key` VARCHAR(128) NOT NULL,
  `action` VARCHAR(32) NOT NULL,
  `reason` VARCHAR(255) NULL,
  `created_by` VARCHAR(64) NULL,
  `active` TINYINT(1) NOT NULL DEFAULT 1,
  `expires_at` DATETIME NULL,
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_safety_control_active_scope` (`active`, `scope`, `target_key`),
  KEY `idx_ai_safety_control_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `game_events` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `archive_id` VARCHAR(96) NOT NULL,
  `room_id` VARCHAR(64) NOT NULL,
  `game_id` VARCHAR(64) NOT NULL,
  `seq` INT NOT NULL,
  `public_seq` BIGINT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `phase` VARCHAR(64) NULL,
  `round_number` INT NOT NULL DEFAULT 1,
  `actor_player_id` VARCHAR(64) NULL,
  `target_player_id` VARCHAR(64) NULL,
  `visibility` VARCHAR(32) NOT NULL,
  `visible_to_player_ids` LONGTEXT NULL,
  `data` LONGTEXT NULL,
  `occurred_at` DATETIME NULL,
  `created_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_game_events_archive_seq` (`archive_id`, `seq`),
  UNIQUE KEY `uk_game_events_archive_public_seq` (`archive_id`, `public_seq`),
  KEY `idx_game_events_room` (`room_id`, `id`),
  KEY `idx_game_events_game` (`game_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `game_archives` (
  `id` VARCHAR(96) NOT NULL,
  `room_id` VARCHAR(64) NOT NULL,
  `game_id` VARCHAR(64) NOT NULL,
  `room_name` VARCHAR(128) NOT NULL,
  `winner` VARCHAR(64) NULL,
  `player_count` INT NOT NULL DEFAULT 0,
  `total_rounds` INT NOT NULL DEFAULT 0,
  `duration_seconds` BIGINT NOT NULL DEFAULT 0,
  `event_count` BIGINT NOT NULL DEFAULT 0,
  `players_snapshot` LONGTEXT NULL,
  `ai_quality_summary` LONGTEXT NULL,
  `summary` VARCHAR(512) NULL,
  `started_at` DATETIME NULL,
  `finished_at` DATETIME NULL,
  `created_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_game_archives_game_finished` (`game_id`, `finished_at`),
  KEY `idx_game_archives_room` (`room_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_accounts` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `project_key` VARCHAR(64) NOT NULL,
  `temp_balance` BIGINT NOT NULL DEFAULT 0,
  `temp_expires_at` DATETIME NULL,
  `permanent_balance` BIGINT NOT NULL DEFAULT 0,
  `version` BIGINT NULL,
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credit_account_user_project` (`user_id`, `project_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_ledger_entries` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `request_id` VARCHAR(128) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `project_key` VARCHAR(64) NOT NULL,
  `type` VARCHAR(32) NOT NULL,
  `token_delta_temp` BIGINT NOT NULL DEFAULT 0,
  `token_delta_permanent` BIGINT NOT NULL DEFAULT 0,
  `token_delta_public` BIGINT NOT NULL DEFAULT 0,
  `balance_temp` BIGINT NOT NULL DEFAULT 0,
  `balance_permanent` BIGINT NOT NULL DEFAULT 0,
  `balance_public` BIGINT NOT NULL DEFAULT 0,
  `source` VARCHAR(64) NULL,
  `metadata_json` LONGTEXT NULL,
  `related_entry_id` BIGINT NULL,
  `created_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credit_ledger_request_id` (`request_id`),
  KEY `idx_credit_ledger_user_project_id` (`user_id`, `project_key`, `id`),
  KEY `idx_credit_ledger_related` (`related_entry_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_checkin_records` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `request_id` VARCHAR(128) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `project_key` VARCHAR(64) NOT NULL,
  `checkin_date` DATE NOT NULL,
  `tokens_granted` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credit_checkin_request_id` (`request_id`),
  UNIQUE KEY `uk_credit_checkin_user_project_date` (`user_id`, `project_key`, `checkin_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_redeem_codes` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(64) NOT NULL,
  `credit_type` VARCHAR(32) NOT NULL,
  `tokens` BIGINT NOT NULL,
  `active` TINYINT(1) NOT NULL DEFAULT 1,
  `valid_from` DATETIME NULL,
  `valid_until` DATETIME NULL,
  `max_redemptions` INT NULL,
  `redeemed_count` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credit_redeem_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_redemption_records` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `request_id` VARCHAR(128) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `project_key` VARCHAR(64) NOT NULL,
  `code` VARCHAR(64) NOT NULL,
  `tokens_granted` BIGINT NOT NULL DEFAULT 0,
  `credit_type` VARCHAR(32) NOT NULL,
  `success` TINYINT(1) NOT NULL DEFAULT 0,
  `error_message` VARCHAR(255) NULL,
  `redeemed_at` DATETIME NULL,
  `created_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_credit_redeem_user_project_id` (`user_id`, `project_key`, `id`),
  KEY `idx_credit_redeem_user_project_code_success` (`user_id`, `project_key`, `code`, `success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_exchange_transactions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `request_id` VARCHAR(128) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `project_key` VARCHAR(64) NOT NULL,
  `public_tokens` BIGINT NOT NULL,
  `project_tokens` BIGINT NOT NULL,
  `public_before` BIGINT NOT NULL DEFAULT 0,
  `public_after` BIGINT NOT NULL DEFAULT 0,
  `project_permanent_before` BIGINT NOT NULL DEFAULT 0,
  `project_permanent_after` BIGINT NOT NULL DEFAULT 0,
  `status` VARCHAR(16) NOT NULL,
  `fail_reason` VARCHAR(255) NULL,
  `retriable` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credit_exchange_request_id` (`request_id`),
  KEY `idx_credit_exchange_user_project_status_created` (`user_id`, `project_key`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- Administrator password-first TOTP, restricted recovery and one-use operation proof state.
CREATE TABLE IF NOT EXISTS admin_totp_credentials (
  subject_id VARCHAR(128) NOT NULL,
  encrypted_secret TEXT NOT NULL,
  nonce VARBINARY(32) NOT NULL,
  key_version VARCHAR(64) NOT NULL,
  algorithm VARCHAR(32) NOT NULL,
  digits INT NOT NULL,
  period_seconds INT NOT NULL,
  last_accepted_timestep BIGINT NULL,
  credential_version BIGINT NOT NULL DEFAULT 1,
  enabled_at DATETIME(6) NOT NULL,
  PRIMARY KEY (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_auth_challenges (
  challenge_hash CHAR(64) NOT NULL,
  subject_id VARCHAR(128) NOT NULL,
  purpose VARCHAR(32) NOT NULL,
  session_hash CHAR(64) NULL,
  password_authenticated_at DATETIME(6) NULL,
  encrypted_secret TEXT NULL,
  nonce VARBINARY(32) NULL,
  key_version VARCHAR(64) NULL,
  expires_at DATETIME(6) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  consumed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (challenge_hash),
  KEY idx_admin_auth_challenge_subject (subject_id, purpose, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_recovery_codes (
  id BIGINT NOT NULL AUTO_INCREMENT,
  subject_id VARCHAR(128) NOT NULL,
  code_hash VARCHAR(255) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  used_at DATETIME(6) NULL,
  replaced_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  KEY idx_admin_recovery_subject (subject_id, used_at, replaced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_sessions (
  session_hash CHAR(64) NOT NULL,
  subject_id VARCHAR(128) NOT NULL,
  scope VARCHAR(32) NOT NULL,
  environment VARCHAR(32) NOT NULL,
  auth_mode VARCHAR(32) NOT NULL,
  assurance VARCHAR(32) NOT NULL,
  password_authenticated_at DATETIME(6) NULL,
  totp_authenticated_at DATETIME(6) NULL,
  credential_version BIGINT NOT NULL,
  password_credential_hash CHAR(64) NOT NULL,
  issued_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  idle_expires_at DATETIME(6) NOT NULL,
  last_seen_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  PRIMARY KEY (session_hash),
  KEY idx_admin_sessions_subject (subject_id, revoked_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_operation_challenges (
  challenge_hash CHAR(64) NOT NULL,
  subject_id VARCHAR(128) NOT NULL,
  session_hash CHAR(64) NOT NULL,
  action_key VARCHAR(255) NOT NULL,
  target_id VARCHAR(255) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  consumed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (challenge_hash),
  KEY idx_admin_operation_challenge_session (session_hash, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_operation_proofs (
  proof_hash CHAR(64) NOT NULL,
  subject_id VARCHAR(128) NOT NULL,
  session_hash CHAR(64) NOT NULL,
  action_key VARCHAR(255) NOT NULL,
  target_id VARCHAR(255) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  consumed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (proof_hash),
  KEY idx_admin_operation_proof_session (session_hash, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_auth_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_type VARCHAR(64) NOT NULL,
  subject_id VARCHAR(128) NULL,
  session_hash CHAR(64) NULL,
  source VARCHAR(128) NULL,
  result VARCHAR(32) NOT NULL,
  reason_code VARCHAR(128) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_admin_auth_audit_subject (subject_id, created_at),
  KEY idx_admin_auth_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `ai_turn_jobs` (
  `id` VARCHAR(64) NOT NULL,
  `room_id` VARCHAR(64) NOT NULL,
  `instance_id` VARCHAR(96) NOT NULL,
  `actor_id` VARCHAR(64) NOT NULL,
  `kind` VARCHAR(40) NOT NULL,
  `turn_key` VARCHAR(256) NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  `observation` LONGTEXT NULL,
  `diagnostics` LONGTEXT NULL,
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

-- Additive, repeatable. Run after 20260919_ai_turn_diagnostics.sql. No history rewrite.
CREATE TABLE IF NOT EXISTS ai_call_usage (
 id VARCHAR(128) NOT NULL PRIMARY KEY,
 source VARCHAR(64), user_id VARCHAR(64), room_id VARCHAR(64), persona_id VARCHAR(64), model_key VARCHAR(128),
 started_epoch_ms BIGINT NOT NULL, completed_epoch_ms BIGINT NULL,
 outcome VARCHAR(32), admitted BOOLEAN NOT NULL DEFAULT FALSE,
 prompt_tokens BIGINT NULL, completion_tokens BIGINT NULL, anomaly BOOLEAN NOT NULL DEFAULT FALSE, anomaly_scopes VARCHAR(128) NULL,
 INDEX idx_call_usage_time (started_epoch_ms)
);

CREATE TABLE IF NOT EXISTS ai_model_catalog (
  id BIGINT NOT NULL PRIMARY KEY,
  models_json LONGTEXT NOT NULL,
  synced_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO ai_model_catalog (id, models_json, version)
SELECT 1, '[]', 0 WHERE NOT EXISTS (SELECT 1 FROM ai_model_catalog WHERE id = 1);

CREATE TABLE IF NOT EXISTS ai_function_model_configs (
  function_code VARCHAR(40) NOT NULL PRIMARY KEY,
  model_key VARCHAR(128) NULL,
  thinking_mode VARCHAR(16) NOT NULL,
  thinking_effort VARCHAR(16) NULL,
  updated_at DATETIME(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Local-only diagnostic payloads. Additive and repeatable; existing history is untouched.
CREATE TABLE IF NOT EXISTS local_ai_debug_turns (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  room_id VARCHAR(64) NOT NULL,
  game_id VARCHAR(64) NOT NULL,
  instance_id VARCHAR(96) NOT NULL,
  actor_id VARCHAR(64) NOT NULL,
  actor_name VARCHAR(128) NULL,
  seat_number INT NULL,
  role_key VARCHAR(64) NULL,
  phase VARCHAR(64) NULL,
  round_number INT NOT NULL,
  kind VARCHAR(40) NULL,
  status VARCHAR(24) NULL,
  terminal_reason VARCHAR(128) NULL,
  model_key VARCHAR(128) NULL,
  prompt_tokens BIGINT NULL,
  completion_tokens BIGINT NULL,
  cache_read_tokens BIGINT NULL,
  cache_write_tokens BIGINT NULL,
  reasoning_tokens BIGINT NULL,
  usage_authoritative BOOLEAN NULL,
  usage_complete BOOLEAN NOT NULL DEFAULT FALSE,
  attempt_count INT NOT NULL DEFAULT 0,
  response_count INT NOT NULL DEFAULT 0,
  latency_ms BIGINT NULL,
  created_at DATETIME(6) NULL,
  updated_at DATETIME(6) NULL,
  payload LONGTEXT NOT NULL,
  version BIGINT NULL,
  KEY idx_local_ai_debug_instance (room_id, instance_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_auth_subject_locks (
 subject_id VARCHAR(128) PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_emergency_codes (
 record_id CHAR(36) PRIMARY KEY,
 subject_id VARCHAR(128) NOT NULL,
 code_hash VARCHAR(255) NOT NULL,
 ciphertext TEXT NOT NULL,
 nonce VARBINARY(32) NOT NULL,
 key_version VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 used_at DATETIME(6) NULL,
 KEY idx_admin_emergency_subject(subject_id,used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_emergency_challenge_versions (
 challenge_hash CHAR(64) PRIMARY KEY,
 credential_version BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_emergency_upgrade (
 version_id INT PRIMARY KEY,
 completed_at DATETIME(6) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ai_credit_reservations (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  identity_hash VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  project_key VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  budget_id VARCHAR(36) NOT NULL,
  state VARCHAR(32) NOT NULL,
  reserved_temp BIGINT NOT NULL,
  reserved_permanent BIGINT NOT NULL,
  temp_portions_json TEXT NOT NULL,
  updated_at BIGINT NOT NULL,
  result_base64 LONGTEXT NULL,
  billing_quote_base64 LONGTEXT NULL,
  UNIQUE KEY uk_ai_credit_identity (identity_hash),
  KEY idx_ai_credit_reconcile (state, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

-- Resumable, evidence-bound werewolf lessons. Additive only; no games, calls or strategies are created.
CREATE TABLE IF NOT EXISTS lab_training_runs (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  experiment_id VARCHAR(36) NOT NULL, operation_id VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL, goal VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL, stage VARCHAR(24) NOT NULL, reason VARCHAR(128) NULL,
  candidate_strategy_version_id VARCHAR(36) NULL,
  configuration LONGTEXT NOT NULL, assessment LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL, updated_at_epoch_ms BIGINT NOT NULL, version BIGINT NULL,
  UNIQUE KEY uk_lab_training_operation (experiment_id, operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_training_cases (
  id VARCHAR(36) NOT NULL PRIMARY KEY, run_id VARCHAR(36) NOT NULL,
  purpose VARCHAR(24) NOT NULL, source_type VARCHAR(48) NOT NULL, source_group VARCHAR(200) NOT NULL,
  persona_id VARCHAR(64) NOT NULL, participant_id VARCHAR(36) NOT NULL, ordinal INT NOT NULL,
  content_hash VARCHAR(64) NOT NULL, data LONGTEXT NOT NULL, created_at DATETIME(6) NOT NULL,
  KEY idx_lab_training_case_run (run_id, purpose, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_training_tasks (
  id VARCHAR(36) NOT NULL PRIMARY KEY, run_id VARCHAR(36) NOT NULL, case_id VARCHAR(36) NULL,
  task_key VARCHAR(128) NOT NULL, kind VARCHAR(24) NOT NULL, variant VARCHAR(24) NULL,
  status VARCHAR(24) NOT NULL, ordinal INT NOT NULL, reason VARCHAR(128) NULL,
  request_id VARCHAR(128) NULL, attempt INT NOT NULL, paid_retries INT NOT NULL,
  started_at_epoch_ms BIGINT NULL, updated_at_epoch_ms BIGINT NOT NULL,
  not_before_epoch_ms BIGINT NOT NULL, lease_until_epoch_ms BIGINT NOT NULL,
  input LONGTEXT NOT NULL, checkpoint LONGTEXT NOT NULL, result LONGTEXT NOT NULL, version BIGINT NULL,
  UNIQUE KEY uk_lab_training_task_identity (run_id, task_key),
  KEY idx_lab_training_task_run (run_id, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Postconditions: three new tables with immutable case content, operation/task identities and query indexes.
-- Existing experiment, participant, wallet and published-strategy records are unchanged.
