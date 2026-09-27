-- Additive and re-entrant. Preserve existing aggregate likes; do not invent historical voters.
CREATE TABLE IF NOT EXISTS community_likes (
  post_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  PRIMARY KEY (post_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @audit_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='game_archives' AND column_name='public_replay')=0,
  'ALTER TABLE game_archives ADD COLUMN public_replay BOOLEAN NULL', 'SELECT 1');
PREPARE audit_statement FROM @audit_ddl;
EXECUTE audit_statement;
DEALLOCATE PREPARE audit_statement;
SET @audit_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='game_archives' AND column_name='host_user_id')=0,
  'ALTER TABLE game_archives ADD COLUMN host_user_id VARCHAR(36) NULL', 'SELECT 1');
PREPARE audit_statement FROM @audit_ddl;
EXECUTE audit_statement;
DEALLOCATE PREPARE audit_statement;

CREATE TABLE IF NOT EXISTS archive_participants (
  archive_id VARCHAR(96) NOT NULL,
  player_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (archive_id, player_id),
  KEY idx_archive_participant_player (player_id, archive_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Only copy identities already present in the archived participant snapshot.
-- Never infer old visibility or ownership from today's room configuration.
INSERT IGNORE INTO archive_participants (archive_id, player_id)
SELECT a.id, JSON_UNQUOTE(JSON_EXTRACT(a.snapshot, CONCAT('$.players[', p.n - 1, '].playerId')))
FROM (SELECT id, CASE WHEN JSON_VALID(players_snapshot) THEN players_snapshot ELSE '{}' END AS snapshot FROM game_archives) a
JOIN JSON_TABLE(
  CASE WHEN JSON_TYPE(JSON_EXTRACT(a.snapshot, '$.players'))='ARRAY'
    THEN JSON_EXTRACT(a.snapshot, '$.players') ELSE JSON_ARRAY() END,
  '$[*]' COLUMNS (n FOR ORDINALITY)
) p ON JSON_TYPE(JSON_EXTRACT(a.snapshot, CONCAT('$.players[', p.n - 1, '].playerId')))='STRING'
AND CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot, CONCAT('$.players[', p.n - 1, '].playerId')))) BETWEEN 1 AND 64;
