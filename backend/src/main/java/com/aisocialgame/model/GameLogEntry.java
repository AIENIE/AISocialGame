package com.aisocialgame.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class GameLogEntry {
    private String type;
    private String message;
    private LocalDateTime time;
    private String actorId;
    private String targetId;
    private Integer roundNumber;
    private String phase;
    private Map<String, Object> metadata = new HashMap<>();

    public GameLogEntry() {}

    public GameLogEntry(String type, String message) {
        this.type = type;
        this.message = message;
        this.time = LocalDateTime.now();
    }

    public String getType() { return type; }
    public String getMessage() { return message; }
    public LocalDateTime getTime() { return time; }
    public String getActorId() { return actorId; }
    public String getTargetId() { return targetId; }
    public Integer getRoundNumber() { return roundNumber; }
    public String getPhase() { return phase; }
    public Map<String, Object> getMetadata() { return metadata; }

    public void setType(String type) { this.type = type; }
    public void setMessage(String message) { this.message = message; }
    public void setTime(LocalDateTime time) { this.time = time; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public void setRoundNumber(Integer roundNumber) { this.roundNumber = roundNumber; }
    public void setPhase(String phase) { this.phase = phase; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new HashMap<>(); }
}
