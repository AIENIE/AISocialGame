package com.aisocialgame.service;

import com.aisocialgame.dto.GameLogPage;
import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.GameEvent;
import com.aisocialgame.model.GameEventVisibility;
import com.aisocialgame.model.GameLogEntry;
import com.aisocialgame.model.GameState;
import com.aisocialgame.repository.GameEventRepository;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.repository.RoomRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GameLogQueryService {
    private final RoomRepository rooms;
    private final GameStateRepository states;
    private final GameEventRepository events;

    public GameLogQueryService(RoomRepository rooms, GameStateRepository states, GameEventRepository events) {
        this.rooms = rooms;
        this.states = states;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public GameLogPage page(String gameId, String roomId, String viewerId, Long before, int requestedSize) {
        if (before != null && before <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "日志游标无效");
        int size = Math.min(Math.max(requestedSize, 1), 100);
        var room = rooms.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"));
        if (!room.getGameId().equals(gameId)) throw new ApiException(HttpStatus.NOT_FOUND, "房间与玩法不匹配");
        GameState state = states.findById(roomId).orElse(null);
        RoomAccessPolicy.requireRead(room, state, viewerId);
        if (state == null) return new GameLogPage(List.of(), null, false);

        String archiveId = RuleSupport.text(state.getData().get("archiveId"));
        int recorded = RuleSupport.number(state.getData().get("recordedEventCount"), 0);
        // Old active games remain readable until their event-table conversion is verified.
        if (archiveId.isBlank() || (!Boolean.TRUE.equals(state.getData().get("eventTableComplete"))
                && recorded > 0 && events.countByArchiveId(archiveId) != recorded))
            return legacyPage(state.getLogs(), before, size);
        List<GameEvent> descending = events.publicPage(archiveId, GameEventVisibility.PUBLIC, before,
                PageRequest.of(0, size + 1));
        if (descending.isEmpty() && !state.getLogs().isEmpty() && recorded == 0)
            return legacyPage(state.getLogs(), before, size);
        boolean hasMore = descending.size() > size;
        List<GameEvent> chosen = new ArrayList<>(descending.subList(0, Math.min(size, descending.size())));
        Long next = hasMore ? chosen.getLast().getPublicSeq() : null;
        Collections.reverse(chosen);
        return new GameLogPage(chosen.stream().map(this::toLog).toList(), next, hasMore);
    }

    private GameLogPage legacyPage(List<GameLogEntry> logs, Long before, int size) {
        int end = before == null ? logs.size() : (int) Math.min(before - 1, logs.size());
        int start = Math.max(0, end - size);
        boolean more = start > 0;
        return new GameLogPage(List.copyOf(logs.subList(start, end)), more ? (long) start + 1 : null, more);
    }

    private GameLogEntry toLog(GameEvent event) {
        Map<String, Object> data = event.getData();
        GameLogEntry log = new GameLogEntry(event.getEventType(), RuleSupport.text(data.get("message")));
        log.setActorId(event.getActorPlayerId());
        log.setTargetId(event.getTargetPlayerId());
        log.setRoundNumber(event.getRoundNumber());
        log.setPhase(event.getPhase());
        log.setTime(event.getOccurredAt());
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (data.get("eventId") != null) metadata.put("eventId", data.get("eventId"));
        if (data.get("presentation") != null) metadata.put("presentation", data.get("presentation"));
        log.setMetadata(metadata);
        return log;
    }
}
