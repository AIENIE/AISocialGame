package com.aisocialgame.engine;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.dto.GamePlayerView;
import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.NightActionRequest;
import com.aisocialgame.dto.PendingAction;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.dto.SpeakRequest;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.dto.ws.GameStateEvent;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatMessageDto;
import com.aisocialgame.model.Game;
import com.aisocialgame.model.GameConfigOption;
import com.aisocialgame.model.GameLogEntry;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.model.TurtleSoupAnswerRule;
import com.aisocialgame.model.TurtleSoupStory;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.repository.TurtleSoupStoryRepository;
import com.aisocialgame.service.GameEventRecorder;
import com.aisocialgame.service.ReplayArchiveService;
import com.aisocialgame.service.RoomService;
import com.aisocialgame.service.StatsService;
import com.aisocialgame.service.safety.AiSafetyContext;
import com.aisocialgame.service.safety.AiSafetyService;
import com.aisocialgame.websocket.GamePushService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@Transactional
public class TurtleSoupGameEngine implements GameEngine {
    private static final String GAME_ID = GameIds.TURTLE_SOUP;
    private static final String PHASE_QUESTIONING = GamePhases.QUESTIONING;
    private static final String PHASE_SOLVING = GamePhases.SOLVING;
    private static final String PHASE_SETTLEMENT = GamePhases.SETTLEMENT;
    private static final String STATUS_ONLINE = PlayerConnectionStatuses.ONLINE;
    private static final String ACTION_ASK_QUESTION = "ASK_QUESTION";
    private static final String ACTION_FINAL_GUESS = "FINAL_GUESS";

    private final RoomService roomService;
    private final GameStateRepository gameStateRepository;
    private final TurtleSoupStoryRepository storyRepository;
    private final GameEventRecorder gameEventRecorder;
    private final ReplayArchiveService replayArchiveService;
    private final StatsService statsService;
    private final AiSafetyService aiSafetyService;
    private final GamePushService gamePushService;
    private final AiGrpcClient aiGrpcClient;
    private final AppProperties appProperties;

    public TurtleSoupGameEngine(RoomService roomService,
                                GameStateRepository gameStateRepository,
                                TurtleSoupStoryRepository storyRepository,
                                GameEventRecorder gameEventRecorder,
                                ReplayArchiveService replayArchiveService,
                                StatsService statsService,
                                AiSafetyService aiSafetyService,
                                GamePushService gamePushService,
                                AiGrpcClient aiGrpcClient,
                                AppProperties appProperties) {
        this.roomService = roomService;
        this.gameStateRepository = gameStateRepository;
        this.storyRepository = storyRepository;
        this.gameEventRecorder = gameEventRecorder;
        this.replayArchiveService = replayArchiveService;
        this.statsService = statsService;
        this.aiSafetyService = aiSafetyService;
        this.gamePushService = gamePushService;
        this.aiGrpcClient = aiGrpcClient;
        this.appProperties = appProperties;
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
                com.aisocialgame.model.GameStatus.ACTIVE,
                0,
                List.of(
                        new GameConfigOption("playerCount", "玩家人数", "select", 2, List.of(
                                new GameConfigOption.Option("1人", 1),
                                new GameConfigOption.Option("2人", 2),
                                new GameConfigOption.Option("4人", 4),
                                new GameConfigOption.Option("6人", 6)
                        ), null, null),
                        new GameConfigOption("storyPack", "题库", "select", "classic", List.of(
                                new GameConfigOption.Option("经典精选", "classic")
                        ), null, null),
                        new GameConfigOption("difficulty", "难度", "select", "easy", List.of(
                                new GameConfigOption.Option("简单", "easy"),
                                new GameConfigOption.Option("中等", "medium")
                        ), null, null),
                        new GameConfigOption("questionLimit", "提问上限", "number", 20, null, 6, 40)
                )
        );
    }

    @Override
    public List<PhaseDefinition> phaseDefinitions() {
        return List.of(
                new PhaseDefinition(PHASE_QUESTIONING, "提问阶段", 0, true),
                new PhaseDefinition(PHASE_SOLVING, "解答阶段", 0, true),
                new PhaseDefinition(PHASE_SETTLEMENT, "结算", 0, true)
        );
    }

    @Override
    public List<RoleDefinition> roleDefinitions() {
        return List.of(new RoleDefinition("SOLVER", "解谜者", "COOP", false));
    }

    @Override
    public ValidationResult validateStart(Room room) {
        return room.getSeats().isEmpty() ? ValidationResult.invalid("至少需要1名玩家才能开始") : ValidationResult.ok();
    }

    @Override
    public GameStateResponse state(String roomId, User user) {
        Room room = roomService.getRoom(roomId);
        String viewerId = resolvePlayerId(user);
        GameState state = gameStateRepository.findById(roomId).orElse(null);
        if (state == null) {
            return buildWaitingResponse(room, viewerId);
        }
        if (autoAdvanceAiQuestion(state, room)) {
            state = gameStateRepository.save(state);
            pushStateEvent(roomId, "STATE_SYNC", state);
        }
        return buildResponse(room, state, viewerId);
    }

    @Override
    public GameStateResponse start(String roomId, User user) {
        Room room = roomService.getRoom(roomId);
        String actorId = resolvePlayerId(user);
        if (!isHost(room, actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有房主可以开始游戏");
        }
        if (room.getSeats().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "至少需要1名玩家才能开始");
        }

        TurtleSoupStory story = storyRepository.select(asString(room.getConfig().get("storyPack")), asString(room.getConfig().get("difficulty")));
        List<GamePlayerState> players = room.getSeats().stream()
                .map(seat -> {
                    GamePlayerState player = new GamePlayerState(seat.getPlayerId(), seat.getDisplayName(), seat.getSeatNumber(), seat.isAi(), seat.getPersonaId(), seat.getAvatar());
                    player.setRole("SOLVER");
                    player.setAlive(true);
                    player.setConnectionStatus(STATUS_ONLINE);
                    player.setLastActiveAt(LocalDateTime.now());
                    return player;
                })
                .sorted(Comparator.comparingInt(GamePlayerState::getSeatNumber))
                .toList();

        GameState state = new GameState(room.getId(), GAME_ID, PHASE_QUESTIONING);
        state.setPlayers(players);
        state.setCurrentSeat(players.get(0).getSeatNumber());
        state.setData(new HashMap<>(Map.of(
                "storyId", story.getId(),
                "questionLimit", resolveQuestionLimit(room),
                "qaHistory", new ArrayList<Map<String, Object>>(),
                "confirmedClues", new ArrayList<String>()
        )));
        state.setLogs(new ArrayList<>());
        addLog(state, "system", "海龟汤开局，AI 主持已给出汤面");
        recordGameStart(state, story);
        roomService.updateStatus(room.getId(), RoomStatus.PLAYING);
        autoAdvanceAiQuestion(state, room);
        state = gameStateRepository.save(state);
        pushStateEvent(room.getId(), "PHASE_CHANGE", state);
        return buildResponse(room, state, actorId);
    }

    @Override
    public GameStateResponse speak(String roomId, SpeakRequest request, User user) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "海龟汤请使用统一动作提交提问");
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
    public GameStateResponse action(String roomId, PlayerAction action, User user) {
        String actorId = requirePlayer(user);
        Room room = roomService.getRoom(roomId);
        GameState state = gameStateRepository.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "游戏尚未开始"));
        String type = action != null && action.getType() != null ? action.getType().trim().toUpperCase(Locale.ROOT) : "";
        if (ACTION_ASK_QUESTION.equals(type)) {
            askQuestion(state, room, actorId, action.getContent());
        } else if (ACTION_FINAL_GUESS.equals(type)) {
            finalGuess(state, room, actorId, action.getContent());
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "未知海龟汤动作");
        }
        autoAdvanceAiQuestion(state, room);
        state = gameStateRepository.save(state);
        pushStateEvent(roomId, PHASE_SETTLEMENT.equals(state.getPhase()) ? "SETTLEMENT" : "ACTION", state);
        return buildResponse(room, state, actorId);
    }

    private void askQuestion(GameState state, Room room, String actorId, String content) {
        if (!PHASE_QUESTIONING.equals(state.getPhase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前阶段不支持提问");
        }
        GamePlayerState actor = currentSpeaker(state);
        if (actor == null || !actor.getPlayerId().equals(actorId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前不需要你提问");
        }
        if (!StringUtils.hasText(content)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "提问内容不能为空");
        }
        String question = aiSafetyService.requireAllowedInput(content, AiSafetyContext.source(AiSafetyService.SOURCE_GAME_SPEECH)
                .room(state.getRoomId(), GAME_ID)
                .user(actorId, actorId));
        submitQuestion(state, room, actor, question);
    }

    private void submitQuestion(GameState state, Room room, GamePlayerState actor, String question) {
        TurtleSoupStory story = story(state);
        HostAnswer answer = answerQuestion(state, story, question);
        addQa(state, actor, question, answer);
        addLog(state, "question", actor.getDisplayName() + "：" + question);
        addLog(state, "host", "AI 主持：" + answer.content());
        gameEventRecorder.recordPublic(state, "SOUP_QUESTION", actor.getPlayerId(), null, Map.of(
                "displayName", actor.getDisplayName(),
                "question", question
        ));
        gameEventRecorder.recordPublic(state, "HOST_ANSWER", null, actor.getPlayerId(), Map.of(
                "answerType", answer.type(),
                "answer", answer.content()
        ));
        advanceTurnOrSolving(state, room);
    }

    private void finalGuess(GameState state, Room room, String actorId, String content) {
        if (!PHASE_QUESTIONING.equals(state.getPhase()) && !PHASE_SOLVING.equals(state.getPhase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前阶段不支持提交解答");
        }
        GamePlayerState actor = playerById(state, actorId);
        if (actor == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "未找到你的座位信息");
        }
        if (!StringUtils.hasText(content)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "最终解答不能为空");
        }
        String guess = aiSafetyService.requireAllowedInput(content, AiSafetyContext.source(AiSafetyService.SOURCE_GAME_SPEECH)
                .room(state.getRoomId(), GAME_ID)
                .user(actorId, actorId));
        boolean solved = isCorrectGuess(story(state), guess);
        addLog(state, "guess", actor.getDisplayName() + " 提交最终解答：" + guess);
        gameEventRecorder.recordPublic(state, "FINAL_GUESS", actorId, null, Map.of(
                "displayName", actor.getDisplayName(),
                "guess", guess,
                "solved", solved
        ));
        finishGame(state, room, solved);
    }

    private boolean autoAdvanceAiQuestion(GameState state, Room room) {
        if (!PHASE_QUESTIONING.equals(state.getPhase())) {
            return false;
        }
        GamePlayerState speaker = currentSpeaker(state);
        if (speaker == null || !speaker.isAi()) {
            return false;
        }
        submitQuestion(state, room, speaker, buildAiQuestion(state));
        return true;
    }

    private String buildAiQuestion(GameState state) {
        TurtleSoupStory story = story(state);
        List<Map<String, Object>> history = qaHistory(state);
        List<String> asked = history.stream().map(item -> asString(item.get("question"))).filter(StringUtils::hasText).toList();
        for (String clue : story.getKeyClues()) {
            String question = clueToQuestion(clue);
            if (asked.stream().noneMatch(existing -> existing.contains(question.replace("吗？", "")))) {
                return question;
            }
        }
        return "这个细节和汤的真实来源有关吗？";
    }

    private String clueToQuestion(String clue) {
        if (clue.contains("荒岛")) {
            return "他以前流落过荒岛吗？";
        }
        if (clue.contains("不是真正")) {
            return "当年那碗汤不是真正的海龟汤吗？";
        }
        if (clue.contains("同伴")) {
            return "当年的食物和遇难同伴有关吗？";
        }
        if (clue.contains("味道")) {
            return "餐厅里汤的味道让他想起了真相吗？";
        }
        return clue + "吗？";
    }

    private HostAnswer answerQuestion(GameState state, TurtleSoupStory story, String question) {
        String normalized = normalize(question);
        String type = "UNKNOWN";
        String clue = "";
        for (TurtleSoupAnswerRule rule : story.getAnswerRules()) {
            if (rule.getKeywords().stream().anyMatch(keyword -> normalized.contains(normalize(keyword)))) {
                type = rule.getAnswerType();
                clue = rule.getClue();
                break;
            }
        }
        String fallback = switch (type) {
            case "YES" -> "是。";
            case "NO" -> "否。";
            case "CLOSE" -> "接近，但还不准确。";
            default -> "不重要或无法回答。";
        };
        if (StringUtils.hasText(clue) && ("YES".equals(type) || "CLOSE".equals(type))) {
            addConfirmedClue(state, clue);
        }
        String polished = polishHostAnswer(story, question, type, fallback);
        String safe = aiSafetyService.safeOutput(polished, AiSafetyContext.source(AiSafetyService.SOURCE_AI_PLAYER)
                .room(state.getRoomId(), GAME_ID));
        return new HostAnswer(type, StringUtils.hasText(safe) ? safe : fallback, clue);
    }

    private String polishHostAnswer(TurtleSoupStory story, String question, String type, String fallback) {
        try {
            var response = aiGrpcClient.chatCompletions(
                    appProperties.getProjectKey(),
                    appProperties.getAi().getSystemUserId(),
                    "",
                    appProperties.getAi().getDefaultModel(),
                    List.of(
                            new AiChatMessageDto("system", "你是海龟汤主持。只能基于给定判定回答，不能泄露汤底。只输出一句 12 字以内中文回答。"),
                            new AiChatMessageDto("user", "汤面：" + story.getPrompt() + "\n玩家问题：" + question + "\n判定：" + type + "\n兜底回答：" + fallback)
                    )
            );
            if (response != null && StringUtils.hasText(response.content()) && response.content().length() <= 40 && !response.content().contains(story.getSolution())) {
                return response.content().trim();
            }
        } catch (Exception ignored) {
            // Live game flow must keep working when the model is unavailable.
        }
        return fallback;
    }

    private void finishGame(GameState state, Room room, boolean solved) {
        state.setPhase(PHASE_SETTLEMENT);
        state.setCurrentSeat(null);
        state.setPhaseEndsAt(null);
        state.getData().put("winner", solved ? "SOLVED" : "UNSOLVED");
        state.getData().put("solutionRevealed", true);
        addLog(state, "system", solved ? "解答通过，汤底揭晓" : "解答未通过，汤底揭晓");
        gameEventRecorder.recordPublic(state, solved ? "SOUP_SOLVED" : "SOUP_UNSOLVED", null, null, Map.of(
                "solution", story(state).getSolution()
        ));
        gameEventRecorder.recordPublic(state, "GAME_END", null, null, Map.of(
                "winner", solved ? "SOLVED" : "UNSOLVED",
                "roundNumber", state.getRoundNumber()
        ));
        if (!Boolean.TRUE.equals(state.getData().get("statsRecorded"))) {
            Set<String> winners = solved
                    ? state.getPlayers().stream().filter(player -> !player.isAi()).map(GamePlayerState::getPlayerId).collect(java.util.stream.Collectors.toSet())
                    : Set.of();
            statsService.recordResult(GAME_ID, state.getPlayers(), winners);
            state.getData().put("statsRecorded", true);
        }
        replayArchiveService.archiveFinishedGame(state, room);
        roomService.updateStatus(room.getId(), RoomStatus.WAITING);
    }

    private void advanceTurnOrSolving(GameState state, Room room) {
        int questionCount = qaHistory(state).size();
        if (questionCount >= questionLimit(state)) {
            state.setPhase(PHASE_SOLVING);
            state.setCurrentSeat(null);
            state.setPhaseEndsAt(null);
            addLog(state, "system", "提问次数已用完，请提交最终解答");
            return;
        }
        List<Integer> seats = state.getPlayers().stream()
                .sorted(Comparator.comparingInt(GamePlayerState::getSeatNumber))
                .map(GamePlayerState::getSeatNumber)
                .toList();
        if (seats.isEmpty()) {
            return;
        }
        Integer current = state.getCurrentSeat();
        int idx = current == null ? 0 : seats.indexOf(current) + 1;
        if (idx < 0 || idx >= seats.size()) {
            idx = 0;
        }
        state.setCurrentSeat(seats.get(idx));
        state.setPhaseEndsAt(null);
    }

    private boolean isCorrectGuess(TurtleSoupStory story, String guess) {
        String normalized = normalize(guess);
        long hits = story.getKeyClues().stream()
                .filter(clue -> clueMatchesGuess(clue, normalized))
                .count();
        return hits >= Math.min(3, story.getKeyClues().size());
    }

    private boolean clueMatchesGuess(String clue, String normalizedGuess) {
        if (clue.contains("荒岛")) {
            return normalizedGuess.contains("荒岛") || normalizedGuess.contains("岛");
        }
        if (clue.contains("不是真正")) {
            return normalizedGuess.contains("不是真") || normalizedGuess.contains("不是海龟") || normalizedGuess.contains("骗");
        }
        if (clue.contains("同伴")) {
            return normalizedGuess.contains("同伴") || normalizedGuess.contains("人肉") || normalizedGuess.contains("救命恩人");
        }
        if (clue.contains("味道")) {
            return normalizedGuess.contains("味道") || normalizedGuess.contains("想起") || normalizedGuess.contains("发现");
        }
        return normalizedGuess.contains(normalize(clue));
    }

    private void addQa(GameState state, GamePlayerState actor, String question, HostAnswer answer) {
        List<Map<String, Object>> history = qaHistory(state);
        Map<String, Object> item = new HashMap<>();
        item.put("playerId", actor.getPlayerId());
        item.put("displayName", actor.getDisplayName());
        item.put("ai", actor.isAi());
        item.put("question", question);
        item.put("answerType", answer.type());
        item.put("answer", answer.content());
        item.put("clue", answer.clue());
        history.add(item);
        state.getData().put("qaHistory", history);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> qaHistory(GameState state) {
        Object raw = state.getData().get("qaHistory");
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new HashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    result.add(copy);
                }
            }
            return result;
        }
        List<Map<String, Object>> history = new ArrayList<>();
        state.getData().put("qaHistory", history);
        return history;
    }

    private void addConfirmedClue(GameState state, String clue) {
        List<String> clues = confirmedClues(state);
        if (!clues.contains(clue)) {
            clues.add(clue);
            state.getData().put("confirmedClues", clues);
        }
    }

    private List<String> confirmedClues(GameState state) {
        Object raw = state.getData().get("confirmedClues");
        if (raw instanceof List<?> list) {
            return new ArrayList<>(list.stream().map(String::valueOf).toList());
        }
        List<String> clues = new ArrayList<>();
        state.getData().put("confirmedClues", clues);
        return clues;
    }

    private TurtleSoupStory story(GameState state) {
        return storyRepository.findById(asString(state.getData().get("storyId"))).orElseGet(() -> storyRepository.select("classic", "easy"));
    }

    private int resolveQuestionLimit(Room room) {
        Object value = room.getConfig().get("questionLimit");
        int requested = value instanceof Number number ? number.intValue() : parseInt(asString(value), 20);
        return Math.min(Math.max(requested, 6), 40);
    }

    private int questionLimit(GameState state) {
        Object value = state.getData().get("questionLimit");
        return value instanceof Number number ? number.intValue() : parseInt(asString(value), 20);
    }

    private int parseInt(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void recordGameStart(GameState state, TurtleSoupStory story) {
        gameEventRecorder.ensureArchiveId(state);
        gameEventRecorder.recordPublic(state, "TURTLE_SOUP_START", null, null, Map.of(
                "title", story.getTitle(),
                "prompt", story.getPrompt(),
                "difficulty", story.getDifficulty()
        ));
        gameEventRecorder.recordGod(state, "SOUP_SOLUTION_ASSIGNED", null, null, Map.of(
                "solution", story.getSolution(),
                "keyClues", story.getKeyClues()
        ));
    }

    private void addLog(GameState state, String type, String message) {
        List<GameLogEntry> logs = state.getLogs() == null ? new ArrayList<>() : new ArrayList<>(state.getLogs());
        GameLogEntry entry = new GameLogEntry(type, message);
        entry.setPhase(state.getPhase());
        entry.setRoundNumber(state.getRoundNumber());
        logs.add(entry);
        state.setLogs(logs);
        gameEventRecorder.recordPublic(state, eventTypeForLog(type), null, null, Map.of(
                "message", message,
                "logType", type
        ));
    }

    private String eventTypeForLog(String type) {
        return switch (type) {
            case "question" -> "SOUP_QUESTION_LOG";
            case "host" -> "HOST_ANSWER_LOG";
            case "guess" -> "FINAL_GUESS_LOG";
            case "system" -> "SYSTEM_LOG";
            default -> "LOG";
        };
    }

    private GameStateResponse buildWaitingResponse(Room room, String viewerId) {
        List<GamePlayerView> players = room.getSeats().stream()
                .map(seat -> new GamePlayerView(seat.getPlayerId(), seat.getDisplayName(), seat.getSeatNumber(), seat.isAi(), seat.getPersonaId(), seat.getAvatar(), true, null, null, STATUS_ONLINE))
                .toList();
        return new GameStateResponse(room.getId(), GAME_ID, GamePhases.WAITING, 0, null, null, null, viewerId, null, null, null, null, players, List.of(), Map.of("roomStatus", room.getStatus().name()), Map.of(), null);
    }

    private GameStateResponse buildResponse(Room room, GameState state, String viewerId) {
        TurtleSoupStory story = story(state);
        List<GamePlayerView> players = state.getPlayers().stream()
                .map(player -> new GamePlayerView(player.getPlayerId(), player.getDisplayName(), player.getSeatNumber(), player.isAi(), player.getPersonaId(), player.getAvatar(), player.isAlive(), player.getRole(), null, player.getConnectionStatus()))
                .toList();
        GamePlayerState me = viewerId == null ? null : playerById(state, viewerId);
        Map<String, Object> extra = new HashMap<>();
        extra.put("soupTitle", story.getTitle());
        extra.put("soupPrompt", story.getPrompt());
        extra.put("difficulty", story.getDifficulty());
        extra.put("tags", story.getTags());
        extra.put("qaHistory", qaHistory(state));
        extra.put("confirmedClues", confirmedClues(state));
        extra.put("questionCount", qaHistory(state).size());
        extra.put("questionLimit", questionLimit(state));
        if (PHASE_SETTLEMENT.equals(state.getPhase())) {
            extra.put("solutionRevealed", true);
            extra.put("solution", story.getSolution());
            extra.put("keyClues", story.getKeyClues());
            extra.put("misleadingPoints", story.getMisleadingPoints());
        }
        String currentSpeaker = state.getCurrentSeat() == null ? null : players.stream()
                .filter(player -> player.getSeatNumber() == state.getCurrentSeat())
                .map(GamePlayerView::getDisplayName)
                .findFirst()
                .orElse(null);
        PendingAction pendingAction = null;
        if (me != null && PHASE_QUESTIONING.equals(state.getPhase()) && state.getCurrentSeat() != null && me.getSeatNumber() == state.getCurrentSeat()) {
            pendingAction = new PendingAction(ACTION_ASK_QUESTION, "请向 AI 主持提出一个是/否问题", 0);
        } else if (me != null && PHASE_SOLVING.equals(state.getPhase())) {
            pendingAction = new PendingAction(ACTION_FINAL_GUESS, "请提交最终解答", 0);
        }
        return new GameStateResponse(
                room.getId(),
                GAME_ID,
                state.getPhase(),
                state.getRoundNumber(),
                state.getCurrentSeat(),
                currentSpeaker,
                asString(state.getData().get("winner")),
                viewerId,
                me != null ? me.getSeatNumber() : null,
                null,
                me != null ? me.getRole() : null,
                state.getPhaseEndsAt(),
                players,
                state.getLogs(),
                extra,
                Map.of(),
                pendingAction
        );
    }

    private void pushStateEvent(String roomId, String type, GameState state) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("logs", state.getLogs() != null ? state.getLogs().size() : 0);
        payload.put("questionCount", qaHistory(state).size());
        gamePushService.pushStateChange(roomId, new GameStateEvent(type, state.getPhase(), state.getRoundNumber(), state.getCurrentSeat(), payload));
    }

    private GamePlayerState currentSpeaker(GameState state) {
        Integer seat = state.getCurrentSeat();
        if (seat == null) {
            return null;
        }
        return state.getPlayers().stream().filter(player -> player.getSeatNumber() == seat).findFirst().orElse(null);
    }

    private GamePlayerState playerById(GameState state, String playerId) {
        if (!StringUtils.hasText(playerId)) {
            return null;
        }
        return state.getPlayers().stream().filter(player -> playerId.equals(player.getPlayerId())).findFirst().orElse(null);
    }

    private boolean isHost(Room room, String playerId) {
        return StringUtils.hasText(playerId) && room.getSeats().stream().anyMatch(seat -> playerId.equals(seat.getPlayerId()) && seat.isHost());
    }

    private String resolvePlayerId(User user) {
        return user != null ? user.getId() : null;
    }

    private String requirePlayer(User user) {
        String playerId = resolvePlayerId(user);
        if (!StringUtils.hasText(playerId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未找到你的座位信息");
        }
        return playerId;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private record HostAnswer(String type, String content, String clue) {
    }
}
