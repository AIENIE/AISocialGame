package com.aisocialgame.dto.admin;

import com.aisocialgame.model.AiDecisionTrace;

import java.time.LocalDateTime;
import java.util.Map;

public class AdminAiDecisionTraceView {
    private final Long id;
    private final String roomId;
    private final String gameId;
    private final String phase;
    private final int roundNumber;
    private final String action;
    private final String actorPlayerId;
    private final String personaId;
    private final String roleKey;
    private final String modelKey;
    private final long latencyMs;
    private final boolean fallback;
    private final boolean validDecision;
    private final Double confidence;
    private final String targetPlayerId;
    private final String nightAction;
    private final String reason;
    private final String outputSummary;
    private final String inputSummary;
    private final Map<String, Object> quality;
    private final Map<String, Object> beliefSnapshot;
    private final Map<String, Object> memorySnapshot;
    private final LocalDateTime createdAt;

    public AdminAiDecisionTraceView(AiDecisionTrace trace) {
        this.id = trace.getId();
        this.roomId = trace.getRoomId();
        this.gameId = trace.getGameId();
        this.phase = trace.getPhase();
        this.roundNumber = trace.getRoundNumber();
        this.action = trace.getAction();
        this.actorPlayerId = trace.getActorPlayerId();
        this.personaId = trace.getPersonaId();
        this.roleKey = trace.getRoleKey();
        this.modelKey = trace.getModelKey();
        this.latencyMs = trace.getLatencyMs();
        this.fallback = trace.isFallback();
        this.validDecision = trace.isValidDecision();
        this.confidence = trace.getConfidence();
        this.targetPlayerId = trace.getTargetPlayerId();
        this.nightAction = trace.getNightAction();
        this.reason = trace.getReason();
        this.outputSummary = trace.getOutputSummary();
        this.inputSummary = trace.getInputSummary();
        this.quality = trace.getQuality();
        this.beliefSnapshot = trace.getBeliefSnapshot();
        this.memorySnapshot = trace.getMemorySnapshot();
        this.createdAt = trace.getCreatedAt();
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
    public long getLatencyMs() { return latencyMs; }
    public boolean isFallback() { return fallback; }
    public boolean isValidDecision() { return validDecision; }
    public Double getConfidence() { return confidence; }
    public String getTargetPlayerId() { return targetPlayerId; }
    public String getNightAction() { return nightAction; }
    public String getReason() { return reason; }
    public String getOutputSummary() { return outputSummary; }
    public String getInputSummary() { return inputSummary; }
    public Map<String, Object> getQuality() { return quality; }
    public Map<String, Object> getBeliefSnapshot() { return beliefSnapshot; }
    public Map<String, Object> getMemorySnapshot() { return memorySnapshot; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
