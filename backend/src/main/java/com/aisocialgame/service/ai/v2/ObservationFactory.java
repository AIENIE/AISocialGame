package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.*;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.PersonaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ObservationFactory {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final PersonaRepository personas;
    private final AiMemoryServiceV2 memories;
    public ObservationFactory(PersonaRepository personas, AiMemoryServiceV2 memories) { this.personas = personas; this.memories = memories; }
    public VisibleObservation build(GameState state, GameRuleSet rules, TurnRequest turn) {
        String actorId = turn.actorId();
        List<Map<String, Object>> players = state.getPlayers().stream().map(p -> player(state, rules, p, actorId)).toList();
        Map<String, Object> self = players.stream().filter(p -> actorId.equals(p.get("playerId"))).findFirst()
                .orElse(Map.of("playerId", actorId, "displayName", "主持人", "role", "HOST"));
        Map<String, Object> publicRules = new LinkedHashMap<>(rules.observationRules(state));
        publicRules.put("ruleVersion", 2);
        Map<String, Object> memory = RuleSupport.HOST.equals(actorId) ? Map.of() : memories.snapshot(state, actorId);
        List<Map<String, Object>> visible = visibleEvents(state, actorId);
        Set<String> important = new HashSet<>();
        List<Map<String, Object>> ownSpeech = visible.stream().filter(e -> actorId.equals(e.get("actorId"))
                && AiSpeechEvents.isSpeech(e)).toList();
        ownSpeech.subList(Math.max(0, ownSpeech.size() - 8), ownSpeech.size())
                .forEach(e -> important.add(RuleSupport.text(e.get("eventId"))));
        RuleSupport.map(memory.get("beliefs")).values().forEach(b -> important.addAll(RuleSupport.strings(RuleSupport.map(b).get("evidenceEventIds"))));
        important.addAll(AiMemoryEntries.evidenceIds(memory));
        Map<String, Object> privateFacts = rules.privateData(state, actorId);
        collectEventIds(privateFacts, important);
        int start = Math.max(0, visible.size() - 48);
        List<Map<String, Object>> selected = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) if (i >= start || important.contains(visible.get(i).get("eventId"))
                || rules.retainObservationEvent(visible.get(i))) selected.add(visible.get(i));
        VisibleObservation base = new VisibleObservation(state.getGameId(), RuleSupport.text(state.getData().get("archiveId")), state.getPhase(), state.getRoundNumber(), actorId, turn.kind(),
                self, players, publicRules, selected, privateFacts, rules.legalActions(state, actorId), persona(state, actorId), memory, List.of());
        VisibleObservation complete = new VisibleObservation(base.gameId(), base.instanceId(), base.phase(), base.round(), base.actorId(), base.turnKind(), base.self(), base.players(), base.rules(), base.events(), base.privateFacts(), base.legalActions(), base.persona(), base.memory(), rules.knowledge(base));
        // A deep copy prevents asynchronous generation from retaining a live entity/map reference.
        return JSON.convertValue(JSON.convertValue(complete, Map.class), VisibleObservation.class);
    }
    private void collectEventIds(Object value, Set<String> ids) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("eventId") instanceof String id) ids.add(id);
            map.values().forEach(child -> collectEventIds(child, ids));
        } else if (value instanceof Collection<?> collection) collection.forEach(child -> collectEventIds(child, ids));
    }
    public List<Map<String, Object>> visibleEvents(GameState state, String actorId) {
        return RuleSupport.maps(state.getData().get("events")).stream()
                .filter(e -> "PUBLIC".equals(e.get("visibility")) || ("PRIVATE".equals(e.get("visibility")) && RuleSupport.strings(e.get("visibleTo")).contains(actorId)))
                .map(e -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    for (String key : List.of("eventId", "type", "actorId", "targetId", "message", "phase", "round", "data", "visibility")) result.put(key, e.get(key));
                    return result;
                }).toList();
    }
    private Map<String, Object> player(GameState state, GameRuleSet rules, GamePlayerState p, String actorId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("playerId", p.getPlayerId()); result.put("displayName", p.getDisplayName()); result.put("seatNumber", p.getSeatNumber());
        result.put("alive", p.isAlive()); result.put("ai", p.isAi());
        String role = rules.visibleRole(state, p, actorId); String word = rules.visibleWord(state, p, actorId);
        if (role != null) result.put("role", role); if (word != null) result.put("word", word);
        return result;
    }
    private Map<String, Object> persona(GameState state, String actorId) {
        GamePlayerState actor = state.getPlayers().stream().filter(p -> p.getPlayerId().equals(actorId)).findFirst().orElse(null);
        Persona persona = actor == null ? null : personas.findById(actor.getPersonaId());
        if (persona == null) return Map.of("name", RuleSupport.HOST.equals(actorId) ? "主持人" : "代管玩家", "trait", "认真、克制，尊重玩家此前立场", "presetVersion", 0);
        Map<String, Object> profile = new LinkedHashMap<>(Map.of("name", persona.getName(), "trait", persona.getTrait(), "speechStyle", persona.getSpeechStyle(),
                "strategyStyle", persona.getStrategyStyle(), "difficultyLevel", persona.getDifficultyLevel(),
                "riskPreference", persona.getRiskPreference(), "emotionalRecovery", persona.getEmotionalRecovery(),
                "sociability", persona.getSociability(), "memorySeed", persona.getMemorySeed()));
        Persona complete = PersonaPresets.complete(persona);
        profile.put("id", persona.getId()); profile.put("behaviorGuide", complete.getBehaviorGuide()); profile.put("presetVersion", complete.getPresetVersion());
        profile.put("difficulty", AiRoundReflection.difficulty(RuleSupport.number(RuleSupport.map(state.getData().get("rules")).get("aiDifficulty"), persona.getDifficultyLevel())));
        return profile;
    }
}
