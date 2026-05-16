package com.aisocialgame.model;

import com.aisocialgame.model.converter.MapToJsonConverter;
import com.aisocialgame.model.converter.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "game_events")
public class GameEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 96)
    private String archiveId;

    @Column(nullable = false, length = 64)
    private String roomId;

    @Column(nullable = false, length = 64)
    private String gameId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 64)
    private String eventType;

    @Column(length = 64)
    private String phase;

    private int roundNumber;

    @Column(length = 64)
    private String actorPlayerId;

    @Column(length = 64)
    private String targetPlayerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GameEventVisibility visibility = GameEventVisibility.PUBLIC;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private List<String> visibleToPlayerIds = List.of();

    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> data = new HashMap<>();

    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (occurredAt == null) {
            occurredAt = now;
        }
        createdAt = now;
    }

    public Long getId() { return id; }
    public String getArchiveId() { return archiveId; }
    public String getRoomId() { return roomId; }
    public String getGameId() { return gameId; }
    public int getSeq() { return seq; }
    public String getEventType() { return eventType; }
    public String getPhase() { return phase; }
    public int getRoundNumber() { return roundNumber; }
    public String getActorPlayerId() { return actorPlayerId; }
    public String getTargetPlayerId() { return targetPlayerId; }
    public GameEventVisibility getVisibility() { return visibility; }
    public List<String> getVisibleToPlayerIds() { return visibleToPlayerIds; }
    public Map<String, Object> getData() { return data; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setArchiveId(String archiveId) { this.archiveId = archiveId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public void setSeq(int seq) { this.seq = seq; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public void setPhase(String phase) { this.phase = phase; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }
    public void setActorPlayerId(String actorPlayerId) { this.actorPlayerId = actorPlayerId; }
    public void setTargetPlayerId(String targetPlayerId) { this.targetPlayerId = targetPlayerId; }
    public void setVisibility(GameEventVisibility visibility) { this.visibility = visibility; }
    public void setVisibleToPlayerIds(List<String> visibleToPlayerIds) { this.visibleToPlayerIds = visibleToPlayerIds != null ? visibleToPlayerIds : List.of(); }
    public void setData(Map<String, Object> data) { this.data = data != null ? data : new HashMap<>(); }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
