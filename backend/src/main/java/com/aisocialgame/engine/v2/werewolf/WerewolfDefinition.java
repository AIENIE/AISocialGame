package com.aisocialgame.engine.v2.werewolf;
import com.aisocialgame.model.*;
import com.aisocialgame.engine.*;
import java.util.*;
public final class WerewolfDefinition {
private static final String GAME_ID="werewolf";
    public static Game metadata() {
        return new Game(
                GAME_ID,
                "狼人杀",
                "经典的社交推理游戏。天黑请闭眼，与伪装者博弈，活到最后。",
                "Moon",
                List.of("逻辑推理", "社交", "硬核"),
                6,
                12,
                GameStatus.ACTIVE,
                0,
                List.of(
                        new GameConfigOption("template", "板子预设", "select", "standard", List.of(
                                new GameConfigOption.Option("预女猎白 (标准)", "standard"),
                                new GameConfigOption.Option("预女猎守 (进阶)", "guard"),
                                new GameConfigOption.Option("生推局 (无神职)", "no_god")
                        ), null, null),
                        new GameConfigOption("playerCount", "玩家人数", "select", 12, List.of(
                                new GameConfigOption.Option("6人 (娱乐)", 6),
                                new GameConfigOption.Option("9人 (进阶)", 9),
                                new GameConfigOption.Option("12人 (标准)", 12)
                        ), null, null),
                        new GameConfigOption("witchRule", "女巫规则", "select", "first_night", List.of(
                                new GameConfigOption.Option("全程不可自救", "no_save"),
                                new GameConfigOption.Option("仅首夜可自救", "first_night"),
                                new GameConfigOption.Option("全程可自救", "always_save")
                        ), null, null),
                        new GameConfigOption("winCondition", "胜利条件", "select", "side", List.of(
                                new GameConfigOption.Option("屠边规则", "side"),
                                new GameConfigOption.Option("屠城规则", "city")
                        ), null, null),
                        new GameConfigOption("speechTime", "发言时长", "select", 120, List.of(
                                new GameConfigOption.Option("60秒", 60),
                                new GameConfigOption.Option("90秒", 90),
                                new GameConfigOption.Option("120秒", 120)
                        ), null, null),
                        new GameConfigOption("hasLastWords", "遗言规则", "select", "first_night", List.of(
                                new GameConfigOption.Option("仅首夜", "first_night"),
                                new GameConfigOption.Option("全程有遗言", "always"),
                                new GameConfigOption.Option("无遗言", "none")
                        ), null, null)
                )
        );
    }
    public static List<PhaseDefinition> phaseDefinitions() {
        return List.of(
                new PhaseDefinition(GamePhases.NIGHT, "夜晚行动", 30, false),
                new PhaseDefinition(GamePhases.DAY_DISCUSS, "白天讨论", 90, true),
                new PhaseDefinition("DAY_INTERACTION", "公开质询", 30, true),
                new PhaseDefinition("LAST_WORDS", "遗言", 30, true),
                new PhaseDefinition("DEATH_ACTION", "离场行动", 30, false),
                new PhaseDefinition(GamePhases.DAY_VOTE, "白天投票", 30, true),
                new PhaseDefinition(GamePhases.SETTLEMENT, "结算", 0, true)
        );
    }
    public static List<RoleDefinition> roleDefinitions() {
        return List.of(
                new RoleDefinition("WEREWOLF", "狼人", "EVIL", true),
                new RoleDefinition("SEER", "预言家", "GOOD", true),
                new RoleDefinition("WITCH", "女巫", "GOOD", true),
                new RoleDefinition("HUNTER", "猎人", "GOOD", false),
                new RoleDefinition("VILLAGER", "村民", "GOOD", false)
        );
    }
}
