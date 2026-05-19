package com.aisocialgame.engine;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.NightActionRequest;
import com.aisocialgame.dto.SpeakRequest;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.Game;
import com.aisocialgame.model.GameConfigOption;
import com.aisocialgame.model.GameStatus;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class UndercoverGameEngine implements GameEngine {
    private static final String GAME_ID = "undercover";
    private final GameRuntimeSupport runtime;

    public UndercoverGameEngine(GameRuntimeSupport runtime) {
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

    @Override
    public List<PhaseDefinition> phaseDefinitions() {
        return List.of(
                new PhaseDefinition("DESCRIPTION", "描述阶段", 60, true),
                new PhaseDefinition("VOTING", "投票阶段", 30, true),
                new PhaseDefinition("SETTLEMENT", "结算", 0, true)
        );
    }

    @Override
    public List<RoleDefinition> roleDefinitions() {
        return List.of(
                new RoleDefinition("CIVILIAN", "平民", "GOOD", false),
                new RoleDefinition("UNDERCOVER", "卧底", "EVIL", false),
                new RoleDefinition("BLANK", "白板", "NEUTRAL", false)
        );
    }

    @Override
    public ValidationResult validateStart(Room room) {
        return room.getSeats().size() >= 4 ? ValidationResult.ok() : ValidationResult.invalid("至少需要4名玩家才能开始");
    }

    @Override
    public GameStateResponse state(String roomId, User user) {
        return runtime.state(GAME_ID, roomId, user);
    }

    @Override
    public GameStateResponse start(String roomId, User user) {
        return runtime.start(GAME_ID, roomId, user);
    }

    @Override
    public GameStateResponse speak(String roomId, SpeakRequest request, User user) {
        return runtime.speak(GAME_ID, roomId, request, user);
    }

    @Override
    public GameStateResponse vote(String roomId, VoteRequest request, User user) {
        return runtime.vote(GAME_ID, roomId, request, user);
    }

    @Override
    public GameStateResponse nightAction(String roomId, NightActionRequest request, User user) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "谁是卧底不支持夜晚行动");
    }
}
