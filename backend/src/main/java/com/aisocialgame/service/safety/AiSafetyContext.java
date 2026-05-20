package com.aisocialgame.service.safety;

import java.util.HashMap;
import java.util.Map;

public class AiSafetyContext {
    private String source;
    private String roomId;
    private String gameId;
    private String userId;
    private String playerId;
    private String personaId;
    private String modelKey;
    private String traceId;
    private Map<String, Object> metadata = new HashMap<>();

    public static AiSafetyContext source(String source) {
        AiSafetyContext context = new AiSafetyContext();
        context.setSource(source);
        return context;
    }

    public String getSource() { return source; }
    public String getRoomId() { return roomId; }
    public String getGameId() { return gameId; }
    public String getUserId() { return userId; }
    public String getPlayerId() { return playerId; }
    public String getPersonaId() { return personaId; }
    public String getModelKey() { return modelKey; }
    public String getTraceId() { return traceId; }
    public Map<String, Object> getMetadata() { return metadata; }

    public void setSource(String source) { this.source = source; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }
    public void setPersonaId(String personaId) { this.personaId = personaId; }
    public void setModelKey(String modelKey) { this.modelKey = modelKey; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new HashMap<>(); }

    public AiSafetyContext room(String roomId, String gameId) {
        this.roomId = roomId;
        this.gameId = gameId;
        return this;
    }

    public AiSafetyContext user(String userId, String playerId) {
        this.userId = userId;
        this.playerId = playerId;
        return this;
    }

    public AiSafetyContext persona(String personaId) {
        this.personaId = personaId;
        return this;
    }

    public AiSafetyContext model(String modelKey) {
        this.modelKey = modelKey;
        return this;
    }

    public AiSafetyContext trace(String traceId) {
        this.traceId = traceId;
        return this;
    }

    public AiSafetyContext metadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }
}
