package com.aisocialgame;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.engine.v2.TurnRequest;
import com.aisocialgame.engine.v2.turtlesoup.TurtleSoupCaseCatalog;
import com.aisocialgame.engine.v2.turtlesoup.TurtleSoupRuleSet;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomSeat;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.service.ai.v2.AiTurnDecision;
import com.aisocialgame.service.ai.v2.VisibleObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** These tests exercise authored adjudications and state transitions, not a live model's semantic accuracy. */
class TurtleSoupRuleSetTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 12, 0);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TurtleSoupCaseCatalog catalog = new TurtleSoupCaseCatalog(mapper);
    private final TurtleSoupRuleSet rules = new TurtleSoupRuleSet(catalog);

    @Test void catalogHasSixCompleteCasesWithoutGivingFixturesToTheHost() {
        assertEquals(6, catalog.cases().size());
        for (String level : List.of("EASY", "MEDIUM", "HARD")) assertEquals(2, catalog.cases().stream().filter(c -> c.difficulty().equals(level)).count());
        for (var soupCase : catalog.cases()) {
            assertEquals(20, soupCase.questionFixtures().size()); assertEquals(5, soupCase.solutionFixtures().size());
            assertEquals(2, soupCase.hints().size());
            assertFalse(soupCase.hostTruth().containsKey("questionFixtures"));
            assertFalse(soupCase.hostTruth().containsKey("solutionFixtures"));
            assertTrue(soupCase.questionFixtures().stream().anyMatch(q -> q.verdict().equals("NO")));
            assertTrue(soupCase.questionFixtures().stream().anyMatch(q -> q.verdict().equals("UNKNOWN")));
        }
        assertThrows(IllegalArgumentException.class, () -> catalog.require("unknown_case"));
        assertFalse(rules.validateStart(room("missing", 12, false)).valid());
    }

    @TestFactory Stream<DynamicTest> allAuthoredQuestionAdjudicationsRespectEvidenceAndBudget() {
        return catalog.cases().stream().flatMap(c -> c.questionFixtures().stream().map(f -> DynamicTest.dynamicTest(c.id() + ": " + f.question(), () -> {
            GameState state = state(c.id(), 12, false);
            rules.apply(state, "human", action("ASK_QUESTION", f.question(), null), NOW);
            assertEquals(0, number(state.getData().get("questionCount"), -1));
            assertEquals(HOST, rules.pendingTurns(state).getFirst().actorId());
            PlayerAction answer = verdict(state, f.verdict(), f.factIds(), f.question());
            assertTrue(rules.validateDecision(observation(state, HOST), AiTurnDecision.fallback(answer)).isEmpty());
            rules.apply(state, HOST, answer, NOW);
            assertEquals(Set.of("YES", "NO", "IRRELEVANT").contains(f.verdict()) ? 1 : 0, state.getData().get("questionCount"));
            assertEquals(f.verdict(), maps(rules.publicData(state).get("qaHistory")).getFirst().get("verdict"));
            assertFalse(rules.publicData(state).containsKey("solution"));
        })));
    }

    @TestFactory Stream<DynamicTest> allAuthoredSolutionOutcomesAreAppliedAfterHostAdjudication() {
        return catalog.cases().stream().flatMap(c -> c.solutionFixtures().stream().map(f -> DynamicTest.dynamicTest(c.id() + ": " + f.reason() + f.solution(), () -> {
            GameState state = state(c.id(), 12, false);
            rules.apply(state, "human", action("SUBMIT_SOLUTION", f.solution(), null), NOW);
            assertEquals("HOST_SOLUTION", rules.pendingTurns(state).getFirst().kind());
            PlayerAction decision = solutionVerdict(state, f.solved(), f.solved() ? new ArrayList<>(c.requiredFactIds()) : List.of());
            rules.apply(state, HOST, decision, NOW);
            assertEquals(f.solved() ? "SETTLEMENT" : "QUESTIONING", state.getPhase());
            if (f.solved()) assertEquals(c.solution(), rules.publicData(state).get("solution"));
            else assertEquals(1, state.getData().get("questionCount"));
        })));
    }

    @Test void playerObservationsExcludeTruthPendingRequestsAndEvaluationData() throws Exception {
        GameState state = state("midnight_train", 12, true);
        rules.apply(state, "human", action("ASK_QUESTION", "车窗是不是开着的？", null), NOW);
        assertTrue(rules.privateData(state, "ai-1").isEmpty());
        String visible = mapper.writeValueAsString(rules.publicData(state));
        assertFalse(visible.contains("hostTruth")); assertFalse(visible.contains("pendingHost"));
        assertFalse(visible.contains("requiredForSolution")); assertFalse(visible.contains("questionFixtures"));
        assertFalse(visible.contains("寻人海报"));
        assertTrue(map(rules.privateData(state, HOST).get("truth")).containsKey("solution"));
        rules.apply(state, HOST, verdict(state, "UNKNOWN", List.of(), ""), NOW);
        assertTrue(strings(rules.publicData(state).get("knownClues")).isEmpty());
        assertEquals(0, state.getData().get("questionCount"));
    }

    @Test void semanticEquivalentQuestionReusesVerdictWithoutChargingButOppositeClaimDoesNot() {
        GameState state = state("midnight_train", 12, false);
        askAndAnswer(state, "她在发车之前遇难了吗？", "YES", List.of("death"), "女人在发车前已经遇难");
        askAndAnswer(state, "也就是说，那位女士发车前就不在人世了？", "YES", List.of("death"), "女人在发车前已经遇难");
        assertEquals(1, state.getData().get("questionCount"));
        assertTrue(flag(maps(rules.publicData(state).get("qaHistory")).getLast().get("duplicate")));
        askAndAnswer(state, "她发车时还活着吗？", "NO", List.of("death"), "女人在发车时还活着");
        assertEquals(2, state.getData().get("questionCount"));
    }

    @Test void staleUnknownOrContradictoryHostResultsAreRejectedWithoutChangingPendingQuestion() {
        GameState state = state("midnight_train", 12, false);
        askAndAnswer(state, "她先遇难了吗？", "YES", List.of("death"), "女人在发车前已经遇难");
        rules.apply(state, "human", action("ASK_QUESTION", "她在发车前已遇难，对吗？", null), NOW);
        TurnRequest pending = rules.pendingTurns(state).getFirst();
        PlayerAction stale = verdict(state, "YES", List.of("death"), "女人在发车前已经遇难");
        var staleExtra = map(stale.getExtra()); staleExtra.put("requestId", "old-request"); stale.setExtra(staleExtra);
        assertThrows(RuntimeException.class, () -> rules.apply(state, HOST, stale, NOW));
        assertThrows(RuntimeException.class, () -> rules.apply(state, HOST, verdict(state, "YES", List.of("made-up"), "x"), NOW));
        assertThrows(RuntimeException.class, () -> rules.apply(state, HOST, verdict(state, "NO", List.of("death"), "女人在发车前已经遇难"), NOW));
        assertEquals(pending, rules.pendingTurns(state).getFirst());
        assertEquals(1, state.getData().get("questionCount"));
    }

    @Test void hostCannotWinFromOneKeywordAndCannotPublishPrivilegedSpeech() {
        GameState state = state("midnight_train", 12, false);
        rules.apply(state, "human", action("SUBMIT_SOLUTION", "她没有死亡，也不是车窗反光，围巾无关。", null), NOW);
        PlayerAction unsupported = solutionVerdict(state, true, List.of("death"));
        assertFalse(rules.validateDecision(observation(state, HOST), AiTurnDecision.fallback(unsupported)).isEmpty());
        PlayerAction complete = solutionVerdict(state, true, new ArrayList<>(catalog.require("midnight_train").requiredFactIds()));
        var extra = map(complete.getExtra()); extra.put("contradictionFactIds", List.of("death")); complete.setExtra(extra);
        assertThrows(RuntimeException.class, () -> rules.apply(state, HOST, complete, NOW));
        AiTurnDecision leak = new AiTurnDecision(solutionVerdict(state, false, List.of()), catalog.require("midnight_train").solution(), Map.of(), List.of(), Map.of(), false, Map.of());
        assertFalse(rules.validateDecision(observation(state, HOST), leak).isEmpty());
    }

    @Test void finalQuestionLeavesExactlyOneHumanFinalAnswerAndModelFailureDoesNotConsumeIt() {
        GameState state = state("rainy_key", 4, false);
        for (int i = 0; i < 4; i++) askAndAnswer(state, "确认事实 " + i, "YES", List.of("spare"), "独立命题 " + i);
        assertEquals("FINAL_ANSWER", state.getPhase());
        assertTrue(rules.legalActions(state, "human").stream().anyMatch(a -> a.type().equals("SUBMIT_SOLUTION")));
        assertFalse(rules.legalActions(state, "human").stream().anyMatch(a -> a.type().equals("ASK_QUESTION")));
        rules.apply(state, "human", action("SUBMIT_SOLUTION", "最后的共同解答", null), NOW);
        PlayerAction fallback = rules.fallback(observation(state, HOST));
        rules.apply(state, HOST, fallback, NOW);
        assertEquals("FINAL_ANSWER", state.getPhase()); assertEquals(1, state.getData().get("finalAnswerRemaining"));
        rules.apply(state, "human", action("SUBMIT_SOLUTION", "修改后的最终解答", null), NOW);
        rules.apply(state, HOST, solutionVerdict(state, false, List.of()), NOW);
        assertEquals("SETTLEMENT", state.getPhase()); assertEquals("FAILED", state.getData().get("winner"));
        assertEquals(0, state.getData().get("finalAnswerRemaining"));
        assertTrue(rules.pendingTurns(state).isEmpty());
        assertThrows(RuntimeException.class, () -> rules.apply(state, "human", action("SUBMIT_SOLUTION", "再试", null), NOW));
    }

    @Test void finalAnswerCanAlsoSucceedAfterExplorationIsExhausted() {
        GameState state = state("rainy_key", 4, false);
        for (int i = 0; i < 4; i++) askAndAnswer(state, "问题 " + i, "YES", List.of("spare"), "命题 " + i);
        rules.apply(state, "human", action("SUBMIT_SOLUTION", catalog.require("rainy_key").solution(), null), NOW);
        rules.apply(state, HOST, solutionVerdict(state, true, new ArrayList<>(catalog.require("rainy_key").requiredFactIds())), NOW);
        assertEquals("SOLVED", state.getData().get("winner"));
    }

    @Test void hintsAreHumanRequestedProgressiveAndLimited() {
        GameState state = state("rainy_key", 12, true);
        rules.apply(state, "human", action("REQUEST_HINT", "", null), NOW);
        assertEquals(1, state.getData().get("questionCount"));
        assertEquals(1, state.getData().get("hintCount"));
        assertTrue(strings(state.getData().get("knownClues")).getFirst().contains(catalog.require("rainy_key").hints().getFirst()));
        assertThrows(RuntimeException.class, () -> rules.apply(state, "ai-1", action("REQUEST_HINT", "", null), NOW));
        rules.apply(state, "human", action("REQUEST_HINT", "", null), NOW);
        assertEquals(2, state.getData().get("questionCount"));
        assertFalse(rules.legalActions(state, "human").stream().anyMatch(a -> a.type().equals("REQUEST_HINT")));
    }

    @Test void aiContributionsRotateStopAtTwoAndDoNotStartAnAutonomousLoop() {
        GameState state = state("rainy_key", 12, true);
        assertTrue(rules.pendingTurns(state).isEmpty());
        rules.apply(state, "human", action("DISCUSS", "我们先想想钥匙为什么换了位置。", null), NOW);
        TurnRequest first = rules.pendingTurns(state).getFirst();
        assertEquals(first, rules.pendingTurns(state).getFirst(), "Polling must not create a new opportunity");
        rules.apply(state, first.actorId(), action("ASK_QUESTION", "钥匙是他特意留的吗？", null), NOW);
        rules.apply(state, HOST, verdict(state, "YES", List.of("spare"), "钥匙由父亲特意留下"), NOW);
        TurnRequest second = rules.pendingTurns(state).getFirst();
        assertNotEquals(first.actorId(), second.actorId());
        assertFalse(rules.legalActions(state, second.actorId()).stream().anyMatch(a -> a.type().equals("ASK_QUESTION")));
        rules.apply(state, second.actorId(), action("DISCUSS", "你刚才提到位置变化，这条线索确实值得追。", null), NOW);
        assertTrue(rules.pendingTurns(state).isEmpty()); assertEquals(1, state.getData().get("aiQuestionsUsed"));
        rules.advance(state, NOW.plusMinutes(5)); assertTrue(rules.pendingTurns(state).isEmpty());
        assertThrows(RuntimeException.class, () -> rules.apply(state, first.actorId(), action("SUBMIT_SOLUTION", "我来结束游戏", null), NOW));
    }

    @Test void aiSharesBudgetButCannotConsumeLastTwoHumanQuestions() {
        GameState state = state("rainy_key", 4, true);
        askAndAnswer(state, "第一条", "YES", List.of("spare"), "第一条");
        var ai = rules.pendingTurns(state).getFirst();
        rules.apply(state, ai.actorId(), action("ASK_QUESTION", "是提前说好的吗？", null), NOW);
        rules.apply(state, HOST, verdict(state, "YES", List.of("agreement"), "这是提前约好的"), NOW);
        assertEquals(2, state.getData().get("questionCount")); assertEquals(1, state.getData().get("aiQuestionsUsed"));
        rules.apply(state, "human", action("DISCUSS", "最后两问我们来确认。", null), NOW);
        var next = rules.pendingTurns(state).getFirst();
        assertFalse(rules.legalActions(state, next.actorId()).stream().anyMatch(a -> a.type().equals("ASK_QUESTION")));
    }

    @Test void clarifiedQuestionNeverRevealsWholeReferencedHiddenFact() throws Exception {
        GameState state = state("midnight_train", 12, false);
        askAndAnswer(state, "车窗反光有关吗？", "YES", List.of("reflection"), "车窗反光有关");
        String visible = mapper.writeValueAsString(rules.publicData(state));
        assertTrue(visible.contains("车窗反光有关"));
        assertFalse(visible.contains("寻人海报"));
        assertFalse(visible.contains("factIds")); assertFalse(visible.contains("normalizedProposition"));
    }

    @Test void aiQuestionCapAppliesAcrossHumanTriggeredDiscussionCycles() {
        GameState state = state("rainy_key", 12, true);
        for (int i = 0; i < 4; i++) {
            rules.apply(state, "human", action("DISCUSS", "再确认一个方向 " + i, null), NOW);
            TurnRequest ai = rules.pendingTurns(state).getFirst();
            rules.apply(state, ai.actorId(), action("ASK_QUESTION", "独立问题 " + i, null), NOW);
            rules.apply(state, HOST, verdict(state, "YES", List.of("spare"), "独立命题 " + i), NOW);
        }
        rules.apply(state, "human", action("DISCUSS", "我们还剩八次，让真人来问。", null), NOW);
        TurnRequest ai = rules.pendingTurns(state).getFirst();
        assertEquals(4, state.getData().get("questionCount"));
        assertEquals(4, state.getData().get("aiQuestionsUsed"));
        assertFalse(rules.legalActions(state, ai.actorId()).stream().anyMatch(a -> a.type().equals("ASK_QUESTION")));
        assertTrue(rules.legalActions(state, ai.actorId()).stream().anyMatch(a -> a.type().equals("DISCUSS")));
    }

    @Test void persistedPendingRequestKeepsItsIdentityAndPinnedTruth() throws Exception {
        GameState state = state("rainy_key", 12, false);
        rules.apply(state, "human", action("ASK_QUESTION", "这是提前约好的信号吗？", null), NOW);
        TurnRequest pending = rules.pendingTurns(state).getFirst();
        Map<String, Object> restored = mapper.readValue(mapper.writeValueAsString(state.getData()),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        state.setData(restored);
        assertEquals(pending, rules.pendingTurns(state).getFirst());
        assertEquals(catalog.require("rainy_key").solution(), map(rules.privateData(state, HOST).get("truth")).get("solution"));
        rules.apply(state, HOST, verdict(state, "YES", List.of("agreement"), "钥匙的位置是提前约好的信号"), NOW);
        assertEquals(1, state.getData().get("questionCount"));
        assertTrue(rules.pendingTurns(state).isEmpty());
    }

    @Test void hostFailureLocallyRestoresHumanRetryWithoutChargeOrPrivateTruth() throws Exception {
        for (boolean finalAnswer : List.of(false, true)) {
            GameState state = state("rainy_key", 4, true);
            if (finalAnswer) {
                state.setPhase(TurtleSoupRuleSet.FINAL_ANSWER);
                state.getData().put("questionCount", 4);
            }
            String type = finalAnswer ? "SUBMIT_SOLUTION" : "ASK_QUESTION";
            rules.apply(state, "human", action(type, "请确认这个推测。", null), NOW);
            TurnRequest failed = rules.pendingTurns(state).getFirst();
            int count = number(state.getData().get("questionCount"), 0);
            rules.onAiFailure(state, failed, NOW.plusSeconds(20));
            assertEquals(count, state.getData().get("questionCount"));
            assertEquals(1, state.getData().get("finalAnswerRemaining"));
            assertTrue(map(state.getData().get("pendingHost")).isEmpty());
            assertTrue(rules.pendingTurns(state).isEmpty());
            assertEquals("UNRESOLVED", maps(state.getData().get("qaHistory")).getLast().get("verdict"));
            String visible = mapper.writeValueAsString(rules.publicData(state));
            assertFalse(visible.contains("factIds")); assertFalse(visible.contains("手机"));
            assertFalse(visible.contains(catalog.require("rainy_key").solution()));
            rules.apply(state, "human", action(type, "换一个更明确的推测。", null), NOW.plusSeconds(21));
            TurnRequest retried = rules.pendingTurns(state).getFirst();
            assertNotEquals(failed, retried);
            rules.onAiFailure(state, failed, NOW.plusSeconds(22));
            assertEquals(retried, rules.pendingTurns(state).getFirst());
        }
    }

    @Test void playerFailurePassesOnlyThatOpportunityWithoutFabricatedSpeech() {
        GameState state = state("rainy_key", 12, true);
        rules.apply(state, "human", action("DISCUSS", "先听听你们的想法。", null), NOW);
        TurnRequest first = rules.pendingTurns(state).getFirst();
        int logs = state.getLogs().size();
        rules.onAiFailure(state, first, NOW.plusSeconds(20));
        TurnRequest second = rules.pendingTurns(state).getFirst();
        assertNotEquals(first.actorId(), second.actorId());
        assertEquals(1, state.getData().get("aiContributions"));
        assertEquals(logs, state.getLogs().size()); assertEquals(0, state.getData().get("questionCount"));
        rules.onAiFailure(state, first, NOW.plusSeconds(21));
        assertEquals(second, rules.pendingTurns(state).getFirst());
        rules.onAiFailure(state, second, NOW.plusSeconds(22));
        assertTrue(rules.pendingTurns(state).isEmpty()); assertEquals(logs, state.getLogs().size());
    }

    @Test void playerFallbackUsesOnlyPublicVerdictWithoutSpendingQuestionOrRevealingTruth() {
        GameState state = state("rainy_key", 12, true);
        askAndAnswer(state, "是否有备用钥匙？", "YES", List.of("spare"), "存在备用钥匙");
        VisibleObservation base = observation(state, "ai-1");
        var visible = maps(state.getData().get("events")).stream().filter(e -> "PUBLIC".equals(e.get("visibility"))).toList();
        VisibleObservation input = new VisibleObservation(base.gameId(), base.instanceId(), base.phase(), base.round(), base.actorId(), base.turnKind(),
                base.self(), base.players(), base.rules(), visible, base.privateFacts(), base.legalActions(), base.persona(), base.memory(), base.knowledge());
        int before = number(state.getData().get("questionCount"), 0);
        PlayerAction fallback = rules.fallback(input);
        assertEquals("DISCUSS", fallback.getType()); assertTrue(fallback.getContent().contains("是否有备用钥匙"));
        assertFalse(fallback.getContent().contains(catalog.require("rainy_key").solution()));
        rules.apply(state, "ai-1", fallback, NOW);
        assertEquals(before, number(state.getData().get("questionCount"), 0));
    }

    private GameState state(String caseId, int max, boolean ai) { return rules.initialize(room(caseId, max, ai), NOW); }
    private Room room(String caseId, int max, boolean ai) {
        Room room = new Room("soup-room", "turtle_soup", "测试房", RoomStatus.WAITING, ai ? 3 : 1, false, null, "text",
                Map.of("caseId", caseId, "maxQuestions", max, "aiAssist", ai));
        List<RoomSeat> seats = new ArrayList<>();
        seats.add(new RoomSeat(1, "human", "小林", false, null, null, true, true));
        if (ai) {
            seats.add(new RoomSeat(2, "ai-1", "阿棠", true, "persona-1", null, true, false));
            seats.add(new RoomSeat(3, "ai-2", "小舟", true, "persona-2", null, true, false));
        }
        room.setSeats(seats); return room;
    }
    private void askAndAnswer(GameState state, String question, String verdict, List<String> facts, String proposition) {
        rules.apply(state, "human", action("ASK_QUESTION", question, null), NOW);
        rules.apply(state, HOST, verdict(state, verdict, facts, proposition), NOW);
    }
    private PlayerAction verdict(GameState state, String verdict, List<String> facts, String proposition) {
        PlayerAction action = action("HOST_VERDICT", "", null);
        action.setExtra(Map.of("requestId", map(state.getData().get("pendingHost")).get("requestId"),
                "verdict", verdict, "factIds", facts, "normalizedProposition", proposition));
        return action;
    }
    private PlayerAction solutionVerdict(GameState state, boolean correct, List<String> facts) {
        PlayerAction action = action("HOST_VERDICT", "", null);
        action.setExtra(Map.of("requestId", map(state.getData().get("pendingHost")).get("requestId"), "correct", correct,
                "factIds", facts, "contradictionFactIds", List.of())); return action;
    }
    private VisibleObservation observation(GameState state, String actor) {
        return new VisibleObservation("turtle_soup", "instance", state.getPhase(), 1, actor, "HOST_ANSWER", Map.of(), List.of(),
                rules.publicData(state), List.of(), rules.privateData(state, actor), rules.legalActions(state, actor), Map.of(), Map.of(), List.of());
    }
}
