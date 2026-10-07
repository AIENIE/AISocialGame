package com.aisocialgame;

import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.PublicLogMetadata;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PublicLogMetadataTest {
    @Test void onlyPresentationFieldsCrossThePublicBoundary() {
        var projected = PublicLogMetadata.project("ANSWER_PLAYER", Map.of("content", "我的回答", "questionEventId", "question-1",
                "role", "WEREWOLF", "word", "secret", "reasoning", "secret reasoning", "presentation", Map.of("gesture", "nod", "reason", "private")), "event-2", 4L);
        assertEquals(Map.of("content", "我的回答", "correlationId", "question-1", "eventId", "event-2", "publicSeq", 4L,
                "presentation", Map.of("gesture", "nod")), projected);
    }

    @Test void privateActionsNeverBecomeLivePublicMessages() {
        var state = new GameState("room", "werewolf", "NIGHT");
        RuleSupport.event(state, "SEER_CHECK", "seer", "wolf", "secret result", Map.of("content", "secret result"), "PRIVATE", List.of("seer"));
        assertTrue(state.getLogs().isEmpty());
        RuleSupport.event(state, "SPEECH", "seer", null, "seer's speech", Map.of("content", "a public claim", "result", "secret"));
        assertEquals(1, state.getLogs().size());
        var log = state.getLogs().getFirst();
        assertEquals("a public claim", log.getMetadata().get("content"));
        assertFalse(log.getMetadata().containsKey("result"));
    }

    @Test void questionsAndAnswersShareAnExplicitCorrelationWithoutParsingText() {
        assertEquals("q", PublicLogMetadata.project("ASK_PLAYER", Map.of("content", "question"), "q", 1L).get("correlationId"));
        var pending = PublicLogMetadata.project("TURTLE_SOUP_QUESTION_PENDING", Map.of("requestId", "request-1", "content", "question"), "q", 1L);
        var answer = PublicLogMetadata.project("TURTLE_SOUP_QUESTION", Map.of("id", "request-1", "question", "question", "answer", "yes", "normalizedProposition", "internal"), "a", 2L);
        assertEquals(pending.get("correlationId"), answer.get("correlationId"));
        assertEquals("yes", answer.get("content"));
        assertFalse(answer.containsKey("normalizedProposition"));
    }

    @Test void ballotsAreExposedOnlyByTheRevealEvent() {
        var data = Map.<String, Object>of("votes", Map.of("p1", "p2"), "tally", Map.of("p2", 1));
        assertFalse(PublicLogMetadata.project("VOTE_CAST", data, "v", null).containsKey("voteResult"));
        assertEquals(data, PublicLogMetadata.project("VOTE_REVEAL", data, "v", 3L).get("voteResult"));
    }
}
