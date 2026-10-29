package com.aisocialgame.service.ai.v2;

import com.aisocialgame.dto.PlayerAction;
import java.util.List;
import java.util.Map;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Authored offline actions, never available to the production engine. */
public final class ScriptedAiActions {
    private ScriptedAiActions() {}
    public static AiTurnDecision decision(PlayerAction action) {
        return new AiTurnDecision(action, action.getContent(), Map.of(), List.of(), Map.of(), Map.of());
    }
    public static AiTurnDecision decision(GameAiAdapter adapter, VisibleObservation observation) {
        PlayerAction selected = action(adapter, observation);
        List<String> evidence = "ANSWER_PLAYER".equals(selected.getType()) ? observation.events().stream()
                .filter(event -> "ASK_PLAYER".equals(text(event.get("type"))) && observation.actorId().equals(text(event.get("targetId"))))
                .map(event -> text(event.get("eventId"))).filter(id -> !id.isBlank()).toList() : List.of();
        return new AiTurnDecision(selected, selected.getContent(), Map.of(), evidence, Map.of(), Map.of());
    }
    public static PlayerAction action(GameAiAdapter adapter, VisibleObservation observation) {
        if ("werewolf".equals(observation.gameId())) return werewolfAction(observation);
        if ("turtle_soup".equals(observation.gameId())) {
            if (!HOST.equals(observation.actorId())) return com.aisocialgame.engine.v2.RuleSupport.action("DISCUSS", "离线测试讨论" + observation.events().size(), null);
            var verdict = com.aisocialgame.engine.v2.RuleSupport.action("HOST_VERDICT", "", null);
            verdict.setExtra(Map.of("requestId", map(observation.privateFacts().get("pendingHost")).get("requestId"),
                    "verdict", "UNRESOLVED", "factIds", List.of()));
            return verdict;
        }
        if (!"undercover".equals(observation.gameId())) throw new IllegalArgumentException("No authored fixture for " + adapter.gameId());
        if (observation.legalActions().stream().anyMatch(a -> "SKIP".equals(a.type()))) return com.aisocialgame.engine.v2.RuleSupport.action("SKIP", "", null);
        return com.aisocialgame.engine.v2.RuleSupport.action("SPEAK", "这是一条离线测试描述，序号" + observation.round() + "之" + observation.events().size() + "。", null);
    }
    private static PlayerAction werewolfAction(VisibleObservation observation) {
        for (String type : List.of("SPEAK", "ANSWER_PLAYER", "ASK_PLAYER", "SKIP", "VOTE", "NIGHT_ACTION", "HUNTER_SHOOT")) {
            var available = observation.legalActions().stream().filter(a -> type.equals(a.type())).findFirst();
            if (available.isEmpty()) continue;
            var capability = available.get();
            String speech = switch (type) {
                case "SPEAK" -> "我仍需核对依据。";
                case "ANSWER_PLAYER" -> "还需核对。";
                case "ASK_PLAYER" -> "依据是什么？";
                default -> "";
            };
            var selected = com.aisocialgame.engine.v2.RuleSupport.action(type, speech,
                    capability.targets().isEmpty() ? null : capability.targets().getFirst());
            selected.setNightAction(capability.nightAction());
            if ("WITCH_SAVE".equals(capability.nightAction())) selected.setUseHeal(true);
            return selected;
        }
        throw new IllegalArgumentException("No authored offline Werewolf action for " + observation.phase());
    }
}
