package com.aisocialgame.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Public presentation fields shared by live state and historical log pages. */
public final class PublicLogMetadata {
    private PublicLogMetadata() {}

    public static Map<String, Object> project(String type, Map<String, Object> data, String eventId, Long publicSeq) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (eventId != null && !eventId.isBlank()) result.put("eventId", eventId);
        if (publicSeq != null) result.put("publicSeq", publicSeq);
        if (data == null) return result;
        copyString(data, result, "content", "content");
        if ("TURTLE_SOUP_QUESTION".equals(type)) copyString(data, result, "answer", "content");
        if ("TURTLE_SOUP_HINT".equals(type)) copyString(data, result, "hint", "content");
        if ("TURTLE_SOUP_START".equals(type)) copyString(data, result, "surface", "content");
        if (data.get("presentation") instanceof Map<?, ?> presentation) {
            Map<String, Object> cue = new LinkedHashMap<>();
            for (String key : new String[]{"emotion", "gesture", "intensity"}) {
                if (presentation.containsKey(key)) cue.put(key, presentation.get(key));
            }
            result.put("presentation", cue);
        }
        copyString(data, result, "questionEventId", "correlationId");
        copyString(data, result, "requestId", "correlationId");
        if ("TURTLE_SOUP_QUESTION".equals(type)) copyString(data, result, "id", "correlationId");
        if ("ASK_PLAYER".equals(type) && eventId != null) result.put("correlationId", eventId);
        if ("VOTE_REVEAL".equals(type)) {
            Map<String, Object> ballot = new LinkedHashMap<>();
            ballot.put("votes", stringValues(data.get("votes")));
            Map<String, Integer> tally = new LinkedHashMap<>();
            if (data.get("tally") instanceof Map<?, ?> values) values.forEach((key, value) -> {
                if (key instanceof String id && value instanceof Number count) tally.put(id, count.intValue());
            });
            ballot.put("tally", tally);
            result.put("voteResult", ballot);
        }
        return result;
    }

    private static void copyString(Map<String, Object> source, Map<String, Object> target, String from, String to) {
        if (source.get(from) instanceof String value && !value.isBlank()) target.put(to, value);
    }

    private static Map<String, String> stringValues(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> {
            if (key instanceof String id && item instanceof String text) result.put(id, text);
        });
        return result;
    }
}
