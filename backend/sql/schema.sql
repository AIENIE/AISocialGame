-- AISocialGame current schema snapshot. Frozen historical migration sources remain in migrations.json.
-- Structure only: no historical data, allocated sequence values or compatibility backfills.

CREATE TABLE IF NOT EXISTS `admin_auth_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_type` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `subject_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `session_hash` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `result` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reason_code` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_admin_auth_audit_subject` (`subject_id`,`created_at`),
  KEY `idx_admin_auth_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `admin_auth_challenges` (
  `challenge_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `subject_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `purpose` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `session_hash` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `password_authenticated_at` datetime(6) DEFAULT NULL,
  `encrypted_secret` text COLLATE utf8mb4_unicode_ci,
  `nonce` varbinary(32) DEFAULT NULL,
  `key_version` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  `attempt_count` int NOT NULL DEFAULT '0',
  `consumed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`challenge_hash`),
  KEY `idx_admin_auth_challenge_subject` (`subject_id`,`purpose`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `admin_auth_subject_locks` (
  `subject_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`subject_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `admin_emergency_challenge_versions` (
  `challenge_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `credential_version` bigint NOT NULL,
  PRIMARY KEY (`challenge_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `admin_emergency_codes` (
  `record_id` char(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `subject_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `code_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `ciphertext` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `nonce` varbinary(32) NOT NULL,
  `key_version` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`record_id`),
  KEY `idx_admin_emergency_subject` (`subject_id`,`used_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `admin_emergency_upgrade` (
  `version_id` int NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `admin_operation_challenges` (
  `challenge_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `subject_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `session_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `action_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `attempt_count` int NOT NULL DEFAULT '0',
  `consumed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`challenge_hash`),
  KEY `idx_admin_operation_challenge_session` (`session_hash`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `admin_operation_proofs` (
  `proof_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `subject_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `session_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `action_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `consumed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`proof_hash`),
  KEY `idx_admin_operation_proof_session` (`session_hash`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `admin_recovery_codes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `subject_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `code_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `replaced_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_admin_recovery_subject` (`subject_id`,`used_at`,`replaced_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `admin_sessions` (
  `session_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `subject_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `scope` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `environment` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `auth_mode` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `assurance` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password_authenticated_at` datetime(6) DEFAULT NULL,
  `totp_authenticated_at` datetime(6) DEFAULT NULL,
  `credential_version` bigint NOT NULL,
  `password_credential_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `issued_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `idle_expires_at` datetime(6) NOT NULL,
  `last_seen_at` datetime(6) NOT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`session_hash`),
  KEY `idx_admin_sessions_subject` (`subject_id`,`revoked_at`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `admin_totp_credentials` (
  `subject_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `encrypted_secret` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `nonce` varbinary(32) NOT NULL,
  `key_version` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `algorithm` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `digits` int NOT NULL,
  `period_seconds` int NOT NULL,
  `last_accepted_timestep` bigint DEFAULT NULL,
  `credential_version` bigint NOT NULL DEFAULT '1',
  `enabled_at` datetime(6) NOT NULL,
  PRIMARY KEY (`subject_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_call_budgets` (
  `id` varchar(96) COLLATE utf8mb4_unicode_ci NOT NULL,
  `consumed` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_call_usage` (
  `id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `room_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `persona_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `model_key` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `started_epoch_ms` bigint NOT NULL,
  `completed_epoch_ms` bigint DEFAULT NULL,
  `outcome` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `admitted` tinyint(1) NOT NULL DEFAULT '0',
  `prompt_tokens` bigint DEFAULT NULL,
  `completion_tokens` bigint DEFAULT NULL,
  `anomaly` tinyint(1) NOT NULL DEFAULT '0',
  `anomaly_scopes` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_call_usage_time` (`started_epoch_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_credit_reservations` (
  `id` varchar(36) NOT NULL,
  `identity_hash` varchar(64) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `request_id` varchar(128) NOT NULL,
  `project_key` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `budget_id` varchar(36) NOT NULL,
  `state` varchar(32) NOT NULL,
  `updated_at` bigint NOT NULL,
  `result_base64` longtext,
  `billing_quote_base64` longtext,
  `lab_experiment_id` varchar(36) DEFAULT NULL,
  `lab_match_id` varchar(36) DEFAULT NULL,
  `lab_purpose` varchar(24) DEFAULT NULL,
  `billed_points` bigint DEFAULT NULL,
  `billed_cost` decimal(24,6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_credit_identity` (`identity_hash`),
  KEY `idx_ai_credit_reconcile` (`state`,`updated_at`),
  KEY `idx_lab_credit_experiment` (`lab_experiment_id`,`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `ai_decision_traces` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor_player_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `belief_snapshot` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `completion_tokens` bigint NOT NULL,
  `confidence` double DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `fallback` bit(1) NOT NULL,
  `game_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `input_summary` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `latency_ms` bigint NOT NULL,
  `memory_snapshot` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `model_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `night_action` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `output_summary` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `persona_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `phase` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `prompt_tokens` bigint NOT NULL,
  `quality` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `raw_output` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `reason` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `role_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `room_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `instance_id` varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `round_number` int NOT NULL,
  `target_player_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `valid_decision` bit(1) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_trace_instance` (`instance_id`,`fallback`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_function_model_configs` (
  `function_code` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `model_key` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `thinking_mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `thinking_effort` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`function_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_model_catalog` (
  `id` bigint NOT NULL,
  `models_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `synced_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_persona_memories` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `game_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `games_played` int NOT NULL,
  `memory_summary` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `mistake_notes` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `persona_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `role_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `speech_patterns` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `strategy_notes` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `updated_at` datetime(6) DEFAULT NULL,
  `approved_summary` longtext COLLATE utf8mb4_unicode_ci,
  `review_status` varchar(24) COLLATE utf8mb4_unicode_ci DEFAULT 'PENDING',
  `strategy_publication_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_persona_memory_scope` (`persona_id`,`game_id`,`role_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_safety_controls` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `active` bit(1) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `created_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `scope` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_safety_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `acknowledged_at` datetime(6) DEFAULT NULL,
  `acknowledged_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `action` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `close_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  `closed_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `content_summary` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `game_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `metadata` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `model_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `persona_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `player_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `room_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sanitized_content` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `severity` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `source` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_turn_jobs` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `room_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `instance_id` varchar(96) COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `kind` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `turn_key` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'QUEUED',
  `observation` longtext COLLATE utf8mb4_unicode_ci,
  `created_at` datetime DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `version` bigint DEFAULT NULL,
  `diagnostics` longtext COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_ai_turn_job_status` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `archive_participants` (
  `archive_id` varchar(96) COLLATE utf8mb4_unicode_ci NOT NULL,
  `player_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`archive_id`,`player_id`),
  KEY `idx_archive_participant_player` (`player_id`,`archive_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `community_likes` (
  `post_id` char(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` char(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`post_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `community_posts` (
  `id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `author_id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `author_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `comments` int NOT NULL,
  `content` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `likes` int NOT NULL,
  `tags` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `permanent_balance` bigint NOT NULL,
  `project_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `temp_balance` bigint NOT NULL,
  `temp_expires_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credit_account_user_project` (`user_id`,`project_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_checkin_records` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `checkin_date` date NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `project_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `request_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `tokens_granted` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credit_checkin_user_project_date` (`user_id`,`project_key`,`checkin_date`),
  UNIQUE KEY `uk_credit_checkin_request_id` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_exchange_transactions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `fail_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `project_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `project_permanent_after` bigint NOT NULL,
  `project_permanent_before` bigint NOT NULL,
  `project_tokens` bigint NOT NULL,
  `public_after` bigint NOT NULL,
  `public_before` bigint NOT NULL,
  `public_tokens` bigint NOT NULL,
  `request_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `retriable` bit(1) NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKf49am2nrchynw75eyncag5lnc` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_ledger_entries` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `balance_permanent` bigint NOT NULL,
  `balance_public` bigint NOT NULL,
  `balance_temp` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `metadata_json` tinytext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `project_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `related_entry_id` bigint DEFAULT NULL,
  `request_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `source` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `token_delta_permanent` bigint NOT NULL,
  `token_delta_public` bigint NOT NULL,
  `token_delta_temp` bigint NOT NULL,
  `type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKn1w1779sy2gq1f3mxxg87g6da` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_redeem_codes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `credit_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `max_redemptions` int DEFAULT NULL,
  `redeemed_count` int NOT NULL,
  `tokens` bigint NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `valid_from` datetime(6) DEFAULT NULL,
  `valid_until` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK9yvcuqq1ngw90cr66jmhqkasw` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_redemption_records` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `credit_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `error_message` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `project_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `redeemed_at` datetime(6) DEFAULT NULL,
  `request_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `success` bit(1) NOT NULL,
  `tokens_granted` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `credit_temp_lots` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `project_key` varchar(64) NOT NULL,
  `remaining` bigint NOT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_temp_lot_owner` (`user_id`,`project_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `game_archives` (
  `id` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `ai_quality_summary` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(6) DEFAULT NULL,
  `duration_seconds` bigint NOT NULL,
  `event_count` bigint NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `game_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `player_count` int NOT NULL,
  `players_snapshot` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `room_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `room_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `started_at` datetime(6) DEFAULT NULL,
  `summary` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `total_rounds` int NOT NULL,
  `winner` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `public_replay` tinyint(1) DEFAULT NULL,
  `host_user_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NORMAL',
  PRIMARY KEY (`id`),
  KEY `idx_archives_source` (`source`,`finished_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `game_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `actor_player_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `archive_id` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `event_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `game_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `occurred_at` datetime(6) DEFAULT NULL,
  `phase` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `room_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `round_number` int NOT NULL,
  `seq` int NOT NULL,
  `public_seq` bigint DEFAULT NULL,
  `target_player_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `visibility` enum('GOD','PRIVATE','PUBLIC') COLLATE utf8mb4_unicode_ci NOT NULL,
  `visible_to_player_ids` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_game_events_archive_public_seq` (`archive_id`,`public_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `game_states` (
  `room_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `current_seat` int DEFAULT NULL,
  `data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `game_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `logs` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `phase` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `phase_ends_at` datetime(6) DEFAULT NULL,
  `players` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `round_number` int NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`room_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lab_agents` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `persona_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `model_selection` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lab_enrollments` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `experiment_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `display_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `avatar` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `match_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `confirm_until` bigint DEFAULT NULL,
  `joined_at` datetime(6) NOT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lab_enrollment_user` (`experiment_id`,`user_id`),
  KEY `idx_lab_enrollment_queue` (`experiment_id`,`status`,`joined_at`),
  KEY `idx_lab_enrollment_match` (`match_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lab_evaluations` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `experiment_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `agent_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `pairs` int NOT NULL,
  `next_game` int NOT NULL,
  `random_seed` bigint NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `configuration` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `version` bigint DEFAULT NULL,
  `participant_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_lab_evaluations_experiment` (`experiment_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lab_experiences` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `experiment_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `agent_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sequence_number` int NOT NULL,
  `summary` varchar(1200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `previous_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `evidence` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `participant_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lab_participant_version` (`experiment_id`,`participant_id`,`sequence_number`),
  KEY `idx_lab_experiences_page` (`experiment_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lab_experiments` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reason` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `budget_points` bigint NOT NULL,
  `next_sequence` int NOT NULL,
  `review_cursor` int NOT NULL,
  `configuration` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_by` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_lab_experiments_status` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lab_matches` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `experiment_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sequence_number` int NOT NULL,
  `room_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `archive_id` varchar(96) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `evaluation_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `variant` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reason` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `random_seed` bigint NOT NULL,
  `starts_at_epoch_ms` bigint NOT NULL,
  `review_lease_until` bigint DEFAULT NULL,
  `snapshot` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lab_match_sequence` (`experiment_id`,`sequence_number`),
  UNIQUE KEY `uk_lab_match_room` (`room_id`),
  KEY `idx_lab_matches_active` (`status`,`experiment_id`),
  KEY `idx_lab_matches_evaluation` (`evaluation_id`,`sequence_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lab_strategy_versions` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `experiment_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sequence_number` int NOT NULL,
  `previous_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `game_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `request_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `compatibility` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `evidence` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lab_strategy_sequence` (`experiment_id`,`sequence_number`),
  UNIQUE KEY `uk_lab_strategy_request` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lab_training_cases` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `purpose` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_type` varchar(48) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_group` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `persona_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `participant_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `ordinal` int NOT NULL,
  `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `data` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_lab_training_case_run` (`run_id`,`purpose`,`ordinal`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lab_training_runs` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `experiment_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `operation_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `request_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `goal` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `stage` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reason` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `candidate_strategy_version_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `configuration` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `assessment` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at_epoch_ms` bigint NOT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lab_training_operation` (`experiment_id`,`operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lab_training_tasks` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `run_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `case_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `task_key` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `kind` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `variant` varchar(24) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci NOT NULL,
  `ordinal` int NOT NULL,
  `reason` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `request_id` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `attempt` int NOT NULL,
  `paid_retries` int NOT NULL,
  `started_at_epoch_ms` bigint DEFAULT NULL,
  `updated_at_epoch_ms` bigint NOT NULL,
  `not_before_epoch_ms` bigint NOT NULL,
  `lease_until_epoch_ms` bigint NOT NULL,
  `input` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `checkpoint` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `result` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lab_training_task_identity` (`run_id`,`task_key`),
  KEY `idx_lab_training_task_run` (`run_id`,`ordinal`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `local_ai_debug_turns` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `room_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `game_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `instance_id` varchar(96) COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor_name` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `seat_number` int DEFAULT NULL,
  `role_key` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `phase` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `round_number` int NOT NULL,
  `kind` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(24) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `terminal_reason` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `model_key` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `prompt_tokens` bigint DEFAULT NULL,
  `completion_tokens` bigint DEFAULT NULL,
  `cache_read_tokens` bigint DEFAULT NULL,
  `cache_write_tokens` bigint DEFAULT NULL,
  `reasoning_tokens` bigint DEFAULT NULL,
  `usage_authoritative` tinyint(1) DEFAULT NULL,
  `usage_complete` tinyint(1) NOT NULL DEFAULT '0',
  `attempt_count` int NOT NULL DEFAULT '0',
  `response_count` int NOT NULL DEFAULT '0',
  `latency_ms` bigint DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `payload` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_local_ai_debug_instance` (`room_id`,`instance_id`,`created_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `player_settlement_locks` (
  `player_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`player_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `player_settlements` (
  `archive_id` varchar(96) COLLATE utf8mb4_unicode_ci NOT NULL,
  `player_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `game_id` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `won` tinyint(1) NOT NULL,
  `score_delta` int NOT NULL,
  `coin_delta` int NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`archive_id`,`player_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `player_stats` (
  `id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `display_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `game_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `games_played` int NOT NULL,
  `player_id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `score` int NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `wins` int NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rooms` (
  `id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `comm_mode` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `config` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(6) DEFAULT NULL,
  `game_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `is_private` bit(1) NOT NULL,
  `max_players` int NOT NULL,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `seats` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `seat_count` int NOT NULL,
  `version` bigint DEFAULT NULL,
  `host_user_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `private_config` longtext COLLATE utf8mb4_unicode_ci,
  `room_code` varchar(6) COLLATE utf8mb4_unicode_ci NOT NULL,
  `waiting_since` datetime DEFAULT NULL,
  `expired_at` datetime DEFAULT NULL,
  `source` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NORMAL',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rooms_code` (`room_code`),
  KEY `idx_rooms_discovery` (`game_id`,`is_private`,`status`,`created_at`),
  KEY `idx_rooms_expiration` (`status`,`waiting_since`),
  KEY `idx_rooms_source_status` (`source`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `strategy_publications` (
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `operation_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `request_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `action` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `game_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `strategy_version_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `mode` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_by` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `parameters` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `previous_bindings` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `result` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_strategy_publication_operation` (`operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `users` (
  `id` varchar(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `access_token` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `coins` int NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `email` varchar(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `external_user_id` bigint DEFAULT NULL,
  `level` int NOT NULL,
  `nickname` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `session_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`),
  UNIQUE KEY `UKj96ni9p7id4xvtxvb2jveryq3` (`external_user_id`),
  UNIQUE KEY `UKr43af9ap4edm43mmtq01oddj6` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
