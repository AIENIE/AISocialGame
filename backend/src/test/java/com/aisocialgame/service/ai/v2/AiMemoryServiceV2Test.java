package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.LegalAction;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiMemoryServiceV2Test {
    private final AiPersonaMemoryRepository repository = mock(AiPersonaMemoryRepository.class);
    private final PersonaRepository personas = mock(PersonaRepository.class);
    private final AiMemoryServiceV2 service = new AiMemoryServiceV2(repository, personas);

    AiMemoryServiceV2Test() {
        when(repository.findByPersonaIdAndGameIdAndRoleKey(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void newGameWithSamePersonaResetsWordsRolesAndRelationshipsButKeepsReviewedGeneralExperience() {
        GameState previous = state("previous");
        previous.getData().put("aiMemoriesV2", Map.of("p0", Map.of("hypotheses", List.of("本局秘密词是咖啡"), "beliefs", Map.of("p1", Map.of("hypothesis", "旧局卧底")),
                "relationships", Map.of("p1", Map.of("trust", -3)), "emotion", Map.of("name", "disappointed", "intensity", 3, "round", 1))));
        AiPersonaMemory approved = new AiPersonaMemory("persona", "undercover", "GENERAL_V2");
        approved.setReviewStatus("APPROVED"); approved.setApprovedSummary("改变投票立场时解释新出现的公开依据。");
        approved.setMemorySummary("旧版未经审核的词语和玩家身份"); approved.setMistakeNotes("私人旧局记录");
        when(repository.findByPersonaIdAndGameIdAndRoleKey("persona", "undercover", "GENERAL_V2")).thenReturn(Optional.of(approved));
        assertTrue(service.snapshot(previous, "p0").containsKey("hypotheses"));
        Map<String, Object> fresh = service.snapshot(state("fresh"), "p0");
        assertEquals(Map.of("approvedExperience", "改变投票立场时解释新出现的公开依据。"), fresh);
        assertFalse(fresh.toString().contains("咖啡")); assertFalse(fresh.toString().contains("旧版")); assertFalse(fresh.toString().contains("p1"));
        verify(repository, never()).findByPersonaIdAndGameIdAndRoleKey(anyString(), anyString(), eq("UNDERCOVER"));
    }

    @Test void pendingOrRejectedSummariesNeverEnterTheGameObservation() {
        for (String status : List.of("PENDING", "REJECTED")) {
            AiPersonaMemory memory = new AiPersonaMemory("persona", "undercover", "GENERAL_V2");
            memory.setApprovedSummary("尚未审核的秘密记录"); memory.setMemorySummary("旧版隐藏词语"); memory.setReviewStatus(status);
            when(repository.findByPersonaIdAndGameIdAndRoleKey("persona", "undercover", "GENERAL_V2")).thenReturn(Optional.of(memory));
            assertTrue(service.snapshot(state("fresh"), "p0").isEmpty());
        }
    }

    @Test void committedFallbackRecordsTheActualUsedCueWithoutModelMemoryUpdates() {
        GameState state = state("fallback-memory");
        VisibleObservation base = observation(state);
        VisibleObservation o = new VisibleObservation("undercover", base.instanceId(), "DESCRIPTION", 1, "p0", "SPEAK", base.self(), base.players(),
                base.rules(), base.events(), Map.of("wordKnowledge", Map.of("attributes", List.of("香气浓郁", "经常用于提神"))),
                base.legalActions(), base.persona(), base.memory(), base.knowledge());
        service.commit(state, "p0", AiTurnDecision.fallback(action("SPEAK", "我想到的特点是香气浓郁。", null)), o);
        assertEquals(List.of("香气浓郁"), service.snapshot(state, "p0").get("usedDescriptions"));
    }

    @Test void emotionRecoversAcrossRoundsWithoutRepeatedReadsChangingState() {
        GameState state = state("emotion"); state.setRoundNumber(3);
        state.getData().put("aiMemoriesV2", Map.of("p0", Map.of("emotion", Map.of("name", "tense", "intensity", 3, "round", 1))));
        Map<String, Object> first = service.snapshot(state, "p0");
        assertEquals(1, map(first.get("emotion")).get("intensity"));
        assertEquals(first, service.snapshot(state, "p0"));
        assertEquals(3, map(map(map(state.getData().get("aiMemoriesV2")).get("p0")).get("emotion")).get("intensity"));
        state.setRoundNumber(8);
        assertEquals(0, map(service.snapshot(state, "p0").get("emotion")).get("intensity"));
    }

    @Test void updatesNeedKnownPlayersPubliclyAvailableEvidenceAndAllowedEmotionNames() {
        Map<String, Object> updates = Map.of("secretRoles", Map.of("p1", "UNDERCOVER"),
                "beliefs", List.of(Map.of("playerId", "ghost", "evidenceEventIds", List.of("hidden-event"))),
                "relationships", List.of(Map.of("playerId", "p1", "eventId", "hidden-event", "trustDelta", 1)),
                "emotion", Map.of("name", "rage", "eventId", "e1"));
        List<String> errors = service.validate(observation(state("validating")), updates);
        assertTrue(errors.containsAll(List.of("UNSUPPORTED_MEMORY_UPDATE", "UNKNOWN_BELIEF_PLAYER", "INVISIBLE_MEMORY_EVIDENCE", "UNGROUNDED_RELATIONSHIP", "UNGROUNDED_EMOTION")));
    }

    @Test void severalCommittedActionsInTheSameRoundDoNotAccelerateEmotionalRecovery() {
        GameState state = state("emotion-commits"); state.setRoundNumber(2);
        state.getData().put("aiMemoriesV2", Map.of("p0", Map.of("emotion", Map.of("name", "tense", "intensity", 3, "round", 1))));
        AiTurnDecision decision = new AiTurnDecision(action("SPEAK", "我还在核对这条线索。", null), "我还在核对这条线索。", Map.of(), List.of(), Map.of(), false, Map.of());
        for (int i = 0; i < 3; i++) {
            service.commit(state, "p0", decision, observation(state));
            assertEquals(2, map(service.snapshot(state, "p0").get("emotion")).get("intensity"));
        }
        state.setRoundNumber(3);
        assertEquals(1, map(service.snapshot(state, "p0").get("emotion")).get("intensity"));
    }

    @Test void reducerBoundsBeliefsRelationshipsEmotionAndRecentDecisionsInsideTheCurrentGameOnly() {
        GameState state = state("bounded");
        for (int index = 0; index < 20; index++) {
            Map<String, Object> updates = Map.of(
                    "beliefs", List.of(Map.of("playerId", "p1", "hypothesis", "另一名玩家的描述可能来自不同场景", "confidence", 10.0, "evidenceEventIds", List.of("e1"))),
                    "relationships", List.of(Map.of("playerId", "p1", "eventId", "e1", "trustDelta", 999)),
                    "emotion", Map.of("name", "curious", "intensity", 99, "eventId", "e1"),
                    "usedDescriptions", List.of("描述维度" + index), "hypotheses", List.of("本局假说" + index));
            AiTurnDecision decision = new AiTurnDecision(action("SPEAK", "我补充一条新线索。", null), "我补充一条新线索。", Map.of(), List.of("e1"), updates, false, Map.of());
            service.commit(state, "p0", decision, observation(state));
        }
        Map<String, Object> memory = service.snapshot(state, "p0");
        assertEquals(1.0, map(map(memory.get("beliefs")).get("p1")).get("confidence"));
        assertEquals(3, map(map(memory.get("relationships")).get("p1")).get("trust"));
        assertEquals(3, map(memory.get("emotion")).get("intensity"));
        assertEquals(16, strings(memory.get("usedDescriptions")).size());
        assertEquals(16, maps(memory.get("hypotheses")).size());
        assertEquals("本局假说19", maps(memory.get("hypotheses")).getLast().get("text"));
        assertEquals(AiMemoryEntries.FORMAT_VERSION, memory.get("formatVersion"));
        assertEquals(8, maps(memory.get("recentDecisions")).size());
        verify(repository, never()).save(any());
    }

    @Test void finishingCountsSharedPersonaOnceAndDoesNotPersistPrivateGameContent() {
        GameState state = state("finished");
        state.getData().put("aiMemoriesV2", Map.of("p0", Map.of("hypotheses", List.of("隐藏词语"), "relationships", Map.of("p1", Map.of("trust", -3)))));
        player(state, "p1").setAi(true); player(state, "p1").setPersonaId("persona");
        service.finishGame(state); service.finishGame(state);
        verify(repository).incrementGamesPlayed("persona", "undercover");
        verify(repository, never()).save(any());
    }

    @Test void hostCannotAcquirePlayerMemoryThroughTheReducer() {
        GameState state = state("host");
        service.commit(state, HOST, new AiTurnDecision(action("HOST_VERDICT", "", null), "", Map.of(), List.of(), Map.of("hypotheses", List.of("不应持久化的答案")), false, Map.of()), observation(state));
        assertFalse(state.getData().containsKey("aiMemoriesV2"));
        verifyNoInteractions(repository);
    }

    @Test void multiRoundMemoryRetainsRevisedJudgmentAndUnverifiedCommitmentWithoutInventingFulfillment() {
        GameState state = state("multi-round");
        state.getData().put("aiMemoriesV2", Map.of("p0", Map.of("commitments", List.of("我会再看一轮"))));
        Map<String, Object> old = map(state.getData().get("aiMemoriesV2"));
        var read = service.snapshot(state, "p0");
        assertEquals("LEGACY_UNVERIFIED", maps(read.get("commitments")).getFirst().get("source"));
        assertEquals(old, state.getData().get("aiMemoriesV2"));
        assertFalse(map(old.get("p0")).containsKey("formatVersion"));
        for (int round = 1; round <= 2; round++) {
            state.setRoundNumber(round);
            VisibleObservation base = observation(state);
            List<Map<String, Object>> visibleEvents = new ArrayList<>(List.of(
                    Map.of("eventId", "e1", "actorId", "p1", "type", "SPEAK", "round", 1, "message", "我主要在户外用它。")));
            if (round == 2) visibleEvents.add(Map.of("eventId", "e2", "actorId", "p1", "type", "ANSWER_PLAYER", "round", 2,
                    "message", "我说的户外是自家阳台，和你说的用法一样。"));
            VisibleObservation current = new VisibleObservation(base.gameId(), base.instanceId(), base.phase(), round, base.actorId(), base.turnKind(),
                    base.self(), base.players(), base.rules(), visibleEvents, base.privateFacts(), base.legalActions(), base.persona(), service.snapshot(state, "p0"), base.knowledge());
            Map<String, Object> update = Map.of("hypotheses", List.of(Map.of("text", "对方可能理解了不同场景", "confidence", round == 1 ? .8 : .2,
                    "evidenceEventIds", List.of(round == 1 ? "e1" : "e2"))));
            assertTrue(service.validate(current, update).isEmpty());
            String speech = round == 1 ? "我暂时怀疑这个方向。" : "新的解释让我降低了怀疑。";
            service.commit(state, "p0", new AiTurnDecision(action("SPEAK", speech, null),
                    speech, Map.of(), List.of(round == 1 ? "e1" : "e2"), update, false, Map.of()), current);
            assertEquals("我会再看一轮", maps(AiGrounding.context(current).get("commitmentsToVerify")).getFirst().get("text"));
        }
        var memory = service.snapshot(state, "p0");
        var hypothesis = maps(memory.get("hypotheses")).getFirst();
        assertEquals(1, maps(memory.get("hypotheses")).size());
        assertEquals(.2, hypothesis.get("confidence")); assertEquals(2, hypothesis.get("round"));
        assertEquals("MODEL_PROPOSAL", hypothesis.get("source"));
        assertEquals(List.of("e1", "e2"), hypothesis.get("evidenceEventIds"));
        var commitment = maps(memory.get("commitments")).getFirst();
        assertEquals("我会再看一轮", commitment.get("text")); assertNull(commitment.get("round"));
        assertEquals("UNVERIFIED", commitment.get("status"));
        assertEquals(2, maps(memory.get("recentDecisions")).size());
    }

    @Test void invalidStructuredMemoryIsRejectedBeforeAnyReducerMutation() {
        GameState state = state("invalid");
        var decision = new AiTurnDecision(action("SPEAK", "合法动作", null), "合法动作", Map.of(), List.of(),
                Map.of("hypotheses", List.of(Map.of("text", "猜想", "eventId", "hidden"))), false, Map.of());
        assertTrue(service.validate(observation(state), decision.memoryUpdates()).contains("INVISIBLE_MEMORY_EVIDENCE"));
        assertThrows(IllegalArgumentException.class, () -> service.commit(state, "p0", decision, observation(state)));
        assertFalse(state.getData().containsKey("aiMemoriesV2"));
    }

    private GameState state(String id) {
        GameState state = new GameState(id, "undercover", "DESCRIPTION");
        GamePlayerState p0 = new GamePlayerState("p0", "玩家零", 0, true, "persona", ""); p0.setRole("CIVILIAN"); p0.setWord("咖啡");
        GamePlayerState p1 = new GamePlayerState("p1", "玩家一", 1, false, null, ""); p1.setRole("UNDERCOVER"); p1.setWord("茶水");
        state.setPlayers(new ArrayList<>(List.of(p0, p1))); return state;
    }
    private VisibleObservation observation(GameState state) {
        return new VisibleObservation("undercover", state.getRoomId(), state.getPhase(), state.getRoundNumber(), "p0", "SPEAK", Map.of("playerId", "p0"),
                List.of(Map.of("playerId", "p0"), Map.of("playerId", "p1")), Map.of(), List.of(Map.of("eventId", "e1", "actorId", "p1", "message", "公开线索")), Map.of(),
                List.of(LegalAction.text("SPEAK", "描述", 90)), Map.of(), Map.of(), List.of());
    }
}
