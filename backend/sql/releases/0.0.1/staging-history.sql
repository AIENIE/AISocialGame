-- One-time approved staging retirement at mysql.testhut.top:13306/aisocialgame.
-- Stop the old application and freeze candidate IDs/counts before execution.
-- The reviewed staging baseline has no archives, lab records or V2 memory fragments.
-- Account balances and current monetary receipts are preserved.
DROP TEMPORARY TABLE IF EXISTS staging_retired_rooms;
CREATE TEMPORARY TABLE staging_retired_rooms AS
 SELECT room_id FROM game_states
 WHERE COALESCE(JSON_EXTRACT(data,'$.ruleVersion'),-1) <> 2;

DELETE FROM ai_safety_controls WHERE scope='ROOM'
 AND target_key IN (SELECT room_id FROM staging_retired_rooms);
DELETE FROM local_ai_debug_turns WHERE room_id IN (SELECT room_id FROM staging_retired_rooms);
DELETE FROM local_ai_debug_turns WHERE id IN
 (SELECT id FROM ai_turn_jobs WHERE room_id IN (SELECT room_id FROM staging_retired_rooms));
DELETE FROM ai_turn_jobs WHERE room_id IN (SELECT room_id FROM staging_retired_rooms);
DELETE FROM ai_decision_traces WHERE instance_id IS NULL
 OR room_id IN (SELECT room_id FROM staging_retired_rooms);
DELETE FROM game_events WHERE room_id IN (SELECT room_id FROM staging_retired_rooms);
DELETE FROM game_states WHERE room_id IN (SELECT room_id FROM staging_retired_rooms);
DELETE FROM rooms WHERE id IN (SELECT room_id FROM staging_retired_rooms);
DELETE FROM ai_persona_memories WHERE role_key <> 'GENERAL_V2';
DELETE FROM credit_ledger_entries WHERE type IN ('AI_RESERVE','AI_RELEASE')
 OR (type='CONSUME' AND JSON_EXTRACT(metadata_json,'$.billedTokens') IS NOT NULL
     AND COALESCE(JSON_TYPE(JSON_EXTRACT(metadata_json,'$.billedTokens')),'NULL') <> 'NULL'
     AND COALESCE(JSON_TYPE(JSON_EXTRACT(metadata_json,'$.billedPoints')),'NULL') = 'NULL');
DROP TEMPORARY TABLE staging_retired_rooms;
