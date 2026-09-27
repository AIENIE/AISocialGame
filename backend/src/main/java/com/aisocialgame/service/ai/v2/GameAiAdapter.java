package com.aisocialgame.service.ai.v2;

import com.aisocialgame.dto.PlayerAction;
import java.util.List;
import java.util.Map;

public interface GameAiAdapter {
    String gameId();
    default boolean isPlayerClaim(Map<String,Object> visibleEvent) { return AiSpeechEvents.isSpeech(visibleEvent); }
    String instruction(VisibleObservation observation);
    default AiConversationContext conversation(VisibleObservation observation) { return AiConversationContext.generic(observation); }
    default Map<String, Object> confirmedFacts(Map<String, Object> visibleEvent) {
        return java.util.Map.of();
    }
    default java.util.Map<String,Object> privateConfirmed(java.util.Map<String,Object> facts) { return java.util.Map.of(); }
    default List<String> knowledge(VisibleObservation observation) { return List.of(); }
    PlayerAction fallback(VisibleObservation observation);
    /** Extra semantic checks, beyond the common capability and event-reference checks. */
    default List<String> validateDecision(VisibleObservation observation, AiTurnDecision decision) { return List.of(); }
}
