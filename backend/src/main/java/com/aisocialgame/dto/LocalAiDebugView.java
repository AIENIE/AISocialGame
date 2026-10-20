package com.aisocialgame.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class LocalAiDebugView {
    private LocalAiDebugView() {}
    public record Instance(String id, LocalDateTime startedAt, long turnCount) {}
    public record Turn(String id, String actorId, String actorName, Integer seatNumber, String roleKey,
                       int round, String phase, String kind, String status, String terminalReason, String modelKey,
                       Long promptTokens, Long completionTokens, Long cacheReadTokens, Long cacheWriteTokens, Long reasoningTokens,
                       Boolean usageAuthoritative, boolean usageComplete, int attemptCount, int responseCount, Long latencyMs,
                       LocalDateTime createdAt, LocalDateTime updatedAt, boolean legacy) {}
    public record Usage(int turns, int attempts, int responses, Long promptTokens, Long completionTokens,
                        Long cacheReadTokens, Long cacheWriteTokens, Long reasoningTokens, boolean complete, Boolean authoritative) {}
    public record Round(int round, String phase, Usage usage, int problems) {}
    public record Actor(String id, String name, Integer seatNumber, String roleKey) {}
    public record Summary(String instanceId, Usage usage, List<Round> rounds, List<Actor> actors, String captureWarning) {}
    public record Detail(Turn turn, Map<String, Object> data) {}
}
