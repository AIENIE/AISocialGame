package com.aisocialgame.dto;

import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomSeat;

import java.util.List;
import java.util.Map;

public class RoomResponse {
    private String id;
    private String gameId;
    private String name;
    private String status;
    private int maxPlayers;
    private int seatCount;
    private boolean isPrivate;
    private String commMode;
    private Map<String, Object> config;
    private List<RoomSeat> seats;
    private String selfPlayerId;
    private String hostUserId;

    public RoomResponse(Room room) {
        this.id = room.getId();
        this.gameId = room.getGameId();
        this.name = room.getName();
        this.status = room.getStatus().name();
        this.maxPlayers = room.getMaxPlayers();
        this.seatCount = room.getSeatCount();
        this.isPrivate = room.isPrivate();
        this.commMode = room.getCommMode();
        this.config = new java.util.LinkedHashMap<>();
        java.util.Set<String> visibleKeys = java.util.Set.of("playerCount", "template", "witchRule", "winCondition", "speechTime", "hasLastWords", "spyMode", "spyCount", "hasBlank", "wordPack", "speakTime", "caseId", "maxQuestions", "aiAssist", "customWordCount", "hostMode");
        room.getConfig().forEach((key, value) -> { if (visibleKeys.contains(key)) this.config.put(key, value); });
        this.seats = room.getSeats();
        this.hostUserId = room.getHostUserId() != null ? room.getHostUserId() : room.getSeats().stream().filter(RoomSeat::isHost).map(RoomSeat::getPlayerId).findFirst().orElse(null);
    }

    public RoomResponse(Room room, String selfPlayerId) {
        this(room);
        this.selfPlayerId = selfPlayerId;
    }

    public String getId() { return id; }
    public String getGameId() { return gameId; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getSeatCount() { return seatCount; }
    public boolean isPrivate() { return isPrivate; }
    public String getCommMode() { return commMode; }
    public Map<String, Object> getConfig() { return config; }
    public List<RoomSeat> getSeats() { return seats; }
    public String getSelfPlayerId() { return selfPlayerId; }
    public String getHostUserId() { return hostUserId; }
}
