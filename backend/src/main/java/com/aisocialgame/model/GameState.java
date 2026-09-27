package com.aisocialgame.model;

import com.aisocialgame.model.converter.GameLogEntryListConverter;
import com.aisocialgame.model.converter.GamePlayerStateListConverter;
import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "game_states")
public class GameState {
    @Id
    @Column(length = 64)
    private String roomId;

    @Column(nullable = false, length = 64)
    private String gameId;

    @Column(nullable = false, length = 64)
    private String phase;

    @Column(nullable = false)
    private int roundNumber;

    private Integer currentSeat;

    @Convert(converter = GamePlayerStateListConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private List<GamePlayerState> players = new ArrayList<>();

    @Convert(converter = GameLogEntryListConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private List<GameLogEntry> logs = new ArrayList<>();

    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> data = new HashMap<>();

    private LocalDateTime phaseEndsAt;
    @jakarta.persistence.Version
    private Long version;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;

    public GameState() {}

    public GameState(String roomId, String gameId, String phase) {
        this.roomId = roomId;
        this.gameId = gameId;
        this.phase = phase;
        this.roundNumber = 1;
    }

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getRoomId() { return roomId; }
    public String getGameId() { return gameId; }
    public String getPhase() { return phase; }
    public int getRoundNumber() { return roundNumber; }
    public Integer getCurrentSeat() { return currentSeat; }
    public List<GamePlayerState> getPlayers() { return players; }
    public List<GameLogEntry> getLogs() { return logs; }
    public Map<String, Object> getData() { return data; }
    public LocalDateTime getPhaseEndsAt() { return phaseEndsAt; }
    public Long getVersion() { return version; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setRoomId(String roomId) { this.roomId = roomId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public void setPhase(String phase) { this.phase = phase; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }
    public void setCurrentSeat(Integer currentSeat) { this.currentSeat = currentSeat; }
    public void setPlayers(List<GamePlayerState> players) { this.players = players; }
    public void setLogs(List<GameLogEntry> logs) { this.logs = logs; }
    public void setData(Map<String, Object> data) { this.data = data; }
    public void setPhaseEndsAt(LocalDateTime phaseEndsAt) { this.phaseEndsAt = phaseEndsAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
