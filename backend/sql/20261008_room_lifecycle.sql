-- Executors supply JVM local time because historical DATETIME values use the application clock.
SET @room_now = COALESCE(@room_migration_now, CURRENT_TIMESTAMP);
-- Additive, repeatable room lifecycle migration. MySQL DDL implicitly commits.
-- Hibernate-created local schemas may still use the old two-value MySQL ENUM.
SET @room_ddl = IF((SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rooms' AND column_name='status')='enum',
 'ALTER TABLE rooms MODIFY COLUMN status VARCHAR(32) NOT NULL', 'SELECT 1');
PREPARE room_statement FROM @room_ddl;
EXECUTE room_statement;
DEALLOCATE PREPARE room_statement;
SET @room_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rooms' AND column_name='room_code')=0,
 'ALTER TABLE rooms ADD COLUMN room_code VARCHAR(6) NULL', 'SELECT 1');
PREPARE room_statement FROM @room_ddl;
EXECUTE room_statement;
DEALLOCATE PREPARE room_statement;
SET @room_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rooms' AND column_name='waiting_since')=0,
 'ALTER TABLE rooms ADD COLUMN waiting_since DATETIME NULL', 'SELECT 1');
PREPARE room_statement FROM @room_ddl;
EXECUTE room_statement;
DEALLOCATE PREPARE room_statement;
SET @room_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rooms' AND column_name='expired_at')=0,
 'ALTER TABLE rooms ADD COLUMN expired_at DATETIME NULL', 'SELECT 1');
PREPARE room_statement FROM @room_ddl;
EXECUTE room_statement;
DEALLOCATE PREPARE room_statement;
SET @room_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='rooms' AND index_name='uk_rooms_code')=0,
 'CREATE UNIQUE INDEX uk_rooms_code ON rooms(room_code)', 'SELECT 1');
PREPARE room_statement FROM @room_ddl;
EXECUTE room_statement;
DEALLOCATE PREPARE room_statement;
SET @room_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='rooms' AND index_name='idx_rooms_discovery')=0,
 'CREATE INDEX idx_rooms_discovery ON rooms(game_id,is_private,status,created_at)', 'SELECT 1');
PREPARE room_statement FROM @room_ddl;
EXECUTE room_statement;
DEALLOCATE PREPARE room_statement;
SET @room_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='rooms' AND index_name='idx_rooms_expiration')=0,
 'CREATE INDEX idx_rooms_expiration ON rooms(status,waiting_since)', 'SELECT 1');
PREPARE room_statement FROM @room_ddl;
EXECUTE room_statement;
DEALLOCATE PREPARE room_statement;
-- Preserve previously allocated numbers on retries; allocate only unused numbers.
DROP TEMPORARY TABLE IF EXISTS room_missing_codes;
CREATE TEMPORARY TABLE room_missing_codes AS SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS n FROM rooms WHERE room_code IS NULL;
SET @room_needed = (SELECT COUNT(*) FROM room_missing_codes);
-- Among the first total-room-count numbers, at least missing-count are free.
-- Bound each digit group before the anti-join instead of sorting all 900,000 codes.
SET @room_bound = LEAST(900000, (SELECT COUNT(*) FROM rooms));
DROP TEMPORARY TABLE IF EXISTS room_free_codes;
SET @room_ddl = CONCAT('CREATE TEMPORARY TABLE room_free_codes AS SELECT code, ROW_NUMBER() OVER (ORDER BY code) AS n FROM (SELECT CAST(100000 + a.n*10000 + b.n*100 + c.n AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_unicode_ci AS code FROM (SELECT x.n*10+y.n AS n FROM JSON_TABLE(''[0,1,2,3,4,5,6,7,8]'',''$[*]'' COLUMNS(n INT PATH ''$'')) x CROSS JOIN JSON_TABLE(''[0,1,2,3,4,5,6,7,8,9]'',''$[*]'' COLUMNS(n INT PATH ''$'')) y) a CROSS JOIN (SELECT x.n*10+y.n AS n FROM JSON_TABLE(''[0,1,2,3,4,5,6,7,8,9]'',''$[*]'' COLUMNS(n INT PATH ''$'')) x CROSS JOIN JSON_TABLE(''[0,1,2,3,4,5,6,7,8,9]'',''$[*]'' COLUMNS(n INT PATH ''$'')) y) b CROSS JOIN (SELECT x.n*10+y.n AS n FROM JSON_TABLE(''[0,1,2,3,4,5,6,7,8,9]'',''$[*]'' COLUMNS(n INT PATH ''$'')) x CROSS JOIN JSON_TABLE(''[0,1,2,3,4,5,6,7,8,9]'',''$[*]'' COLUMNS(n INT PATH ''$'')) y) c WHERE a.n <= FLOOR((@room_bound-1)/10000) AND b.n <= FLOOR((@room_bound-1)/100) AND a.n*10000+b.n*100+c.n < @room_bound) candidates WHERE NOT EXISTS (SELECT 1 FROM rooms r WHERE r.room_code=candidates.code) ORDER BY code LIMIT ', @room_needed);
PREPARE room_statement FROM @room_ddl;
EXECUTE room_statement;
DEALLOCATE PREPARE room_statement;
UPDATE rooms r JOIN room_missing_codes m ON r.id=m.id JOIN room_free_codes f ON m.n=f.n SET r.room_code=f.code WHERE r.room_code IS NULL;
ALTER TABLE rooms MODIFY COLUMN room_code VARCHAR(6) NOT NULL;
DROP TEMPORARY TABLE room_missing_codes;
DROP TEMPORARY TABLE room_free_codes;

-- Archive finish time is authoritative. Never use room.updated_at: lobby activity changes it.
UPDATE rooms r LEFT JOIN (SELECT room_id, MAX(finished_at) AS finished_at FROM game_archives GROUP BY room_id) a ON a.room_id=r.id
LEFT JOIN game_states s ON s.room_id=r.id
SET r.waiting_since = COALESCE(a.finished_at, CASE WHEN s.phase='SETTLEMENT' THEN s.updated_at ELSE NULL END,
 CASE WHEN s.room_id IS NULL OR s.phase='WAITING' THEN r.created_at ELSE NULL END)
WHERE r.status='WAITING' AND r.waiting_since IS NULL;
SELECT COUNT(*) AS unknown_waiting_origin FROM rooms WHERE status='WAITING' AND waiting_since IS NULL;
UPDATE rooms SET status='EXPIRED', expired_at=@room_now, version=COALESCE(version,0)+1
WHERE status='WAITING' AND (waiting_since IS NULL OR waiting_since<=DATE_SUB(@room_now, INTERVAL 3 HOUR));
UPDATE rooms SET waiting_since=NULL WHERE status='PLAYING' AND waiting_since IS NOT NULL;
