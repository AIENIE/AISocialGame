package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.*;
import com.aisocialgame.engine.v2.undercover.*;
import com.aisocialgame.engine.v2.werewolf.*;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiConversationContextTest {
    final UndercoverRuleSet undercover = new UndercoverRuleSet(new UndercoverWordCatalog());
    final WerewolfRuleSet wolf = new WerewolfRuleSet();
    private Map<String,Object> event(String id, String type, String actor, String target, int round, String phase, Map<String,Object> data) {
        return new LinkedHashMap<>(Map.of("eventId", id, "type", type, "actorId", actor, "targetId", target, "round", round, "phase", phase, "visibility", "PUBLIC", "message", "test", "data", data));
    }
    private VisibleObservation observation(String game, String phase, List<LegalAction> actions, List<Map<String,Object>> events) {
        return new VisibleObservation(game, "i", phase, 2, "self", "ANSWER_PLAYER", Map.of(), List.of(), Map.of(), events, Map.of(), actions, Map.of(), Map.of(), List.of());
    }
    @Test void onlyCurrentUnansweredQuestionsWithMatchingCapabilityAreReferenced() {
        for (var rule : List.<GameRuleSet>of(undercover, wolf)) {
            String phase = rule == undercover ? "RESPONSE" : "DAY_INTERACTION", questionPhase = rule == undercover ? "CHALLENGE" : "DAY_INTERACTION";
            var actions = List.of(LegalAction.target("ANSWER_PLAYER", "reply", List.of("asker"), 60));
            var q = event("q", "ASK_PLAYER", "asker", "self", 2, questionPhase, Map.of());
            assertEquals("q", rule.conversation(observation(rule.gameId(), phase, actions, List.of(q))).replyTo().getFirst().eventId());
            for (String key : List.of("round", "phase", "targetId", "actorId", "visibility", "eventId")) {
                var wrong = new LinkedHashMap<>(q); wrong.put(key, "round".equals(key) ? 1 : "eventId".equals(key) ? "" : "wrong");
                assertTrue(rule.conversation(observation(rule.gameId(), phase, actions, List.of(wrong))).replyTo().isEmpty(), key);
            }
            assertTrue(rule.conversation(observation(rule.gameId(), phase, List.of(LegalAction.simple("SKIP", "skip")), List.of(q))).replyTo().isEmpty());
            for (Map<String,Object> data : List.of(Map.<String,Object>of(), Map.<String,Object>of("questionEventId", "q"))) {
                var answered = event("a", "ANSWER_PLAYER", "self", "asker", 2, phase, data);
                assertTrue(rule.conversation(observation(rule.gameId(), phase, actions, List.of(q, answered))).replyTo().isEmpty());
            }
            var skipped = event("skip", rule == undercover ? "SKIP" : "ANSWER_SKIPPED", "self", "asker", 2, phase, Map.of());
            assertTrue(rule.conversation(observation(rule.gameId(), phase, actions, List.of(q, skipped))).replyTo().isEmpty());
            var unrelated = event("a", "ANSWER_PLAYER", "other", "asker", 2, phase, Map.of("questionEventId", "q"));
            assertEquals(1, rule.conversation(observation(rule.gameId(), phase, actions, List.of(q, unrelated))).replyTo().size());
            var oldLink = event("a", "ANSWER_PLAYER", "self", "asker", 2, phase, Map.of("questionEventId", "old"));
            assertEquals(1, rule.conversation(observation(rule.gameId(), phase, actions, List.of(q, oldLink))).replyTo().size());
            var otherQuestion = event("q2", "ASK_PLAYER", "asker", "other", 2, questionPhase, Map.of());
            assertTrue(rule.conversation(observation(rule.gameId(), phase, actions, List.of(q, otherQuestion))).replyTo().isEmpty());
        }
    }
    @Test void skipPurposesRespectVotingNightSkillsAndOptionalSpeech() {
        var skip = List.of(LegalAction.simple("SKIP", "skip"));
        for (String phase : List.of("VOTING", "RUNOFF")) assertEquals(List.of("ABSTAIN"), undercover.conversation(observation("undercover", phase, skip, List.of())).actions().getFirst().purposes());
        for (var entry : Map.of("DAY_VOTE", "ABSTAIN", "NIGHT", "NIGHT_SKIP", "DEATH_ACTION", "DECLINE_SKILL", "DAY_DISCUSS", "YIELD").entrySet())
            assertEquals(List.of(entry.getValue()), wolf.conversation(observation("werewolf", entry.getKey(), skip, List.of())).actions().getFirst().purposes());
        var required = undercover.conversation(observation("undercover", "DESCRIPTION", List.of(LegalAction.text("SPEAK", "describe", 90)), List.of()));
        assertEquals(1, required.actions().size()); assertEquals(List.of("DESCRIBE"), required.actions().getFirst().purposes());
        var shot = wolf.conversation(observation("werewolf", "DEATH_ACTION", List.of(LegalAction.target("HUNTER_SHOOT", "shoot", List.of("other"), 120)), List.of()));
        assertTrue(shot.actions().getFirst().lengthGuide().contains("公开规则"));
        var skill = wolf.conversation(observation("werewolf", "NIGHT", List.of(LegalAction.night("WOLF_KILL", "kill", List.of("other"))), List.of()));
        assertTrue(skill.actions().getFirst().lengthGuide().contains("私密范围"));
    }
    @Test void defaultAdapterAddsNoGuessedReplyAndPrivacyFilterRunsBeforeConversation() {
        GameAiAdapter plugin = new GameAiAdapter() {
            public String gameId() { return "test-plugin"; }
            public String instruction(VisibleObservation o) { return ""; }
            public com.aisocialgame.dto.PlayerAction fallback(VisibleObservation o) { return action("SKIP", null, null); }
        };
        var q = event("secret", "ASK_PLAYER", "asker", "self", 2, "DAY_INTERACTION", Map.of());
        var actions = List.of(LegalAction.target("ANSWER_PLAYER", "reply", List.of("asker"), 120));
        assertTrue(plugin.conversation(observation("test-plugin", "DAY_INTERACTION", actions, List.of(q))).replyTo().isEmpty());
        q.put("visibility", "PRIVATE"); q.put("visibleTo", List.of("other")); q.put("message", "private-secret-body");
        var state = new GameState(); state.setData(new LinkedHashMap<>(Map.of("events", List.of(q))));
        var factory = new ObservationFactory(new PersonaRepository(), new AiMemoryServiceV2(mock(AiPersonaMemoryRepository.class), new PersonaRepository()));
        var visible = factory.visibleEvents(state, "self"); assertTrue(visible.isEmpty());
        var payload = AiPromptProjection.project(wolf, observation("werewolf", "DAY_INTERACTION", actions, visible));
        assertFalse(payload.toString().contains("secret"));
        assertTrue(((AiConversationContext) payload.get("conversation")).replyTo().isEmpty());
    }
}
