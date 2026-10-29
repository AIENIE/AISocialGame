package com.aisocialgame.engine.v2.undercover;

import com.aisocialgame.engine.v2.TurnRequest;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class UndercoverSelfDestructTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0);
    private final UndercoverWordCatalog catalog = new UndercoverWordCatalog();
    private final UndercoverRuleSet rules = new UndercoverRuleSet(catalog);

    @Test void actualUndercoverWinsForTheWholeTeamInEveryAvailableWindowEvenWithBlankAlive() {
        for (String phase : List.of("DESCRIPTION", "TIE_DEFENSE", "VOTING", "RUNOFF")) {
            GameState state = state(true);
            player(state, "p4").setRole("UNDERCOVER"); player(state, "p4").setAlive(false);
            window(state, phase, "p0");
            rules.apply(state, "p0", action("SELF_DESTRUCT", "平 民、原词！", null), NOW);
            assertEquals("SETTLEMENT", state.getPhase(), phase);
            assertEquals("UNDERCOVER", state.getData().get("winner"));
            assertEquals(Set.of("p0", "p4"), rules.winningPlayerIds(state));
            assertTrue(player(state, "p5").isAlive());
            assertThrows(ApiException.class, () -> rules.apply(state, "p0", action("SELF_DESTRUCT", "平民原词", null), NOW));
        }
    }

    @Test void registeredAliasesMatchExactlyAndCustomAnswersNeverUseSubstringOrSemanticMatching() {
        assertTrue(catalog.matchesAnswer("单 车。", "自行车"));
        assertTrue(catalog.matchesAnswer("ＣＯＳＰＬＡＹ", "角色扮演"));
        assertFalse(catalog.matchesAnswer("我猜是自行车", "自行车"));
        assertFalse(catalog.matchesAnswer("自行车或公交车", "自行车"));
        assertFalse(catalog.matchesAnswer("交通工具", "自行车"));
        assertFalse(catalog.matchesAnswer("，。", "自行车"));
        assertTrue(catalog.matchesAnswer("自 创 答案", "自创答案"));
        for (String guess : List.of("单车", "自行车")) {
            GameState state = state(false); state.getData().put("civilianWord", "自行车");
            rules.apply(state, "p0", action("SELF_DESTRUCT", guess, null), NOW);
            assertEquals("UNDERCOVER", state.getData().get("winner"));
        }
    }

    @Test void civilianGuessingTheirExactWordStillLosesOnlyTheirOwnSeatAndKeepsDescriptionProgress() {
        for (int seconds : List.of(0, 90)) {
            GameState state = state(false);
            window(state, "DESCRIPTION", "p1");
            state.setPhaseEndsAt(seconds == 0 ? null : NOW.plusSeconds(seconds));
            state.getData().put("speakers", List.of("p0"));
            state.getData().put("noEliminationRounds", 2);
            LocalDateTime deadline = state.getPhaseEndsAt();
            Object token = state.getData().get("phaseSerial");
            rules.apply(state, "p1", action("SELF_DESTRUCT", "平民原词", null), NOW.plusSeconds(5));
            assertFalse(player(state, "p1").isAlive());
            assertEquals("CIVILIAN", rules.visibleRole(state, player(state, "p1"), "p2"));
            assertEquals("DESCRIPTION", state.getPhase());
            assertEquals(1, state.getRoundNumber()); assertEquals(2, state.getCurrentSeat());
            assertEquals(List.of("p0"), strings(state.getData().get("speakers")));
            assertEquals(deadline, state.getPhaseEndsAt());
            assertNotEquals(token, state.getData().get("phaseSerial"));
            assertEquals(0, state.getData().get("noEliminationRounds"));
            assertFalse(rules.publicData(state).containsKey("civilianWord"));
            var attempt = maps(state.getData().get("events")).stream().filter(e -> "SELF_DESTRUCT".equals(e.get("type"))).findFirst().orElseThrow();
            assertEquals(Map.of("content", "平民原词", "actorName", "玩家1", "success", false), map(attempt.get("data")));
            assertThrows(ApiException.class, () -> rules.apply(state, "p1", action("SELF_DESTRUCT", "平民原词", null), NOW.plusSeconds(6)));
        }
    }

    @Test void wrongUndercoverGuessIsAnEliminationRatherThanAnInvalidAction() {
        for (String phase : List.of("DESCRIPTION", "TIE_DEFENSE", "VOTING", "RUNOFF")) {
            GameState state = state(false); player(state, "p4").setRole("UNDERCOVER");
            window(state, phase, "p0"); LocalDateTime deadline = state.getPhaseEndsAt();
            rules.apply(state, "p0", action("SELF_DESTRUCT", "我猜是平民原词", null), NOW.plusSeconds(3));
            assertFalse(player(state, "p0").isAlive());
            assertEquals(phase, state.getPhase()); assertEquals(deadline, state.getPhaseEndsAt());
            assertEquals(1, state.getRoundNumber());
        }
        GameState lastSpy = state(false);
        rules.apply(lastSpy, "p0", action("SELF_DESTRUCT", "错误答案", null), NOW);
        assertEquals("CIVILIAN", lastSpy.getData().get("winner"));
    }

    @Test void tiedCandidateEliminationContinuesSupplementaryDescriptionsAndRemovesDeadTargets() {
        GameState state = state(false); window(state, "TIE_DEFENSE", "p1");
        state.getData().put("runoffCandidates", List.of("p1", "p2")); state.getData().put("defenseIndex", 0);
        LocalDateTime deadline = state.getPhaseEndsAt();
        rules.apply(state, "p1", action("SELF_DESTRUCT", "错误答案", null), NOW.plusSeconds(1));
        assertEquals("TIE_DEFENSE", state.getPhase()); assertEquals(2, state.getCurrentSeat());
        assertEquals(deadline, state.getPhaseEndsAt());
        assertEquals(List.of("p2"), rules.publicData(state).get("runoffCandidates"));
        rules.apply(state, "p2", action("SPEAK", "补充一个使用场景", null), NOW.plusSeconds(2));
        assertEquals("RUNOFF", state.getPhase());
        assertEquals(List.of("p2"), rules.legalActions(state, "p0").getFirst().targets());
    }

    @Test void ballotsAndClockSurviveAndVotesForTheEliminatedPlayerDoNotCount() {
        for (String phase : List.of("VOTING", "RUNOFF")) {
            GameState state = state(false); window(state, phase, "p1");
            state.getPlayers().forEach(p -> p.setAi(true));
            state.getData().put("runoffCandidates", List.of("p1", "p3"));
            rules.apply(state, "p0", action("VOTE", "", "p1"), NOW);
            rules.apply(state, "p2", action("VOTE", "", "p3"), NOW);
            List<TurnRequest> old = rules.pendingTurns(state); LocalDateTime deadline = state.getPhaseEndsAt();
            rules.apply(state, "p1", action("SELF_DESTRUCT", "错误答案", null), NOW.plusSeconds(5));
            assertEquals(phase, state.getPhase()); assertEquals(deadline, state.getPhaseEndsAt());
            assertEquals(Map.of("p0", "p1", "p2", "p3"), map(state.getData().get("votes")));
            assertTrue(old.stream().noneMatch(turn -> rules.isTurnCurrent(state, turn)));
            assertEquals(3, rules.pendingTurns(state).size());
            assertTrue(rules.legalActions(state, "p0").isEmpty());
            assertThrows(ApiException.class, () -> rules.apply(state, "p3", action("VOTE", "", "p1"), NOW.plusSeconds(5)));
            assertTrue(maps(state.getData().get("events")).stream().noneMatch(e -> "VOTE_REVEAL".equals(e.get("type"))));
            for (String actor : List.of("p3", "p4", "p5")) rules.apply(state, actor, action("SKIP", "", null), NOW.plusSeconds(6));
            var result = map(state.getData().get("lastVoteResult"));
            assertEquals("p1", map(result.get("votes")).get("p0"));
            assertEquals(Map.of("p3", 1L), map(result.get("tally")));
            assertFalse(player(state, "p3").isAlive());
        }
    }

    @Test void lastOutstandingVoterCanSelfDestructWithoutDeadlockOrCountingAnEmptyEliminationRound() {
        GameState state = state(false); window(state, "VOTING", "p1");
        state.getData().put("noEliminationRounds", 2);
        for (String actor : List.of("p0", "p2", "p3", "p4", "p5")) rules.apply(state, actor, action("SKIP", "", null), NOW);
        rules.apply(state, "p1", action("SELF_DESTRUCT", "错误答案", null), NOW.plusSeconds(1));
        assertEquals("DESCRIPTION", state.getPhase()); assertEquals(2, state.getRoundNumber());
        assertEquals(0, state.getData().get("noEliminationRounds"));
        while ("DESCRIPTION".equals(state.getPhase())) rules.apply(state, current(state).getPlayerId(), action("SPEAK", "新的描述线索", null), NOW.plusSeconds(2));
        for (GamePlayerState actor : alive(state)) rules.apply(state, actor.getPlayerId(), action("SKIP", "", null), NOW.plusSeconds(2));
        assertEquals(1, state.getData().get("noEliminationRounds"));
    }

    @Test void failureCanTriggerExistingFinalThreeAndBlankVictoryConditions() {
        for (boolean blank : List.of(false, true)) {
            GameState state = state(blank);
            player(state, "p2").setAlive(false); player(state, "p3").setAlive(false);
            if (blank) player(state, "p4").setAlive(false);
            window(state, "VOTING", "p1");
            rules.apply(state, "p1", action("SELF_DESTRUCT", "错误答案", null), NOW);
            assertEquals(blank ? "BLANK" : "UNDERCOVER", state.getData().get("winner"));
        }
    }

    @Test void eligibilityDoesNotExposeAlignmentAndMalformedGuessesDoNotConsumeAnAttempt() {
        GameState state = state(true);
        var spy = rules.legalActions(state, "p0"); window(state, "DESCRIPTION", "p1");
        assertEquals(spy, rules.legalActions(state, "p1"));
        int events = maps(state.getData().get("events")).size();
        for (String guess : List.of("", " ", "？！", "长".repeat(21))) {
            assertThrows(ApiException.class, () -> rules.apply(state, "p1", action("SELF_DESTRUCT", guess, null), NOW));
        }
        assertEquals(events, maps(state.getData().get("events")).size()); assertTrue(player(state, "p1").isAlive());
        assertThrows(ApiException.class, () -> rules.apply(state, "p0", action("SELF_DESTRUCT", "平民原词", null), NOW));
        rules.apply(state, "p1", action("SPEAK", "平民原词", null), NOW);
        assertEquals(1,rules.wordFeedback(state,"p1").get("violationCount"));
        assertTrue(player(state,"p1").isAlive());
        assertTrue(rules.legalActions(state, "spectator").isEmpty());
        window(state, "DESCRIPTION", "p5");
        assertTrue(rules.legalActions(state, "p5").stream().noneMatch(a -> "SELF_DESTRUCT".equals(a.type())));
        assertThrows(ApiException.class, () -> rules.apply(state, "p5", action("SELF_DESTRUCT", "平民原词", null), NOW));
        window(state, "VOTING", "p1"); rules.apply(state, "p1", action("SKIP", "", null), NOW);
        assertThrows(ApiException.class, () -> rules.apply(state, "p1", action("SELF_DESTRUCT", "平民原词", null), NOW));
    }

    private GameState state(boolean blank) {
        Room room = new Room(UUID.randomUUID().toString(), "undercover", "自爆测试", RoomStatus.WAITING, 6, false, null, "text",
                new LinkedHashMap<>(Map.of("wordPack", "custom", "hasBlank", blank)));
        room.setHostUserId("host"); room.getPrivateConfig().put("customWords", List.of(Map.of("wordA", "平民原词", "wordB", "卧底原词")));
        for (int i = 0; i < 6; i++) room.getSeats().add(new RoomSeat(i, "p" + i, "玩家" + i, false, null, "", true, false));
        GameState state = rules.initialize(room, NOW);
        state.getData().put("civilianWord", "平民原词"); state.getData().put("undercoverWord", "卧底原词");
        for (int i = 0; i < 6; i++) {
            var p = player(state, "p" + i);
            p.setRole(i == 0 ? "UNDERCOVER" : blank && i == 5 ? "BLANK" : "CIVILIAN");
            p.setWord(i == 0 ? "卧底原词" : blank && i == 5 ? "" : "平民原词");
        }
        window(state, "DESCRIPTION", "p0"); return state;
    }
    private void window(GameState state, String phase, String actor) {
        phase(state, phase, Set.of("VOTING", "RUNOFF").contains(phase) ? null : player(state, actor).getSeatNumber(), 60, NOW);
        var order = state.getPlayers().stream().map(GamePlayerState::getPlayerId).toList();
        state.getData().put("descriptionOrder", order); state.getData().put("descriptionIndex", order.indexOf(actor));
        state.getData().put("runoffCandidates", order); state.getData().put("defenseIndex", order.indexOf(actor));
        state.getData().put("votes", new LinkedHashMap<>());
    }
    private GamePlayerState current(GameState state) { return state.getPlayers().stream().filter(p -> Objects.equals(p.getSeatNumber(), state.getCurrentSeat())).findFirst().orElseThrow(); }
}
