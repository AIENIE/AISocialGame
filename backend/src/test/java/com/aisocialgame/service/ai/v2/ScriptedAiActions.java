package com.aisocialgame.service.ai.v2;

import com.aisocialgame.dto.PlayerAction;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Authored offline actions, never available to the production engine. */
public final class ScriptedAiActions {
    private ScriptedAiActions() {}
    public static PlayerAction action(GameAiAdapter adapter, VisibleObservation observation) {
        if (!"undercover".equals(observation.gameId())) return adapter.fallback(observation);
        if (observation.legalActions().stream().anyMatch(a -> "SKIP".equals(a.type()))) return com.aisocialgame.engine.v2.RuleSupport.action("SKIP", "", null);
        return com.aisocialgame.engine.v2.RuleSupport.action("SPEAK", "这是一条离线测试描述，序号" + observation.round() + "之" + observation.events().size() + "。", null);
    }
}
