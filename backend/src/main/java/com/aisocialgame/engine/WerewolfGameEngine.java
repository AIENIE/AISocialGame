package com.aisocialgame.engine;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.NightActionRequest;
import com.aisocialgame.dto.SpeakRequest;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.model.Game;
import com.aisocialgame.model.GameConfigOption;
import com.aisocialgame.model.GameStatus;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.User;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WerewolfGameEngine implements GameEngine {
    private static final String GAME_ID = "werewolf";
    private final GameRuntimeSupport runtime;

    public WerewolfGameEngine(GameRuntimeSupport runtime) {
        this.runtime = runtime;
    }

    @Override
    public String gameId() {
        return GAME_ID;
    }

    @Override
    public Game metadata() {
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

    @Override
    public List<PhaseDefinition> phaseDefinitions() {
        return List.of(
                new PhaseDefinition("NIGHT", "夜晚行动", 30, false),
                new PhaseDefinition("DAY_DISCUSS", "白天讨论", 90, true),
                new PhaseDefinition("DAY_VOTE", "白天投票", 30, true),
                new PhaseDefinition("SETTLEMENT", "结算", 0, true)
        );
    }

    @Override
    public List<RoleDefinition> roleDefinitions() {
        return List.of(
                new RoleDefinition("WEREWOLF", "狼人", "EVIL", true),
                new RoleDefinition("SEER", "预言家", "GOOD", true),
                new RoleDefinition("WITCH", "女巫", "GOOD", true),
                new RoleDefinition("HUNTER", "猎人", "GOOD", false),
                new RoleDefinition("VILLAGER", "村民", "GOOD", false)
        );
    }

    @Override
    public ValidationResult validateStart(Room room) {
        return room.getSeats().size() >= 6 ? ValidationResult.ok() : ValidationResult.invalid("狼人杀至少需要6名玩家");
    }

    @Override
    public GameStateResponse state(String roomId, User user, String playerIdHeader) {
        return runtime.state(GAME_ID, roomId, user, playerIdHeader);
    }

    @Override
    public GameStateResponse start(String roomId, User user, String playerIdHeader) {
        return runtime.start(GAME_ID, roomId, user, playerIdHeader);
    }

    @Override
    public GameStateResponse speak(String roomId, SpeakRequest request, User user, String playerIdHeader) {
        return runtime.speak(GAME_ID, roomId, request, user, playerIdHeader);
    }

    @Override
    public GameStateResponse vote(String roomId, VoteRequest request, User user, String playerIdHeader) {
        return runtime.vote(GAME_ID, roomId, request, user, playerIdHeader);
    }

    @Override
    public GameStateResponse nightAction(String roomId, NightActionRequest request, User user, String playerIdHeader) {
        return runtime.nightAction(GAME_ID, roomId, request, user, playerIdHeader);
    }
}
