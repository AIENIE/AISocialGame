-- AISocialGame 全量表结构（v1.1）

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
  `game_id` VARCHAR(64) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `max_players` INT NOT NULL,
  `is_private` TINYINT(1) NOT NULL DEFAULT 0,
  `password` VARCHAR(255) NULL,
  `comm_mode` VARCHAR(64) NULL,
  `config` LONGTEXT NULL,
  `seats` LONGTEXT NULL,
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_rooms_game` (`game_id`),
  KEY `idx_rooms_status` (`status`)
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
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_persona_memory_scope` (`persona_id`, `game_id`, `role_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_decision_traces` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `room_id` VARCHAR(64) NULL,
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
  KEY `idx_ai_trace_game_action` (`game_id`, `action`, `id`),
  KEY `idx_ai_trace_persona` (`persona_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `game_events` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `archive_id` VARCHAR(96) NOT NULL,
  `room_id` VARCHAR(64) NOT NULL,
  `game_id` VARCHAR(64) NOT NULL,
  `seq` INT NOT NULL,
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
