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

\n