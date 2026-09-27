package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.RuleSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Model-only view. JSON pointers reference this payload, never a live state or hidden event. */
public final class AiPromptProjection {
    public static final int VERSION = 3;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private AiPromptProjection() {}

    public static Map<String, Object> project(GameAiAdapter adapter, VisibleObservation o) {
        Map<String, Object> observation = JSON.convertValue(o, Map.class);
        List<Map<String, Object>> sources = new ArrayList<>();
        List<Map<String, Object>> events = maps(observation.get("events"));
        Map<String, Object> classified = AiInformationSources.project(adapter, o);
        for (Map<String, Object> item : maps(classified.get("events"))) {
            int index = -1;
            for (int i = 0; i < events.size(); i++) if (Objects.equals(events.get(i).get("eventId"), item.get("eventId"))) { index = i; break; }
            if (index < 0) throw new IllegalArgumentException("Unknown visible event reference");
            Map<String, Object> event = events.get(index);
            String path = "/observation/events/" + index;
            Map<String, Object> value = new LinkedHashMap<>();
            for (var entry : map(item.get("value")).entrySet()) {
                String pointer;
                if (Set.of("statement", "record").contains(entry.getKey()))
                    pointer = path + (text(map(event.get("data")).get("content")).isBlank() ? "/message" : "/data/content");
                else pointer = entry.getValue() instanceof Boolean || entry.getValue() instanceof Number ? null : locate(event, entry.getValue(), path);
                value.put(entry.getKey(), pointer == null ? entry.getValue() : ref(pointer));
            }
            Map<String, Object> source = new LinkedHashMap<>(item); source.put("value", value); sources.add(source);
        }
        // Equal content fields resolve to the original message; differing wording remains intact.
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> e = events.get(i), data = map(e.get("data"));
            if (data.containsKey("content") && Objects.equals(data.get("content"), e.get("message"))) {
                data.put("content", ref("/observation/events/" + i + "/message")); e.put("data", data);
            }
        }
        observation.put("events", events);
        Map<String, Object> privateInfo = map(classified.get("privateKnowledge"));
        Map<String, Object> privateRefs = new LinkedHashMap<>();
        map(privateInfo.get("data")).keySet().forEach(k -> privateRefs.put(k, ref("/observation/privateFacts/" + escape(k))));
        privateInfo.put("data", privateRefs);
        Map<String, Object> hypotheses = new LinkedHashMap<>(map(classified.get("hypotheses")));
        hypotheses.put("entries", RuleSupport.HOST.equals(o.actorId()) ? List.of() : ref("/observation/memory/hypotheses"));
        Map<String, Object> memory = map(observation.get("memory"));
        if (!RuleSupport.HOST.equals(o.actorId())) memory.putIfAbsent("hypotheses", List.of());
        observation.put("memory", memory);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inputFormatVersion", VERSION);
        payload.put("referenceFormat", "$ref is a JSON pointer within this payload; follow references to the original data. References do not change source or visibility.");
        payload.put("instruction", adapter.instruction(o)); payload.put("observation", observation);
        payload.put("informationSources", Map.of("events", sources, "privateKnowledge", privateInfo, "hypotheses", hypotheses, "note", classified.get("note")));
        if (!RuleSupport.HOST.equals(o.actorId())) {
            var conversation = adapter.conversation(o);
            payload.put("conversation", conversation);
            List<String> current = o.events().stream().filter(e -> number(e.get("round"), -1) == o.round()).map(e -> text(e.get("eventId"))).toList();
            List<String> own = AiGrounding.speechEvents(o).stream().filter(e -> o.actorId().equals(e.get("actorId"))).map(e -> text(e.get("eventId"))).toList();
            // Empty targets are explicitly present so every pointer resolves, even on a first turn.
            memory.putIfAbsent("commitments", List.of()); memory.putIfAbsent("usedDescriptions", List.of());
            payload.put("continuity", Map.of("currentRoundEventIds", tail(current, 12), "ownStatementEventIds", tail(own, 8),
                    "usedDescriptions", ref("/observation/memory/usedDescriptions"), "commitmentsToVerify", ref("/observation/memory/commitments")));
            payload.put("outputExamples", o.legalActions().stream().map(a -> {
                Map<String, Object> action = new LinkedHashMap<>(); action.put("type", a.type());
                if (a.maxLength() > 0) action.put("content", "本次简短话语");
                if (!a.targets().isEmpty()) action.put("targetPlayerId", a.targets().getFirst());
                if (a.nightAction() != null) action.put("nightAction", a.nightAction());
                Map<String, Object> example = new LinkedHashMap<>(); example.put("action", action);
                // A directed reply needs the current question reference at the decision root, not inside PlayerAction.
                if ("ANSWER_PLAYER".equals(a.type()) && !conversation.replyTo().isEmpty())
                    example.put("evidenceEventIds", conversation.replyTo().stream().map(AiConversationContext.ReplyReference::eventId).toList());
                return example;
            }).toList());
        }
        return payload;
    }
    private static Map<String, String> ref(String pointer) { return Map.of("$ref", pointer); }
    private static String escape(String key) { return key.replace("~", "~0").replace("/", "~1"); }
    private static <T> List<T> tail(List<T> values, int size) { return values.subList(Math.max(0, values.size() - size), values.size()); }
    private static String locate(Object node, Object value, String path) {
        if (Objects.equals(node, value)) return path;
        if (node instanceof Map<?, ?> map) for (var entry : map.entrySet()) {
            String found = locate(entry.getValue(), value, path + "/" + escape(entry.getKey().toString()));
            if (found != null) return found;
        }
        return null;
    }
}
