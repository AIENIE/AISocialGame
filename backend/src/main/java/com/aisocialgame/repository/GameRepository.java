package com.aisocialgame.repository;

import com.aisocialgame.model.Game;
import com.aisocialgame.model.GameConfigOption;
import com.aisocialgame.model.GameStatus;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class GameRepository {
    private final Map<String, Game> games = new ConcurrentHashMap<>();

    public GameRepository() {
        seedGames();
    }

    public List<Game> findAll() {
        return games.values().stream().toList();
    }

    public Optional<Game> findById(String id) {
        return Optional.ofNullable(games.get(id));
    }

    private void seedGames() {
        Game werewolf = new Game(
                "werewolf",
                "狼人杀",
                "经典的社交推理游戏。天黑请闭眼，与伪装者博弈，活到最后。",
                "Moon",
                List.of("逻辑推理", "社交", "硬核"),
                6,
                12,
                GameStatus.ACTIVE,
                1240,
                List.of(
                        new GameConfigOption(
                                "template",
                                "板子预设",
                                "select",
                                "standard",
                                List.of(
                                        new GameConfigOption.Option("预女猎白 (标准)", "standard"),
                                        new GameConfigOption.Option("预女猎守 (进阶)", "guard"),
                                        new GameConfigOption.Option("生推局 (无神职)", "no_god")
                                ),
                                null,
                                null
                        ),
                        new GameConfigOption(
                                "playerCount",
                                "玩家人数",
                                "select",
                                12,
                                List.of(
                                        new GameConfigOption.Option("6人 (娱乐)", 6),
                                        new GameConfigOption.Option("9人 (进阶)", 9),
                                        new GameConfigOption.Option("12人 (标准)", 12)
                                ),
                                null,
                                null
                        ),
                        new GameConfigOption(
                                "witchRule",
                                "女巫规则",
                                "select",
                                "first_night",
                                List.of(
                                        new GameConfigOption.Option("全程不可自救", "no_save"),
                                        new GameConfigOption.Option("仅首夜可自救", "first_night"),
                                        new GameConfigOption.Option("全程可自救", "always_save")
                                ),
                                null,
                                null
                        ),
                        new GameConfigOption(
                                "winCondition",
                                "胜利条件",
                                "select",
                                "side",
                                List.of(
                                        new GameConfigOption.Option("屠边规则", "side"),
                                        new GameConfigOption.Option("屠城规则", "city")
                                ),
                                null,
                                null
                        ),
                        new GameConfigOption(
                                "speechTime",
                                "发言时长",
                                "select",
                                120,
                                List.of(
                                        new GameConfigOption.Option("60秒", 60),
                                        new GameConfigOption.Option("90秒", 90),
                                        new GameConfigOption.Option("120秒", 120)
                                ),
                                null,
                                null
                        ),
                        new GameConfigOption(
                                "hasLastWords",
                                "遗言规则",
                                "select",
                                "first_night",
                                List.of(
                                        new GameConfigOption.Option("仅首夜", "first_night"),
                                        new GameConfigOption.Option("全程有遗言", "always"),
                                        new GameConfigOption.Option("无遗言", "none")
                                ),
                                null,
                                null
                        )
                )
        );

        Game undercover = new Game(
                "undercover",
                "谁是卧底",
                "用语言描述你的词语，找出隐藏在人群中的卧底！",
                "Spy",
                List.of("聚会", "休闲", "语言类"),
                4,
                10,
                GameStatus.ACTIVE,
                856,
                List.of(
                        new GameConfigOption(
                                "playerCount",
                                "玩家人数",
                                "select",
                                6,
                                Arrays.asList(
                                        new GameConfigOption.Option("4人", 4),
                                        new GameConfigOption.Option("5人", 5),
                                        new GameConfigOption.Option("6人", 6),
                                        new GameConfigOption.Option("7人", 7),
                                        new GameConfigOption.Option("8人", 8),
                                        new GameConfigOption.Option("9人", 9),
                                        new GameConfigOption.Option("10人", 10)
                                ),
                                null,
                                null
                        ),
                        new GameConfigOption(
                                "spyMode",
                                "卧底数量模式",
                                "select",
                                "auto",
                                List.of(
                                        new GameConfigOption.Option("系统自动 (推荐)", "auto"),
                                        new GameConfigOption.Option("手动设置", "manual")
                                ),
                                null,
                                null
                        ),
                        new GameConfigOption(
                                "hasBlank",
                                "加入白板玩家",
                                "boolean",
                                false,
                                null,
                                null,
                                null
                        ),
                        new GameConfigOption(
                                "wordPack",
                                "词库类型",
                                "select",
                                "daily",
                                List.of(
                                        new GameConfigOption.Option("日常生活", "daily"),
                                        new GameConfigOption.Option("成语俗语", "idiom"),
                                        new GameConfigOption.Option("二次元", "acg"),
                                        new GameConfigOption.Option("硬核科技", "tech"),
                                        new GameConfigOption.Option("自定义词库", "custom")
                                ),
                                null,
                                null
                        ),
                        new GameConfigOption(
                                "speakTime",
                                "发言时长",
                                "select",
                                60,
                                List.of(
                                        new GameConfigOption.Option("30秒", 30),
                                        new GameConfigOption.Option("60秒", 60),
                                        new GameConfigOption.Option("90秒", 90),
                                        new GameConfigOption.Option("不限时", 0)
                                ),
                                null,
                                null
                        )
                )
        );

        Game turtleSoup = new Game(
                "turtle_soup",
                "海龟汤",
                "通过提问“是”或“否”来还原离奇故事的真相。",
                "BookOpen",
                List.of("悬疑", "合作", "故事"),
                1,
                6,
                GameStatus.ACTIVE,
                0,
                List.of(
                        new GameConfigOption(
                                "playerCount",
                                "玩家人数",
                                "select",
                                2,
                                List.of(
                                        new GameConfigOption.Option("1人", 1),
                                        new GameConfigOption.Option("2人", 2),
                                        new GameConfigOption.Option("3人", 3),
                                        new GameConfigOption.Option("4人", 4),
                                        new GameConfigOption.Option("6人", 6)
                                ),
                                null,
                                null
                        ),
                        new GameConfigOption(
                                "caseId",
                                "题目",
                                "select",
                                "midnight_train",
                                List.of(
                                        new GameConfigOption.Option("末班车的乘客", "midnight_train"),
                                        new GameConfigOption.Option("雨夜的钥匙", "rainy_key")
                                ),
                                null,
                                null
                        ),
                        new GameConfigOption("maxQuestions", "问题上限", "number", 12, null, 4, 30),
                        new GameConfigOption("aiAssist", "AI 玩家追问", "boolean", true, null, null, null)
                )
        );

        games.put(werewolf.getId(), werewolf);
        games.put(undercover.getId(), undercover);
        games.put(turtleSoup.getId(), turtleSoup);
    }
}
