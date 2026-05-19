-- AISocialGame v1.0 performance/stability hardening

ALTER TABLE `rooms`
  ADD COLUMN `seat_count` INT NOT NULL DEFAULT 0 AFTER `seats`,
  ADD COLUMN `version` BIGINT NULL AFTER `seat_count`;

UPDATE `rooms`
SET `seat_count` = CASE
  WHEN `seats` IS NULL OR JSON_VALID(`seats`) = 0 THEN 0
  ELSE JSON_LENGTH(`seats`)
END;

CREATE INDEX `idx_rooms_game_status_created`
  ON `rooms` (`game_id`, `status`, `created_at`);
