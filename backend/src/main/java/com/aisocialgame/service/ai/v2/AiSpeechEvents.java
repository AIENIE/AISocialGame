package com.aisocialgame.service.ai.v2;

import java.util.Map;
import java.util.Set;

/** Existing event names are preserved; this is an interpretation of actor-authored speech only. */
public final class AiSpeechEvents {
    private AiSpeechEvents() {}
    private static final Set<String> TYPES = Set.of("SPEAK", "SPEECH", "LAST_WORDS", "ASK_PLAYER", "ANSWER_PLAYER", "DISCUSS",
            "TURTLE_SOUP_DISCUSSION", "TURTLE_SOUP_QUESTION_PENDING", "TURTLE_SOUP_SOLUTION_PENDING");

    public static boolean isSpeech(Map<String, Object> event) {
        return TYPES.contains(event.get("type") instanceof String type ? type : "");
    }
}
