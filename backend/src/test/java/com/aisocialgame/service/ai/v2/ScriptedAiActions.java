package com.aisocialgame.service.ai.v2;

import com.aisocialgame.dto.PlayerAction;
import java.util.List;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Authored offline actions, never available to the production engine. */
public final class ScriptedAiActions {
    private ScriptedAiActions() {}
    public static PlayerAction action(GameAiAdapter adapter, VisibleObservation observation) {
        if ("werewolf".equals(observation.gameId())) return werewolfAction(observation);
        if (!"undercover".equals(observation.gameId())) return adapter.fallback(observation);
        if (observation.legalActions().stream().anyMatch(a -> "SKIP".equals(a.type()))) return com.aisocialgame.engine.v2.RuleSupport.action("SKIP", "", null);
        return com.aisocialgame.engine.v2.RuleSupport.action("SPEAK", "这是一条离线测试描述，序号" + observation.round() + "之" + observation.events().size() + "。", null);
    }
    private static PlayerAction werewolfAction(VisibleObservation observation) {
        for (String type : List.of("SPEAK", "ANSWER_PLAYER", "ASK_PLAYER", "SKIP", "VOTE", "NIGHT_ACTION", "HUNTER_SHOOT")) {
            var available = observation.legalActions().stream().filter(a -> type.equals(a.type())).findFirst();
            if (available.isEmpty()) continue;
            var capability = available.get();
            String speech = switch (type) {
                case "SPEAK" -> "我继续观察大家的公开发言，这是第" + observation.round() + "轮第" + observation.events().size() + "条线索。";
                case "ANSWER_PLAYER" -> "关于刚才的问题，我目前仍保留其他解释，需要继续核对。";
                case "ASK_PLAYER" -> "你目前的判断主要依据哪条公开信息？";
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
