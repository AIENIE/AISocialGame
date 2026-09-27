package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.LegalAction;
import java.util.List;
import java.util.Map;

/** Detached, actor-specific input. Never serialize GameState or Room directly into a prompt. */
public record VisibleObservation(
        String gameId, String instanceId, String phase, int round, String actorId, String turnKind,
        Map<String, Object> self, List<Map<String, Object>> players, Map<String, Object> rules,
        List<Map<String, Object>> events, Map<String, Object> privateFacts,
        List<LegalAction> legalActions, Map<String, Object> persona,
        Map<String, Object> memory, List<String> knowledge) {}
