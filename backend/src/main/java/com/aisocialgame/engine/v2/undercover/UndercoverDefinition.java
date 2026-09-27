package com.aisocialgame.engine.v2.undercover;
import com.aisocialgame.model.*;
import com.aisocialgame.engine.*;
import java.util.*;
public final class UndercoverDefinition {
private static final String GAME_ID="undercover";
    public static Game metadata() {
        return new Game(
                GAME_ID,
                "谁是卧底",
                "用语言描述你的词语，找出隐藏在人群中的卧底！",
                "Spy",
                List.of("聚会", "休闲", "语言类"),
                4,
                10,
                GameStatus.ACTIVE,
                0,
                List.of(
                        new GameConfigOption("playerCount", "玩家人数", "select", 6, Arrays.asList(
                                new GameConfigOption.Option("4人", 4),
                                new GameConfigOption.Option("5人", 5),
                                new GameConfigOption.Option("6人", 6),
                                new GameConfigOption.Option("7人", 7),
                                new GameConfigOption.Option("8人", 8),
                                new GameConfigOption.Option("9人", 9),
                                new GameConfigOption.Option("10人", 10)
                        ), null, null),
                        new GameConfigOption("spyMode", "卧底数量模式", "select", "auto", List.of(
                                new GameConfigOption.Option("系统自动 (推荐)", "auto"),
                                new GameConfigOption.Option("手动设置", "manual")
                        ), null, null),
                        new GameConfigOption("hasBlank", "加入白板玩家", "boolean", false, null, null, null),
                        new GameConfigOption("wordPack", "词库类型", "select", "daily", List.of(
                                new GameConfigOption.Option("日常生活", "daily"),
                                new GameConfigOption.Option("成语俗语", "idiom"),
                                new GameConfigOption.Option("二次元", "acg"),
                                new GameConfigOption.Option("硬核科技", "tech"),
                                new GameConfigOption.Option("自定义词库", "custom")
                        ), null, null),
                        new GameConfigOption("speakTime", "发言时长", "select", 60, List.of(
                                new GameConfigOption.Option("30秒", 30),
                                new GameConfigOption.Option("60秒", 60),
                                new GameConfigOption.Option("90秒", 90),
                                new GameConfigOption.Option("不限时", 0)
                        ), null, null)
                )
        );
    }
    public static List<PhaseDefinition> phaseDefinitions() {
        return List.of(
                new PhaseDefinition(GamePhases.DESCRIPTION, "描述阶段", 60, true),
                new PhaseDefinition("CHALLENGE", "质询", 30, true),
                new PhaseDefinition("RESPONSE", "回应", 30, true),
                new PhaseDefinition("TIE_DEFENSE", "平票申辩", 30, true),
                new PhaseDefinition("RUNOFF", "复投", 30, true),
                new PhaseDefinition(GamePhases.VOTING, "投票阶段", 30, true),
                new PhaseDefinition(GamePhases.SETTLEMENT, "结算", 0, true)
        );
    }
    public static List<RoleDefinition> roleDefinitions() {
        return List.of(
                new RoleDefinition("CIVILIAN", "平民", "GOOD", false),
                new RoleDefinition("UNDERCOVER", "卧底", "EVIL", false),
                new RoleDefinition("BLANK", "白板", "NEUTRAL", false)
        );
    }
}
