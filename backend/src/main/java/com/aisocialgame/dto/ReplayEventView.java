package com.aisocialgame.dto;

import com.aisocialgame.model.GameEvent;
import com.aisocialgame.model.GameEventVisibility;

import java.time.LocalDateTime;
import java.util.Map;

public class ReplayEventView {
    private final Long id;
    private final String archiveId;
    private final int seq;
    private final String eventType;
    private final String phase;
    private final int roundNumber;
    private final String actorPlayerId;
    private final String targetPlayerId;
    private final GameEventVisibility visibility;
    private final Map<String, Object> data;
    private final LocalDateTime occurredAt;

    public ReplayEventView(GameEvent event) {
        this.id = event.getId();
        this.archiveId = event.getArchiveId();
        this.seq = event.getSeq();
        this.eventType = event.getEventType();
        this.phase = event.getPhase();
        this.roundNumber = event.getRoundNumber();
        this.actorPlayerId = event.getActorPlayerId();
        this.targetPlayerId = event.getTargetPlayerId();
        this.visibility = event.getVisibility();
        this.data = event.getData();
        this.occurredAt = event.getOccurredAt();
    }

    public Long getId() { return id; }
    public String getArchiveId() { return archiveId; }
    public int getSeq() { return seq; }
    public String getEventType() { return eventType; }
    public String getPhase() { return phase; }
    public int getRoundNumber() { return roundNumber; }
    public String getActorPlayerId() { return actorPlayerId; }
    public String getTargetPlayerId() { return targetPlayerId; }
    public GameEventVisibility getVisibility() { return visibility; }
    public Map<String, Object> getData() { return data; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}
