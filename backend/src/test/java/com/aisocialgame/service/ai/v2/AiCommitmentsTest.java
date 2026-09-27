package com.aisocialgame.service.ai.v2;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.engine.v2.*;
import com.aisocialgame.engine.v2.undercover.*;
import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.LocalDateTime;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiCommitmentsTest {
    final UndercoverRuleSet undercover = new UndercoverRuleSet(new UndercoverWordCatalog());
    final WerewolfRuleSet werewolf = new WerewolfRuleSet();
    final AiMemoryServiceV2 memories = new AiMemoryServiceV2(mock(AiPersonaMemoryRepository.class), mock(PersonaRepository.class));
    final ObservationFactory observations = new ObservationFactory(mock(PersonaRepository.class), memories);
    final LocalDateTime now = LocalDateTime.now();

    GameState state(String game) {
        GameState s = new GameState("room", game, "undercover".equals(game) ? "DESCRIPTION" : "DAY_DISCUSS");
        s.setRoundNumber(1); s.setCurrentSeat(0); s.setPhaseEndsAt(now.plusMinutes(10));
        s.getData().put("archiveId", "instance");
        s.setPlayers(new ArrayList<>());
        for (int i = 0; i < 6; i++) {
            GamePlayerState p = new GamePlayerState("p" + i, "玩家" + i, i, true, null, "");
            p.setRole(i == 5 ? "WEREWOLF" : "VILLAGER"); p.setWord("咖啡"); s.getPlayers().add(p);
        }
        if ("werewolf".equals(game)) s.getData().put("werewolf", new LinkedHashMap<>(Map.of("revealedIdiots", List.of(), "votes", Map.of())));
        return s;
    }
    GameRuleSet rules(GameState s) { return "undercover".equals(s.getGameId()) ? undercover : werewolf; }
    Map<String, Object> contract(String quote, String kind, String target, int offset, String ballot) {
        Map<String, Object> a = new LinkedHashMap<>(); a.put("kind", kind); a.put("ballot", ballot);
        if (target != null) a.put("targetPlayerId", target);
        return Map.of("text", quote, "sourceQuote", quote, "action", a, "roundOffset", offset);
    }
    void speak(GameState s, String speech, Map<String, Object> updates) {
        VisibleObservation o = observations.build(s, rules(s), new TurnRequest("p0", "SPEAK", "test"));
        assertTrue(memories.validate(o, updates).isEmpty());
        int start = maps(s.getData().get("events")).size();
        event(s, "undercover".equals(s.getGameId()) ? "SPEAK" : "SPEECH", "p0", null, speech, Map.of("content", speech));
        memories.commit(s, "p0", new AiTurnDecision(action("SPEAK", speech, null), speech, Map.of(), List.of(), updates, false, Map.of()), o, rules(s), start);
    }
    void promise(GameState s, String quote, String kind, String target, int offset, String ballot) {
        speak(s, quote, Map.of("commitments", List.of(contract(quote, kind, target, offset, ballot))));
    }
    List<Map<String, Object>> entries(GameState s) { return maps(memories.snapshot(s, "p0").get("commitments")); }
    String status(GameState s, int index) { return text(entries(s).get(index).get("status")); }
    void submit(GameState s, PlayerAction a, String origin) {
        var receipt = rules(s).commitmentAction(s, "p0", a); int start = maps(s.getData().get("events")).size();
        AiCommitments.reconcile(s, rules(s), "OPPORTUNITY_LOST");
        rules(s).apply(s, "p0", a, now);
        AiCommitments.submitted(s, receipt, origin, start);
        AiCommitments.reconcile(s, rules(s), "OPPORTUNITY_LOST");
    }

    @ParameterizedTest @CsvSource({"undercover,VOTING", "werewolf,DAY_VOTE"})
    void offlinePromiseNewEvidenceChangedVoteAndNextObservation(String game, String phase) {
        GameState s = state(game); promise(s, "我本轮投给p1", "VOTE", "p1", 0, "NORMAL");
        assertEquals("ACTIVE", status(s, 0));
        String id = text(entries(s).getFirst().get("id"));
        event(s, "ANSWER_PLAYER", "p1", "p0", "这条解释推翻了你的理由", Map.of("content", "这条解释推翻了你的理由"));
        s.setPhase(phase); submit(s, action("VOTE", "", "p2"), "MODEL");
        assertEquals("NOT_FULFILLED", status(s, 0));
        Map<String, Object> resolution = map(entries(s).getFirst().get("resolution"));
        assertEquals("DIFFERENT_ACTION", resolution.get("reason")); assertEquals("MODEL", resolution.get("origin"));
        assertFalse(strings(resolution.get("evidenceEventIds")).isEmpty());
        VisibleObservation next = observations.build(s, rules(s), new TurnRequest("p0", "SPEAK", "next"));
        assertEquals(id, maps(next.memory().get("commitments")).getFirst().get("id"));
        assertTrue(AiGrounding.context(next).toString().contains("NOT_FULFILLED"));
        VisibleObservation other = observations.build(s, rules(s), new TurnRequest("p3", "SPEAK", "other"));
        assertFalse(other.memory().toString().contains(id));
        assertTrue(other.events().stream().noneMatch(e -> strings(resolution.get("evidenceEventIds")).contains(text(e.get("eventId"))) && "PRIVATE".equals(e.get("visibility"))));
        var terminal = entries(s);
        speak(s, "我撤回之前的承诺", Map.of("commitmentWithdrawals", List.of(Map.of("id", id, "sourceQuote", "我撤回之前的承诺"))));
        assertEquals(terminal, entries(s));
    }
    @ParameterizedTest @CsvSource({"undercover,VOTING", "werewolf,DAY_VOTE"})
    void votesAndAbstentionsUseActualActionEvenForFallback(String game, String phase) {
        for (boolean abstain : List.of(false, true)) {
            GameState s = state(game);
            promise(s, abstain ? "我本轮弃票" : "我本轮投给p1", abstain ? "ABSTAIN" : "VOTE", abstain ? null : "p1", 0, "NORMAL");
            s.setPhase(phase); submit(s, action(abstain ? "SKIP" : "VOTE", "", abstain ? null : "p1"), "LEGAL_FALLBACK");
            assertEquals("FULFILLED", status(s, 0)); assertEquals("LEGAL_FALLBACK", map(entries(s).getFirst().get("resolution")).get("origin"));
        }
    }
    @Test void normalRunoffAndNextRoundHaveSeparateIdentity() {
        GameState s = state("undercover");
        promise(s, "我本轮投给p1", "VOTE", "p1", 0, "NORMAL");
        promise(s, "我本轮复投投给p1", "VOTE", "p1", 0, "RUNOFF");
        promise(s, "我下轮投给p1", "VOTE", "p1", 1, "NORMAL");
        s.setPhase("VOTING"); submit(s, action("VOTE", "", "p1"), "HUMAN");
        assertEquals(List.of("FULFILLED", "ACTIVE", "ACTIVE"), entries(s).stream().map(e -> e.get("status")).toList());
        s.setPhase("RUNOFF"); s.getData().put("votes", Map.of()); s.getData().put("runoffCandidates", List.of("p1", "p2"));
        submit(s, action("SKIP", "", null), "TIMEOUT_FALLBACK");
        assertEquals(List.of("FULFILLED", "NOT_FULFILLED", "ACTIVE"), entries(s).stream().map(e -> e.get("status")).toList());
        s.setRoundNumber(2); s.setPhase("VOTING"); s.getData().put("votes", Map.of());
        submit(s, action("VOTE", "", "p1"), "HUMAN"); assertEquals("FULFILLED", status(s, 2));
        assertEquals(3, entries(s).stream().map(e -> map(e.get("opportunity")).get("id")).distinct().count());
    }
    @ParameterizedTest @CsvSource({"WEREWOLF,WOLF_KILL,刀", "SEER,SEER_CHECK,查验", "GUARD,GUARD_PROTECT,守护", "WITCH,WITCH_SAVE,救", "WITCH,WITCH_POISON,毒", "SEER,NIGHT_SKIP,跳过夜间行动"})
    void nightChoicesAreCheckedAgainstActorAndSkillNotOutcome(String role, String kind, String verb) {
        for (boolean match : List.of(true, false)) {
            GameState s = state("werewolf"); player(s, "p0").setRole(role);
            String target = "NIGHT_SKIP".equals(kind) ? null : "p1";
            promise(s, "我下一夜会" + verb + (target == null ? "" : target), kind, target, 1, "NORMAL");
            assertEquals("ACTIVE", status(s, 0)); s.setRoundNumber(2); s.setPhase("NIGHT");
            Map<String, Object> d = map(s.getData().get("werewolf")); d.put("nightActor", "p0"); d.put("nightQueue", List.of("p0", "p5")); d.put("nightIndex", 0); d.put("wolfTarget", "p1"); s.getData().put("werewolf", d);
            boolean skip = "NIGHT_SKIP".equals(kind) == match;
            PlayerAction a = action(skip ? "SKIP" : "NIGHT_ACTION", "", skip ? null : "p1");
            a.setNightAction("NIGHT_SKIP".equals(kind) ? "SEER_CHECK" : kind); a.setUseHeal(true);
            submit(s, a, "MODEL"); assertEquals(match ? "FULFILLED" : "NOT_FULFILLED", status(s, 0));
            assertTrue(player(s, "p1").isAlive(), "selection is fulfilled before any final night outcome");
        }
    }
    @Test void explicitWithdrawalRequiresLaterSpeechAndCannotRewriteTerminal() {
        GameState s = state("undercover"); promise(s, "我本轮投给p1", "VOTE", "p1", 0, "NORMAL");
        String id = text(entries(s).getFirst().get("id"));
        speak(s, "我仍在考虑", Map.of("commitmentWithdrawals", List.of(Map.of("id", id, "sourceQuote", "我撤回承诺"))));
        assertEquals("ACTIVE", status(s, 0));
        speak(s, "我撤回投给p1的承诺", Map.of("commitmentWithdrawals", List.of(Map.of("id", id, "sourceQuote", "我撤回投给p1的承诺"))));
        assertEquals("WITHDRAWN", status(s, 0));
        s.setPhase("VOTING"); submit(s, action("VOTE", "", "p1"), "MODEL"); assertEquals("WITHDRAWN", status(s, 0));
    }
    @Test void ambiguityBluffConditionsMissingSourceAndWrongScopeStayUnverified() {
        for (String quote : List.of("如果有反证我本轮投给p1", "我可能本轮投给p1", "我刚才投给p1", "我本轮投给p1吗？")) {
            GameState s = state("undercover"); promise(s, quote, "VOTE", "p1", 0, "NORMAL"); assertEquals("UNVERIFIED", status(s, 0));
        }
        GameState s = state("werewolf");
        promise(s, "我下一夜会查验p1", "SEER_CHECK", "p1", 1, "NORMAL"); assertEquals("UNVERIFIED", status(s, 0));
        promise(s, "我本轮投给p1", "VOTE", "p1", 1, "NORMAL"); assertEquals("UNVERIFIED", status(s, 1));
        speak(s, "我先听听", Map.of("commitments", List.of(contract("我本轮投给p2", "VOTE", "p2", 0, "NORMAL")))); assertEquals("UNVERIFIED", status(s, 2));
        promise(s, "我下轮投给p1", "VOTE", "p1", 4, "NORMAL"); assertEquals("UNVERIFIED", status(s, 3));
        promise(s, "我本轮复投投给p1", "VOTE", "p1", 0, "RUNOFF"); assertEquals("UNVERIFIED", status(s, 4));
        assertTrue(AiGrounding.check(observations.build(s, werewolf, new TurnRequest("p0", "SPEAK", "bluff")), Map.of("speech", "我是预言家")).isEmpty());
    }
    @Test void expiryReasonsAndCapacityNeverDiscardActiveEntries() {
        for (String reason : List.of("ACTOR_DEAD", "TARGET_UNAVAILABLE", "GAME_ENDED", "OPPORTUNITY_ENDED")) {
            GameState s = state("undercover"); promise(s, "我本轮投给p1", "VOTE", "p1", 0, "NORMAL");
            switch (reason) { case "ACTOR_DEAD" -> player(s, "p0").setAlive(false); case "TARGET_UNAVAILABLE" -> player(s, "p1").setAlive(false); case "GAME_ENDED" -> s.setPhase("SETTLEMENT"); default -> s.setRoundNumber(2); }
            AiCommitments.reconcile(s, undercover, "TIMEOUT"); assertEquals("EXPIRED", status(s, 0));
            assertEquals(reason, map(entries(s).getFirst().get("resolution")).get("reason"));
        }
        GameState s = state("undercover");
        for (int i = 0; i < 17; i++) promise(s, "我本轮投给p1，第" + i + "次表态", "VOTE", "p1", 0, "NORMAL");
        assertEquals(16, entries(s).size()); assertTrue(entries(s).stream().allMatch(e -> "ACTIVE".equals(e.get("status"))));
        assertEquals(List.of("COMMITMENT_CAPACITY_REACHED"), memories.snapshot(s, "p0").get("commitmentQualityFlags"));
        String first = text(entries(s).getFirst().get("id"));
        speak(s, "我撤回投给p1的承诺", Map.of("commitmentWithdrawals", List.of(Map.of("id", first, "sourceQuote", "我撤回投给p1的承诺"))));
        promise(s, "我本轮投给p2", "VOTE", "p2", 0, "NORMAL"); assertEquals(16, entries(s).size()); assertTrue(entries(s).stream().noneMatch(e -> first.equals(e.get("id"))));
    }
    @Test void legacyProvenanceIsPreservedWithoutReadMutationOrRetroactiveFulfillment() {
        GameState s = state("undercover");
        Map<String, Object> original = Map.of("p0", Map.of("formatVersion", 2, "commitments", List.of(Map.of("text", "我本轮投给p1", "evidenceEventIds", List.of("old"), "round", 1, "source", "MODEL_PROPOSAL")),
                "hypotheses", List.of(Map.of("text", "一个猜测", "evidenceEventIds", List.of("old"), "round", 1, "source", "MODEL_PROPOSAL", "confidence", .5))));
        s.getData().put("aiMemoriesV2", original);
        var first = memories.snapshot(s, "p0"); assertEquals(first, memories.snapshot(s, "p0")); assertEquals(original, s.getData().get("aiMemoriesV2"));
        assertEquals("MODEL_PROPOSAL", entries(s).getFirst().get("source")); assertEquals(1, entries(s).getFirst().get("round"));
        assertEquals("UNVERIFIED", status(s, 0)); assertEquals(.5, maps(first.get("hypotheses")).getFirst().get("confidence"));
        AiCommitments.reconcile(s, undercover, "TIMEOUT"); assertEquals(original, s.getData().get("aiMemoriesV2"));
        s.setPhase("VOTING"); memories.normalizeOnAction(s, "p0"); submit(s, action("VOTE", "", "p1"), "HUMAN");
        assertEquals("UNVERIFIED", status(s, 0)); assertEquals(AiMemoryEntries.FORMAT_VERSION, map(map(s.getData().get("aiMemoriesV2")).get("p0")).get("formatVersion"));
        assertTrue(memories.snapshot(state("undercover"), "p0").isEmpty());
    }
    @Test void codecRejectsServerFieldsWrongTypesInvisibleEvidenceAndConflictingAliases() {
        for (Map<String, Object> p : List.of(Map.<String,Object>of("text", "承诺", "status", "FULFILLED"), Map.<String,Object>of("text", "承诺", "roundOffset", .5),
                Map.<String,Object>of("text", "承诺", "action", "VOTE"), Map.<String,Object>of("text", "承诺", "sourceQuote", 123)))
            assertFalse(AiCommitments.proposals(List.of(p), Set.of(), 1).errors().isEmpty());
        assertEquals(List.of("INVISIBLE_MEMORY_EVIDENCE"), AiCommitments.proposals(List.of(Map.of("text", "承诺", "eventId", "secret")), Set.of(), 1).errors());
        assertEquals(List.of("CONFLICTING_MEMORY_FIELDS"), AiCommitments.proposals(List.of(Map.of("text", "a", "content", "b")), Set.of(), 1).errors());
        assertEquals(List.of("INVALID_MEMORY_CONFIDENCE"), AiCommitments.proposals(List.of(Map.of("text", "承诺", "confidence", .5)), Set.of(), 1).errors());
        var parsed = AiCommitments.proposals(Collections.nCopies(12, Map.of("content", "😀".repeat(170), "eventId", "e")), Set.of("e"), 1);
        assertTrue(parsed.errors().isEmpty()); assertEquals(8, parsed.entries().size()); assertEquals(160, text(parsed.entries().getFirst().get("text")).codePointCount(0, text(parsed.entries().getFirst().get("text")).length()));
    }
    @Test void identicalTextInDifferentRoundsGetsDifferentIdsAndCannotBeMovedByAStaleReference() {
        GameState s = state("undercover"); promise(s, "我会投给p1", "VOTE", "p1", 0, "NORMAL");
        String id = text(entries(s).getFirst().get("id"));
        s.setPhase("VOTING"); submit(s, action("VOTE", "", "p1"), "MODEL");
        s.setRoundNumber(2); s.setPhase("DESCRIPTION"); s.getData().put("votes", Map.of());
        promise(s, "我会投给p1", "VOTE", "p1", 0, "NORMAL");
        assertEquals(2, entries(s).size()); assertNotEquals(id, entries(s).getLast().get("id"));
        assertEquals(List.of("FULFILLED", "ACTIVE"), entries(s).stream().map(e -> e.get("status")).toList());
        assertEquals(1, map(entries(s).getFirst().get("opportunity")).get("round"));
        assertEquals(2, map(entries(s).getLast().get("opportunity")).get("round"));
    }
    @Test void unavailableMedicineGuardTargetAndVoteRightsExpireAtTheCorrectOpportunity() {
        for (String role : List.of("WITCH", "GUARD", "IDIOT")) {
            GameState s = state("werewolf"); player(s, "p0").setRole(role);
            String kind = "WITCH".equals(role) ? "WITCH_POISON" : "GUARD".equals(role) ? "GUARD_PROTECT" : "VOTE";
            String quote = "WITCH".equals(role) ? "我下一夜会毒p1" : "GUARD".equals(role) ? "我下一夜会守护p1" : "我下轮投给p1";
            promise(s, quote, kind, "p1", 1, "NORMAL"); assertEquals("ACTIVE", status(s, 0));
            s.setRoundNumber(2); s.setPhase("IDIOT".equals(role) ? "DAY_VOTE" : "NIGHT");
            var d = map(s.getData().get("werewolf")); d.put("nightQueue", List.of("p0", "p5")); d.put("nightIndex", 0); d.put("nightActor", "p0");
            d.put("poisonUsed", true); d.put("guardLastTargets", Map.of("p0", "p1"));
            if ("IDIOT".equals(role)) d.put("revealedIdiots", List.of("p0"));
            s.getData().put("werewolf", d); AiCommitments.reconcile(s, werewolf, "OPPORTUNITY_LOST");
            assertEquals("EXPIRED", status(s, 0));
            assertEquals("IDIOT".equals(role) ? "VOTE_RIGHT_LOST" : "ACTION_UNAVAILABLE", map(entries(s).getFirst().get("resolution")).get("reason"));
        }
    }
    @Test void automaticNightTimeoutCannotFulfillSkipPromiseAndDeathInterruptionClosesOldNight() {
        GameState s = state("werewolf"); player(s, "p0").setRole("SEER");
        promise(s, "我下一夜会跳过夜间行动", "NIGHT_SKIP", null, 1, "NORMAL");
        s.setRoundNumber(2); s.setPhase("NIGHT"); s.setPhaseEndsAt(now.minusSeconds(1));
        s.getData().put("werewolf", new LinkedHashMap<>(Map.of("nightActor", "p0", "nightQueue", List.of("p0", "p5"), "nightIndex", 0)));
        werewolf.advance(s, now); AiCommitments.reconcile(s, werewolf, "TIMEOUT");
        assertEquals("EXPIRED", status(s, 0)); assertEquals("TIMEOUT", map(entries(s).getFirst().get("resolution")).get("origin"));
        assertTrue(map(entries(s).getFirst().get("resolution")).containsKey("stateBasis"));
        // A hunter/last-words interruption must not leave a completed night's promise active.
        GameState interrupted = state("werewolf"); player(interrupted, "p0").setRole("SEER");
        promise(interrupted, "我下一夜会查验p1", "SEER_CHECK", "p1", 1, "NORMAL");
        interrupted.setRoundNumber(2); interrupted.setPhase("LAST_WORDS");
        interrupted.getData().put("werewolf", Map.of("afterDeaths", "DAY_DISCUSS"));
        AiCommitments.reconcile(interrupted, werewolf, "OPPORTUNITY_LOST"); assertEquals("EXPIRED", status(interrupted, 0));
    }
    @Test void explicitCurrentNightAndNormalizedHumanDeclinedHealMatchTheirOwnOpportunity() {
        GameState s = state("werewolf"); player(s, "p0").setRole("WITCH"); s.setPhase("NIGHT");
        s.getData().put("werewolf", new LinkedHashMap<>(Map.of("nightActor", "p0", "nightQueue", List.of("p0", "p5"), "nightIndex", 0, "wolfTarget", "p1")));
        promise(s, "我本夜会留药", "NIGHT_SKIP", null, 0, "NORMAL"); assertEquals("ACTIVE", status(s, 0));
        PlayerAction declined = action("NIGHT_ACTION", "", "p1"); declined.setNightAction("WITCH_SAVE"); declined.setUseHeal(false);
        submit(s, declined, "HUMAN"); assertEquals("FULFILLED", status(s, 0));
        assertEquals("NIGHT_SKIP", map(map(entries(s).getFirst().get("resolution")).get("actualAction")).get("kind"));
    }
    @Test void conditionalSourceCannotBeTruncatedIntoAnUnconditionalPromise() {
        GameState s = state("undercover");
        speak(s, "如果新的证据成立，我本轮投给p1", Map.of("commitments", List.of(contract("我本轮投给p1", "VOTE", "p1", 0, "NORMAL"))));
        assertEquals("UNVERIFIED", status(s, 0));
        assertFalse(AiCommitments.validateWithdrawals(List.of(Map.of("id", "id", "sourceQuote", "撤回", "status", "FULFILLED")), Set.of()).isEmpty());
    }
    @Test void soupPromisesRemainUnverifiedEvenWhenTheyLookLikeAnActionContract() {
        GameState s = state("turtle_soup");
        var soup = new com.aisocialgame.engine.v2.turtlesoup.TurtleSoupRuleSet(new com.aisocialgame.engine.v2.turtlesoup.TurtleSoupCaseCatalog(new com.fasterxml.jackson.databind.ObjectMapper()));
        var o = new VisibleObservation("turtle_soup", "instance", "DISCUSSION", 1, "p0", "DISCUSS", Map.of(), List.of(), Map.of(), List.of(), Map.of(), List.of(), Map.of(), Map.of(), List.of());
        String quote = "我本轮投给p1";
        event(s, "TURTLE_SOUP_DISCUSSION", "p0", null, quote, Map.of("content", quote));
        var decision = new AiTurnDecision(action("DISCUSS", quote, null), quote, Map.of(), List.of(), Map.of("commitments", List.of(contract(quote, "VOTE", "p1", 0, "NORMAL"))), false, Map.of());
        memories.commit(s, "p0", decision, o, soup, 0); assertEquals("UNVERIFIED", status(s, 0));
        s.setPhase("SETTLEMENT"); AiCommitments.reconcile(s, soup, "OPPORTUNITY_LOST"); assertEquals("UNVERIFIED", status(s, 0));
    }
    @Test void sourceMustDescribeTheSpecifiedTargetAndWithdrawalMustMatchTheReferencedContract() {
        GameState s = state("undercover");
        speak(s, "听了p2的解释，我本轮投给p1", Map.of("commitments", List.of(contract("听了p2的解释，我本轮投给p1", "VOTE", "p2", 0, "NORMAL"))));
        assertEquals("UNVERIFIED", status(s, 0));
        promise(s, "我本轮投给p1", "VOTE", "p1", 0, "NORMAL");
        promise(s, "我本轮投给p2", "VOTE", "p2", 0, "NORMAL");
        String id2 = text(entries(s).get(2).get("id"));
        speak(s, "我撤回投给p1的承诺", Map.of("commitmentWithdrawals", List.of(Map.of("id", id2, "sourceQuote", "我撤回投给p1的承诺"))));
        assertEquals("ACTIVE", status(s, 2));
        speak(s, "我撤回之前的承诺", Map.of("commitmentWithdrawals", List.of(Map.of("id", id2, "sourceQuote", "我撤回之前的承诺"))));
        assertEquals("ACTIVE", status(s, 2));
        speak(s, "我撤回投给p2的承诺", Map.of("commitmentWithdrawals", List.of(Map.of("id", id2, "sourceQuote", "我撤回投给p2的承诺"))));
        assertEquals("WITHDRAWN", status(s, 2)); assertEquals("ACTIVE", status(s, 1));
    }
}
