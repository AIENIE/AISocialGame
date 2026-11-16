package com.aisocialgame.service.ai.v2;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.engine.v2.LegalAction;
import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatMessageDto;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.service.ai.config.AiModelSelection;
import com.aisocialgame.service.safety.AiSafetyContext;
import com.aisocialgame.service.safety.AiSafetyResult;
import com.aisocialgame.service.safety.AiSafetyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WerewolfPublicReferencesTest {
    // The two perspectives follow the visible event order in the six-seat retest.
    private static final String PEACEFUL_NIGHT = "same-peaceful-night";
    private static final String ORIGINAL_ANSWER = "支持我判断的是公开公告本身：E7原文写着‘天亮了，昨夜无人死亡’，E8写着‘第1天，按座位开始讨论’。";
    private static final String ORIGINAL_QUESTION = "P3，你压我时称E7才是无人死亡，但公开记录E6已写明昨夜无人死亡。你没核对就压票，解释一下？";
    private static final String ORIGINAL_VOTE = "编号我再核一次：E6 是无人死亡、E7 是白天开始、E8 是 P1 发言。P6 把 E7、E8、E9 顺延后还说我错位，这一条我不接受；本轮投 P6。";
    private static final String RECENT_NUMBERING_DISPUTE = "E6 和 E7 对不上，3号拿错位编号压人。";
    private final WerewolfRuleSet rules = new WerewolfRuleSet();

    @Test void samePublicEventKeepsItsIdentityAndOldNumberArgumentsAreOmittedFromThePrompt() {
        var wolf = observation("p6", "WEREWOLF", "DAY_INTERACTION", LegalAction.target("ANSWER_PLAYER", "回答", List.of("p1"), 120));
        var witch = observation("p2", "WITCH", "DAY_INTERACTION", LegalAction.target("ASK_PLAYER", "提问", List.of("p3"), 60));
        var cases = List.of(Map.entry(wolf, "E7"), Map.entry(witch, "E6"));
        for (var sample : cases) {
            var original = sample.getKey();
            var refs = WerewolfAiProtocol.references(original);
            assertEquals(PEACEFUL_NIGHT, refs.events().get(sample.getValue()));
            var prompt = WerewolfAiProtocol.encode(AiPromptProjection.project(rules, original), refs);
            var projected = map(prompt.get("observation"));
            var visible = maps(projected.get("events"));
            var announcement = visible.stream().filter(e -> "PEACEFUL_NIGHT".equals(e.get("type"))).findFirst().orElseThrow();
            assertEquals(sample.getValue(), announcement.get("eventId"));
            assertEquals("天亮了，昨夜无人死亡。", announcement.get("message"));
            var oldQuestion = visible.stream().filter(e -> "legacy-question".equals(refs.events().get(e.get("eventId")))).findFirst().orElseThrow();
            assertEquals("", oldQuestion.get("message"));
            assertEquals("P2", oldQuestion.get("actorId"));
            assertEquals("P3", oldQuestion.get("targetId"));
            assertEquals(1, oldQuestion.get("round"));
            var oldVote = visible.stream().filter(e -> "VOTE_REVEALED".equals(e.get("type"))).findFirst().orElseThrow();
            assertEquals("", oldVote.get("message"));
            assertEquals("P6", oldVote.get("targetId"));
            assertEquals(false, map(oldVote.get("data")).get("abstain"));
            var hypothesis = maps(map(projected.get("memory")).get("hypotheses")).getFirst();
            assertEquals("", hypothesis.get("text"));
            assertEquals(List.of(sample.getValue()), hypothesis.get("evidenceEventIds"));
            var recent = maps(map(projected.get("memory")).get("recentDecisions"));
            assertEquals(Map.of("action", "SPEAK", "round", 1, "speech", "", "targetPlayerId", "P3",
                    "evidenceEventIds", List.of(sample.getValue())), recent.getFirst());
            assertEquals("先听3号解释。", recent.getLast().get("speech"));
            assertEquals(RECENT_NUMBERING_DISPUTE, maps(original.memory().get("recentDecisions")).getFirst().get("speech"));
            assertTrue(visible.stream().anyMatch(e -> "1号先请大家给出依据。".equals(e.get("message"))));
            assertTrue(original.events().stream().anyMatch(e -> ORIGINAL_QUESTION.equals(e.get("message"))));
        }
    }

    @Test void oldAnswerQuestionAndVoteFailThePublicTextCheckWhileReadableRepliesKeepTheirEvidence() {
        assertReplay("p6", "WEREWOLF", "DAY_INTERACTION", LegalAction.target("ANSWER_PLAYER", "回答", List.of("p1"), 120),
                "P1", ORIGINAL_ANSWER, "昨夜无人死亡，这条公告只能说明没人出局。", "current-question");
        assertReplay("p2", "WITCH", "DAY_INTERACTION", LegalAction.target("ASK_PLAYER", "提问", List.of("p3"), 60),
                "P3", ORIGINAL_QUESTION, "P3号，天亮时大家都听到无人死亡，你怀疑我的其他依据是什么？", PEACEFUL_NIGHT);
        assertReplay("p5", "SEER", "DAY_VOTE", LegalAction.target("VOTE", "投票", List.of("p6"), 120),
                "P6", ORIGINAL_VOTE, "我投6号，因为他没有回应1号刚才对发言的质疑。", "current-question");
    }

    @Test void speakingLastWordsAndAbstentionUseTheActualDisplayedSeatAndCheckBothTextFields() {
        var generator = generator(mock(AiGrpcClient.class));
        for (String phase : List.of("DAY_DISCUSS", "LAST_WORDS", "DAY_VOTE")) {
            String type = "DAY_VOTE".equals(phase) ? "SKIP" : "SPEAK";
            var source = observation("p2", "WITCH", phase, LegalAction.text(type, "说明", 120));
            // The stored seat and request reference index need not be equal.
            var sparse = new VisibleObservation("werewolf", source.instanceId(), phase, 1, source.actorId(), type,
                    source.self(), List.of(Map.of("playerId", "p2", "seatNumber", 1), Map.of("playerId", "p6", "seatNumber", 9)),
                    source.rules(), source.events(), source.privateFacts(), source.legalActions(), source.persona(), source.memory(), source.knowledge());
            var refs = WerewolfAiProtocol.references(sparse);
            var raw = Map.<String, Object>of("speech", "等P2号补充理由。", "action", Map.of("type", type, "content", "等P2号补充理由。"));
            var decoded = WerewolfAiProtocol.decode(sparse, raw, refs);
            var decision = generator.parse(decoded.output());
            assertEquals("等10号补充理由。", decision.speech());
            assertEquals(decision.speech(), decision.action().getContent());
            assertEquals(List.of(), generator.validate(rules, sparse, decision));
            assertEquals("等P2号补充理由。", raw.get("speech"));
            var badContent = Map.<String, Object>of("speech", "我先听一轮。", "action", Map.of("type", type, "content", "等P20补充。"));
            var badSpeech = Map.<String, Object>of("speech", "依据E6。", "action", Map.of("type", type, "content", "我先听一轮。"));
            var fabricatedReference = Map.<String, Object>of("action", Map.of("type", type, "content", "T7（昨夜无人死亡）是一个事实。"));
            for (var bad : List.of(badContent, badSpeech, fabricatedReference)) {
                assertTrue(generator.validate(rules, sparse, generator.parse(WerewolfAiProtocol.decode(sparse, bad, refs).output()))
                        .contains(WerewolfAiProtocol.PUBLIC_TEXT_REFERENCE));
            }
        }
    }

    @Test void existingCorrectionReconsidersTheEvidenceAndFindsTheOriginalQuestionAgain() throws Exception {
        var wolf = observation("p6", "WEREWOLF", "DAY_INTERACTION", LegalAction.target("ANSWER_PLAYER", "回答", List.of("p1"), 120));
        var client = mock(AiGrpcClient.class);
        var json = new ObjectMapper();
        String questionAlias = WerewolfAiProtocol.references(wolf).events().entrySet().stream()
                .filter(e -> "current-question".equals(e.getValue())).findFirst().orElseThrow().getKey();
        when(client.chatCompletions(anyString(), anyLong(), anyString(), any(AiModelSelection.class), anyList(), anyString(), anyInt()))
                .thenReturn(new AiChatResult(json.writeValueAsString(response("ANSWER_PLAYER", "P1", ORIGINAL_ANSWER, questionAlias)), "fixture", 1, 1))
                .thenAnswer(call -> {
                    List<AiChatMessageDto> messages = call.getArgument(4);
                    assertTrue(messages.getLast().content().contains("按可见发言、公告和实际票型重新判断"));
                    return new AiChatResult(json.writeValueAsString(response("ANSWER_PLAYER", "P1", "昨夜无人死亡。我想听1号对票向的理由。", questionAlias)), "fixture", 1, 1);
                });
        var decision = generator(client).generate(rules, wolf, "reference-regression");
        assertEquals("昨夜无人死亡。我想听1号对票向的理由。", decision.speech());
        assertEquals(List.of("current-question"), decision.evidenceEventIds());
        assertEquals(2, decision.diagnostics().get("calls"));
    }

    private void assertReplay(String actor, String role, String phase, LegalAction action, String target,
                              String original, String readable, String evidence) {
        var observation = observation(actor, role, phase, action);
        var refs = WerewolfAiProtocol.references(observation);
        var alias = refs.events().entrySet().stream().filter(entry -> evidence.equals(entry.getValue())).findFirst().orElseThrow().getKey();
        var generator = generator(mock(AiGrpcClient.class));
        var old = generator.parse(WerewolfAiProtocol.decode(observation, response(action.type(), target, original, alias), refs).output());
        assertTrue(generator.validate(rules, observation, old).contains(WerewolfAiProtocol.PUBLIC_TEXT_REFERENCE));
        var repaired = generator.parse(WerewolfAiProtocol.decode(observation, response(action.type(), target, readable, alias), refs).output());
        assertEquals(List.of(), generator.validate(rules, observation, repaired));
        assertEquals(action.targets().getFirst(), repaired.action().getTargetPlayerId());
        assertEquals(List.of(evidence), repaired.evidenceEventIds());
        assertFalse(WerewolfAiProtocol.containsTextReference(repaired.speech()));
    }

    private static Map<String, Object> response(String type, String target, String content, String reference) {
        return Map.of("speech", content, "action", Map.of("type", type, "content", content, "targetPlayerId", target),
                "evidenceEventIds", List.of(reference));
    }

    private static AiTurnGenerator generator(AiGrpcClient client) {
        var safety = mock(AiSafetyService.class);
        when(safety.review(anyString(), any(AiSafetyContext.class))).thenAnswer(call ->
                new AiSafetyResult("ALLOW", "LOW", "NONE", "", call.getArgument(0), null));
        var memory = new AiMemoryServiceV2(mock(com.aisocialgame.repository.AiPersonaMemoryRepository.class),
                new com.aisocialgame.repository.PersonaRepository());
        return new AiTurnGenerator(client, new AppProperties(), memory, safety);
    }

    private static VisibleObservation observation(String actor, String role, String phase, LegalAction action) {
        var players = java.util.stream.IntStream.range(0, 6).mapToObj(i -> Map.<String, Object>of(
                "playerId", "p" + (i + 1), "seatNumber", i, "alive", true)).toList();
        var events = new ArrayList<Map<String, Object>>();
        events.add(event("started", "GAME_STARTED", "PUBLIC", "NIGHT", "", "", "狼人杀开始。", Map.of()));
        events.add(event("role", "ROLE_ASSIGNED", "PRIVATE", "NIGHT", actor, "", "你的身份已发放。", Map.of("role", role)));
        events.add(event("night", "NIGHT_STARTED", "PUBLIC", "NIGHT", "", "", "第1夜开始。", Map.of()));
        events.add(event("start", "start", "PUBLIC", "NIGHT", "", "", "开始对局。", Map.of()));
        events.add(event("skill", "WITCH".equals(role) ? "WITCH_ACTION" : "SEER".equals(role) ? "SEER_CHECK" : "WOLF_COUNCIL",
                "PRIVATE", "NIGHT", actor, "p1", "你的夜间行动已记录。", Map.of()));
        if ("WEREWOLF".equals(role)) events.add(event("wolf-note", "WOLF_COUNCIL", "PRIVATE", "NIGHT", "p3", "p1", "队友已建议刀口。", Map.of()));
        events.add(event(PEACEFUL_NIGHT, "PEACEFUL_NIGHT", "PUBLIC", "DAY_DISCUSS", "", "", "天亮了，昨夜无人死亡。", Map.of()));
        events.add(event("day", "DAY_STARTED", "PUBLIC", "DAY_DISCUSS", "", "", "第1天，按座位开始讨论。", Map.of()));
        events.add(event("opening", "SPEECH", "PUBLIC", "DAY_DISCUSS", "p1", "", "1号先请大家给出依据。", Map.of("content", "先请大家给出依据。")));
        events.add(event("current-question", "ASK_PLAYER", "PUBLIC", "DAY_INTERACTION", "p1", "p6", "6号，你的判断有什么依据？", Map.of("content", "你的判断有什么依据？")));
        // Legacy replays can carry the exact wording that the other perspective misread.
        if (!"ANSWER_PLAYER".equals(action.type())) {
            events.add(event("legacy-answer", "ANSWER_PLAYER", "PUBLIC", "DAY_INTERACTION", "p6", "p1", ORIGINAL_ANSWER, Map.of("content", ORIGINAL_ANSWER)));
        }
        events.add(event("legacy-question", "ASK_PLAYER", "PUBLIC", "DAY_INTERACTION", "p2", "p3", ORIGINAL_QUESTION, Map.of("content", ORIGINAL_QUESTION)));
        if ("ANSWER_PLAYER".equals(action.type())) {
            var pending = events.remove(events.size() - 2);
            events.add(pending);
        }
        events.add(event("legacy-vote", "VOTE_REVEALED", "PUBLIC", "DAY_VOTE", "p5", "p6", ORIGINAL_VOTE, Map.of("abstain", false)));
        return new VisibleObservation("werewolf", "reference-replay", phase, 1, actor, action.type(),
                Map.of("playerId", actor, "seatNumber", Integer.parseInt(actor.substring(1)) - 1, "role", role),
                players, Map.of(), List.copyOf(events), Map.of(), List.of(action), Map.of(),
                Map.of("hypotheses", List.of(Map.of("text", "E6 和 E7 对不上，所以要压票。", "evidenceEventIds", List.of(PEACEFUL_NIGHT))),
                        "recentDecisions", List.of(
                                Map.of("action", "SPEAK", "round", 1, "speech", RECENT_NUMBERING_DISPUTE,
                                        "targetPlayerId", "p3", "evidenceEventIds", List.of(PEACEFUL_NIGHT)),
                                Map.of("action", "SPEAK", "round", 1, "speech", "先听3号解释。"))), List.of());
    }

    private static Map<String, Object> event(String id, String type, String visibility, String phase,
                                             String actor, String target, String message, Map<String, Object> data) {
        return Map.of("eventId", id, "type", type, "visibility", visibility, "phase", phase,
                "actorId", actor, "targetId", target, "message", message, "round", 1, "data", data);
    }
}
