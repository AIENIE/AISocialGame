package com.aisocialgame.engine.v2.turtlesoup;
import com.aisocialgame.model.*;
import com.aisocialgame.engine.*;
import java.util.*;
public final class TurtleSoupDefinition {
private static final String GAME_ID="turtle_soup";
private static final String ROLE_PLAYER="TURTLE_SOUP_PLAYER";
    public static Game metadata() {
        return new Game(
                GAME_ID,
                "海龟汤",
                "通过提问“是”或“否”来还原离奇故事的真相。",
                "BookOpen",
                List.of("悬疑", "合作", "故事"),
                1,
                6,
                GameStatus.ACTIVE,
                0,
                List.of(
                        new GameConfigOption("playerCount", "玩家人数", "select", 2, List.of(
                                new GameConfigOption.Option("1人", 1),
                                new GameConfigOption.Option("2人", 2),
                                new GameConfigOption.Option("3人", 3),
                                new GameConfigOption.Option("4人", 4),
                                new GameConfigOption.Option("5人", 5),
                                new GameConfigOption.Option("6人", 6)
                        ), null, null),
                        new GameConfigOption("caseId", "题目", "select", "midnight_train", List.of(
                                new GameConfigOption.Option("末班车的乘客", "midnight_train"),
                                new GameConfigOption.Option("雨夜的钥匙", "rainy_key")
                        ), null, null),
                        new GameConfigOption("maxQuestions", "问题上限", "number", 12, null, 4, 30),
                        new GameConfigOption("aiAssist", "AI 玩家追问", "boolean", true, null, null, null)
                )
        );
    }
    public static List<PhaseDefinition> phaseDefinitions() {
        return List.of(
                new PhaseDefinition(GamePhases.QUESTIONING, "提问解谜", 0, true),
                new PhaseDefinition("FINAL_ANSWER", "最终解答", 0, true),
                new PhaseDefinition(GamePhases.SETTLEMENT, "汤底揭示", 0, true)
        );
    }
    public static List<RoleDefinition> roleDefinitions() {
        return List.of(new RoleDefinition(ROLE_PLAYER, "解谜玩家", "COOP", false));
    }
}
