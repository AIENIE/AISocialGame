package com.aisocialgame.service.ai.v2;

import com.aisocialgame.dto.PlayerAction;
import java.util.List;
import java.util.Map;

/** Memory updates are proposals; only the bounded memory reducer can commit them. */
public record AiTurnDecision(PlayerAction action, String speech, Map<String, Object> presentation,
                             List<String> evidenceEventIds, Map<String, Object> memoryUpdates,
                             boolean fallback, Map<String, Object> diagnostics) {
    public AiTurnDecision {
        speech = speech == null ? "" : speech;
        presentation = presentation == null ? Map.of() : Map.copyOf(presentation);
        evidenceEventIds = evidenceEventIds == null ? List.of() : List.copyOf(evidenceEventIds);
        memoryUpdates = memoryUpdates == null ? Map.of() : Map.copyOf(memoryUpdates);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
    public static AiTurnDecision fallback(PlayerAction action) {
        return new AiTurnDecision(action, action.getContent(), Map.of(), List.of(), Map.of(), true, Map.of());
    }
}
