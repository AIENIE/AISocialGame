package com.aisocialgame.service.safety;

import com.aisocialgame.model.AiSafetyEvent;

public record AiSafetyResult(
        String action,
        String severity,
        String category,
        String reason,
        String safeContent,
        AiSafetyEvent event
) {
    public boolean allowed() {
        return AiSafetyAction.ALLOW.equals(action);
    }

    public boolean redacted() {
        return AiSafetyAction.REDACT.equals(action);
    }

    public boolean blocked() {
        return AiSafetyAction.BLOCK.equals(action) || AiSafetyAction.ESCALATE.equals(action) || AiSafetyAction.RATE_LIMIT.equals(action);
    }
}
