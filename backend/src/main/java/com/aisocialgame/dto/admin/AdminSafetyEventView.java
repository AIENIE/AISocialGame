package com.aisocialgame.dto.admin;

import com.aisocialgame.model.AiSafetyEvent;

import java.time.LocalDateTime;
import java.util.Map;

public class AdminSafetyEventView {
    private final Long id;
    private final String source;
    private final String action;
    private final String severity;
    private final String category;
    private final String status;
    private final String roomId;
    private final String gameId;
    private final String userId;
    private final String playerId;
    private final String personaId;
    private final String modelKey;
    private final String traceId;
    private final String contentSummary;
    private final String sanitizedContent;
    private final String reason;
    private final Map<String, Object> metadata;
    private final String acknowledgedBy;
    private final LocalDateTime acknowledgedAt;
    private final String closedBy;
    private final LocalDateTime closedAt;
    private final String closeReason;
    private final LocalDateTime createdAt;

    public AdminSafetyEventView(AiSafetyEvent event) {
        this.id = event.getId();
        this.source = event.getSource();
        this.action = event.getAction();
        this.severity = event.getSeverity();
        this.category = event.getCategory();
        this.status = event.getStatus();
        this.roomId = event.getRoomId();
        this.gameId = event.getGameId();
        this.userId = event.getUserId();
        this.playerId = event.getPlayerId();
        this.personaId = event.getPersonaId();
        this.modelKey = event.getModelKey();
        this.traceId = event.getTraceId();
        this.contentSummary = event.getContentSummary();
        this.sanitizedContent = event.getSanitizedContent();
        this.reason = event.getReason();
        this.metadata = event.getMetadata();
        this.acknowledgedBy = event.getAcknowledgedBy();
        this.acknowledgedAt = event.getAcknowledgedAt();
        this.closedBy = event.getClosedBy();
        this.closedAt = event.getClosedAt();
        this.closeReason = event.getCloseReason();
        this.createdAt = event.getCreatedAt();
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
}
