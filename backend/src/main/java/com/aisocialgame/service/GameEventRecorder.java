package com.aisocialgame.service;

import com.aisocialgame.model.GameEvent;
import com.aisocialgame.model.GameEventVisibility;
import com.aisocialgame.model.GameState;
import com.aisocialgame.repository.GameEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GameEventRecorder {
    public static final String ARCHIVE_ID_KEY = "archiveId";

    private final GameEventRepository gameEventRepository;

    public GameEventRecorder(GameEventRepository gameEventRepository) {
        this.gameEventRepository = gameEventRepository;
    }

    @Transactional
    public GameEvent recordPublic(GameState state, String eventType, String actorPlayerId, String targetPlayerId, Map<String, Object> data) {
        return record(state, eventType, actorPlayerId, targetPlayerId, GameEventVisibility.PUBLIC, List.of(), data);
    }

    @Transactional
    public GameEvent recordPrivate(GameState state, String eventType, String actorPlayerId, String targetPlayerId, List<String> visibleToPlayerIds, Map<String, Object> data) {
        return record(state, eventType, actorPlayerId, targetPlayerId, GameEventVisibility.PRIVATE, visibleToPlayerIds, data);
    }

    @Transactional
    public GameEvent recordGod(GameState state, String eventType, String actorPlayerId, String targetPlayerId, Map<String, Object> data) {
        return record(state, eventType, actorPlayerId, targetPlayerId, GameEventVisibility.GOD, List.of(), data);
    }

    @Transactional
    public String ensureArchiveId(GameState state) {
        Map<String, Object> data = state.getData();
        Object existing = data.get(ARCHIVE_ID_KEY);
        if (existing instanceof String value && !value.isBlank()) {
            return value;
        }
        String archiveId = "archive-" + UUID.randomUUID();
        data.put(ARCHIVE_ID_KEY, archiveId);
        state.setData(data);
        return archiveId;
    }

    @Transactional
    public GameEvent record(GameState state,
                            String eventType,
                            String actorPlayerId,
                            String targetPlayerId,
                            GameEventVisibility visibility,
                            List<String> visibleToPlayerIds,
                            Map<String, Object> data) {
        String archiveId = ensureArchiveId(state);
        GameEvent event = new GameEvent();
        event.setArchiveId(archiveId);
        event.setRoomId(state.getRoomId());
        event.setGameId(state.getGameId());
        event.setSeq(gameEventRepository.maxSeq(archiveId) + 1);
        event.setEventType(eventType);
        event.setPhase(state.getPhase());
        event.setRoundNumber(state.getRoundNumber());
        event.setActorPlayerId(actorPlayerId);
        event.setTargetPlayerId(targetPlayerId);
        event.setVisibility(visibility);
        event.setVisibleToPlayerIds(visibleToPlayerIds);
        event.setData(data == null ? new HashMap<>() : new HashMap<>(data));
        event.setOccurredAt(LocalDateTime.now());
        return gameEventRepository.save(event);
    }
}
