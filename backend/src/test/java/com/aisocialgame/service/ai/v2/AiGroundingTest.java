package com.aisocialgame.service.ai.v2;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiGroundingTest {
    private VisibleObservation observation(String game) {
        return new VisibleObservation(game, "i", "RESPONSE", 2, "p0", "ANSWER_PLAYER", Map.of(), List.of(), Map.of(),
                List.of(Map.of("eventId", "own", "actorId", "p0", "type", "SPEAK", "round", 1, "message", "闻起来很香。"),
                        Map.of("eventId", "other", "actorId", "p1", "type", "ASK_PLAYER", "round", 2, "message", "有什么用途？")),
                Map.of(), List.of(), Map.of(), Map.of("commitments", List.of("我会再看一轮")), List.of());
    }
    @Test void exactQuoteMustMatchOwnStatementEvenWhenEvidenceIdExists() {
        assertTrue(AiGrounding.check(observation("undercover"), Map.of("speech", "我刚才说过“闻起来很香”。", "evidenceEventIds", List.of("own"))).isEmpty());
        assertTrue(AiGrounding.check(observation("undercover"), Map.of("speech", "我刚才说过“用于解渴”。", "evidenceEventIds", List.of("own"))).contains("CONTRADICTED_SELF_QUOTE"));
        assertTrue(AiGrounding.check(observation("undercover"), Map.of("speech", "我不是没说用途。", "evidenceEventIds", List.of("own"))).contains("UNSUPPORTED_SELF_HISTORY"));
    }
    @Test void excerptKeepsSourceAndDoesNotPromoteCommitmentsIntoFacts() {
        Map<String, Object> context = AiGrounding.context(observation("undercover"));
        assertTrue(context.get("ownStatements").toString().contains("own"));
        assertFalse(context.get("currentRound").toString().contains("闻起来"));
        assertTrue(context.containsKey("commitmentsToVerify"));
        assertFalse(context.get("ownStatements").toString().contains("我会再看一轮"));
    }
    @Test void newBluffIsAllowedButInventedHistoryIsRejectedInEveryGame() {
        for (String game : List.of("undercover", "werewolf", "turtle_soup")) {
            assertTrue(AiGrounding.check(observation(game), Map.of("speech", "我是预言家。")).isEmpty());
            assertTrue(AiGrounding.check(observation(game), Map.of("speech", "我刚才说过“我是预言家”。")).contains("UNSUPPORTED_SELF_HISTORY"));
            assertTrue(AiGrounding.check(observation(game), Map.of("speech", "我刚才说过“我是预言家”。", "evidenceEventIds", List.of("own"))).contains("CONTRADICTED_SELF_QUOTE"));
        }
    }
}
