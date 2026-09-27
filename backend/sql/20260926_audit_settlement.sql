CREATE TABLE IF NOT EXISTS player_settlement_locks (
  player_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_settlements (
  archive_id VARCHAR(96) NOT NULL,
  player_id VARCHAR(36) NOT NULL,
  game_id VARCHAR(32) NOT NULL,
  won BOOLEAN NOT NULL,
  score_delta INT NOT NULL,
  coin_delta INT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (archive_id, player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- No fabricated historical receipts. Existing archives and state settlement markers remain authoritative.
