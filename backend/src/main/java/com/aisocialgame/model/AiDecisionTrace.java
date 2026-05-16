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
@Table(name = "ai_decision_traces")
public class AiDecisionTrace {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String roomId;

    @Column(nullable = false, length = 64)
    private String gameId;

    @Column(length = 64)
    private String phase;

    private int roundNumber;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 64)
    private String actorPlayerId;

    @Column(length = 64)
    private String personaId;

    @Column(length = 64)
    private String roleKey;

    @Column(length = 128)
    private String modelKey;

    private long promptTokens;
    private long completionTokens;
    private long latencyMs;
    private boolean fallback;
    private boolean validDecision;
    private Double confidence;

    @Column(length = 64)
    private String targetPlayerId;

    @Column(length = 64)
    private String nightAction;

    @Column(length = 512)
    private String reason;

    @Column(length = 512)
    private String outputSummary;

    @Column(length = 1024)
    private String inputSummary;

    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> beliefSnapshot = new HashMap<>();

    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> memorySnapshot = new HashMap<>();

    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> quality = new HashMap<>();

    @Convert(converter = MapToJsonConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> rawOutput = new HashMap<>();

    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getRoomId() { return roomId; }
    public String getGameId() { return gameId; }
    public String getPhase() { return phase; }
    public int getRoundNumber() { return roundNumber; }
    public String getAction() { return action; }
    public String getActorPlayerId() { return actorPlayerId; }
    public String getPersonaId() { return personaId; }
    public String getRoleKey() { return roleKey; }
    public String getModelKey() { return modelKey; }
    public long getPromptTokens() { return promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public long getLatencyMs() { return latencyMs; }
    public boolean isFallback() { return fallback; }
    public boolean isValidDecision() { return validDecision; }
    public Double getConfidence() { return confidence; }
    public String getTargetPlayerId() { return targetPlayerId; }
    public String getNightAction() { return nightAction; }
    public String getReason() { return reason; }
    public String getOutputSummary() { return outputSummary; }
    public String getInputSummary() { return inputSummary; }
    public Map<String, Object> getBeliefSnapshot() { return beliefSnapshot; }
    public Map<String, Object> getMemorySnapshot() { return memorySnapshot; }
    public Map<String, Object> getQuality() { return quality; }
    public Map<String, Object> getRawOutput() { return rawOutput; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public void setPhase(String phase) { this.phase = phase; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }
    public void setAction(String action) { this.action = action; }
    public void setActorPlayerId(String actorPlayerId) { this.actorPlayerId = actorPlayerId; }
    public void setPersonaId(String personaId) { this.personaId = personaId; }
    public void setRoleKey(String roleKey) { this.roleKey = roleKey; }
    public void setModelKey(String modelKey) { this.modelKey = modelKey; }
    public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }
    public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }
    public void setValidDecision(boolean validDecision) { this.validDecision = validDecision; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public void setTargetPlayerId(String targetPlayerId) { this.targetPlayerId = targetPlayerId; }
    public void setNightAction(String nightAction) { this.nightAction = nightAction; }
    public void setReason(String reason) { this.reason = reason; }
    public void setOutputSummary(String outputSummary) { this.outputSummary = outputSummary; }
    public void setInputSummary(String inputSummary) { this.inputSummary = inputSummary; }
    public void setBeliefSnapshot(Map<String, Object> beliefSnapshot) { this.beliefSnapshot = beliefSnapshot != null ? beliefSnapshot : new HashMap<>(); }
    public void setMemorySnapshot(Map<String, Object> memorySnapshot) { this.memorySnapshot = memorySnapshot != null ? memorySnapshot : new HashMap<>(); }
    public void setQuality(Map<String, Object> quality) { this.quality = quality != null ? quality : new HashMap<>(); }
    public void setRawOutput(Map<String, Object> rawOutput) { this.rawOutput = rawOutput != null ? rawOutput : new HashMap<>(); }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
