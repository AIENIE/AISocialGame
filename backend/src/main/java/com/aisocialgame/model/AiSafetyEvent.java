package com.aisocialgame.model;

import com.aisocialgame.model.converter.MapToJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "ai_safety_events")
public class AiSafetyEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(nullable = false, length = 32)
    private String severity;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false, length = 32)
    private String status = "OPEN";

    @Column(length = 64)
    private String roomId;

    @Column(length = 64)
    private String gameId;

    @Column(length = 64)
    private String userId;

    @Column(length = 64)
    private String playerId;

    @Column(length = 64)
    private String personaId;

    @Column(length = 128)
    private String modelKey;

    @Column(length = 64)
    private String traceId;

    @Column(length = 512)
    private String contentSummary;

    @Column(length = 512)
    private String sanitizedContent;

    @Column(length = 255)
    private String reason;

    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> metadata = new HashMap<>();

    @Column(length = 64)
    private String acknowledgedBy;

    private LocalDateTime acknowledgedAt;

    @Column(length = 64)
    private String closedBy;

    private LocalDateTime closedAt;

    @Column(length = 255)
    private String closeReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getAction() { return action; }
    public String getSeverity() { return severity; }
    public String getCategory() { return category; }
    public String getStatus() { return status; }
    public String getRoomId() { return roomId; }
    public String getGameId() { return gameId; }
    public String getUserId() { return userId; }
    public String getPlayerId() { return playerId; }
    public String getPersonaId() { return personaId; }
    public String getModelKey() { return modelKey; }
    public String getTraceId() { return traceId; }
    public String getContentSummary() { return contentSummary; }
    public String getSanitizedContent() { return sanitizedContent; }
    public String getReason() { return reason; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public LocalDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public String getClosedBy() { return closedBy; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public String getCloseReason() { return closeReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setSource(String source) { this.source = source; }
    public void setAction(String action) { this.action = action; }
    public void setSeverity(String severity) { this.severity = severity; }
    public void setCategory(String category) { this.category = category; }
    public void setStatus(String status) { this.status = status; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }
    public void setPersonaId(String personaId) { this.personaId = personaId; }
    public void setModelKey(String modelKey) { this.modelKey = modelKey; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public void setContentSummary(String contentSummary) { this.contentSummary = contentSummary; }
    public void setSanitizedContent(String sanitizedContent) { this.sanitizedContent = sanitizedContent; }
    public void setReason(String reason) { this.reason = reason; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new HashMap<>(); }
    public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
    public void setAcknowledgedAt(LocalDateTime acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
    public void setClosedBy(String closedBy) { this.closedBy = closedBy; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public void setCloseReason(String closeReason) { this.closeReason = closeReason; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
