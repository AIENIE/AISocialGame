package com.aisocialgame.service.ai.v2;

import java.util.*;

/** Local memory proposals, never authoritative facts or automatically fulfilled promises. */
public final class AiMemoryEntries {
    public static final int FORMAT_VERSION = 4;
    public static final List<String> KEYS = List.of("hypotheses", "commitments");
    private static final Set<String> INPUT_FIELDS = Set.of("text", "hypothesis", "content", "eventId", "evidenceEventIds", "confidence");
    private static final Set<String> SOURCES = Set.of("MODEL_PROPOSAL", "LEGACY_UNVERIFIED");
    private AiMemoryEntries() {}

    public record Parsed(List<Map<String, Object>> entries, List<String> errors) {}

    public static Parsed proposals(Object value, boolean hypothesis, Set<String> visibleIds, int round) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Set<String> errors = new LinkedHashSet<>();
        if (value == null) return new Parsed(List.of(), List.of());
        if (!(value instanceof List<?> list)) return new Parsed(List.of(), List.of("INVALID_MEMORY_FIELDS"));
        for (Object item : list) {
            try {
                Map<String, Object> entry = decode(item, hypothesis, false, false);
                if (!visibleIds.containsAll(ids(entry))) throw invalid("INVISIBLE_MEMORY_EVIDENCE");
                entry.put("round", round);
                entry.put("source", "MODEL_PROPOSAL");
                entry.put("evidenceEventIds", tail(ids(entry), 6));
                if (entries.size() < 8) entries.add(entry);
            } catch (InvalidEntry error) { errors.add(error.getMessage()); }
        }
        return new Parsed(List.copyOf(entries), List.copyOf(errors));
    }

    /** No mutation, inferred provenance or parsing of old Map.toString() fragments. */
    public static List<Map<String, Object>> stored(Object value, boolean hypothesis, boolean currentFormat) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            try { result.add(decode(item, hypothesis, true, currentFormat)); }
            catch (InvalidEntry ignored) { /* Invalid persisted shapes cannot become model authority. */ }
        }
        return merge(List.of(), result);
    }

    private static Map<String, Object> decode(Object item, boolean hypothesis, boolean stored, boolean currentFormat) {
        Map<String, Object> result = new LinkedHashMap<>();
        String text;
        List<String> evidence = List.of();
        if (item instanceof String string) {
            text = nonblank(string);
        } else if (item instanceof Map<?, ?> map) {
            Set<String> allowed = new HashSet<>(INPUT_FIELDS);
            if (stored) allowed.addAll(Set.of("round", "source"));
            if (!allowed.containsAll(map.keySet())) throw invalid("INVALID_MEMORY_FIELDS");
            text = null;
            for (String alias : List.of("text", "hypothesis", "content")) {
                if (!map.containsKey(alias)) continue;
                String candidate = nonblank(map.get(alias));
                if (text != null && !text.equals(candidate)) throw invalid("CONFLICTING_MEMORY_FIELDS");
                text = candidate;
            }
            if (text == null) throw invalid("INVALID_MEMORY_FIELDS");
            if (map.containsKey("evidenceEventIds")) {
                if (!(map.get("evidenceEventIds") instanceof List<?> values)) throw invalid("INVALID_MEMORY_FIELDS");
                LinkedHashSet<String> unique = new LinkedHashSet<>();
                for (Object id : values) unique.add(nonblank(id));
                evidence = new ArrayList<>(unique);
            }
            if (map.containsKey("eventId")) {
                String id = nonblank(map.get("eventId"));
                if (map.containsKey("evidenceEventIds") && !evidence.contains(id)) throw invalid("CONFLICTING_MEMORY_FIELDS");
                if (!map.containsKey("evidenceEventIds")) evidence = List.of(id);
            }
            if (map.containsKey("confidence")) {
                if (!hypothesis || !(map.get("confidence") instanceof Number number)
                        || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0 || number.doubleValue() > 1)
                    throw invalid("INVALID_MEMORY_CONFIDENCE");
                result.put("confidence", ((Number) map.get("confidence")).doubleValue());
            }
            if (stored && currentFormat && map.get("source") instanceof String source && SOURCES.contains(source) && validRound(map.get("round"))) {
                result.put("source", map.get("source"));
                result.put("round", map.get("round"));
            }
        } else throw invalid("INVALID_MEMORY_FIELDS");
        result.put("text", cut(text, 160));
        result.put("evidenceEventIds", stored ? tail(evidence, 6) : evidence);
        if (stored && !result.containsKey("source")) {
            result.put("source", "LEGACY_UNVERIFIED");
            result.put("round", null);
        }
        return result;
    }

    private static boolean validRound(Object value) {
        return value == null || value instanceof Number number && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= 0 && number.doubleValue() <= Integer.MAX_VALUE
                && number.doubleValue() == Math.rint(number.doubleValue());
    }

    /** Same text is one proposal; newest metadata wins, evidence remains bounded and ordered. */
    public static List<Map<String, Object>> merge(List<Map<String, Object>> previous, List<Map<String, Object>> additions) {
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        List<Map<String, Object>> all = new ArrayList<>(previous); all.addAll(additions);
        for (Map<String, Object> entry : all) {
            String key = (String) entry.get("text");
            Map<String, Object> old = merged.remove(key);
            Map<String, Object> next = old == null ? new LinkedHashMap<>() : new LinkedHashMap<>(old);
            LinkedHashSet<String> evidence = new LinkedHashSet<>(old == null ? List.of() : ids(old));
            evidence.addAll(ids(entry));
            next.putAll(entry);
            next.put("evidenceEventIds", tail(new ArrayList<>(evidence), 6));
            merged.put(key, next);
        }
        return tail(new ArrayList<>(merged.values()), 16);
    }

    public static Set<String> evidenceIds(Map<String, Object> memory) {
        Set<String> result = new LinkedHashSet<>();
        boolean current = memory.get("formatVersion") instanceof Number n && n.intValue() >= 2;
        for (Map<String, Object> entry : stored(memory.get("hypotheses"), true, current)) result.addAll(ids(entry));
        for (Map<String, Object> entry : AiCommitments.stored(memory.get("commitments"), current ? ((Number) memory.get("formatVersion")).intValue() : 1, "", "")) {
            result.addAll(com.aisocialgame.engine.v2.RuleSupport.strings(entry.get("evidenceEventIds")));
            result.addAll(com.aisocialgame.engine.v2.RuleSupport.strings(com.aisocialgame.engine.v2.RuleSupport.map(entry.get("resolution")).get("evidenceEventIds")));
        }
        for (var reflection : com.aisocialgame.engine.v2.RuleSupport.maps(memory.get("roundReflections")))
            result.addAll(com.aisocialgame.engine.v2.RuleSupport.strings(reflection.get("evidenceEventIds")));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> entry) { return (List<String>) entry.get("evidenceEventIds"); }
    private static String nonblank(Object value) {
        if (!(value instanceof String text) || text.isBlank()) throw invalid("INVALID_MEMORY_FIELDS");
        return text.strip();
    }
    public static String cut(String text, int length) {
        return text == null ? "" : text.substring(0, text.offsetByCodePoints(0, Math.min(length, text.codePointCount(0, text.length()))));
    }
    private static <T> List<T> tail(List<T> values, int count) { return List.copyOf(values.subList(Math.max(0, values.size() - count), values.size())); }
    private static InvalidEntry invalid(String code) { return new InvalidEntry(code); }
    private static final class InvalidEntry extends IllegalArgumentException {
        InvalidEntry(String code) { super(code); }
    }
}
