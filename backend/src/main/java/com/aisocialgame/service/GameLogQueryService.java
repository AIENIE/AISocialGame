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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class GameLogQueryService {
    private final RoomLifecycle lifecycle;
    private final GameStateRepository states;
    private final GameEventRepository events;

    public GameLogQueryService(RoomLifecycle lifecycle, GameStateRepository states, GameEventRepository events) {
        this.lifecycle = lifecycle;
        this.states = states;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public GameLogPage page(String gameId, String roomId, String viewerId, Long before, int requestedSize) {
        if (before != null && before <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "日志游标无效");
        int size = Math.min(Math.max(requestedSize, 1), 100);
        var room = lifecycle.requireActive(roomId);
        if (!room.getGameId().equals(gameId)) throw new ApiException(HttpStatus.NOT_FOUND, "房间与玩法不匹配");
        GameState state = states.findById(roomId).orElse(null);
        RoomAccessPolicy.requireRead(room, state, viewerId);
        if (state == null) return new GameLogPage(List.of(), null, false);

        String archiveId = RuleSupport.text(state.getData().get("archiveId"));
        List<GameEvent> descending = events.publicPage(archiveId, GameEventVisibility.PUBLIC, before,
                PageRequest.of(0, size + 1));
        boolean hasMore = descending.size() > size;
        List<GameEvent> chosen = new ArrayList<>(descending.subList(0, Math.min(size, descending.size())));
        Long next = hasMore ? chosen.getLast().getPublicSeq() : null;
        Collections.reverse(chosen);
        return new GameLogPage(chosen.stream().map(this::toLog).toList(), next, hasMore);
    }


    private GameLogEntry toLog(GameEvent event) {
        Map<String, Object> data = event.getData();
        GameLogEntry log = new GameLogEntry(event.getEventType(), RuleSupport.text(data.get("message")));
        log.setActorId(event.getActorPlayerId());
        log.setTargetId(event.getTargetPlayerId());
        log.setRoundNumber(event.getRoundNumber());
        log.setPhase(event.getPhase());
        log.setTime(event.getOccurredAt());
        log.setMetadata(com.aisocialgame.model.PublicLogMetadata.project(event.getEventType(), data,
                RuleSupport.text(data.get("eventId")), event.getPublicSeq()));
        return log;
    }
}
