package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.*;
import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiInformationSourcesTest {
    Map<String, Object> event(String type, String message, Map<String, Object> data) {
        return Map.of("eventId", "e", "type", type, "message", message, "actorId", "p0", "targetId", "p1", "data", data, "visibility", "PUBLIC");
    }
    @Test void playerClaimsDeathAndIdiotExemptionNeverGainAdditionalAuthority() {
        assertTrue(new WerewolfRuleSet().confirmedFacts( event("SPEECH", "我是预言家", Map.of("role", "SEER"))).isEmpty());
        assertEquals(Map.of("deadPlayerId", "p1"), new WerewolfRuleSet().confirmedFacts( event("DEATH", "玩家死亡，一定被毒了", Map.of("cause", "POISON"))));
        var idiot = new WerewolfRuleSet().confirmedFacts( event("IDIOT_REVEALED", "免于放逐", Map.of("role", "IDIOT", "immuneToNightDamage", true)));
        assertEquals(Map.of("playerId", "p0", "exemptFromThisExile", true, "canVote", false, "role", "IDIOT"), idiot);
        var vote = new WerewolfRuleSet().confirmedFacts( event("VOTE_REVEALED", "投给p1因为他是狼人", Map.of("abstain", false, "reason", "狼人")));
        assertEquals(Map.of("voterId", "p0", "targetPlayerId", "p1", "abstain", false), vote);
    }
    @Test void soupVerdictIsLimitedToItsSpecificQuestion() {
        var facts = new com.aisocialgame.engine.v2.turtlesoup.TurtleSoupRuleSet(new com.aisocialgame.engine.v2.turtlesoup.TurtleSoupCaseCatalog(new com.fasterxml.jackson.databind.ObjectMapper())).confirmedFacts( event("TURTLE_SOUP_QUESTION", "他看到东西了吗？是", Map.of("question", "他看到东西了吗？", "verdict", "YES", "answer", "是", "clues", List.of("所以一定是凶手"))));
        assertEquals(Map.of("question", "他看到东西了吗？", "verdict", "YES"), facts);
        assertTrue(new com.aisocialgame.engine.v2.turtlesoup.TurtleSoupRuleSet(new com.aisocialgame.engine.v2.turtlesoup.TurtleSoupCaseCatalog(new com.fasterxml.jackson.databind.ObjectMapper())).confirmedFacts( event("TURTLE_SOUP_SOLUTION_PENDING", "我是凶手", Map.of())).isEmpty());
    }
    @Test void classificationRunsAfterAclAndLastWordsRemainAnOldRetainedClaim() {
        GameState s = new GameState("r", "werewolf", "DAY_DISCUSS"); s.setRoundNumber(2); s.setCurrentSeat(0);
        s.setPlayers(List.of(new GamePlayerState("p0", "玩家零", 0, true, null, ""), new GamePlayerState("p1", "玩家一", 1, false, null, "")));
        player(s, "p0").setRole("SEER"); player(s, "p1").setRole("WEREWOLF");
        s.getData().put("werewolf", Map.of()); s.getData().put("archiveId", "i");
        RuleSupport.event(s, "LAST_WORDS", "p0", null, "我说自己是预言家", Map.of("statement", "PLAYER_CLAIM")); String old = text(maps(s.getData().get("events")).getLast().get("eventId"));
        RuleSupport.event(s, "SEER_CHECK", "p0", "p1", "私密查验", Map.of("result", "WOLF"), "PRIVATE", List.of("p0"));
        RuleSupport.event(s, "ROLE_ASSIGNED", "p1", null, "禁止观察者看到", Map.of("role", "SECRET_OTHER"), "PRIVATE", List.of("p1"));
        RuleSupport.event(s, "ROLE_ASSIGNED", "p0", null, "禁止看到上帝记录", Map.of("role", "SECRET_GOD"), "GOD", List.of("p0"));
        for (int i = 0; i < 60; i++) RuleSupport.event(s, "SPEECH", "p1", null, "另一条声明" + i, Map.of());
        AiMemoryServiceV2 memories = new AiMemoryServiceV2(mock(AiPersonaMemoryRepository.class), mock(PersonaRepository.class));
        var factory = new ObservationFactory(mock(PersonaRepository.class), memories); var rules = new WerewolfRuleSet();
        VisibleObservation o = factory.build(s, rules, new TurnRequest("p0", "SPEAK", "t"));
        assertTrue(o.events().stream().anyMatch(e -> old.equals(e.get("eventId"))));
        assertTrue(AiGrounding.context(o).get("ownStatements").toString().contains(old));
        String projection = AiInformationSources.project(rules, o).toString();
        assertFalse(projection.contains("SECRET_OTHER")); assertFalse(projection.contains("SECRET_GOD"));
        var record = maps(AiInformationSources.project(rules, o).get("events")).stream().filter(e -> old.equals(e.get("eventId"))).findFirst().orElseThrow();
        assertEquals("PLAYER_CLAIM", record.get("source")); assertEquals("PUBLIC", record.get("scope"));
        // Add a recent check to inspect its scope without relying on window retention.
        RuleSupport.event(s, "SEER_CHECK", "p0", "p1", "查验结果", Map.of("result", "WOLF"), "PRIVATE", List.of("p0"));
        o = factory.build(s, rules, new TurnRequest("p0", "SPEAK", "t"));
        var last = maps(AiInformationSources.project(rules, o).get("events")).getLast();
        assertEquals("SYSTEM_CONFIRMED", last.get("source")); assertEquals("PRIVATE_TO_OBSERVER", last.get("scope"));
        assertTrue(AiGrounding.check(o, Map.of("speech", "我之前说过“我说自己是预言家”。", "evidenceEventIds", List.of(old))).isEmpty());
    }
}
