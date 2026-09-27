-- Public cursor is contiguous per archive. Private/GOD events keep NULL, so
-- public clients cannot infer private event counts from a visible sequence.
SET @public_cursor_ddl = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='game_events' AND column_name='public_seq')=0,
  'ALTER TABLE game_events ADD COLUMN public_seq BIGINT NULL AFTER seq', 'SELECT 1');
PREPARE public_cursor_statement FROM @public_cursor_ddl;
EXECUTE public_cursor_statement;
DEALLOCATE PREPARE public_cursor_statement;

UPDATE game_events event JOIN (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY archive_id ORDER BY seq, id) AS visible_number
  FROM game_events WHERE visibility='PUBLIC'
) visible ON visible.id=event.id
SET event.public_seq=visible.visible_number
WHERE event.visibility='PUBLIC' AND event.public_seq IS NULL;

SET @public_cursor_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='game_events' AND index_name='uk_game_events_archive_public_seq')=0,
  'CREATE UNIQUE INDEX uk_game_events_archive_public_seq ON game_events(archive_id, public_seq)', 'SELECT 1');
PREPARE public_cursor_statement FROM @public_cursor_ddl;
EXECUTE public_cursor_statement;
DEALLOCATE PREPARE public_cursor_statement;
