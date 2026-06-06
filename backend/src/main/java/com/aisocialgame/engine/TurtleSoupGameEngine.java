package com.aisocialgame.engine;

import com.aisocialgame.dto.GamePlayerView;
import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.NightActionRequest;
import com.aisocialgame.dto.PendingAction;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.dto.SpeakRequest;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.Game;
import com.aisocialgame.model.GameConfigOption;
import com.aisocialgame.model.GameLogEntry;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameStatus;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.service.GameEventRecorder;
import com.aisocialgame.service.ReplayArchiveService;
import com.aisocialgame.service.RoomService;
import com.aisocialgame.service.StatsService;
import com.aisocialgame.service.safety.AiSafetyContext;
import com.aisocialgame.service.safety.AiSafetyService;
import com.aisocialgame.websocket.GamePushService;
import com.aisocialgame.dto.ws.GameStateEvent;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class TurtleSoupGameEngine implements GameEngine {
    private static final String GAME_ID = GameIds.TURTLE_SOUP;
    private static final String ROLE_PLAYER = "TURTLE_SOUP_PLAYER";
    private static final String STATUS_ONLINE = "ONLINE";

    private final RoomService roomService;
    private final GameStateRepository gameStateRepository;
    private final GameEventRecorder gameEventRecorder;
    private final ReplayArchiveService replayArchiveService;
    private final StatsService statsService;
    private final AiSafetyService aiSafetyService;
    private final GamePushService gamePushService;

    public TurtleSoupGameEngine(RoomService roomService,
                                GameStateRepository gameStateRepository,
                                GameEventRecorder gameEventRecorder,
                                ReplayArchiveService replayArchiveService,
                                StatsService statsService,
                                AiSafetyService aiSafetyService,
                                GamePushService gamePushService) {
        this.roomService = roomService;
        this.gameStateRepository = gameStateRepository;
        this.gameEventRecorder = gameEventRecorder;
        this.replayArchiveService = replayArchiveService;
        this.statsService = statsService;
        this.aiSafetyService = aiSafetyService;
        this.gamePushService = gamePushService;
    }

    @Override
    public String gameId() {
        return GAME_ID;
    }

    @Override
    public Game metadata() {
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

    @Override
    public List<PhaseDefinition> phaseDefinitions() {
        return List.of(
                new PhaseDefinition(GamePhases.QUESTIONING, "提问解谜", 0, true),
                new PhaseDefinition(GamePhases.SETTLEMENT, "汤底揭示", 0, true)
        );
    }

    @Override
    public List<RoleDefinition> roleDefinitions() {
        return List.of(new RoleDefinition(ROLE_PLAYER, "解谜玩家", "COOP", false));
    }

    @Override
    public ValidationResult validateStart(Room room) {
        return room.getSeats().isEmpty() ? ValidationResult.invalid("至少需要1名玩家才能开始") : ValidationResult.ok();
    }

    @Override
    @Transactional
    public GameStateResponse state(String roomId, User user) {
        Room room = roomService.getRoom(roomId);
        String viewerId = resolvePlayerId(user);
        return gameStateRepository.findById(roomId)
                .map(state -> buildResponse(room, state, viewerId))
                .orElseGet(() -> buildWaitingResponse(room, viewerId));
    }

    @Override
    @Transactional
    public GameStateResponse start(String roomId, User user) {
        Room room = roomService.getRoom(roomId);
        String actorId = requirePlayer(user);
        if (!isHost(room, actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有房主可以开始游戏");
        }
        if (room.getSeats().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "至少需要1名玩家才能开局");
        }

        TurtleSoupCase soupCase = caseById(asString(room.getConfig().get("caseId")));
        GameState state = new GameState(room.getId(), GAME_ID, GamePhases.QUESTIONING);
        state.setPlayers(room.getSeats().stream().map(seat -> {
            GamePlayerState player = new GamePlayerState(seat.getPlayerId(), seat.getDisplayName(), seat.getSeatNumber(), seat.isAi(), seat.getPersonaId(), seat.getAvatar());
            player.setRole(ROLE_PLAYER);
            player.setAlive(true);
            player.setConnectionStatus(STATUS_ONLINE);
            player.setLastActiveAt(LocalDateTime.now());
            return player;
        }).toList());
        state.setCurrentSeat(null);
        Map<String, Object> data = new HashMap<>();
        data.put("caseId", soupCase.id());
        data.put("caseTitle", soupCase.title());
        data.put("surface", soupCase.surface());
        data.put("solution", soupCase.solution());
        data.put("maxQuestions", maxQuestions(room));
        data.put("questionCount", 0);
        data.put("knownClues", new ArrayList<String>());
        data.put("qaHistory", new ArrayList<Map<String, Object>>());
        data.put("usedAiRecommendations", new ArrayList<String>());
        data.put("aiAssist", aiAssist(room));
        data.put("hostVerdict", "");
        state.setData(data);
        state.setLogs(new ArrayList<>(List.of(new GameLogEntry("system", "海龟汤开局，AI 主持已给出汤面"))));
        recordGameStart(state);
        roomService.updateStatus(room.getId(), RoomStatus.PLAYING);
        state = gameStateRepository.save(state);
        pushState(roomId, "PHASE_CHANGE", state);
        return buildResponse(room, state, actorId);
    }

    @Override
    public GameStateResponse speak(String roomId, SpeakRequest request, User user) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "海龟汤请使用统一 action 提问");
    }

    @Override
    public GameStateResponse vote(String roomId, VoteRequest request, User user) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "海龟汤不支持投票");
    }

    @Override
    public GameStateResponse nightAction(String roomId, NightActionRequest request, User user) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "海龟汤不支持夜晚行动");
    }

    @Override
    @Transactional
    public GameStateResponse action(String roomId, PlayerAction action, User user) {
        Room room = roomService.getRoom(roomId);
        String actorId = requirePlayer(user);
        GameState state = gameStateRepository.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "游戏尚未开始"));
        ensurePlayer(room, actorId);
        String type = action == null ? "" : action.getType();
        if ("ASK_QUESTION".equalsIgnoreCase(type)) {
            askQuestion(state, room, actorId, action.getContent(), false);
            maybeAskAiFollowUp(state, room, actorId);
        } else if ("SUBMIT_SOLUTION".equalsIgnoreCase(type)) {
            submitSolution(state, room, actorId, action.getContent());
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "海龟汤动作类型不支持");
        }
        state = gameStateRepository.save(state);
        pushState(roomId, GamePhases.SETTLEMENT.equals(state.getPhase()) ? "SETTLEMENT" : "STATE_SYNC", state);
        return buildResponse(room, state, actorId);
    }

    private void askQuestion(GameState state, Room room, String actorId, String content, boolean aiGenerated) {
        ensureQuestioning(state);
        String safeContent = aiSafetyService.requireAllowedInput(content, AiSafetyContext.source(AiSafetyService.SOURCE_GAME_SPEECH)
                .room(state.getRoomId(), state.getGameId())
                .user(actorId, actorId));
        int count = intValue(state.getData().get("questionCount"));
        int maxQuestions = intValue(state.getData().get("maxQuestions"));
        if (count >= maxQuestions) {
            finishGame(state, room, "FAILED", "问题次数已用完，汤底未解开。");
            return;
        }

        TurtleSoupCase soupCase = caseById(asString(state.getData().get("caseId")));
        QuestionResult result = answerQuestion(soupCase, safeContent);
        int nextCount = count + 1;
        state.getData().put("questionCount", nextCount);
        mergeClues(state, result.clues());
        appendQa(state, safeContent, result.answer(), result.clues(), aiGenerated);
        String displayName = playerName(state, actorId);
        addLog(state, aiGenerated ? "ai_question" : "question", displayName + " 问：" + safeContent + " 主持：" + result.answer(), Map.of("answer", result.answer()));
        gameEventRecorder.recordPublic(state, "TURTLE_SOUP_QUESTION", actorId, null, Map.of(
                "question", safeContent,
                "answer", result.answer(),
                "aiGenerated", aiGenerated,
                "questionCount", nextCount
        ));
        if (nextCount >= maxQuestions) {
            finishGame(state, room, "FAILED", "问题次数已用完，汤底未解开。");
        }
    }

    private void submitSolution(GameState state, Room room, String actorId, String content) {
        ensureQuestioning(state);
        String safeContent = aiSafetyService.requireAllowedInput(content, AiSafetyContext.source(AiSafetyService.SOURCE_GAME_SPEECH)
                .room(state.getRoomId(), state.getGameId())
                .user(actorId, actorId));
        TurtleSoupCase soupCase = caseById(asString(state.getData().get("caseId")));
        boolean solved = isSolutionCorrect(soupCase, safeContent);
        gameEventRecorder.recordPublic(state, "TURTLE_SOUP_SOLUTION_SUBMITTED", actorId, null, Map.of(
                "solved", solved,
                "summary", summarize(safeContent, 120)
        ));
        if (solved) {
            finishGame(state, room, "SOLVED", "解答成功，汤底已揭示。");
        } else {
            state.getData().put("hostVerdict", "还没有命中核心汤底，请继续提问。");
            addLog(state, "solution", playerName(state, actorId) + " 提交了解答，主持认为还不完整。");
        }
    }

    private void maybeAskAiFollowUp(GameState state, Room room, String actorId) {
        if (!Boolean.TRUE.equals(state.getData().get("aiAssist")) || !GamePhases.QUESTIONING.equals(state.getPhase())) {
            return;
        }
        GamePlayerState aiPlayer = state.getPlayers().stream()
                .filter(GamePlayerState::isAi)
                .filter(player -> !player.getPlayerId().equals(actorId))
                .findFirst()
                .orElse(null);
        if (aiPlayer == null) {
            return;
        }
        TurtleSoupCase soupCase = caseById(asString(state.getData().get("caseId")));
        String question = nextAiQuestion(state, soupCase);
        if (!StringUtils.hasText(question)) {
            return;
        }
        askQuestion(state, room, aiPlayer.getPlayerId(), question, true);
    }

    @SuppressWarnings("unchecked")
    private String nextAiQuestion(GameState state, TurtleSoupCase soupCase) {
        List<String> used = (List<String>) state.getData().getOrDefault("usedAiRecommendations", new ArrayList<String>());
        Set<String> usedSet = new LinkedHashSet<>(used);
        for (String question : soupCase.aiQuestions()) {
            if (!usedSet.contains(question)) {
                used.add(question);
                state.getData().put("usedAiRecommendations", used);
                return question;
            }
        }
        return "";
    }

    private QuestionResult answerQuestion(TurtleSoupCase soupCase, String question) {
        String normalized = normalize(question);
        List<String> clues = new ArrayList<>();
        for (ClueRule rule : soupCase.clueRules()) {
            if (containsAny(normalized, rule.keywords())) {
                clues.add(rule.clue());
            }
        }
        if (!clues.isEmpty()) {
            return new QuestionResult("是。", clues);
        }
        if (containsAny(normalized, soupCase.noKeywords())) {
            return new QuestionResult("否。", List.of());
        }
        if (containsAny(normalized, soupCase.nearKeywords())) {
            return new QuestionResult("接近但不准确。", List.of());
        }
        return new QuestionResult("不重要。", List.of());
    }

    private boolean isSolutionCorrect(TurtleSoupCase soupCase, String content) {
        String normalized = normalize(content);
        return soupCase.solutionKeywordGroups().stream()
                .allMatch(group -> group.stream().anyMatch(normalized::contains));
    }

    private void finishGame(GameState state, Room room, String winner, String verdict) {
        if (GamePhases.SETTLEMENT.equals(state.getPhase())) {
            return;
        }
        state.setPhase(GamePhases.SETTLEMENT);
        state.setPhaseEndsAt(null);
        state.getData().put("winner", winner);
        state.getData().put("hostVerdict", verdict);
        addLog(state, "system", verdict);
        gameEventRecorder.recordPublic(state, "TURTLE_SOUP_SOLVED", null, null, Map.of(
                "winner", winner,
                "verdict", verdict,
                "questionCount", intValue(state.getData().get("questionCount"))
        ));
        gameEventRecorder.recordPublic(state, "GAME_END", null, null, Map.of(
                "winner", winner,
                "winnerText", "SOLVED".equals(winner) ? "解谜成功" : "解谜失败",
                "roundNumber", state.getRoundNumber()
        ));
        if (!Boolean.TRUE.equals(state.getData().get("statsRecorded"))) {
            Set<String> winners = "SOLVED".equals(winner)
                    ? state.getPlayers().stream().map(GamePlayerState::getPlayerId).collect(java.util.stream.Collectors.toSet())
                    : Set.of();
            statsService.recordResult(room.getGameId(), state.getPlayers(), winners);
            state.getData().put("statsRecorded", true);
        }
        replayArchiveService.archiveFinishedGame(state, room);
        roomService.updateStatus(room.getId(), RoomStatus.WAITING);
    }

    private void recordGameStart(GameState state) {
        gameEventRecorder.ensureArchiveId(state);
        gameEventRecorder.recordPublic(state, "GAME_START", null, null, Map.of(
                "message", "海龟汤开局",
                "phase", state.getPhase(),
                "roundNumber", state.getRoundNumber(),
                "surface", state.getData().get("surface")
        ));
    }

    private GameStateResponse buildWaitingResponse(Room room, String viewerId) {
        List<GamePlayerView> players = room.getSeats().stream()
                .map(seat -> new GamePlayerView(seat.getPlayerId(), seat.getDisplayName(), seat.getSeatNumber(), seat.isAi(), seat.getPersonaId(), seat.getAvatar(), true, null, null, STATUS_ONLINE))
                .toList();
        return new GameStateResponse(room.getId(), room.getGameId(), GamePhases.WAITING, 0, null, null, null, viewerId, null, null, null, null, players, List.of(), Map.of("roomStatus", room.getStatus().name()), Map.of(), null);
    }

    private GameStateResponse buildResponse(Room room, GameState state, String viewerId) {
        List<GamePlayerView> players = state.getPlayers().stream()
                .map(player -> new GamePlayerView(
                        player.getPlayerId(),
                        player.getDisplayName(),
                        player.getSeatNumber(),
                        player.isAi(),
                        player.getPersonaId(),
                        player.getAvatar(),
                        player.isAlive(),
                        viewerId != null && viewerId.equals(player.getPlayerId()) || GamePhases.SETTLEMENT.equals(state.getPhase()) ? player.getRole() : null,
                        null,
                        player.getConnectionStatus()
                ))
                .toList();
        GamePlayerState me = viewerId == null ? null : state.getPlayers().stream().filter(player -> viewerId.equals(player.getPlayerId())).findFirst().orElse(null);
        return new GameStateResponse(
                room.getId(),
                room.getGameId(),
                state.getPhase(),
                state.getRoundNumber(),
                state.getCurrentSeat(),
                "AI 主持",
                asString(state.getData().get("winner")),
                viewerId,
                me == null ? null : me.getSeatNumber(),
                null,
                me == null ? null : me.getRole(),
                state.getPhaseEndsAt(),
                players,
                state.getLogs(),
                visibleExtra(state),
                Map.of(),
                GamePhases.QUESTIONING.equals(state.getPhase()) ? new PendingAction("TURTLE_SOUP", "可以继续提问或提交最终解答", 0) : null
        );
    }

    private Map<String, Object> visibleExtra(GameState state) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("caseId", state.getData().get("caseId"));
        extra.put("caseTitle", state.getData().get("caseTitle"));
        extra.put("surface", state.getData().get("surface"));
        extra.put("knownClues", state.getData().getOrDefault("knownClues", List.of()));
        extra.put("qaHistory", state.getData().getOrDefault("qaHistory", List.of()));
        extra.put("questionCount", intValue(state.getData().get("questionCount")));
        extra.put("maxQuestions", intValue(state.getData().get("maxQuestions")));
        extra.put("aiHostName", "AI 主持");
        extra.put("hostVerdict", state.getData().getOrDefault("hostVerdict", ""));
        if (GamePhases.SETTLEMENT.equals(state.getPhase())) {
            extra.put("solution", state.getData().get("solution"));
        }
        return extra;
    }

    private void addLog(GameState state, String type, String message) {
        addLog(state, type, message, Map.of());
    }

    private void addLog(GameState state, String type, String message, Map<String, Object> metadata) {
        List<GameLogEntry> logs = state.getLogs() == null ? new ArrayList<>() : new ArrayList<>(state.getLogs());
        GameLogEntry entry = new GameLogEntry(type, message);
        entry.setPhase(state.getPhase());
        entry.setRoundNumber(state.getRoundNumber());
        entry.setMetadata(metadata);
        logs.add(entry);
        state.setLogs(logs);
        gameEventRecorder.recordPublic(state, "TURTLE_SOUP_LOG", null, null, Map.of("message", message, "logType", type));
    }

    @SuppressWarnings("unchecked")
    private void appendQa(GameState state, String question, String answer, List<String> clues, boolean aiGenerated) {
        List<Map<String, Object>> history = (List<Map<String, Object>>) state.getData().getOrDefault("qaHistory", new ArrayList<Map<String, Object>>());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("question", question);
        item.put("answer", answer);
        item.put("clues", clues);
        item.put("aiGenerated", aiGenerated);
        item.put("time", LocalDateTime.now().toString());
        history.add(item);
        state.getData().put("qaHistory", history);
    }

    @SuppressWarnings("unchecked")
    private void mergeClues(GameState state, List<String> clues) {
        if (clues.isEmpty()) {
            return;
        }
        List<String> existing = (List<String>) state.getData().getOrDefault("knownClues", new ArrayList<String>());
        LinkedHashSet<String> merged = new LinkedHashSet<>(existing);
        merged.addAll(clues);
        state.getData().put("knownClues", new ArrayList<>(merged));
    }

    private void pushState(String roomId, String type, GameState state) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("logs", state.getLogs());
        payload.put("extra", visibleExtra(state));
        gamePushService.pushStateChange(roomId, new GameStateEvent(type, state.getPhase(), state.getRoundNumber(), state.getCurrentSeat(), payload));
    }

    private void ensureQuestioning(GameState state) {
        if (!GamePhases.QUESTIONING.equals(state.getPhase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前阶段不支持该操作");
        }
    }

    private void ensurePlayer(Room room, String actorId) {
        if (room.getSeats().stream().noneMatch(seat -> actorId.equals(seat.getPlayerId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "你不在该房间中");
        }
    }

    private String requirePlayer(User user) {
        String playerId = resolvePlayerId(user);
        if (!StringUtils.hasText(playerId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return playerId;
    }

    private String resolvePlayerId(User user) {
        return user == null ? null : user.getId();
    }

    private boolean isHost(Room room, String actorId) {
        return room.getSeats().stream().anyMatch(seat -> actorId.equals(seat.getPlayerId()) && seat.isHost());
    }

    private String playerName(GameState state, String playerId) {
        return state.getPlayers().stream()
                .filter(player -> playerId.equals(player.getPlayerId()))
                .map(GamePlayerState::getDisplayName)
                .findFirst()
                .orElse("玩家");
    }

    private int maxQuestions(Room room) {
        int requested = intValue(room.getConfig().get("maxQuestions"));
        if (requested <= 0) {
            return 12;
        }
        return Math.min(Math.max(requested, 4), 30);
    }

    private boolean aiAssist(Room room) {
        Object value = room.getConfig().get("aiAssist");
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return true;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private boolean containsAny(String value, List<String> keywords) {
        return keywords.stream().anyMatch(value::contains);
    }

    private String summarize(String value, int max) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private TurtleSoupCase caseById(String caseId) {
        return CASES.stream()
                .filter(item -> item.id().equals(caseId))
                .findFirst()
                .orElse(CASES.getFirst());
    }

    private static final List<TurtleSoupCase> CASES = List.of(
            new TurtleSoupCase(
                    "midnight_train",
                    "末班车的乘客",
                    "末班车司机明明看到最后一位女乘客坐在车上，却在终点站发现车厢空无一人。第二天，人们在她上车前的站台附近发现了她的遗物。",
                    "女乘客上车前已经遇难，司机看到的是车窗反光与红色围巾形成的错觉，并不是她本人仍在车上。",
                    List.of(
                            new ClueRule(List.of("红色围巾", "围巾", "红围巾"), "司机看到的红色围巾是关键线索。"),
                            new ClueRule(List.of("反光", "车窗", "玻璃"), "司机看到的是车窗反光造成的错觉。"),
                            new ClueRule(List.of("死亡", "遇难", "死了", "尸体"), "乘客在上车前已经遇难。"),
                            new ClueRule(List.of("遗物", "站台"), "遗物出现在上车前的站台附近。")
                    ),
                    List.of("鬼", "幽灵", "隐身", "下车"),
                    List.of("梦", "幻觉", "错觉"),
                    List.of(
                            List.of("死亡", "遇难", "死"),
                            List.of("反光", "车窗", "玻璃"),
                            List.of("红色围巾", "围巾")
                    ),
                    List.of("她上车前是否已经遇难？", "司机看到的是否是车窗反光？", "红色围巾是不是关键线索？")
            ),
            new TurtleSoupCase(
                    "rainy_key",
                    "雨夜的钥匙",
                    "雨夜里，一个人回家后发现钥匙插在门外。他没有报警，反而松了一口气。",
                    "钥匙是他白天故意留给家人的备用钥匙，雨夜家人已经安全回来并进屋，所以他松了一口气。",
                    List.of(
                            new ClueRule(List.of("备用钥匙", "故意", "留下"), "钥匙是白天故意留下的备用钥匙。"),
                            new ClueRule(List.of("家人", "孩子", "妻子"), "他担心的是家人是否安全到家。"),
                            new ClueRule(List.of("安全", "进屋", "回来"), "钥匙留在门外说明家人已经进屋。")
                    ),
                    List.of("小偷", "抢劫", "陌生人"),
                    List.of("忘记", "丢了"),
                    List.of(
                            List.of("备用钥匙", "故意", "留下"),
                            List.of("家人", "孩子", "妻子"),
                            List.of("安全", "进屋", "回来")
                    ),
                    List.of("钥匙是不是他故意留下的？", "他担心的是家人吗？", "钥匙在门外是否表示家人已经安全进屋？")
            )
    );

    private record TurtleSoupCase(String id,
                                  String title,
                                  String surface,
                                  String solution,
                                  List<ClueRule> clueRules,
                                  List<String> noKeywords,
                                  List<String> nearKeywords,
                                  List<List<String>> solutionKeywordGroups,
                                  List<String> aiQuestions) {}

    private record ClueRule(List<String> keywords, String clue) {}

    private record QuestionResult(String answer, List<String> clues) {}
}
