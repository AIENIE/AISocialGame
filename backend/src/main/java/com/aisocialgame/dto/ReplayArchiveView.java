package com.aisocialgame.dto;

import com.aisocialgame.model.GameArchive;

import java.time.LocalDateTime;
import java.util.Map;

public class ReplayArchiveView {
    private final String id;
    private final String roomId;
    private final String gameId;
    private final String roomName;
    private final String winner;
    private final int playerCount;
    private final int totalRounds;
    private final long durationSeconds;
    private final long eventCount;
    private final String summary;
    private final Map<String, Object> aiQualitySummary;
    private final LocalDateTime startedAt;
    private final LocalDateTime finishedAt;
    private final LocalDateTime createdAt;

    public ReplayArchiveView(GameArchive archive) {
        this.id = archive.getId();
        this.roomId = archive.getRoomId();
        this.gameId = archive.getGameId();
        this.roomName = archive.getRoomName();
        this.winner = archive.getWinner();
        this.playerCount = archive.getPlayerCount();
        this.totalRounds = archive.getTotalRounds();
        this.durationSeconds = archive.getDurationSeconds();
        this.eventCount = archive.getEventCount();
        this.summary = archive.getSummary();
        this.aiQualitySummary = archive.getAiQualitySummary();
        this.startedAt = archive.getStartedAt();
        this.finishedAt = archive.getFinishedAt();
        this.createdAt = archive.getCreatedAt();
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
    public String getSummary() { return summary; }
    public Map<String, Object> getAiQualitySummary() { return aiQualitySummary; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
