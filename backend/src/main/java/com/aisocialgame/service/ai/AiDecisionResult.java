package com.aisocialgame.service.ai;

public record AiDecisionResult(
        String content,
        String targetPlayerId,
        String action,
        boolean useHeal,
        String reason,
        boolean fallback
) {
    public static AiDecisionResult speech(String content, boolean fallback) {
        return new AiDecisionResult(content, null, null, false, "", fallback);
    }

    public static AiDecisionResult vote(String targetPlayerId, String reason, boolean fallback) {
        return new AiDecisionResult("", targetPlayerId, null, false, reason, fallback);
    }

    public static AiDecisionResult nightAction(String action, String targetPlayerId, boolean useHeal, String reason, boolean fallback) {
        return new AiDecisionResult("", targetPlayerId, action, useHeal, reason, fallback);
    }
}
