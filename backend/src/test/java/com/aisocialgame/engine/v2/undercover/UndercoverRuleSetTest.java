package com.aisocialgame.engine.v2.undercover;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.engine.v2.*;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.*;
import com.aisocialgame.service.ai.v2.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class UndercoverRuleSetTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 12, 0);
    private final UndercoverWordCatalog catalog = new UndercoverWordCatalog();
    private final UndercoverRuleSet rules = new UndercoverRuleSet(catalog);

    @Test void allPlayerCountsSupportExactlyOneBlankAndBoundedManualSpies() {
        for (int count = 4; count <= 10; count++) {
            Room room = room(count, Map.of("hasBlank", true, "spyMode", "manual", "spyCount", (count - 1) / 3));
            assertTrue(rules.validateStart(room).valid());
            GameState state = rules.initialize(room, NOW);
            assertEquals(1, state.getPlayers().stream().filter(p -> "BLANK".equals(p.getRole())).count());
            assertEquals((count - 1) / 3, state.getPlayers().stream().filter(p -> "UNDERCOVER".equals(p.getRole())).count());
            room.getConfig().put("spyCount", (count - 1) / 3 + 1);
            assertFalse(rules.validateStart(room).valid());
        }
        for (Object count : List.of(0, -1, 1.2, "1.0", "abc", 100)) assertFalse(rules.validateStart(room(6, Map.of("spyMode", "manual", "spyCount", count))).valid());
        assertFalse(rules.validateStart(room(3, Map.of())).valid());
        assertFalse(rules.validateStart(room(11, Map.of())).valid());
        assertFalse(rules.validateStart(room(4, Map.of("speakTime", 5))).valid());
    }

    @Test void ownAlignmentAndOtherWordsNeverEnterThePlayerProjectionBeforeReveal() throws Exception {
        GameState state = rules.initialize(room(6, Map.of("hasBlank", true)), NOW);
        ObjectMapper json = new ObjectMapper();
        for (GamePlayerState actor : state.getPlayers()) {
            String opposite = "UNDERCOVER".equals(actor.getRole()) ? text(state.getData().get("civilianWord")) : text(state.getData().get("undercoverWord"));
            Map<String, Object> own = rules.privateData(state, actor.getPlayerId());
            String serialized = json.writeValueAsString(own);
            assertFalse(serialized.contains("UNDERCOVER"));
            assertFalse(serialized.contains("CIVILIAN"));
            assertFalse(serialized.contains("wordPairId"));
            assertFalse(serialized.contains(opposite));
            assertEquals("BLANK".equals(actor.getRole()) ? "BLANK" : null, rules.visibleRole(state, actor, actor.getPlayerId()));
            for (GamePlayerState other : state.getPlayers()) if (!other.getPlayerId().equals(actor.getPlayerId())) {
                assertNull(rules.visibleRole(state, other, actor.getPlayerId()));
                assertNull(rules.visibleWord(state, other, actor.getPlayerId()));
            }
            List<Map<String, Object>> privateEvents = maps(state.getData().get("events")).stream().filter(e -> "PRIVATE".equals(e.get("visibility")) && strings(e.get("visibleTo")).contains(actor.getPlayerId())).toList();
            assertFalse(json.writeValueAsString(privateEvents).contains("UNDERCOVER"));
            assertFalse(json.writeValueAsString(privateEvents).contains("CIVILIAN"));
            if ("BLANK".equals(actor.getRole())) {
                assertEquals("", own.get("word"));
                assertFalse(own.containsKey("wordKnowledge"));
            }
        }
        assertFalse(json.writeValueAsString(rules.publicData(state)).contains(text(state.getData().get("civilianWord"))));
        assertFalse(json.writeValueAsString(rules.publicData(state)).contains(text(state.getData().get("undercoverWord"))));
        assertTrue(rules.privateData(state, "spectator").isEmpty());
    }

    @Test void roomPoolDoesNotRepeatAndBothWordSidesCanBeTheMajority() {
        Room room = room(4, Map.of("wordPack", "tech"));
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            GameState state = rules.initialize(room, NOW);
            assertTrue(text(state.getData().get("wordPairId")).startsWith("uc-tech-"));
            assertTrue(used.add(text(state.getData().get("wordPairId"))));
        }
        assertTrue(used.contains(text(rules.initialize(room, NOW).getData().get("wordPairId"))));
        assertEquals(1, strings(room.getConfig().get("usedWordPairIds")).size());
        Room custom = customRoom("红色信封", "蓝色卡片");
        Set<String> majorityWords = new HashSet<>();
        for (int i = 0; i < 64; i++) majorityWords.add(text(rules.initialize(custom, NOW).getData().get("civilianWord")));
        assertEquals(Set.of("红色信封", "蓝色卡片"), majorityWords);
    }

    @Test void unlimitedDescriptionHasNoDeadlineAndPendingTurnsAreStableUntilAnActionCommits() {
        GameState state = rules.initialize(room(4, Map.of("speakTime", 0)), NOW);
        state.getPlayers().forEach(p -> p.setAi(true));
        assertNull(state.getPhaseEndsAt());
        List<TurnRequest> original = rules.pendingTurns(state);
        assertEquals(1, original.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(original, rules.pendingTurns(state));
            rules.advance(state, NOW.plusYears(1));
        }
        assertEquals(original, rules.pendingTurns(state));
        rules.apply(state, original.getFirst().actorId(), action("SPEAK", "我从一个具体场景来描述。", null), NOW);
        assertNotEquals(original, rules.pendingTurns(state));
        assertFalse(rules.isTurnCurrent(state, original.getFirst()));
    }

    @Test void onlyAutomatedActorsHaveJobsAndDisconnectedHumansNeedExplicitTakeover() {
        GameState state = rules.initialize(room(4, Map.of()), NOW);
        GamePlayerState actor = current(state);
        assertTrue(rules.pendingTurns(state).isEmpty());
        actor.setConnectionStatus("DISCONNECTED");
        assertTrue(rules.pendingTurns(state).isEmpty());
        actor.setConnectionStatus("AI_TAKEOVER");
        assertEquals(actor.getPlayerId(), rules.pendingTurns(state).getFirst().actorId());
    }

    @Test void failedUnlimitedAiDescriptionClosesOnlyItsWindowAndDoesNotReplayAnOldFailure() {
        GameState state = rules.initialize(room(4, Map.of("speakTime", 0)), NOW);
        GamePlayerState actor = current(state); actor.setAi(true);
        TurnRequest failed = rules.pendingTurns(state).getFirst();
        Integer firstSeat = state.getCurrentSeat();
        assertNull(state.getPhaseEndsAt());
        rules.onAiFailure(state, failed, NOW.plusSeconds(2));
        assertEquals("DESCRIPTION", state.getPhase());
        assertNotEquals(firstSeat, state.getCurrentSeat());
        assertNull(state.getPhaseEndsAt());
        assertEquals(1, state.getLogs().stream().filter(log -> "SKIP".equals(log.getType()) && "REQUEST_FAILED".equals(log.getMetadata().get("reason")) && actor.getPlayerId().equals(log.getActorId())).count());
        Integer nextSeat = state.getCurrentSeat();
        int eventCount = maps(state.getData().get("events")).size();
        rules.onAiFailure(state, failed, NOW.plusSeconds(3));
        assertEquals(nextSeat, state.getCurrentSeat());
        assertEquals(eventCount, maps(state.getData().get("events")).size());
    }

    @Test void failedAiBallotAbstainsOnlyForThatActorAndLeavesOtherVotersTheirWindow() {
        GameState state = rules.initialize(room(4, Map.of()), NOW); goToVoting(state);
        player(state, "p0").setAi(true);
        TurnRequest failed = rules.pendingTurns(state).getFirst();
        LocalDateTime deadline = state.getPhaseEndsAt();
        rules.onAiFailure(state, failed, NOW.plusSeconds(1));
        assertEquals("VOTING", state.getPhase());
        assertEquals(deadline, state.getPhaseEndsAt());
        assertEquals(Map.of("p0", "abstain"), map(state.getData().get("votes")));
        assertEquals(List.of("p0"), rules.publicData(state).get("votedPlayers"));
        assertTrue(state.getPlayers().stream().allMatch(GamePlayerState::isAlive));
        assertTrue(state.getLogs().stream().noneMatch(log -> "VOTE_REVEAL".equals(log.getType())));
        assertFalse(rules.legalActions(state, "p1").isEmpty());
        rules.onAiFailure(state, failed, NOW.plusSeconds(2));
        assertEquals(Map.of("p0", "abstain"), map(state.getData().get("votes")));
        assertEquals("VOTING", state.getPhase());
    }

    @Test void descriptionsGoStraightToVotingWithoutQuestions() {
        GameState state = rules.initialize(room(6, Map.of()), NOW);
        finishDescriptions(state);
        assertEquals("VOTING", state.getPhase());
        assertEquals(NOW.plusSeconds(60), state.getPhaseEndsAt());
        assertTrue(maps(state.getData().get("events")).stream().noneMatch(e -> Set.of("CHALLENGE_START", "ASK_PLAYER", "ANSWER_PLAYER").contains(e.get("type"))));
        assertTrue(rules.definition().getPhaseDefinitions().stream().noneMatch(p -> Set.of("CHALLENGE", "RESPONSE").contains(p.phase())));
    }

    @Test void persistedQuestionPhasesAdvanceImmediatelyAndInvalidateOldJobs() {
        for (String legacy : List.of("CHALLENGE", "RESPONSE")) {
            GameState state = rules.initialize(room(4, Map.of()), NOW);
            state.getPlayers().forEach(p -> p.setAi(true));
            phase(state, legacy, 0, 20, NOW);
            state.getData().put("activeQuestion", Map.of("content", "此前的问题"));
            event(state, "ASK_PLAYER", "p1", "p0", "此前的问题", Map.of());
            var history = new ArrayList<>(maps(state.getData().get("events")));
            var old = turn(state, "p0", "CHALLENGE".equals(legacy) ? "ASK_PLAYER" : "ANSWER_PLAYER");
            Object oldToken = state.getData().get("phaseSerial");
            assertTrue(rules.pendingTurns(state).isEmpty());
            assertTrue(rules.legalActions(state, "p0").isEmpty());
            rules.advance(state, NOW.plusSeconds(1));
            assertEquals("VOTING", state.getPhase());
            assertEquals(NOW.plusSeconds(61), state.getPhaseEndsAt());
            assertNotEquals(oldToken, state.getData().get("phaseSerial"));
            assertFalse(rules.isTurnCurrent(state, old));
            assertEquals(history, maps(state.getData().get("events")).subList(0, history.size()));
            assertTrue(map(rules.publicData(state).get("activeQuestion")).isEmpty());
            assertEquals(4, rules.pendingTurns(state).size());
            assertThrows(ApiException.class, () -> rules.apply(state, "p0", action(old.kind(), "迟到的发言", "p1"), NOW.plusSeconds(1)));
            rules.advance(state, NOW.plusSeconds(2));
            assertEquals(NOW.plusSeconds(61), state.getPhaseEndsAt());
        }
    }

    @Test void firstTieAddsWordDescriptionsAndOnlyTiedCandidatesCanReceiveRunoffVotes() {
        GameState state = rules.initialize(room(4, Map.of()), NOW);
        goToVoting(state);
        cast(state, "p0", "p1"); cast(state, "p1", "p0"); cast(state, "p2", "p0"); cast(state, "p3", "p1");
        assertEquals("TIE_DEFENSE", state.getPhase());
        assertEquals(List.of("p0", "p1"), strings(state.getData().get("runoffCandidates")));
        assertEquals(NOW.plusSeconds(60), state.getPhaseEndsAt());
        assertEquals("补充描述", rules.publicData(state).get("phaseLabel"));
        assertEquals(60, rules.legalActions(state, "p0").getFirst().maxLength());
        assertTrue(rules.legalActions(state, "p2").isEmpty());
        assertThrows(ApiException.class, () -> rules.apply(state, "p0", action("SPEAK", "长".repeat(61), null), NOW));
        assertTrue(state.getPlayers().stream().allMatch(GamePlayerState::isAlive));
        rules.apply(state, "p0", action("SKIP", "", null), NOW);
        rules.apply(state, "p1", action("SPEAK", "我补充的是使用习惯，不是外形。", null), NOW);
        assertEquals("RUNOFF", state.getPhase());
        assertEquals(NOW.plusSeconds(60), state.getPhaseEndsAt());
        assertThrows(ApiException.class, () -> cast(state, "p0", "p2"));
        assertThrows(ApiException.class, () -> cast(state, "p0", "p0"));
        cast(state, "p0", "p1"); cast(state, "p1", "p0"); cast(state, "p2", "p0"); cast(state, "p3", "p1");
        assertEquals("DESCRIPTION", state.getPhase());
        assertEquals(2, state.getRoundNumber());
        assertEquals(1, state.getData().get("noEliminationRounds"));
        assertTrue(state.getPlayers().stream().allMatch(GamePlayerState::isAlive));
    }

    @Test void ballotsStayPrivateUntilAllHaveVotedAndDuplicateVotesAreRejected() throws Exception {
        GameState state = rules.initialize(room(4, Map.of()), NOW); goToVoting(state);
        cast(state, "p0", "p1");
        assertEquals(List.of("p0"), rules.publicData(state).get("votedPlayers"));
        assertEquals("p1", rules.privateData(state, "p0").get("myVote"));
        assertFalse(rules.privateData(state, "p2").containsKey("myVote"));
        assertEquals("ALREADY_ACTED", assertThrows(ApiException.class, () -> cast(state, "p0", "p2")).getCode());
        assertTrue(maps(state.getData().get("events")).stream().noneMatch(e -> "VOTE_REVEAL".equals(e.get("type"))));
        assertTrue(maps(state.getData().get("events")).stream().filter(e -> "VOTE_CAST".equals(e.get("type"))).allMatch(e -> e.get("targetId") == null && map(e.get("data")).isEmpty()));
        rules.advance(state, NOW.plusSeconds(61));
        assertTrue(maps(state.getData().get("events")).stream().anyMatch(e -> "VOTE_REVEAL".equals(e.get("type"))));
        assertFalse(player(state, "p1").isAlive());
    }

    @Test void threeNoEliminationRoundsDrawAndEachNewRoundRotatesTheStartingSeat() {
        GameState state = rules.initialize(room(4, Map.of()), NOW);
        int first = state.getCurrentSeat();
        for (int round = 1; round <= 3; round++) {
            assertEquals(round, state.getRoundNumber());
            goToVoting(state);
            for (String id : List.of("p0", "p1", "p2", "p3")) rules.apply(state, id, action("SKIP", "", null), NOW);
            if (round < 3) assertEquals((first + round) % 4, state.getCurrentSeat());
        }
        assertEquals("SETTLEMENT", state.getPhase());
        assertEquals("DRAW", state.getData().get("winner"));
        assertTrue(rules.winningPlayerIds(state).isEmpty());
    }

    @Test void blankDoesNotWinAtThreeButWinsAtTwoBeforeUndercoverParity() {
        GameState state = rules.initialize(room(4, Map.of("hasBlank", true)), NOW);
        roles(state, "UNDERCOVER", "BLANK", "CIVILIAN", "CIVILIAN");
        eliminate(state, "p3");
        assertEquals(3, alive(state).size());
        assertNotEquals("SETTLEMENT", state.getPhase());
        eliminate(state, "p2");
        assertEquals("BLANK", state.getData().get("winner"));
        assertEquals(Set.of("p1"), rules.winningPlayerIds(state));
    }

    @Test void noUndercoverButABlankStillContinuesAndTheBlankCanWinAgainstOneCivilian() {
        GameState state = rules.initialize(room(4, Map.of("hasBlank", true)), NOW);
        roles(state, "UNDERCOVER", "BLANK", "CIVILIAN", "CIVILIAN");
        eliminate(state, "p0");
        assertNotEquals("SETTLEMENT", state.getPhase());
        eliminate(state, "p2");
        assertEquals("BLANK", state.getData().get("winner"));
    }

    @Test void undercoverCanWinBeforeFinalTwoAndEliminatedCivilianTeammatesShareVictory() {
        GameState spyWin = rules.initialize(room(6, Map.of("hasBlank", true)), NOW);
        roles(spyWin, "UNDERCOVER", "UNDERCOVER", "BLANK", "CIVILIAN", "CIVILIAN", "CIVILIAN");
        eliminate(spyWin, "p5"); eliminate(spyWin, "p4");
        assertEquals(4, alive(spyWin).size());
        assertEquals("UNDERCOVER", spyWin.getData().get("winner"));
        GameState civilianWin = rules.initialize(room(5, Map.of()), NOW);
        roles(civilianWin, "UNDERCOVER", "CIVILIAN", "CIVILIAN", "CIVILIAN", "CIVILIAN");
        eliminate(civilianWin, "p3"); eliminate(civilianWin, "p0");
        assertEquals("CIVILIAN", civilianWin.getData().get("winner"));
        assertEquals(Set.of("p1", "p2", "p3", "p4"), rules.winningPlayerIds(civilianWin));
    }

    @Test void undercoverWinsAtFinalThreeWithoutBlankOrAtParity() {
        for (int spies : List.of(1, 2, 3)) {
            int civilians = spies == 1 ? 3 : spies;
            GameState state = rules.initialize(room(spies + civilians + 1, Map.of()), NOW);
            String[] assigned = new String[state.getPlayers().size()];
            Arrays.fill(assigned, "CIVILIAN"); Arrays.fill(assigned, 0, spies, "UNDERCOVER");
            roles(state, assigned);
            eliminate(state, "p" + (assigned.length - 1));
            if (spies == 1) {
                assertEquals("DESCRIPTION", state.getPhase()); // 1 + 3 still plays.
                eliminate(state, "p3"); // 1 + 2 now wins.
            }
            assertEquals("SETTLEMENT", state.getPhase());
            assertEquals("UNDERCOVER", state.getData().get("winner"));
        }
    }

    @Test void removingBlankImmediatelyEnablesFinalThreeWin() {
        GameState state = rules.initialize(room(4, Map.of("hasBlank", true)), NOW);
        roles(state, "UNDERCOVER", "BLANK", "CIVILIAN", "CIVILIAN");
        eliminate(state, "p1");
        assertEquals("UNDERCOVER", state.getData().get("winner"));
        assertEquals(Set.of("p0"), rules.winningPlayerIds(state));
    }

    @Test void timedOutDescriptionsAndExtraDescriptionsReachTheNextBallot() {
        GameState state = rules.initialize(room(4, Map.of()), NOW);
        LocalDateTime now = NOW;
        for (int i = 0; i < 4; i++) { now = state.getPhaseEndsAt().plusSeconds(1); rules.advance(state, now); }
        assertEquals("VOTING", state.getPhase());
        for (String actor : List.of("p0", "p1", "p2", "p3")) {
            String target = Set.of("p0", "p3").contains(actor) ? "p1" : "p0";
            rules.apply(state, actor, action("VOTE", "", target), now);
        }
        assertEquals("TIE_DEFENSE", state.getPhase());
        for (int i = 0; i < 2; i++) { now = state.getPhaseEndsAt().plusSeconds(1); rules.advance(state, now); }
        assertEquals("RUNOFF", state.getPhase());
        rules.advance(state, state.getPhaseEndsAt().plusSeconds(1));
        assertEquals("DESCRIPTION", state.getPhase());
        assertEquals(1, state.getData().get("noEliminationRounds"));
        assertEquals(4, alive(state).size());
    }

    @Test void customHostCannotPlayAndPrivateWordsNeverAppearInPublicRules() throws Exception {
        Room room = customRoom("红色信封", "蓝色卡片");
        assertTrue(rules.validateStart(room).valid());
        GameState state = rules.initialize(room, NOW);
        String publicJson = new ObjectMapper().writeValueAsString(rules.publicData(state));
        assertFalse(publicJson.contains("红色信封")); assertFalse(publicJson.contains("蓝色卡片"));
        assertTrue(rules.legalActions(state, "host").isEmpty());
        room.setHostUserId("p0"); assertFalse(rules.validateStart(room).valid());
        room.setHostUserId("host");
        room.getPrivateConfig().put("customWords", List.of(Map.of("wordA", "同一个", "wordB", "同 一个")));
        assertFalse(rules.validateStart(room).valid());
        room.getPrivateConfig().put("customWords", List.of(Map.of("wordA", "红色信封", "wordB", "蓝色卡片"), Map.of("wordA", "蓝色卡片", "wordB", "红色信封")));
        assertFalse(rules.validateStart(room).valid());
    }

    @Test void secretSpeechFailsBeforeItChangesStateAndVoteSpeechCannotLeakTheBallot() {
        GameState state = rules.initialize(room(4, Map.of()), NOW);
        GamePlayerState actor = current(state);
        int logs = state.getLogs().size();
        rules.apply(state, actor.getPlayerId(), action("SPEAK", "我的词是" + actor.getWord(), null), NOW);
        assertEquals(2, rules.wordFeedback(state,actor.getPlayerId()).get("remainingCorrections"));
        assertEquals(logs, state.getLogs().size());
        var observation = observation(state, actor);
        var leak = AiTurnDecision.fallback(action("SPEAK", actor.getWord(), null));
        assertTrue(rules.validateDecision(observation, leak).contains("SECRET_WORD_LEAK"));
        goToVoting(state);
        observation = observation(state, actor);
        var vote = new AiTurnDecision(action("VOTE", "我会投给二号", "p1"), "我会投给二号", Map.of(), List.of(), Map.of(), false, Map.of());
        assertTrue(rules.validateDecision(observation, vote).contains("VOTE_MUST_REMAIN_PRIVATE"));
    }

    @Test void onlyOwnWordIsForbiddenAndThreeViolationsEliminateWithoutPublishingTheWord() throws Exception {
        var state = rules.initialize(room(6, Map.of("hasBlank",true)),NOW);
        var actor = current(state); actor.setRole("CIVILIAN"); actor.setWord("洗发水");
        var deadline = state.getPhaseEndsAt();
        for(int attempt=1;attempt<=3;attempt++) {
            rules.apply(state,actor.getPlayerId(),action("SPEAK","洗 发 水",null),NOW.plusSeconds(attempt));
            assertEquals(attempt,rules.wordFeedback(state,actor.getPlayerId()).get("violationCount"));
            assertEquals(List.of("洗发水"),rules.wordFeedback(state,actor.getPlayerId()).get("forbiddenTerms"));
            assertEquals(attempt<3,actor.isAlive());
            if(attempt<3) assertEquals(deadline,state.getPhaseEndsAt());
        }
        assertTrue(state.getLogs().stream().noneMatch(log -> log.getMessage().contains("洗 发 水")));
        assertTrue(rules.publicData(state).get("wordFeedback")==null);
        var restored=new ObjectMapper().findAndRegisterModules().readValue(new ObjectMapper().findAndRegisterModules().writeValueAsString(state),GameState.class);
        assertEquals(3,rules.wordFeedback(restored,actor.getPlayerId()).get("violationCount"));
        var opponent=current(state); opponent.setRole("UNDERCOVER"); opponent.setWord("沐浴露");
        rules.apply(state,opponent.getPlayerId(),action("SPEAK","对面像洗发水",null),NOW.plusSeconds(4));
        var blank=current(state); blank.setRole("BLANK"); blank.setWord("");
        rules.apply(state,blank.getPlayerId(),action("SPEAK","洗发水和沐浴露",null),NOW.plusSeconds(5));
        assertTrue(blank.isAlive());
    }

    @Test void legalCorrectionClearsCountAndTimeoutDoesNotEliminate() {
        var state=rules.initialize(room(6,Map.of()),NOW); var actor=current(state);
        rules.apply(state,actor.getPlayerId(),action("SPEAK",actor.getWord(),null),NOW);
        rules.apply(state,actor.getPlayerId(),action("SPEAK","这是一个常见的东西",null),NOW.plusSeconds(1));
        assertTrue(rules.wordFeedback(state,actor.getPlayerId()).isEmpty());
        actor=current(state);
        rules.apply(state,actor.getPlayerId(),action("SPEAK",actor.getWord(),null),NOW.plusSeconds(2));
        rules.advance(state,state.getPhaseEndsAt().plusSeconds(1));
        assertTrue(actor.isAlive()); assertTrue(rules.wordFeedback(state,actor.getPlayerId()).isEmpty());
    }

    @Test void allAiFailuresSkipAndConcludeWithoutInventedSpeech() {
        for (String category : UndercoverWordCatalog.CATEGORIES) {
            GameState state = rules.initialize(room(4, Map.of("hasBlank", true, "wordPack", category)), NOW);
            state.getPlayers().forEach(p -> p.setAi(true));
            int steps = 0;
            while (!"SETTLEMENT".equals(state.getPhase()) && steps++ < 100) {
                List<TurnRequest> turns = rules.pendingTurns(state);
                assertFalse(turns.isEmpty());
                for (TurnRequest turn : turns) {
                    if (!rules.isTurnCurrent(state, turn)) continue;
                    rules.onAiFailure(state, turn, NOW);
                }
            }
            assertEquals("SETTLEMENT", state.getPhase());
            assertEquals("DRAW", state.getData().get("winner"));
        }
    }

    private Room room(int count, Map<String, Object> config) {
        Room room = new Room(UUID.randomUUID().toString(), "undercover", "规则测试房", RoomStatus.WAITING, count, false, null, "text", new LinkedHashMap<>(config));
        room.setHostUserId("p0");
        for (int i = 0; i < count; i++) room.getSeats().add(new RoomSeat(i, "p" + i, "玩家" + i, false, null, "", true, i == 0));
        return room;
    }
    private Room customRoom(String a, String b) {
        Room room = room(4, Map.of("wordPack", "custom")); room.setHostUserId("host");
        room.getPrivateConfig().put("customWords", List.of(Map.of("wordA", a, "wordB", b))); return room;
    }
    private GamePlayerState current(GameState state) { return state.getPlayers().stream().filter(p -> Objects.equals(p.getSeatNumber(), state.getCurrentSeat())).findFirst().orElseThrow(); }
    private void finishDescriptions(GameState state) {
        while ("DESCRIPTION".equals(state.getPhase())) rules.apply(state, current(state).getPlayerId(), action("SPEAK", "我想到一种有具体使用场景的体验。", null), NOW);
    }
    private void goToVoting(GameState state) {
        finishDescriptions(state);
        assertEquals("VOTING", state.getPhase());
    }
    private void cast(GameState state, String actor, String target) { rules.apply(state, actor, action("VOTE", "", target), NOW); }
    private void eliminate(GameState state, String target) {
        goToVoting(state);
        List<String> actors = alive(state).stream().map(GamePlayerState::getPlayerId).toList();
        for (String actor : actors) rules.apply(state, actor, actor.equals(target) ? action("SKIP", "", null) : action("VOTE", "", target), NOW);
        assertFalse(player(state, target).isAlive());
    }
    private void roles(GameState state, String... roles) {
        for (int i = 0; i < roles.length; i++) {
            GamePlayerState p = player(state, "p" + i); p.setRole(roles[i]);
            p.setWord("BLANK".equals(roles[i]) ? "" : "UNDERCOVER".equals(roles[i]) ? text(state.getData().get("undercoverWord")) : text(state.getData().get("civilianWord")));
        }
    }
    private VisibleObservation observation(GameState state, GamePlayerState actor) {
        List<Map<String, Object>> events = maps(state.getData().get("events")).stream().filter(e -> "PUBLIC".equals(e.get("visibility"))).toList();
        return new VisibleObservation("undercover", "test", state.getPhase(), state.getRoundNumber(), actor.getPlayerId(), "test", Map.of("word", text(actor.getWord())), List.of(), Map.of(), events,
                rules.privateData(state, actor.getPlayerId()), rules.legalActions(state, actor.getPlayerId()), Map.of(), Map.of(), List.of());
    }
}
