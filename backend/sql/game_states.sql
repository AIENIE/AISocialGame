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
