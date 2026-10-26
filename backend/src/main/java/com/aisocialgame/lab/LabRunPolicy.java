package com.aisocialgame.lab;

import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.service.ai.v2.VisibleObservation;
import java.util.Map;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** The longer training window is private to all-AI lab training, never normal rooms or evaluation. */
public final class LabRunPolicy {
    public static final long TRAINING_DECISION_MILLIS = 150_000;
    public static final long DEPLOYMENT_DECISION_MILLIS = 50_000;
    private LabRunPolicy() {}

    public static boolean werewolfEvaluation(GameState state) {
        return "werewolf".equals(state.getGameId()) && "EVALUATION".equals(map(state.getData().get("lab")).get("labRunMode"));
    }
    public static boolean werewolfEvaluation(VisibleObservation observation) {
        return "werewolf".equals(observation.gameId()) && "EVALUATION".equals(map(observation.memory().get("lab")).get("labRunMode"));
    }

    public static boolean pureWerewolfTraining(GameState state) {
        return "werewolf".equals(state.getGameId())
                && training(map(state.getData().get("lab")), map(state.getData().get("rules")))
                && !state.getPlayers().isEmpty() && state.getPlayers().stream().allMatch(GamePlayerState::isAi);
    }
    public static boolean pureWerewolfTraining(VisibleObservation observation) {
        return "werewolf".equals(observation.gameId())
                && training(map(observation.memory().get("lab")), observation.rules())
                && (flag(map(observation.memory().get("lab")).get("trainingV2"))
                    || !observation.players().isEmpty() && observation.players().stream().allMatch(p -> flag(p.get("ai"))));
    }
    private static boolean training(Map<String,Object> lab, Map<String,Object> rules) {
        return !lab.isEmpty() && "TRAINING".equals(lab.getOrDefault("labRunMode", rules.get("labRunMode")));
    }
    public static long decisionMillis(GameState state) {
        return pureWerewolfTraining(state) ? TRAINING_DECISION_MILLIS : DEPLOYMENT_DECISION_MILLIS;
    }
    public static long decisionMillis(VisibleObservation observation) {
        return pureWerewolfTraining(observation) ? TRAINING_DECISION_MILLIS : DEPLOYMENT_DECISION_MILLIS;
    }
    public static int requestSeconds(VisibleObservation observation) {
        return pureWerewolfTraining(observation) ? 120 : 45;
    }
}
