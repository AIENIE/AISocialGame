package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.RuleSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Model-only views over detached, actor-visible observations. */
public final class AiPromptProjection {
    public static final int VERSION = 3;
    public static final int UNDERCOVER_VERSION = 5;
    public static final int WEREWOLF_VERSION = 5;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private AiPromptProjection() {}

    public static int version(String gameId) {
        return "undercover".equals(gameId) ? UNDERCOVER_VERSION : "werewolf".equals(gameId) ? WEREWOLF_VERSION : VERSION;
    }

    public static Map<String, Object> project(GameAiAdapter adapter, VisibleObservation o) {
        if ("undercover".equals(o.gameId())) return undercover(adapter, o);
        Map<String, Object> observation = JSON.convertValue(o, Map.class);
        if ("werewolf".equals(o.gameId())) {
            observation.put("self", displayedSeat(map(observation.get("self"))));
            observation.put("players", maps(observation.get("players")).stream().map(AiPromptProjection::displayedSeat).toList());
        }
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
        payload.put("inputFormatVersion", version(o.gameId()));
        if ("werewolf".equals(o.gameId())) payload.put("seatNumbering",
                "self 和 players 的 seatNumber 是页面显示的座位号，从1开始；发言中的‘几号’均用此编号。playerId 是不透明身份标识，不能从其数字推断座位；所有目标仍提交合法列表中的 targetPlayerId。历史发言原文不改写，应根据当前座位表核对指代。");
        payload.put("referenceFormat", "$ref is a JSON pointer within this payload; follow references to the original data. References do not change source or visibility.");
        payload.put("instruction", adapter.instruction(o)); payload.put("observation", observation);
        if("werewolf".equals(o.gameId())&&!map(o.memory().get("protocolCalibration")).isEmpty())
            payload.put("protocolCalibration",Map.of("instruction","仅作协议校准，请提交required中指定的合法动作、目标与证据；这不是策略评测。","required",o.memory().get("protocolCalibration")));
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
                if ("WITCH_SAVE".equals(a.nightAction())) action.put("useHeal", true);
                Map<String, Object> example = new LinkedHashMap<>(); example.put("action", action);
                // A directed reply needs the current question reference at the decision root, not inside PlayerAction.
                if ("ANSWER_PLAYER".equals(a.type()) && !conversation.replyTo().isEmpty())
                    example.put("evidenceEventIds", conversation.replyTo().stream().map(AiConversationContext.ReplyReference::eventId).toList());
                return example;
            }).toList());
        }
        return payload;
    }

    private static Map<String, Object> displayedSeat(Map<String, Object> player) {
        var visible = new LinkedHashMap<>(player);
        if (player.get("seatNumber") instanceof Number n && n.intValue() >= 0)
            visible.put("seatNumber", n.intValue() + 1);
        return visible;
    }

    private static Map<String, Object> undercover(GameAiAdapter adapter, VisibleObservation o) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inputFormatVersion", UNDERCOVER_VERSION);
        payload.put("instruction", adapter.instruction(o));
        payload.put("phase", o.phase()); payload.put("round", o.round());
        if (o.privateFacts().containsKey("wordFeedback")) payload.put("forbiddenFeedback",o.privateFacts().get("wordFeedback"));
        Map<String, Object> self = new LinkedHashMap<>(); self.put("playerId", o.actorId());
        if (flag(o.privateFacts().get("blank")) || "BLANK".equals(o.self().get("role"))) self.put("blank", true);
        else {
            String word = text(o.privateFacts().getOrDefault("word", o.self().get("word")));
            if (!word.isBlank()) self.put("word", word);
        }
        payload.put("self", self);
        payload.put("players", o.players().stream().map(player -> {
            Map<String, Object> visible = fields(player, "playerId", "displayName", "seatNumber", "alive");
            if (Boolean.FALSE.equals(player.get("alive"))) visible.putAll(fields(player, "role"));
            return visible;
        }).toList());
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("hasBlank", flag(o.rules().get("hasBlank")) || flag(self.get("blank")));
        if (!o.players().isEmpty()) rules.put("spyCount", "manual".equals(o.rules().get("spyMode"))
                ? number(o.rules().get("spyCount"), 1) : o.players().size() <= 6 ? 1 : 2);
        payload.put("rules", rules);
        Map<String, Object> persona = fields(o.persona(), "trait", "speechStyle", "strategyStyle");
        String difficulty = text(map(o.persona().get("difficulty")).get("guide"));
        if (difficulty.isBlank()) difficulty = text(AiRoundReflection.difficulty(number(o.rules().get("aiDifficulty"),
                number(o.persona().get("difficultyLevel"), 2))).get("guide"));
        persona.put("difficulty", difficulty); payload.put("persona", persona);
        Map<String,Object> lab = map(o.memory().get("lab"));
        if (!lab.isEmpty()) {
            payload.put("labExperience", Map.of("version", text(lab.get("experienceId")),
                    "summary", text(lab.get("experience")),
                    "usage", "这是历史策略经验，不是本局事实；本局判断仅依据本人词语和当前可见事件。"));
        }
        payload.put("events", o.events().stream().map(AiPromptProjection::undercoverEvent).filter(e -> !e.isEmpty()).toList());
        payload.put("legalActions", o.legalActions().stream().map(a -> {
            Map<String, Object> action = new LinkedHashMap<>(); action.put("type", a.type());
            if (!a.targets().isEmpty()) action.put("targets", a.targets());
            if (a.maxLength() > 0) action.put("maxLength", a.maxLength());
            return action;
        }).toList());
        return payload;
    }

    private static Map<String, Object> undercoverEvent(Map<String, Object> event) {
        String type = text(event.get("type"));
        Map<String, Object> data = map(event.get("data"));
        boolean speech = AiSpeechEvents.isSpeech(event);
        boolean skipped = Set.of("SKIP", "TIMEOUT").contains(type);
        if (!speech && !skipped && !Set.of("VOTE_REVEAL", "TIE", "ELIMINATED", "NO_ELIMINATION", "SELF_DESTRUCT").contains(type)) return Map.of();
        if (skipped && ("VOTE".equals(data.get("action")) || Set.of("VOTING", "RUNOFF").contains(text(event.get("phase"))))) return Map.of();
        Map<String, Object> result = fields(event, "eventId", "type", "round", "actorId");
        if (Set.of("TIE_DEFENSE", "RUNOFF").contains(text(event.get("phase")))) result.put("phase", event.get("phase"));
        if (speech) result.put("content", AiGrounding.content(event));
        else switch (type) {
            case "VOTE_REVEAL" -> result.putAll(fields(data, "votes"));
            case "TIE" -> result.putAll(fields(data, "candidates"));
            case "ELIMINATED" -> result.putAll(fields(data, "role"));
            case "SELF_DESTRUCT" -> result.putAll(fields(data, "content", "success"));
            case "NO_ELIMINATION" -> result.putAll(fields(data, "consecutiveRounds"));
            default -> { }
        }
        return result;
    }

    private static Map<String, Object> fields(Map<String, Object> source, String... keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !(value instanceof String s && s.isBlank())) result.put(key, value);
        }
        return result;
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
