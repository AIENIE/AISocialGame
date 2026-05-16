package com.aisocialgame.service.ai;

import java.util.List;
import java.util.Map;

public record AiDecisionResult(
        String content,
        String targetPlayerId,
        String action,
        boolean useHeal,
        String reason,
        boolean fallback,
        Double confidence,
        List<String> evidence,
        String reflection,
        List<String> qualityFlags,
        Long traceId,
        Map<String, Object> logMetadata
) {
    public static AiDecisionResult speech(String content, boolean fallback) {
        return new AiDecisionResult(content, null, null, false, "", fallback, null, List.of(), "", List.of(), null, Map.of());
    }

    public static AiDecisionResult vote(String targetPlayerId, String reason, boolean fallback) {
        return new AiDecisionResult("", targetPlayerId, null, false, reason, fallback, null, List.of(), "", List.of(), null, Map.of());
    }

    public static AiDecisionResult nightAction(String action, String targetPlayerId, boolean useHeal, String reason, boolean fallback) {
        return new AiDecisionResult("", targetPlayerId, action, useHeal, reason, fallback, null, List.of(), "", List.of(), null, Map.of());
    }

    public AiDecisionResult withQuality(Double confidence,
                                        List<String> evidence,
                                        String reflection,
                                        List<String> qualityFlags,
                                        Long traceId,
                                        Map<String, Object> logMetadata) {
        return new AiDecisionResult(
                content,
                targetPlayerId,
                action,
                useHeal,
                reason,
                fallback,
                confidence,
                evidence == null ? List.of() : evidence,
                reflection == null ? "" : reflection,
                qualityFlags == null ? List.of() : qualityFlags,
                traceId,
                logMetadata == null ? Map.of() : logMetadata
        );
    }
}
