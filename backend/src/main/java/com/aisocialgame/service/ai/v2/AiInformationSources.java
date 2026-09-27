package com.aisocialgame.service.ai.v2;

import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Projection of already-visible input. Event prose never establishes a system fact. */
public final class AiInformationSources {
    private AiInformationSources() {}
    public enum Source { SYSTEM_CONFIRMED, PLAYER_CLAIM, PERSONAL_HYPOTHESIS, UNCONFIRMED }

    public static Map<String, Object> project(GameAiAdapter rules, VisibleObservation o) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> e : o.events()) {
            Map<String, Object> facts = rules.confirmedFacts(e);
            String scope = "PUBLIC".equals(e.get("visibility")) ? "PUBLIC" : "PRIVATE_TO_OBSERVER";
            if (!facts.isEmpty()) items.add(item(e, Source.SYSTEM_CONFIRMED, scope, facts));
            if (rules.isPlayerClaim(e))
                items.add(item(e, Source.PLAYER_CLAIM, scope, Map.of("statement", AiGrounding.content(e))));
            else if (facts.isEmpty()) items.add(item(e, Source.UNCONFIRMED, scope, Map.of("record", AiGrounding.content(e))));
        }
        Map<String, Object> privateConfirmed = rules.privateConfirmed(o.privateFacts());
        // wolfCouncil includes authored notes and is deliberately excluded from confirmed private knowledge.
        return Map.of("events", items, "privateKnowledge", Map.of("source", Source.SYSTEM_CONFIRMED.name(),
                        "scope", "PRIVATE_TO_OBSERVER", "data", privateConfirmed),
                "hypotheses", Map.of("source", Source.PERSONAL_HYPOTHESIS.name(), "scope", "PRIVATE_TO_OBSERVER",
                        "entries", o.memory().getOrDefault("hypotheses", List.of())),
                "note", "分类不改变可见权限。玩家自称、理由、遗言均为声明；私密知识不可因被确认而公开。未列入白名单的记录不推导额外事实。");
    }
    private static Map<String, Object> item(Map<String, Object> e, Source source, String scope, Map<String, Object> value) {
        return Map.of("eventId", text(e.get("eventId")), "source", source.name(), "scope", scope, "value", value);
    }

    public static void ballot(Map<String, Object> e, Map<String, Object> d, Map<String, Object> result) {
        result.put("voterId", text(e.get("actorId"))); result.put("targetPlayerId", text(e.get("targetId"))); copy(d, result, "abstain");
    }
    public static void copy(Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String key : keys) if (from.containsKey(key)) to.put(key, from.get(key));
    }
}
