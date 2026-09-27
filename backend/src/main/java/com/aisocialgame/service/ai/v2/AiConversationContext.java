package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.LegalAction;
import java.util.*;
import java.util.function.Function;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Model-only suggestions, never action permissions or semantic validation rules. */
public record AiConversationContext(String scene, List<ActionGuide> actions, List<ReplyReference> replyTo) {
    public record Guidance(List<String> purposes, String lengthGuide) {}
    public record ActionGuide(String type, String nightAction, List<String> purposes, String lengthGuide) {}
    public record ReplyReference(String eventId, String actorId) {}

    public static Guidance guide(String length, String... purposes) {
        return new Guidance(List.of(purposes), length);
    }

    public static AiConversationContext create(String scene, VisibleObservation o,
            Function<LegalAction, Guidance> guidance, Set<String> questionPhases, Set<String> closingTypes) {
        var actions = o.legalActions().stream().map(a -> {
            var g = guidance.apply(a);
            return new ActionGuide(a.type(), a.nightAction(), g.purposes(), g.lengthGuide());
        }).toList();
        return new AiConversationContext(scene, actions, reply(o, questionPhases, closingTypes));
    }

    public static AiConversationContext generic(VisibleObservation o) {
        return create(o.phase(), o, a -> guide("按当前合法行动表达；需要说明时聚焦一个目的，必要时可展开。", "CHOOSE_FOR_SITUATION"), Set.of(), Set.of());
    }

    /** Only bind an unanswered, public, current-round question supported by a reply capability.
     * No message inspection, hidden state lookup, or guess from an old question. */
    private static List<ReplyReference> reply(VisibleObservation o, Set<String> phases, Set<String> closingTypes) {
        var answer = o.legalActions().stream().filter(a -> "ANSWER_PLAYER".equals(a.type())).findFirst();
        if (answer.isEmpty() || phases.isEmpty()) return List.of();
        Map<String, Object> pending = null;
        for (var e : o.events()) {
            if (number(e.get("round"), -1) != o.round() || !"PUBLIC".equals(e.get("visibility"))) continue;
            String type = text(e.get("type"));
            if ("ASK_PLAYER".equals(type) && phases.contains(text(e.get("phase")))) {
                // These adapters have only one outstanding question at a time.
                pending = o.actorId().equals(e.get("targetId")) && !text(e.get("eventId")).isBlank()
                        && !text(e.get("actorId")).isBlank() ? e : null;
            } else if (pending != null && closingTypes.contains(type) && o.actorId().equals(e.get("actorId"))) {
                String linked = text(map(e.get("data")).get("questionEventId"));
                boolean linkedAnswer = !linked.isBlank() && linked.equals(pending.get("eventId"));
                boolean unlinkedAnswer = linked.isBlank() && ("ANSWER_PLAYER".equals(type)
                        ? Objects.equals(e.get("targetId"), pending.get("actorId")) : o.phase().equals(e.get("phase")));
                if (linkedAnswer || unlinkedAnswer) pending = null;
            }
        }
        if (pending == null || !answer.get().targets().isEmpty() && !answer.get().targets().contains(text(pending.get("actorId")))) return List.of();
        return List.of(new ReplyReference(text(pending.get("eventId")), text(pending.get("actorId"))));
    }
}
