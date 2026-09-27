package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.engine.v2.GameRuleSet;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.AiPersonaMemoryRepository;
import com.aisocialgame.repository.PersonaRepository;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AiMemoryServiceV2 {
    private static final Set<String> UPDATE_KEYS = Set.of("beliefs", "relationships", "emotion", "commitments", "hypotheses", "usedDescriptions", "commitmentWithdrawals");
    private final AiPersonaMemoryRepository repository;
    private final PersonaRepository personas;
    public AiMemoryServiceV2(AiPersonaMemoryRepository repository, PersonaRepository personas) {
        this.repository = repository; this.personas = personas;
    }
    public Map<String, Object> snapshot(GameState state, String actorId) {
        Map<String, Object> memory = RuleSupport.map(RuleSupport.map(state.getData().get("aiMemoriesV2")).get(actorId));
        boolean currentFormat = RuleSupport.number(memory.get("formatVersion"), 1) >= 2;
        for (String key : AiMemoryEntries.KEYS) {
            if (memory.containsKey(key)) memory.put(key, "commitments".equals(key)
                    ? AiCommitments.stored(memory.get(key), RuleSupport.number(memory.get("formatVersion"), 1), RuleSupport.text(state.getData().get("archiveId")), actorId)
                    : AiMemoryEntries.stored(memory.get(key), true, currentFormat));
        }
        if (!memory.isEmpty()) memory.put("formatVersion", AiMemoryEntries.FORMAT_VERSION);
        Map<String, Object> emotion = RuleSupport.map(memory.get("emotion"));
        GamePlayerState actor = state.getPlayers().stream().filter(p -> p.getPlayerId().equals(actorId)).findFirst().orElse(null);
        if (!emotion.isEmpty()) {
            Persona persona = actor == null ? null : personas.findById(actor.getPersonaId());
            int elapsed = Math.max(0, state.getRoundNumber() - RuleSupport.number(emotion.get("round"), state.getRoundNumber()));
            int recovery = persona == null ? 1 : Math.max(1, persona.getEmotionalRecovery() - 1);
            emotion.put("intensity", Math.max(0, RuleSupport.number(emotion.get("intensity"), 0) - elapsed * recovery));
            // A committed snapshot becomes the next decay baseline; multiple actions in this
            // round must not apply the elapsed rounds for a second time.
            emotion.put("round", state.getRoundNumber());
            memory.put("emotion", emotion);
        }
        if (actor != null && actor.getPersonaId() != null) {
            repository.findByPersonaIdAndGameIdAndRoleKey(actor.getPersonaId(), state.getGameId(), "GENERAL_V2")
                    .filter(m -> "APPROVED".equals(m.getReviewStatus()))
                    .ifPresent(m -> memory.put("approvedExperience", cut(m.getApprovedSummary(), 1200)));
        }
        return memory;
    }
    public List<String> validate(VisibleObservation observation, Map<String, Object> updates) {
        List<String> errors = new ArrayList<>();
        if (!UPDATE_KEYS.containsAll(updates.keySet())) errors.add("UNSUPPORTED_MEMORY_UPDATE");
        Set<String> eventIds = new HashSet<>();
        observation.events().forEach(e -> eventIds.add(RuleSupport.text(e.get("eventId"))));
        for (String key : AiMemoryEntries.KEYS) {
            errors.addAll(("commitments".equals(key) ? AiCommitments.proposals(updates.get(key), eventIds, observation.round())
                    : AiMemoryEntries.proposals(updates.get(key), true, eventIds, observation.round())).errors());
        }
        errors.addAll(AiCommitments.validateWithdrawals(updates.get("commitmentWithdrawals"), eventIds));
        Set<String> players = new HashSet<>();
        observation.players().forEach(p -> players.add(RuleSupport.text(p.get("playerId"))));
        for (Map<String, Object> belief : RuleSupport.maps(updates.get("beliefs"))) {
            if (!players.contains(RuleSupport.text(belief.get("playerId")))) errors.add("UNKNOWN_BELIEF_PLAYER");
            if (!eventIds.containsAll(RuleSupport.strings(belief.get("evidenceEventIds")))) errors.add("INVISIBLE_MEMORY_EVIDENCE");
        }
        for (Map<String, Object> relation : RuleSupport.maps(updates.get("relationships"))) {
            if (!players.contains(RuleSupport.text(relation.get("playerId"))) || !eventIds.contains(RuleSupport.text(relation.get("eventId")))) errors.add("UNGROUNDED_RELATIONSHIP");
        }
        Map<String, Object> emotion = RuleSupport.map(updates.get("emotion"));
        if (!emotion.isEmpty() && (!eventIds.contains(RuleSupport.text(emotion.get("eventId"))) || !AiTurnGenerator.EMOTIONS.contains(RuleSupport.text(emotion.get("name"))))) errors.add("UNGROUNDED_EMOTION");
        return errors;
    }
    /** Called inside the same transaction that has committed the validated game action. */
    public void commit(GameState state, String actorId, AiTurnDecision decision, VisibleObservation observation) {
        commit(state, actorId, decision, observation, null, RuleSupport.maps(state.getData().get("events")).size());
    }
    public void commit(GameState state, String actorId, AiTurnDecision decision, VisibleObservation observation, GameRuleSet rules, int newEventStart) {
        if (RuleSupport.HOST.equals(actorId)) return;
        Set<String> eventIds = new HashSet<>();
        observation.events().forEach(e -> eventIds.add(RuleSupport.text(e.get("eventId"))));
        Map<String, AiMemoryEntries.Parsed> proposals = new LinkedHashMap<>();
        for (String key : AiMemoryEntries.KEYS) {
            var parsed = "commitments".equals(key) ? AiCommitments.proposals(decision.memoryUpdates().get(key), eventIds, observation.round())
                    : AiMemoryEntries.proposals(decision.memoryUpdates().get(key), true, eventIds, observation.round());
            if (!parsed.errors().isEmpty()) throw new IllegalArgumentException(String.join(",", parsed.errors()));
            proposals.put(key, parsed);
        }
        List<String> withdrawalErrors = AiCommitments.validateWithdrawals(decision.memoryUpdates().get("commitmentWithdrawals"), eventIds);
        if (!withdrawalErrors.isEmpty()) throw new IllegalArgumentException(String.join(",", withdrawalErrors));
        Map<String, Object> all = RuleSupport.map(state.getData().get("aiMemoriesV2"));
        Map<String, Object> memory = snapshot(state, actorId);
        memory.remove("approvedExperience");
        memory.put("formatVersion", AiMemoryEntries.FORMAT_VERSION);
        Map<String, Object> updates = decision.memoryUpdates();
        Map<String, Object> beliefs = RuleSupport.map(memory.get("beliefs"));
        for (Map<String, Object> value : RuleSupport.maps(updates.get("beliefs")).stream().limit(12).toList()) {
            String id = RuleSupport.text(value.get("playerId"));
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("hypothesis", cut(RuleSupport.text(value.get("hypothesis")), 180));
            safe.put("confidence", clampDouble(value.get("confidence"), 0, 1));
            safe.put("evidenceEventIds", RuleSupport.strings(value.get("evidenceEventIds")).stream().limit(6).toList());
            safe.put("round", state.getRoundNumber()); beliefs.put(id, safe);
        }
        memory.put("beliefs", beliefs);
        Map<String, Object> relationships = RuleSupport.map(memory.get("relationships"));
        for (Map<String, Object> value : RuleSupport.maps(updates.get("relationships")).stream().limit(12).toList()) {
            String id = RuleSupport.text(value.get("playerId"));
            Map<String, Object> previous = RuleSupport.map(relationships.get(id));
            int delta = Math.max(-1, Math.min(1, RuleSupport.number(value.get("trustDelta"), 0)));
            int trust = Math.max(-3, Math.min(3, RuleSupport.number(previous.get("trust"), 0) + delta));
            relationships.put(id, Map.of("trust", trust, "eventId", RuleSupport.text(value.get("eventId"))));
        }
        memory.put("relationships", relationships);
        Map<String, Object> emotion = RuleSupport.map(updates.get("emotion"));
        if (!emotion.isEmpty()) memory.put("emotion", Map.of("name", RuleSupport.text(emotion.get("name")),
                "intensity", Math.max(0, Math.min(3, RuleSupport.number(emotion.get("intensity"), 0))),
                "eventId", RuleSupport.text(emotion.get("eventId")), "round", state.getRoundNumber()));
        memory.put("hypotheses", AiMemoryEntries.merge(AiMemoryEntries.stored(memory.get("hypotheses"), true, true), proposals.get("hypotheses").entries()));
        AiCommitments.update(state, rules, actorId, memory, decision, observation, newEventStart);
        LinkedHashSet<String> descriptions = new LinkedHashSet<>(RuleSupport.strings(memory.get("usedDescriptions")));
        RuleSupport.strings(updates.get("usedDescriptions")).stream().limit(8).map(s -> cut(s, 160)).forEach(descriptions::add);
        if ("undercover".equals(observation.gameId()) && !decision.speech().isBlank()) {
            Map<String, Object> knowledge = RuleSupport.map(observation.privateFacts().get("wordKnowledge"));
            for (String dimension : List.of("attributes", "scenes")) {
                RuleSupport.strings(knowledge.get(dimension)).stream().filter(c -> !c.isBlank() && decision.speech().contains(c))
                        .map(c -> cut(c, 160)).forEach(descriptions::add);
            }
        }
        List<String> used = new ArrayList<>(descriptions);
        memory.put("usedDescriptions", used.subList(Math.max(0, used.size() - 16), used.size()));
        List<Map<String, Object>> recent = RuleSupport.maps(memory.get("recentDecisions"));
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("action", decision.action().getType()); last.put("targetPlayerId", decision.action().getTargetPlayerId());
        last.put("speech", cut(decision.speech(), 240)); last.put("round", observation.round());
        last.put("evidenceEventIds", decision.evidenceEventIds()); recent.add(last);
        memory.put("recentDecisions", recent.subList(Math.max(0, recent.size() - 8), recent.size()));
        AiRoundReflection.decision(memory, observation.memory(), observation);
        all.put(actorId, memory); state.getData().put("aiMemoriesV2", all);
    }
    public void normalizeOnAction(GameState state, String actorId) {
        Map<String, Object> all = RuleSupport.map(state.getData().get("aiMemoriesV2"));
        if (!all.containsKey(actorId)) return;
        Map<String, Object> normalized = snapshot(state, actorId); normalized.remove("approvedExperience");
        all.put(actorId, normalized); state.getData().put("aiMemoriesV2", all);
    }
    public void finishGame(GameState state) {
        if (Boolean.TRUE.equals(state.getData().get("v2MemoryCounted"))) return;
        Set<String> ids = new HashSet<>();
        for (GamePlayerState player : state.getPlayers()) {
            if (!player.isAi() || player.getPersonaId() == null || !ids.add(player.getPersonaId())) continue;
            repository.incrementGamesPlayed(player.getPersonaId(), state.getGameId());
            repository.findByPersonaIdAndGameIdAndRoleKey(player.getPersonaId(),state.getGameId(),"GENERAL_V2").ifPresent(candidate -> {
                candidate.setMemorySummary("区分公开记录、个人假说与策略声明；新证据出现后核对判断；行动变化保留真实承诺记录。");
                // Candidate text is an allowlisted general lesson, not player text. Approved summaries remain separate.
                repository.save(candidate);
            });
        }
        state.getData().put("v2MemoryCounted", true);
    }
    private static double clampDouble(Object value, double min, double max) {
        double number = value instanceof Number n ? n.doubleValue() : 0.5;
        return Double.isFinite(number) ? Math.max(min, Math.min(max, number)) : 0.5;
    }
    private static String cut(String value, int length) { return AiMemoryEntries.cut(value, length); }
}
