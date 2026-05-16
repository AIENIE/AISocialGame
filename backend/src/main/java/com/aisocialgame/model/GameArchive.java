package com.aisocialgame.model;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "game_archives")
public class GameArchive {
    @Id
    @Column(length = 96)
    private String id;

    @Column(nullable = false, length = 64)
    private String roomId;

    @Column(nullable = false, length = 64)
    private String gameId;

    @Column(nullable = false, length = 128)
    private String roomName;

    @Column(length = 64)
    private String winner;

    private int playerCount;
    private int totalRounds;
    private long durationSeconds;
    private long eventCount;

    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> playersSnapshot = new HashMap<>();

    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> aiQualitySummary = new HashMap<>();

    @Column(length = 512)
    private String summary;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getRoomId() { return roomId; }
    public String getGameId() { return gameId; }
    public String getRoomName() { return roomName; }
    public String getWinner() { return winner; }
    public int getPlayerCount() { return playerCount; }
    public int getTotalRounds() { return totalRounds; }
    public long getDurationSeconds() { return durationSeconds; }
    public long getEventCount() { return eventCount; }
    public Map<String, Object> getPlayersSnapshot() { return playersSnapshot; }
    public Map<String, Object> getAiQualitySummary() { return aiQualitySummary; }
    public String getSummary() { return summary; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(String id) { this.id = id; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public void setRoomName(String roomName) { this.roomName = roomName; }
    public void setWinner(String winner) { this.winner = winner; }
    public void setPlayerCount(int playerCount) { this.playerCount = playerCount; }
    public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }
    public void setDurationSeconds(long durationSeconds) { this.durationSeconds = durationSeconds; }
    public void setEventCount(long eventCount) { this.eventCount = eventCount; }
    public void setPlayersSnapshot(Map<String, Object> playersSnapshot) { this.playersSnapshot = playersSnapshot != null ? playersSnapshot : new HashMap<>(); }
    public void setAiQualitySummary(Map<String, Object> aiQualitySummary) { this.aiQualitySummary = aiQualitySummary != null ? aiQualitySummary : new HashMap<>(); }
    public void setSummary(String summary) { this.summary = summary; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
