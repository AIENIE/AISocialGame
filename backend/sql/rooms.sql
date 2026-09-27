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
  `host_user_id` VARCHAR(36) NULL,
  `private_config` LONGTEXT NULL,
  `seats` LONGTEXT NULL,
  `created_at` DATETIME NULL,
  `updated_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_rooms_game` (`game_id`),
  KEY `idx_rooms_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
