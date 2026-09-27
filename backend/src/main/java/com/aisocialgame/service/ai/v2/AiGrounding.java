package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.RuleSupport;
import java.util.*;
import java.util.regex.Pattern;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Deterministic checks over the detached visible input, not a semantic truth oracle. */
public final class AiGrounding {
    private AiGrounding() {}
    private static final Pattern SELF_CLAIM = Pattern.compile("(?:我(?:刚才|刚刚|之前|前面|上轮|上一轮|此前|曾经|已经).{0,4}(?:说|提)|我不是没说|不是没说)");
    private static final Pattern QUOTED_HISTORY = Pattern.compile("(?:我(?:刚才|刚刚|之前|前面|上轮|上一轮|此前|曾经|已经).{0,4}(?:说|提)[^“「\\\"]{0,8})[“「\\\"]([^”」\\\"]{2,90})[”」\\\"]");

    public static String content(Map<String, Object> event) {
        String value = text(map(event.get("data")).get("content"));
        return value.isBlank() ? text(event.get("message")) : value;
    }
    public static List<Map<String, Object>> speechEvents(VisibleObservation o) {
        return o.events().stream().filter(AiSpeechEvents::isSpeech).toList();
    }
    /** These are excerpts, not a model-generated summary and never new authority. */
    public static Map<String, Object> context(VisibleObservation o) {
        List<Map<String, Object>> current = o.events().stream()
                .filter(e -> number(e.get("round"), -1) == o.round()).map(AiGrounding::excerpt).toList();
        List<Map<String, Object>> own = speechEvents(o).stream().filter(e -> o.actorId().equals(e.get("actorId")))
                .map(AiGrounding::excerpt).toList();
        return Map.of("currentRound", tail(current, 12), "ownStatements", tail(own, 8),
                "usedDescriptions", tail(strings(o.memory().get("usedDescriptions")), 12),
                "commitmentsToVerify", tail(AiCommitments.stored(o.memory().get("commitments"), number(o.memory().get("formatVersion"), 1), o.instanceId(), o.actorId()), 16),
                "note", "原文摘录仅供核对；记忆中的承诺和猜测不是事实。未提供的历史不得补写。回应先对照自己的原话。");
    }
    private static Map<String, Object> excerpt(Map<String, Object> e) {
        String message = content(e);
        return Map.of("eventId", text(e.get("eventId")), "actorId", text(e.get("actorId")),
                "round", number(e.get("round"), 0), "type", text(e.get("type")),
                "content", AiMemoryEntries.cut(message, 240));
    }
    private static <T> List<T> tail(List<T> values, int limit) { return values.subList(Math.max(0, values.size() - limit), values.size()); }

    /** Runs on decoded JSON before action conversion, so repair can explain grounding failures too. */
    public static List<String> check(VisibleObservation o, Map<String, Object> output) {
        if (RuleSupport.HOST.equals(o.actorId())) return List.of();
        String speech = text(output.get("speech"));
        if (speech.isBlank()) speech = text(map(output.get("action")).get("content"));
        if (speech.isBlank()) return List.of();
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> visibleSpeech = speechEvents(o);
        List<String> own = visibleSpeech.stream().filter(e -> o.actorId().equals(e.get("actorId"))).map(AiGrounding::content).toList();
        if (SELF_CLAIM.matcher(speech).find()) {
            Set<String> ids = new HashSet<>(strings(output.get("evidenceEventIds")));
            boolean ownEvidence = visibleSpeech.stream().anyMatch(e -> o.actorId().equals(e.get("actorId")) && ids.contains(text(e.get("eventId"))));
            if (!ownEvidence) errors.add("UNSUPPORTED_SELF_HISTORY");
            var quote = QUOTED_HISTORY.matcher(speech);
            while (quote.find()) {
                String claimed = normalize(quote.group(1));
                if (visibleSpeech.stream().filter(e -> o.actorId().equals(e.get("actorId")) && ids.contains(text(e.get("eventId"))))
                        .map(AiGrounding::content).noneMatch(s -> normalize(s).contains(claimed))) errors.add("CONTRADICTED_SELF_QUOTE");
            }
            // Narrow regression for denying omission of a topic. Do not infer arbitrary paraphrase truth.
            if (speech.contains("不是没说用途") && own.stream().noneMatch(s -> s.matches(".*(?:用途|用来|用于|拿来|提神|解渴).*")))
                errors.add("UNSUPPORTED_SELF_HISTORY");
        }
        return errors.stream().distinct().toList();
    }
    public static String normalize(String value) { return value.replaceAll("[\\s\\p{Punct}，。！？：；、]", ""); }
}
