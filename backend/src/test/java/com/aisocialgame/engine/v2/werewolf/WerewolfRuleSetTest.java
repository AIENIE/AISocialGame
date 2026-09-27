package com.aisocialgame.engine.v2.werewolf;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.engine.v2.LegalAction;
import com.aisocialgame.engine.v2.TurnRequest;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomSeat;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.service.ai.v2.VisibleObservation;
import com.aisocialgame.service.ai.v2.AiTurnDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class WerewolfRuleSetTest {
    private final WerewolfRuleSet rules = new WerewolfRuleSet();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private LocalDateTime now;

    @BeforeEach
    void setClock() {
        now = LocalDateTime.of(2026, 9, 12, 12, 0);
    }

    @ParameterizedTest
    @CsvSource({"standard,6", "standard,9", "standard,12", "guard,6", "guard,9", "guard,12", "no_god,6", "no_god,9", "no_god,12"})
    void everyAdvertisedBoardHasExactPersistedComposition(String template, int count) {
        GameState state = game(template, count);
        Map<String, Long> actual = state.getPlayers().stream().collect(Collectors.groupingBy(GamePlayerState::getRole, Collectors.counting()));
        Map<String, Integer> expected = rules.roleCounts(template, count);
        expected.forEach((role, total) -> assertEquals(total.longValue(), actual.get(role)));
        assertEquals(count, actual.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(expected, map(state.getData().get("rules")).get("roleCounts"));
        assertEquals("settlement", map(state.getData().get("rules")).get("deathReveal"));
        assertEquals(2, state.getData().get("ruleVersion"));
        assertNotNull(state.getData().get("archiveId"));
    }

    @Test
    void incompleteAndUnsupportedRoomsAreRejectedInsteadOfSilentlyChangingBoard() {
        Room partial = room("standard", 12, Map.of());
        partial.setSeats(new ArrayList<>(partial.getSeats().subList(0, 9)));
        assertFalse(rules.validateStart(partial).valid());
        Room unsupported = room("standard", 8, Map.of());
        assertFalse(rules.validateStart(unsupported).valid());
        assertFalse(rules.validateStart(room("not-a-board", 6, Map.of())).valid());
        assertFalse(rules.validateStart(room("standard", 6, Map.of("witchRule", "unlimited"))).valid());
        assertFalse(rules.validateStart(room("standard", 6, Map.of("speechTime", 1))).valid());
        Room duplicate = room("standard", 6, Map.of());
        duplicate.getSeats().get(1).setPlayerId(duplicate.getSeats().get(0).getPlayerId());
        assertFalse(rules.validateStart(duplicate).valid());
    }

    @Test
    void everyWolfContributesPrivatelyAndConsensusSelectsTheirSharedTarget() {
        GameState state = game("standard", 6);
        List<GamePlayerState> wolves = players(state, "WEREWOLF");
        GamePlayerState target = role(state, "VILLAGER");
        act(state, wolves.get(0), night("WOLF_KILL", target, "这个目标的说法有影响力。"));
        assertEquals(wolves.get(1).getPlayerId(), data(state).get("nightActor"));
        assertEquals("", data(state).get("wolfTarget"));
        assertEquals(1, maps(rules.privateData(state, wolves.get(1).getPlayerId()).get("wolfCouncil")).size());
        act(state, wolves.get(1), night("WOLF_KILL", target, "我同意这个目标，不过明天别都复述同一种说法。"));
        assertEquals(target.getPlayerId(), data(state).get("wolfTarget"));
        List<Map<String, Object>> council = events(state).stream().filter(e -> "WOLF_COUNCIL".equals(e.get("type"))).toList();
        assertEquals(2, council.size());
        assertTrue(council.stream().allMatch(e -> "PRIVATE".equals(e.get("visibility"))));
        assertTrue(council.stream().allMatch(e -> strings(e.get("visibleTo")).containsAll(wolves.stream().map(GamePlayerState::getPlayerId).toList())));
        assertFalse(rules.privateData(state, target.getPlayerId()).containsKey("wolfCouncil"));
        assertFalse(rules.publicData(state).containsKey("wolfTarget"));
        assertNull(state.getCurrentSeat());
    }

    @Test
    void tiedWolfVotesAreReproducibleAfterPersistence() throws Exception {
        GameState original = game("standard", 6);
        GameState restored = mapper.readValue(mapper.writeValueAsString(original), GameState.class);
        List<GamePlayerState> wolves = players(original, "WEREWOLF");
        List<GamePlayerState> targets = players(original, "VILLAGER");
        for (GameState state : List.of(original, restored)) {
            act(state, player(state, wolves.get(0).getPlayerId()), night("WOLF_KILL", targets.get(0), ""));
            act(state, player(state, wolves.get(1).getPlayerId()), night("WOLF_KILL", targets.get(1), ""));
        }
        assertEquals(data(original).get("wolfTarget"), data(restored).get("wolfTarget"));
        assertTrue(targets.stream().map(GamePlayerState::getPlayerId).toList().contains(data(original).get("wolfTarget")));
    }

    @Test
    void wolfCannotKillKnownTeammateAndCannotSubmitTwice() {
        GameState state = game("standard", 6);
        List<GamePlayerState> wolves = players(state, "WEREWOLF");
        GamePlayerState first = wolves.get(0);
        assertThrows(ApiException.class, () -> act(state, first, night("WOLF_KILL", wolves.get(1), "")));
        GamePlayerState target = role(state, "VILLAGER");
        act(state, first, night("WOLF_KILL", target, ""));
        int events = events(state).size();
        assertThrows(ApiException.class, () -> act(state, first, night("WOLF_KILL", target, "")));
        assertEquals(events, events(state).size());
    }

    @ParameterizedTest
    @CsvSource({"no_save,1,false", "no_save,2,false", "first_night,1,true", "first_night,2,false", "always_save,1,true", "always_save,2,true"})
    void selfSaveAvailabilityMatchesEveryAdvertisedWitchRule(String option, int round, boolean permitted) {
        GameState state = game("standard", 6, Map.of("witchRule", option));
        if (round == 2) {
            skipNight(state);
            reachVoting(state);
            allAbstain(state);
        }
        GamePlayerState witch = role(state, "WITCH");
        untilRole(state, "WITCH", p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", witch, "") : skip());
        assertEquals(round, state.getRoundNumber());
        boolean offered = rules.legalActions(state, witch.getPlayerId()).stream().anyMatch(a -> "WITCH_SAVE".equals(a.nightAction()));
        assertEquals(permitted, offered);
        if (!permitted) assertThrows(ApiException.class, () -> act(state, witch, heal(witch)));
    }

    @Test
    void antidoteConsumptionDoesNotSkipWitchOnLaterNightsOrRevealLaterKnife() {
        GameState state = game("standard", 6);
        GamePlayerState witch = role(state, "WITCH");
        untilRole(state, "WITCH", p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", witch, "") : skip());
        act(state, witch, heal(witch));
        assertTrue(witch.isAlive());
        assertEquals(0, rules.privateData(state, witch.getPlayerId()).get("antidoteRemaining"));
        assertEquals(1, rules.privateData(state, witch.getPlayerId()).get("poisonRemaining"));
        reachVoting(state);
        allAbstain(state);
        untilRole(state, "WITCH", p -> skip());
        assertEquals(witch.getPlayerId(), data(state).get("nightActor"));
        Map<String, Object> privateData = rules.privateData(state, witch.getPlayerId());
        assertFalse(privateData.containsKey("wolfTarget"));
        assertTrue(rules.legalActions(state, witch.getPlayerId()).stream().anyMatch(a -> "WITCH_POISON".equals(a.nightAction())));
        assertFalse(rules.legalActions(state, witch.getPlayerId()).stream().anyMatch(a -> "WITCH_SAVE".equals(a.nightAction())));
        act(state, witch, skip());
        assertEquals("DAY_DISCUSS", state.getPhase());
        assertEquals(1, rules.privateData(state, witch.getPlayerId()).get("poisonRemaining"));
    }

    @Test
    void explicitAndLegacyWitchSkipPreserveBothPotionsAndFinishHerTurn() {
        GameState state = game("standard", 6);
        untilRole(state, "WITCH", p -> skip());
        GamePlayerState witch = role(state, "WITCH");
        PlayerAction legacy = night("WITCH_SAVE", null, "");
        legacy.setUseHeal(false);
        act(state, witch, legacy);
        assertEquals("DAY_DISCUSS", state.getPhase());
        assertEquals(1, rules.privateData(state, witch.getPlayerId()).get("antidoteRemaining"));
        assertEquals(1, rules.privateData(state, witch.getPlayerId()).get("poisonRemaining"));
    }

    @Test
    void witchCannotUseTwoPotionsInOneNight() {
        GameState state = game("standard", 6);
        GamePlayerState witch = role(state, "WITCH");
        GamePlayerState target = role(state, "VILLAGER");
        untilRole(state, "WITCH", p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", target, "") : skip());
        act(state, witch, heal(target));
        assertThrows(ApiException.class, () -> act(state, witch, night("WITCH_POISON", target, "")));
        assertEquals(1, rules.privateData(state, witch.getPlayerId()).get("poisonRemaining"));
    }

    @Test
    void seerHistoryRetainsTargetRoundResultAndPrivateEventAcrossNights() {
        GameState state = game("standard", 6);
        GamePlayerState seer = role(state, "SEER");
        GamePlayerState wolf = role(state, "WEREWOLF");
        GamePlayerState villager = role(state, "VILLAGER");
        playNight(state, p -> p == seer ? night("SEER_CHECK", wolf, "") : skip());
        reachVoting(state);
        allAbstain(state);
        playNight(state, p -> p == seer ? night("SEER_CHECK", villager, "") : skip());
        List<Map<String, Object>> checks = maps(rules.privateData(state, seer.getPlayerId()).get("seerChecks"));
        assertEquals(2, checks.size());
        assertEquals(wolf.getPlayerId(), checks.get(0).get("targetPlayerId"));
        assertEquals(1, checks.get(0).get("round"));
        assertEquals("WOLF", checks.get(0).get("result"));
        assertEquals(villager.getPlayerId(), checks.get(1).get("targetPlayerId"));
        assertEquals(2, checks.get(1).get("round"));
        assertEquals("GOOD", checks.get(1).get("result"));
        assertNotEquals(checks.get(0).get("eventId"), checks.get(1).get("eventId"));
        assertFalse(rules.privateData(state, wolf.getPlayerId()).containsKey("seerChecks"));
        assertFalse(rules.publicData(state).containsKey("seerChecks"));
        assertTrue(events(state).stream().filter(e -> "SEER_CHECK".equals(e.get("type"))).allMatch(e -> List.of(seer.getPlayerId()).equals(e.get("visibleTo"))));
    }

    @Test
    void guardCannotRepeatAndSkippingResetsConsecutiveRestriction() {
        GameState state = game("guard", 6);
        GamePlayerState guard = role(state, "GUARD");
        GamePlayerState protectedPlayer = role(state, "VILLAGER");
        playNight(state, p -> p == guard ? night("GUARD_PROTECT", protectedPlayer, "") : skip());
        reachVoting(state);
        allAbstain(state);
        untilRole(state, "GUARD", p -> skip());
        assertFalse(nightTargets(state, guard, "GUARD_PROTECT").contains(protectedPlayer.getPlayerId()));
        assertThrows(ApiException.class, () -> act(state, guard, night("GUARD_PROTECT", protectedPlayer, "")));
        act(state, guard, skip());
        skipNight(state);
        reachVoting(state);
        allAbstain(state);
        untilRole(state, "GUARD", p -> skip());
        assertTrue(nightTargets(state, guard, "GUARD_PROTECT").contains(protectedPlayer.getPlayerId()));
        assertTrue(nightTargets(state, guard, "GUARD_PROTECT").contains(guard.getPlayerId()));
    }

    @Test
    void guardAndAntidoteOnSameKnifeFailTogetherAndConsumeAntidote() {
        GameState state = game("guard", 12);
        GamePlayerState target = role(state, "VILLAGER");
        playNight(state, p -> switch (p.getRole()) {
            case "WEREWOLF" -> night("WOLF_KILL", target, "");
            case "GUARD" -> night("GUARD_PROTECT", target, "");
            case "WITCH" -> heal(target);
            default -> skip();
        });
        assertFalse(target.isAlive());
        assertEquals(0, rules.privateData(state, role(state, "WITCH").getPlayerId()).get("antidoteRemaining"));
        assertEquals("DAY_DISCUSS", state.getPhase());
    }

    @Test
    void poisonTakesPrecedenceOverKnifeAndPreventsHunterShot() {
        GameState state = game("guard", 12);
        GamePlayerState hunter = role(state, "HUNTER");
        playNight(state, p -> switch (p.getRole()) {
            case "WEREWOLF" -> night("WOLF_KILL", hunter, "");
            case "WITCH" -> night("WITCH_POISON", hunter, "");
            default -> skip();
        });
        assertFalse(hunter.isAlive());
        assertEquals("DAY_DISCUSS", state.getPhase());
        assertFalse((Boolean) rules.privateData(state, hunter.getPlayerId()).get("hunterShotAvailable"));
        assertTrue(rules.legalActions(state, hunter.getPlayerId()).isEmpty());
        assertTrue(events(state).stream().filter(e -> "PUBLIC".equals(e.get("visibility"))).noneMatch(e -> map(e.get("data")).containsKey("cause")));
    }

    @Test
    void exiledHunterActsBeforeWinnerCheckAndCanEliminateLastWolf() {
        GameState state = game("standard", 9);
        skipNight(state);
        List<GamePlayerState> wolves = players(state, "WEREWOLF");
        for (int i = 0; i < 2; i++) {
            reachVoting(state);
            voteOut(state, wolves.get(i));
            assertEquals("NIGHT", state.getPhase());
            skipNight(state);
        }
        GamePlayerState hunter = role(state, "HUNTER");
        reachVoting(state);
        voteOut(state, hunter);
        assertEquals("DEATH_ACTION", state.getPhase());
        assertNull(state.getCurrentSeat());
        assertTrue(rules.legalActions(state, hunter.getPlayerId()).stream().anyMatch(a -> "HUNTER_SHOOT".equals(a.type())));
        assertThrows(ApiException.class, () -> act(state, hunter, action("HUNTER_SHOOT", "", wolves.get(0).getPlayerId())));
        act(state, hunter, action("HUNTER_SHOOT", "我最后选择三号，请看他前后的说法。", wolves.get(2).getPlayerId()));
        assertEquals("SETTLEMENT", state.getPhase());
        assertEquals("GOOD", state.getData().get("winner"));
        assertTrue(rules.winningPlayerIds(state).contains(hunter.getPlayerId()));
    }

    @Test
    void hunterCanDeclineAndTimeoutNeverFiresAtRandom() {
        GameState state = game("standard", 9);
        GamePlayerState hunter = role(state, "HUNTER");
        playNight(state, p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", hunter, "") : skip());
        assertEquals("DEATH_ACTION", state.getPhase());
        long before = alive(state).size();
        timeout(state);
        assertEquals(before, alive(state).size());
        assertEquals("DAY_DISCUSS", state.getPhase());
        assertTrue(rules.legalActions(state, hunter.getPlayerId()).isEmpty());
    }

    @Test
    void revealedIdiotSurvivesExileLosesVoteAndCanStillSpeakThenDieAtNight() {
        GameState state = game("standard", 12);
        GamePlayerState idiot = role(state, "IDIOT");
        skipNight(state);
        reachVoting(state);
        voteOut(state, idiot);
        assertTrue(idiot.isAlive());
        assertEquals("NIGHT", state.getPhase());
        assertEquals("IDIOT", rules.visibleRole(state, idiot, role(state, "VILLAGER").getPlayerId()));
        skipNight(state);
        while ("DAY_DISCUSS".equals(state.getPhase()) && !Objects.equals(idiot.getSeatNumber(), state.getCurrentSeat())) act(state, current(state), skip());
        assertTrue(rules.legalActions(state, idiot.getPlayerId()).stream().anyMatch(a -> "SPEAK".equals(a.type())));
        reachVoting(state);
        assertTrue(rules.legalActions(state, idiot.getPlayerId()).isEmpty());
        assertTrue(alive(state).stream().flatMap(p -> rules.legalActions(state, p.getPlayerId()).stream()).noneMatch(a -> a.targets().contains(idiot.getPlayerId())));
        allAbstain(state);
        playNight(state, p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", idiot, "") : skip());
        assertFalse(idiot.isAlive());
    }

    @Test
    void onlyFirstNightDeathsGetFirstNightLastWords() {
        GameState state = game("standard", 6, Map.of("hasLastWords", "first_night", "winCondition", "city"));
        List<GamePlayerState> villagers = players(state, "VILLAGER");
        playNight(state, p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", villagers.get(0), "") : skip());
        assertEquals("LAST_WORDS", state.getPhase());
        assertEquals(villagers.get(0).getPlayerId(), data(state).get("lastWordsActor"));
        assertThrows(ApiException.class, () -> act(state, role(state, "SEER"), action("SPEAK", "不该轮到我", null)));
        act(state, villagers.get(0), action("SPEAK", "我先退场，请继续对照发言和票型。", null));
        reachVoting(state);
        allAbstain(state);
        playNight(state, p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", villagers.get(1), "") : skip());
        assertEquals(2, state.getRoundNumber());
        assertEquals("DAY_DISCUSS", state.getPhase());
        assertEquals(1, events(state).stream().filter(e -> "LAST_WORDS".equals(e.get("type"))).count());
    }

    @Test
    void alwaysLastWordsIncludesDayExileAndTimeoutAdvancesExactlyOnce() {
        GameState state = game("standard", 6, Map.of("hasLastWords", "always"));
        skipNight(state);
        reachVoting(state);
        voteOut(state, role(state, "VILLAGER"));
        assertEquals("LAST_WORDS", state.getPhase());
        timeout(state);
        assertEquals("NIGHT", state.getPhase());
        String actor = text(data(state).get("nightActor"));
        int serial = number(state.getData().get("phaseSerial"), 0);
        rules.advance(state, now);
        assertEquals(actor, data(state).get("nightActor"));
        assertEquals(serial, number(state.getData().get("phaseSerial"), 0));
    }

    @ParameterizedTest
    @CsvSource({"side,true", "city,false"})
    void sideAndCityUseActualFactionExtinctionInsteadOfParity(String winCondition, boolean wolfWins) {
        GameState state = game("standard", 6, Map.of("winCondition", winCondition));
        List<GamePlayerState> villagers = players(state, "VILLAGER");
        playNight(state, p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", villagers.get(0), "") : skip());
        reachVoting(state);
        allAbstain(state);
        playNight(state, p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", villagers.get(1), "") : skip());
        assertEquals(wolfWins, "SETTLEMENT".equals(state.getPhase()));
        if (wolfWins) assertEquals("WEREWOLF", state.getData().get("winner"));
        else assertEquals(2, alive(state).stream().filter(p -> !"WEREWOLF".equals(p.getRole())).count());
    }

    @Test
    void noGodBoardDoesNotImmediatelyWinBecauseItsGodCategoryIsEmpty() {
        GameState state = game("no_god", 6);
        skipNight(state);
        assertEquals("DAY_DISCUSS", state.getPhase());
        for (GamePlayerState victim : players(state, "VILLAGER")) {
            reachVoting(state);
            allAbstain(state);
            playNight(state, p -> night("WOLF_KILL", victim, ""));
        }
        assertEquals("SETTLEMENT", state.getPhase());
        assertEquals("WEREWOLF", state.getData().get("winner"));
    }

    @Test
    void deadIdentitiesAndNightCausesRemainHiddenUntilSettlement() throws Exception {
        GameState state = game("standard", 6);
        GamePlayerState seer = role(state, "SEER");
        GamePlayerState viewer = role(state, "VILLAGER");
        playNight(state, p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", seer, "") : skip());
        assertFalse(seer.isAlive());
        assertNull(rules.visibleRole(state, seer, viewer.getPlayerId()));
        assertNull(rules.visibleRole(state, seer, null));
        assertEquals("SEER", rules.visibleRole(state, seer, seer.getPlayerId()));
        Map<String, Object> death = events(state).stream().filter(e -> "DEATH".equals(e.get("type"))).findFirst().orElseThrow();
        assertFalse(map(death.get("data")).containsKey("role"));
        assertFalse(map(death.get("data")).containsKey("cause"));
        assertFalse(text(death.get("message")).contains("预言家"));
        String publicJson = mapper.writeValueAsString(rules.publicData(state));
        assertFalse(publicJson.contains("wolfTarget"));
        assertFalse(publicJson.contains("seerChecks"));
        assertFalse(publicJson.contains("nightActor"));
        assertFalse(publicJson.contains("nightStep"));
        assertFalse(publicJson.contains("poisonTarget"));
        state.setPhase("SETTLEMENT");
        assertEquals("SEER", rules.visibleRole(state, seer, viewer.getPlayerId()));
    }

    @Test
    void anotherRolesPrivateFactsDoNotAppearInVillagerObservation() {
        GameState state = game("guard", 12);
        GamePlayerState villager = role(state, "VILLAGER");
        GamePlayerState seer = role(state, "SEER");
        GamePlayerState wolf = role(state, "WEREWOLF");
        untilRole(state, "WITCH", p -> switch (p.getRole()) {
            case "WEREWOLF" -> night("WOLF_KILL", villager, "只有队友能看到的建议");
            case "SEER" -> night("SEER_CHECK", wolf, "");
            case "GUARD" -> night("GUARD_PROTECT", seer, "");
            default -> skip();
        });
        Map<String, Object> ordinary = rules.privateData(state, villager.getPlayerId());
        assertEquals(Set.of("canVote"), ordinary.keySet());
        assertEquals(villager.getPlayerId(), rules.privateData(state, role(state, "WITCH").getPlayerId()).get("wolfTarget"));
        assertFalse(rules.privateData(state, seer.getPlayerId()).containsKey("wolfTarget"));
        assertFalse(rules.privateData(state, wolf.getPlayerId()).containsKey("seerChecks"));
        assertFalse(rules.privateData(state, wolf.getPlayerId()).containsKey("guardHistory"));
    }

    @Test
    void votesArePrivateUntilAllVotesArriveAndRealTiesEliminateNobody() {
        GameState state = game("standard", 6);
        skipNight(state);
        reachVoting(state);
        List<GamePlayerState> p = state.getPlayers();
        act(state, p.get(0), action("VOTE", "暂时选二号。", p.get(1).getPlayerId()));
        assertFalse(rules.publicData(state).containsKey("votes"));
        assertEquals(p.get(1).getPlayerId(), rules.privateData(state, p.get(0).getPlayerId()).get("myVote"));
        assertFalse(rules.privateData(state, p.get(2).getPlayerId()).containsKey("myVote"));
        assertEquals(0, events(state).stream().filter(e -> "VOTE_REVEALED".equals(e.get("type"))).count());
        for (int i = 1; i < p.size(); i++) {
            String target = i <= 3 ? p.get(0).getPlayerId() : p.get(1).getPlayerId();
            act(state, p.get(i), action("VOTE", "", target));
        }
        assertEquals(6, alive(state).size());
        assertEquals("NIGHT", state.getPhase());
        assertEquals(6, events(state).stream().filter(e -> "VOTE_REVEALED".equals(e.get("type"))).count());
        assertTrue(events(state).stream().anyMatch(e -> "VOTE_TIED".equals(e.get("type"))));
    }

    @Test
    void interactionReservesHumanWindowAndEnforcesTwoDirectedPairs() {
        GameState state = game("standard", 6);
        state.getPlayers().forEach(p -> p.setAi(true));
        List<GamePlayerState> p = state.getPlayers();
        p.get(0).setAi(false);
        skipNight(state);
        reachInteraction(state);
        assertTrue(rules.pendingTurns(state).isEmpty());
        assertTrue(rules.legalActions(state, p.get(0).getPlayerId()).stream().anyMatch(a -> "ASK_PLAYER".equals(a.type())));
        act(state, p.get(0), action("ASK_PLAYER", "你刚才判断的依据是哪句话？", p.get(1).getPlayerId()));
        assertEquals(1, rules.pendingTurns(state).size());
        assertEquals(p.get(1).getPlayerId(), rules.pendingTurns(state).get(0).actorId());
        assertTrue(rules.legalActions(state, p.get(2).getPlayerId()).isEmpty());
        act(state, p.get(1), action("ANSWER_PLAYER", "主要是前后解释的变化，我也还没有确定。", p.get(0).getPlayerId()));
        assertTrue(rules.legalActions(state, p.get(0).getPlayerId()).isEmpty());
        assertFalse(rules.legalActions(state, p.get(2).getPlayerId()).get(0).targets().contains(p.get(1).getPlayerId()));
        act(state, p.get(2), action("ASK_PLAYER", "你愿意解释刚才为什么转向吗？", p.get(3).getPlayerId()));
        act(state, p.get(3), action("ANSWER_PLAYER", "刚才的新信息让我觉得需要重新判断。", p.get(2).getPlayerId()));
        assertEquals("DAY_VOTE", state.getPhase());
        assertThrows(ApiException.class, () -> act(state, p.get(4), action("ASK_PLAYER", "不能有第三组。", p.get(5).getPlayerId())));
        assertEquals(2, events(state).stream().filter(e -> "ASK_PLAYER".equals(e.get("type"))).count());
    }

    @Test
    void humanSkippingQuestionDoesNotSilentlySkipAllAiParticipation() {
        GameState state = game("standard", 6);
        state.getPlayers().forEach(p -> p.setAi(true));
        GamePlayerState human = state.getPlayers().get(0);
        human.setAi(false);
        skipNight(state);
        reachInteraction(state);
        act(state, human, skip());
        assertEquals("DAY_INTERACTION", state.getPhase());
        assertFalse(rules.pendingTurns(state).isEmpty());
        assertTrue(flag(data(state).get("humanPriorityElapsed")));
    }

    @Test
    void interactionAndAnswerTimeoutsRemainBounded() {
        GameState state = game("standard", 6);
        skipNight(state);
        reachInteraction(state);
        timeout(state);
        assertTrue(flag(data(state).get("humanPriorityElapsed")));
        GamePlayerState asker = state.getPlayers().get(0);
        GamePlayerState answerer = state.getPlayers().get(1);
        act(state, asker, action("ASK_PLAYER", "你想补充哪条依据？", answerer.getPlayerId()));
        LocalDateTime deadline = state.getPhaseEndsAt();
        assertTrue(java.time.Duration.between(now, deadline).toSeconds() <= 20);
        timeout(state);
        assertEquals(1, data(state).get("interactionPairs"));
        timeout(state);
        assertEquals("DAY_VOTE", state.getPhase());
    }

    @Test
    void readPathsDoNotAdvanceAndTurnKeysExpireWhenOpportunityChanges() throws Exception {
        GameState state = game("standard", 6);
        state.getPlayers().forEach(p -> p.setAi(true));
        String before = mapper.writeValueAsString(state);
        List<TurnRequest> turns = rules.pendingTurns(state);
        assertEquals(turns, rules.pendingTurns(state));
        assertFalse(turns.isEmpty());
        state.getPlayers().forEach(p -> { rules.legalActions(state, p.getPlayerId()); rules.privateData(state, p.getPlayerId()); });
        rules.publicData(state);
        assertEquals(before, mapper.writeValueAsString(state));
        TurnRequest old = turns.get(0);
        act(state, player(state, old.actorId()), skip());
        assertFalse(rules.isTurnCurrent(state, old));
        assertTrue(rules.pendingTurns(state).stream().noneMatch(t -> old.key().equals(t.key())));
    }

    @Test
    void failedNightCallClosesOnlyItsExpiredPrivateOpportunityAndCannotRepeat() throws Exception {
        GameState state = game("standard", 6);
        state.getPlayers().forEach(p -> p.setAi(true));
        TurnRequest failed = rules.pendingTurns(state).get(0);
        int publicLogs = state.getLogs().size();
        state.setPhaseEndsAt(now.minusSeconds(1));

        rules.onAiFailure(state, failed, now);

        assertEquals("NIGHT", state.getPhase());
        assertNotEquals(failed.actorId(), data(state).get("nightActor"));
        assertEquals(Map.of(failed.actorId(), "abstain"), map(data(state).get("wolfVotes")));
        assertEquals(publicLogs, state.getLogs().size());
        Map<String, Object> council = maps(data(state).get("wolfCouncil")).get(0);
        assertEquals("", council.get("content"));
        assertEquals("PRIVATE", events(state).get(events(state).size() - 1).get("visibility"));
        String after = mapper.writeValueAsString(state);
        rules.onAiFailure(state, failed, now);
        assertEquals(after, mapper.writeValueAsString(state), "a stale failed callback cannot close the next actor's turn");
    }

    @Test
    void failedBallotDoesNotExpireOtherPlayersOrRevealUnfinishedVotes() {
        GameState state = game("standard", 6);
        state.getPlayers().forEach(p -> p.setAi(true));
        skipNight(state);
        reachVoting(state);
        TurnRequest failed = rules.pendingTurns(state).get(0);
        LocalDateTime deadline = state.getPhaseEndsAt();
        int publicLogs = state.getLogs().size();

        rules.onAiFailure(state, failed, now);

        assertEquals("DAY_VOTE", state.getPhase());
        assertEquals(deadline, state.getPhaseEndsAt());
        assertEquals(Map.of(failed.actorId(), "abstain"), map(data(state).get("votes")));
        assertEquals(5, rules.pendingTurns(state).size());
        assertTrue(rules.pendingTurns(state).stream().noneMatch(turn -> failed.actorId().equals(turn.actorId())));
        assertEquals(publicLogs, state.getLogs().size());
        assertFalse(events(state).stream().anyMatch(event -> "VOTE_REVEALED".equals(event.get("type"))));
    }

    @Test
    void publicRoleStatementsDoNotChangeAuthoritativeIdentity() {
        GameState state = game("standard", 6);
        skipNight(state);
        GamePlayerState speaker = current(state);
        String identity = speaker.getRole();
        act(state, speaker, action("SPEAK", "我自称是预言家，二号是我的金水。", null));
        assertEquals(identity, speaker.getRole());
        Map<String, Object> statement = events(state).stream().filter(e -> "SPEECH".equals(e.get("type"))).findFirst().orElseThrow();
        assertEquals("PLAYER_CLAIM", map(statement.get("data")).get("statement"));
        assertFalse(map(statement.get("data")).containsKey("role"));
    }

    @Test
    void fallbackWitchCanSaveSelfButNeverBlindlyPoisonsAndSeerPrefersFreshTarget() {
        GameState state = game("standard", 6);
        GamePlayerState witch = role(state, "WITCH");
        untilRole(state, "WITCH", p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", witch, "") : skip());
        PlayerAction saveSelf = rules.fallback(observation(state, witch));
        assertEquals("WITCH_SAVE", saveSelf.getNightAction());
        assertTrue(saveSelf.isUseHeal());
        act(state, witch, saveSelf);
        reachVoting(state);
        allAbstain(state);
        untilRole(state, "WITCH", p -> skip());
        assertEquals("SKIP", rules.fallback(observation(state, witch)).getType());

        GameState seerState = game("standard", 6);
        GamePlayerState seer = role(seerState, "SEER");
        GamePlayerState known = role(seerState, "VILLAGER");
        playNight(seerState, p -> p == seer ? night("SEER_CHECK", known, "") : skip());
        reachVoting(seerState);
        allAbstain(seerState);
        untilRole(seerState, "SEER", p -> skip());
        PlayerAction check = rules.fallback(observation(seerState, seer));
        assertEquals("SEER_CHECK", check.getNightAction());
        assertNotEquals(known.getPlayerId(), check.getTargetPlayerId());
        assertTrue(nightTargets(seerState, seer, "SEER_CHECK").contains(check.getTargetPlayerId()));
    }

    @Test
    void generatedWitchSaveMustExplicitlyUseAntidoteInsteadOfSilentlyBecomingSkip() {
        GameState state = game("standard", 6);
        GamePlayerState witch = role(state, "WITCH");
        untilRole(state, "WITCH", p -> "WEREWOLF".equals(p.getRole()) ? night("WOLF_KILL", witch, "") : skip());
        PlayerAction missingFlag = night("WITCH_SAVE", witch, "");
        AiTurnDecision invalid = new AiTurnDecision(missingFlag, "", Map.of(), List.of(), Map.of(), false, Map.of());
        assertTrue(rules.validateDecision(observation(state, witch), invalid).stream().anyMatch(message -> message.contains("useHeal=true")));
        missingFlag.setUseHeal(true);
        assertTrue(rules.validateDecision(observation(state, witch), invalid).isEmpty());
    }

    @Test
    void knowledgeCompositionCannotDriftAndTenScenariosHaveValidEvidenceReferences() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/game-knowledge/werewolf.json")) {
            assertNotNull(in);
            Map<String, Object> pack = mapper.readValue(in, new TypeReference<>() {});
            assertEquals(2, pack.get("ruleVersion"));
            Map<String, Object> compositions = map(map(pack.get("rules")).get("compositions"));
            for (String template : List.of("standard", "guard", "no_god")) {
                for (int count : List.of(6, 9, 12)) {
                    assertEquals(rules.roleCounts(template, count), map(map(compositions.get(template)).get(String.valueOf(count))));
                }
            }
        }
        try (InputStream in = getClass().getResourceAsStream("/ai-scenarios/werewolf.json")) {
            assertNotNull(in);
            Map<String, Object> fixtures = mapper.readValue(in, new TypeReference<>() {});
            List<Map<String, Object>> scenarios = maps(fixtures.get("scenarios"));
            assertEquals(10, scenarios.size());
            assertEquals(10, scenarios.stream().map(s -> text(s.get("scenarioId"))).distinct().count());
            for (Map<String, Object> scenario : scenarios) {
                Set<String> eventIds = maps(scenario.get("visibleEvents")).stream().map(e -> text(e.get("eventId"))).collect(Collectors.toSet());
                assertTrue(eventIds.containsAll(strings(map(scenario.get("expectations")).get("requiredEventIds"))), text(scenario.get("scenarioId")));
                assertFalse(maps(scenario.get("legalActions")).isEmpty());
                assertFalse(strings(map(scenario.get("expectations")).get("behavior")).isEmpty());
            }
        }
    }

    private GameState game(String template, int count) {
        return game(template, count, Map.of());
    }

    private GameState game(String template, int count, Map<String, Object> extra) {
        return rules.initialize(room(template, count, extra), now);
    }

    private Room room(String template, int count, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>(Map.of("template", template, "playerCount", count, "hasLastWords", "none"));
        config.putAll(extra);
        Room room = new Room("werewolf-test-room", "werewolf", "狼人杀测试", RoomStatus.WAITING, count, false, null, "text", config);
        List<RoomSeat> seats = new ArrayList<>();
        for (int i = 0; i < count; i++) seats.add(new RoomSeat(i, "p" + i, (i + 1) + "号", false, "ai1", "", true, i == 0));
        room.setSeats(seats);
        return room;
    }

    private List<GamePlayerState> players(GameState state, String role) {
        return state.getPlayers().stream().filter(p -> role.equals(p.getRole())).toList();
    }

    private GamePlayerState role(GameState state, String role) {
        return players(state, role).get(0);
    }

    private Map<String, Object> data(GameState state) {
        return map(state.getData().get("werewolf"));
    }

    private List<Map<String, Object>> events(GameState state) {
        return maps(state.getData().get("events"));
    }

    private PlayerAction skip() {
        return action("SKIP", "", null);
    }

    private PlayerAction night(String kind, GamePlayerState target, String content) {
        PlayerAction action = action("NIGHT_ACTION", content, target == null ? null : target.getPlayerId());
        action.setNightAction(kind);
        return action;
    }

    private PlayerAction heal(GamePlayerState target) {
        PlayerAction action = night("WITCH_SAVE", target, "");
        action.setUseHeal(true);
        return action;
    }

    private void act(GameState state, GamePlayerState actor, PlayerAction action) {
        rules.apply(state, actor.getPlayerId(), action, now);
    }

    private void timeout(GameState state) {
        assertNotNull(state.getPhaseEndsAt());
        now = state.getPhaseEndsAt();
        rules.advance(state, now);
    }

    private void skipNight(GameState state) {
        playNight(state, p -> skip());
    }

    private void playNight(GameState state, Function<GamePlayerState, PlayerAction> decisions) {
        int guard = 0;
        while ("NIGHT".equals(state.getPhase())) {
            assertTrue(guard++ < 20, "夜晚应当有限推进");
            GamePlayerState actor = player(state, text(data(state).get("nightActor")));
            act(state, actor, decisions.apply(actor));
        }
    }

    private void untilRole(GameState state, String wanted, Function<GamePlayerState, PlayerAction> decisions) {
        int guard = 0;
        while ("NIGHT".equals(state.getPhase())) {
            assertTrue(guard++ < 20);
            GamePlayerState actor = player(state, text(data(state).get("nightActor")));
            if (wanted.equals(actor.getRole())) return;
            act(state, actor, decisions.apply(actor));
        }
        fail("没有等到夜晚角色 " + wanted);
    }

    private GamePlayerState current(GameState state) {
        return state.getPlayers().stream().filter(p -> Objects.equals(p.getSeatNumber(), state.getCurrentSeat())).findFirst().orElseThrow();
    }

    private void reachInteraction(GameState state) {
        int guard = 0;
        while ("DAY_DISCUSS".equals(state.getPhase())) {
            assertTrue(guard++ < 20);
            act(state, current(state), skip());
        }
        assertEquals("DAY_INTERACTION", state.getPhase());
    }

    private void reachVoting(GameState state) {
        if ("DAY_DISCUSS".equals(state.getPhase())) reachInteraction(state);
        int guard = 0;
        while ("DAY_INTERACTION".equals(state.getPhase())) {
            assertTrue(guard++ < 4);
            timeout(state);
        }
        assertEquals("DAY_VOTE", state.getPhase());
    }

    private void allAbstain(GameState state) {
        for (GamePlayerState voter : new ArrayList<>(alive(state))) {
            if (!"DAY_VOTE".equals(state.getPhase())) break;
            if (!rules.legalActions(state, voter.getPlayerId()).isEmpty()) act(state, voter, skip());
        }
        assertEquals("NIGHT", state.getPhase());
    }

    private void voteOut(GameState state, GamePlayerState target) {
        for (GamePlayerState voter : new ArrayList<>(alive(state))) {
            if (!"DAY_VOTE".equals(state.getPhase())) break;
            if (rules.legalActions(state, voter.getPlayerId()).isEmpty()) continue;
            act(state, voter, voter == target ? skip() : action("VOTE", "", target.getPlayerId()));
        }
    }

    private List<String> nightTargets(GameState state, GamePlayerState actor, String kind) {
        return rules.legalActions(state, actor.getPlayerId()).stream().filter(a -> kind.equals(a.nightAction())).findFirst().orElseThrow().targets();
    }

    private VisibleObservation observation(GameState state, GamePlayerState actor) {
        List<Map<String, Object>> visible = events(state).stream().filter(e -> "PUBLIC".equals(e.get("visibility")) || strings(e.get("visibleTo")).contains(actor.getPlayerId())).toList();
        List<Map<String, Object>> players = state.getPlayers().stream().map(p -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("playerId", p.getPlayerId());
            view.put("displayName", p.getDisplayName());
            view.put("alive", p.isAlive());
            view.put("role", rules.visibleRole(state, p, actor.getPlayerId()));
            return view;
        }).toList();
        return new VisibleObservation("werewolf", text(state.getData().get("archiveId")), state.getPhase(), state.getRoundNumber(), actor.getPlayerId(), state.getPhase(),
                Map.of("playerId", actor.getPlayerId(), "role", actor.getRole()), players, map(state.getData().get("rules")), visible,
                rules.privateData(state, actor.getPlayerId()), rules.legalActions(state, actor.getPlayerId()), Map.of(), Map.of(), List.of());
    }
}
