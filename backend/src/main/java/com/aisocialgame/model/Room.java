package com.aisocialgame.model;

import com.aisocialgame.model.converter.MapToJsonConverter;
import com.aisocialgame.model.converter.RoomSeatListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "rooms")
public class Room {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 64)
    private String gameId;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RoomStatus status;

    @Column(nullable = false)
    private int maxPlayers;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate;

    private String password;
    private String commMode;

    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> config = new HashMap<>();

    @Convert(converter = RoomSeatListConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private List<RoomSeat> seats = new ArrayList<>();

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Version
    private Long version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Room() {}

    public Room(String id, String gameId, String name, RoomStatus status, int maxPlayers, boolean isPrivate, String password, String commMode, Map<String, Object> config) {
        this.id = id;
        this.gameId = gameId;
        this.name = name;
        this.status = status;
        this.maxPlayers = maxPlayers;
        this.isPrivate = isPrivate;
        this.password = password;
        this.commMode = commMode;
        this.config = config;
    }

    @PrePersist
    public void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.config == null) {
            this.config = new HashMap<>();
        }
        if (this.seats == null) {
            this.seats = new ArrayList<>();
        }
        syncSeatCount();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        syncSeatCount();
    }

    public String getId() { return id; }
    public String getGameId() { return gameId; }
    public String getName() { return name; }
    public RoomStatus getStatus() { return status; }
    public int getMaxPlayers() { return maxPlayers; }
    public boolean isPrivate() { return isPrivate; }
    public String getPassword() { return password; }
    public String getCommMode() { return commMode; }
    public Map<String, Object> getConfig() {
        if (config == null) {
            config = new HashMap<>();
        }
        return config;
    }

    public List<RoomSeat> getSeats() {
        if (seats == null) {
            seats = new ArrayList<>();
        }
        return seats;
    }
    public int getSeatCount() { return seatCount; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(String id) { this.id = id; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public void setName(String name) { this.name = name; }
    public void setStatus(RoomStatus status) { this.status = status; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
    public void setPrivate(boolean aPrivate) { isPrivate = aPrivate; }
    public void setPassword(String password) { this.password = password; }
    public void setCommMode(String commMode) { this.commMode = commMode; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public void setSeats(List<RoomSeat> seats) {
        this.seats = seats;
        syncSeatCount();
    }
    public void setSeatCount(int seatCount) { this.seatCount = Math.max(0, seatCount); }
    public void setVersion(Long version) { this.version = version; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public void syncSeatCount() {
        this.seatCount = getSeats().size();
    }
}
