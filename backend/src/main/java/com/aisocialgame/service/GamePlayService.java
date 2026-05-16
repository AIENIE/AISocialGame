package com.aisocialgame.service;

import com.aisocialgame.config.PromptProperties;
import com.aisocialgame.dto.GamePlayerView;
import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.PendingAction;
import com.aisocialgame.dto.SpeakRequest;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.dto.NightActionRequest;
import com.aisocialgame.dto.ws.GameStateEvent;
import com.aisocialgame.dto.ws.PrivateEvent;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.GameLogEntry;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomSeat;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.model.UndercoverWordPair;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.repository.UndercoverWordRepository;
import com.aisocialgame.service.ai.AiDecisionResult;
import com.aisocialgame.service.ai.AiDecisionService;
import com.aisocialgame.websocket.GamePushService;
import com.aisocialgame.websocket.PlayerConnectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class GamePlayService {
    private static final String PHASE_WAITING = "WAITING";
    private static final String PHASE_DESCRIPTION = "DESCRIPTION";
    private static final String PHASE_VOTING = "VOTING";
    private static final String PHASE_DISTRIBUTION = "DISTRIBUTION";
    private static final String PHASE_NIGHT = "NIGHT";
    private static final String PHASE_DAY_DISCUSS = "DAY_DISCUSS";
    private static final String PHASE_DAY_VOTE = "DAY_VOTE";
    private static final String PHASE_SETTLEMENT = "SETTLEMENT";
    private static final String STATUS_ONLINE = "ONLINE";
    private static final String STATUS_DISCONNECTED = "DISCONNECTED";
    private static final String STATUS_AI_TAKEOVER = "AI_TAKEOVER";

    private final RoomService roomService;
    private final GameStateRepository gameStateRepository;
    private final UndercoverWordRepository undercoverWordRepository;
    private final StatsService statsService;
    private final PromptProperties promptProperties;
    private final AiDecisionService aiDecisionService;
    private final GamePushService gamePushService;
    private final PlayerConnectionService playerConnectionService;
    private final Duration disconnectThreshold;

    public GamePlayService(RoomService roomService,
                           GameStateRepository gameStateRepository,
                           UndercoverWordRepository undercoverWordRepository,
                           StatsService statsService,
                           PromptProperties promptProperties,
                           AiDecisionService aiDecisionService,
                           GamePushService gamePushService,
                           PlayerConnectionService playerConnectionService,
                           @Value("${connection.disconnect-threshold-seconds:60}") long disconnectThresholdSeconds) {
        this.roomService = roomService;
        this.gameStateRepository = gameStateRepository;
        this.undercoverWordRepository = undercoverWordRepository;
        this.statsService = statsService;
        this.promptProperties = promptProperties;
        this.aiDecisionService = aiDecisionService;
        this.gamePushService = gamePushService;
        this.playerConnectionService = playerConnectionService;
        this.disconnectThreshold = Duration.ofSeconds(Math.max(5, disconnectThresholdSeconds));
    }

    @Transactional
    public GameStateResponse state(String gameId, String roomId, User user, String playerIdHeader) {
        Room room = roomService.getRoom(roomId);
        String viewerId = resolvePlayerId(room, user, playerIdHeader);
        Optional<GameState> optionalState = gameStateRepository.findById(roomId);
        if (optionalState.isEmpty()) {
            return buildWaitingResponse(room, viewerId);
        }

        GameState state = optionalState.get();
        boolean changed = false;
        if (StringUtils.hasText(viewerId)) {
            playerConnectionService.markActive(viewerId, roomId);
        }
        changed = syncConnectionStatus(state, viewerId) || changed;
        if ("undercover".equals(gameId) && PHASE_VOTING.equals(state.getPhase()) && state.getPhaseEndsAt() != null && LocalDateTime.now().isAfter(state.getPhaseEndsAt())) {
            changed = resolveUndercoverVoting(state, room, true) || changed;
        }
        if (gameId.equals("werewolf") && PHASE_NIGHT.equals(state.getPhase()) && state.getPhaseEndsAt() != null && LocalDateTime.now().isAfter(state.getPhaseEndsAt())) {
            changed = resolveNight(state, room, true);
        }
        if (gameId.equals("werewolf") && PHASE_DAY_DISCUSS.equals(state.getPhase())) {
            changed = autoAdvanceWerewolfDay(state, room) || changed;
        }
        if (gameId.equals("werewolf") && PHASE_DAY_VOTE.equals(state.getPhase()) && state.getPhaseEndsAt() != null && LocalDateTime.now().isAfter(state.getPhaseEndsAt())) {
            changed = resolveWerewolfVoting(state, room, true) || changed;
        }
        if (gameId.equals("undercover")) {
            changed = autoAdvanceUndercover(state, room) || changed;
        }
        if (changed) {
            state = gameStateRepository.save(state);
            pushStateEvent(roomId, "STATE_SYNC", state);
        }
        return buildResponse(gameId, room, state, viewerId);
    }

    public GameStateResponse start(String gameId, String roomId, User user, String playerIdHeader) {
        Room room = roomService.getRoom(roomId);
        String actorId = resolvePlayerId(room, user, playerIdHeader);
        if (!isHost(room, actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有房主可以开始游戏");
        }
        if (room.getSeats().size() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "至少需要两名玩家才能开局");
        }

        if (gameId.equals("undercover")) {
            GameState state = startUndercover(room);
            GameStateResponse response = buildResponse(gameId, room, state, actorId);
            pushStateEvent(roomId, "PHASE_CHANGE", state);
            pushPrivateAssignments(state);
            return response;
        }
        if (gameId.equals("werewolf")) {
            GameState state = startWerewolf(room);
            GameStateResponse response = buildResponse(gameId, room, state, actorId);
            pushStateEvent(roomId, "PHASE_CHANGE", state);
            pushPrivateAssignments(state);
            return response;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "暂不支持的游戏");
    }

    public GameStateResponse speak(String gameId, String roomId, SpeakRequest request, User user, String playerIdHeader) {
        Room room = roomService.getRoom(roomId);
        String actorId = requirePlayer(room, user, playerIdHeader);
        GameState state = gameStateRepository.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "游戏尚未开始"));
        if (gameId.equals("undercover")) {
            speakUndercover(state, room, actorId, request.getContent());
        } else if (gameId.equals("werewolf")) {
            speakWerewolf(state, room, actorId, request.getContent());
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "暂不支持的游戏");
        }
        playerConnectionService.markActive(actorId, roomId);
        markPlayerOnline(state, actorId);
        state = gameStateRepository.save(state);
        pushStateEvent(roomId, "SPEAK", state);
        return buildResponse(gameId, room, state, actorId);
    }

    public GameStateResponse vote(String gameId, String roomId, VoteRequest request, User user, String playerIdHeader) {
        Room room = roomService.getRoom(roomId);
        String actorId = requirePlayer(room, user, playerIdHeader);
        GameState state = gameStateRepository.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "游戏尚未开始"));
        if (gameId.equals("undercover")) {
            voteUndercover(state, room, actorId, request);
        } else if (gameId.equals("werewolf")) {
            voteWerewolf(state, room, actorId, request);
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "暂不支持的游戏");
        }
        playerConnectionService.markActive(actorId, roomId);
        markPlayerOnline(state, actorId);
        state = gameStateRepository.save(state);
        pushStateEvent(roomId, "VOTE", state);
        return buildResponse(gameId, room, state, actorId);
    }

    public GameStateResponse nightAction(String gameId, String roomId, NightActionRequest request, User user, String playerIdHeader) {
        if (!"werewolf".equals(gameId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "该接口仅支持狼人杀夜晚行动");
        }
        Room room = roomService.getRoom(roomId);
        String actorId = requirePlayer(room, user, playerIdHeader);
        GameState state = gameStateRepository.findById(roomId).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "游戏尚未开始"));

        // 如果仍停留在白天环节（讨论/投票），先强制推进到夜晚，避免玩家因未投票而被卡住
        boolean progressed = fastForwardToNight(state, room);
        if (progressed) {
            state = gameStateRepository.save(state);
        }
        if (!PHASE_NIGHT.equals(state.getPhase())) {
            // 游戏可能在推进过程中已经结算，直接返回当前状态
            if (PHASE_SETTLEMENT.equals(state.getPhase())) {
                return buildResponse(gameId, room, state, actorId);
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前阶段不支持该操作");
        }

        setNightAction(state, room, actorId, request);
        playerConnectionService.markActive(actorId, roomId);
        markPlayerOnline(state, actorId);
        state = gameStateRepository.save(state);
        pushStateEvent(roomId, "PHASE_CHANGE", state);
        return buildResponse(gameId, room, state, actorId);
    }

    private GameState startUndercover(Room room) {
        List<GamePlayerState> players = room.getSeats().stream()
                .map(seat -> new GamePlayerState(seat.getPlayerId(), seat.getDisplayName(), seat.getSeatNumber(), seat.isAi(), seat.getPersonaId(), seat.getAvatar()))
                .toList();
        if (players.size() < 4) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "至少需要4名玩家才能开始");
        }
        UndercoverWordPair pair = undercoverWordRepository.randomPair();

        List<GamePlayerState> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);

        int spyCount = resolveUndercoverCount(room, players.size());
        boolean hasBlank = resolveBoolean(room.getConfig().get("hasBlank"));
        for (int i = 0; i < shuffled.size(); i++) {
            GamePlayerState player = shuffled.get(i);
            if (i < spyCount) {
                player.setRole("UNDERCOVER");
                player.setWord(pair.getUndercover());
            } else if (hasBlank && i == shuffled.size() - 1) {
                player.setRole("BLANK");
                player.setWord("");
            } else {
                player.setRole("CIVILIAN");
                player.setWord(pair.getCivilian());
            }
            player.setAlive(true);
            player.setConnectionStatus(STATUS_ONLINE);
            player.setLastActiveAt(LocalDateTime.now());
            player.setDisconnectedAt(null);
        }

        GameState state = new GameState(room.getId(), room.getGameId(), PHASE_DESCRIPTION);
        state.setPlayers(shuffled.stream().map(p -> {
            GamePlayerState copy = new GamePlayerState(p.getPlayerId(), p.getDisplayName(), p.getSeatNumber(), p.isAi(), p.getPersonaId(), p.getAvatar());
            copy.setRole(p.getRole());
            copy.setWord(p.getWord());
            copy.setAlive(true);
            copy.setConnectionStatus(p.getConnectionStatus());
            copy.setLastActiveAt(p.getLastActiveAt());
            copy.setDisconnectedAt(p.getDisconnectedAt());
            return copy;
        }).toList());
        state.setCurrentSeat(aliveSeats(state).stream().findFirst().orElse(null));
        state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(resolveSpeakTime(room)));
        Map<String, Object> data = new HashMap<>();
        data.put("speakers", new ArrayList<String>());
        data.put("votes", new HashMap<String, String>());
        data.put("civilianWord", pair.getCivilian());
        data.put("undercoverWord", pair.getUndercover());
        data.put("hasBlank", hasBlank);
        state.setData(data);
        state.setLogs(new ArrayList<>(List.of(new GameLogEntry("system", "游戏开始，词语已发放"))));
        roomService.updateStatus(room.getId(), RoomStatus.PLAYING);
        autoAdvanceUndercover(state, room);
        return gameStateRepository.save(state);
    }

    private GameState startWerewolf(Room room) {
        List<GamePlayerState> players = room.getSeats().stream()
                .map(seat -> new GamePlayerState(seat.getPlayerId(), seat.getDisplayName(), seat.getSeatNumber(), seat.isAi(), seat.getPersonaId(), seat.getAvatar()))
                .toList();
        if (players.size() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "狼人杀至少需要6名玩家");
        }
        List<String> roles = buildWerewolfRoles(players.size(), room.getConfig());
        Collections.shuffle(roles);
        for (int i = 0; i < players.size(); i++) {
            players.get(i).setRole(roles.get(i));
            players.get(i).setAlive(true);
            players.get(i).setConnectionStatus(STATUS_ONLINE);
            players.get(i).setLastActiveAt(LocalDateTime.now());
            players.get(i).setDisconnectedAt(null);
        }

        GameState state = new GameState(room.getId(), room.getGameId(), PHASE_NIGHT);
        state.setPlayers(players);
        state.setCurrentSeat(null);
        Map<String, Object> data = new HashMap<>();
        data.put("wolfTarget", null);
        data.put("seerTarget", null);
        data.put("witchSaveTarget", null);
        data.put("witchPoisonTarget", null);
        data.put("witchAntidoteUsed", false);
        data.put("witchPoisonUsed", false);
        data.put("seerResults", new HashMap<String, String>());
        data.put("speakers", new ArrayList<String>());
        data.put("votes", new HashMap<String, String>());
        state.setData(data);
        state.setLogs(new ArrayList<>(List.of(new GameLogEntry("system", "天黑请闭眼，狼人行动中"))));
        state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(resolveNightTime(room)));
        roomService.updateStatus(room.getId(), RoomStatus.PLAYING);
        autoFillNightActions(state, room);
        resolveNight(state, room, false);
        return gameStateRepository.save(state);
    }

    private void speakUndercover(GameState state, Room room, String actorId, String content) {
        ensurePhase(state, PHASE_DESCRIPTION);
        GamePlayerState current = currentSpeaker(state);
        if (current == null || !current.getPlayerId().equals(actorId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前不需要你发言");
        }
        addLog(state, "speak", current.getDisplayName() + "：" + content);
        addSpeaker(state, actorId);
        moveUndercoverToNextSpeaker(state, room);
        autoAdvanceUndercover(state, room);
    }

    private void voteUndercover(GameState state, Room room, String actorId, VoteRequest request) {
        ensurePhase(state, PHASE_VOTING);
        GamePlayerState player = playerById(state, actorId);
        if (player == null || !player.isAlive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "你已出局，无法投票");
        }
        Map<String, String> votes = voteMap(state);
        if (votes.containsKey(actorId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "已完成投票");
        }
        if (!request.isAbstain()) {
            GamePlayerState target = playerById(state, request.getTargetPlayerId());
            if (target == null || !target.isAlive()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "非法的投票目标");
            }
        }
        votes.put(actorId, request.isAbstain() ? "abstain" : request.getTargetPlayerId());
        state.getData().put("votes", votes);
        addLog(state, "vote", player.getDisplayName() + " 已提交投票");
        autoAdvanceUndercover(state, room);
    }

    private void speakWerewolf(GameState state, Room room, String actorId, String content) {
        ensurePhase(state, PHASE_DAY_DISCUSS);
        GamePlayerState current = currentSpeaker(state);
        if (current == null || !current.getPlayerId().equals(actorId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前不需要你发言");
        }
        addLog(state, "speak", current.getDisplayName() + "：" + content);
        addSpeaker(state, actorId);
        moveWerewolfToNextSpeaker(state, room);
        autoAdvanceWerewolfDay(state, room);
    }

    private void voteWerewolf(GameState state, Room room, String actorId, VoteRequest request) {
        ensurePhase(state, PHASE_DAY_VOTE);
        GamePlayerState player = playerById(state, actorId);
        if (player == null || !player.isAlive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "你已出局，无法投票");
        }
        Map<String, String> votes = voteMap(state);
        if (votes.containsKey(actorId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "已完成投票");
        }
        if (!request.isAbstain()) {
            GamePlayerState target = playerById(state, request.getTargetPlayerId());
            if (target == null || !target.isAlive()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "非法的投票目标");
            }
        }
        votes.put(actorId, request.isAbstain() ? "abstain" : request.getTargetPlayerId());
        state.getData().put("votes", votes);
        addLog(state, "vote", player.getDisplayName() + " 已提交投票");
        resolveWerewolfVoting(state, room, false);
    }

    private void setNightAction(GameState state, Room room, String actorId, NightActionRequest request) {
        ensurePhase(state, PHASE_NIGHT);
        GamePlayerState actor = playerById(state, actorId);
        if (actor == null || !actor.isAlive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "你已出局，无法行动");
        }
        String action = request.getAction();
        Map<String, Object> data = state.getData();
        switch (action) {
            case "WOLF_KILL" -> {
                ensureRole(actor, "WEREWOLF");
                validateTargetAlive(state, request.getTargetPlayerId());
                data.put("wolfTarget", request.getTargetPlayerId());
                addLog(state, "night", "狼人阵营已锁定目标");
            }
            case "SEER_CHECK" -> {
                ensureRole(actor, "SEER");
                validateTargetAlive(state, request.getTargetPlayerId());
                data.put("seerTarget", request.getTargetPlayerId());
                Map<String, String> seerResults = seerResultMap(state);
                GamePlayerState target = playerById(state, request.getTargetPlayerId());
                seerResults.put(actor.getPlayerId(), target.getRole().startsWith("WEREWOLF") ? "WOLF" : "GOOD");
            }
            case "WITCH_SAVE" -> {
                ensureRole(actor, "WITCH");
                if (Boolean.TRUE.equals(data.getOrDefault("witchAntidoteUsed", false))) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "解药已用完");
                }
                data.put("witchSaveTarget", request.isUseHeal() ? data.get("wolfTarget") : null);
                data.put("witchAntidoteUsed", request.isUseHeal());
            }
            case "WITCH_POISON" -> {
                ensureRole(actor, "WITCH");
                if (Boolean.TRUE.equals(data.getOrDefault("witchPoisonUsed", false))) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "毒药已用完");
                }
                validateTargetAlive(state, request.getTargetPlayerId());
                data.put("witchPoisonTarget", request.getTargetPlayerId());
                data.put("witchPoisonUsed", true);
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "未知夜晚指令");
        }
        autoFillNightActions(state, room);
        resolveNight(state, room, false);
    }

    private boolean resolveUndercoverVoting(GameState state, Room room, boolean forceTimeout) {
        if (!PHASE_VOTING.equals(state.getPhase())) {
            return false;
        }
        Map<String, String> votes = voteMap(state);
        int alive = (int) state.getPlayers().stream().filter(GamePlayerState::isAlive).count();
        if (!forceTimeout && votes.size() < alive) {
            return false;
        }
        Map<String, Long> countMap = votes.values().stream()
                .filter(v -> !"abstain".equals(v))
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()));
        Optional<Map.Entry<String, Long>> winner = countMap.entrySet().stream().max(Map.Entry.comparingByValue());
        if (winner.isEmpty()) {
            addLog(state, "vote", "本轮平票，无人出局");
            startNewUndercoverRound(state, room);
            return true;
        }
        GamePlayerState eliminated = playerById(state, winner.get().getKey());
        if (eliminated != null) {
            eliminated.setAlive(false);
            addLog(state, "vote", eliminated.getDisplayName() + " 出局，身份为 " + eliminated.getRole());
        }
        if (checkUndercoverWinner(state)) {
            finishGame(state, room, determineUndercoverWinners(state));
        } else {
            startNewUndercoverRound(state, room);
        }
        return true;
    }

    private void startNewUndercoverRound(GameState state, Room room) {
        state.setRoundNumber(state.getRoundNumber() + 1);
        state.setPhase(PHASE_DESCRIPTION);
        state.setCurrentSeat(aliveSeats(state).stream().findFirst().orElse(null));
        state.getData().put("speakers", new ArrayList<String>());
        state.getData().put("votes", new HashMap<String, String>());
        state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(resolveSpeakTime(room)));
        addLog(state, "system", "进入第 " + state.getRoundNumber() + " 轮描述");
        autoAdvanceUndercover(state, room);
    }

    private boolean resolveWerewolfVoting(GameState state, Room room, boolean forceTimeout) {
        if (!PHASE_DAY_VOTE.equals(state.getPhase())) {
            return false;
        }
        Map<String, String> votes = voteMap(state);
        int alive = (int) state.getPlayers().stream().filter(GamePlayerState::isAlive).count();
        if (!forceTimeout && votes.size() < alive) {
            return false;
        }
        Map<String, Long> countMap = votes.values().stream()
                .filter(v -> !"abstain".equals(v))
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()));
        Optional<Map.Entry<String, Long>> winner = countMap.entrySet().stream().max(Map.Entry.comparingByValue());
        if (winner.isEmpty()) {
            addLog(state, "vote", "白天平票，无人出局");
            transitionToNight(state, room);
            return true;
        }
        GamePlayerState eliminated = playerById(state, winner.get().getKey());
        if (eliminated != null) {
            eliminated.setAlive(false);
            addLog(state, "vote", eliminated.getDisplayName() + " 被放逐，身份为 " + eliminated.getRole());
        }
        if (checkWerewolfWinner(state)) {
            finishGame(state, room, determineWerewolfWinners(state));
        } else {
            transitionToNight(state, room);
        }
        return true;
    }

    private void transitionToNight(GameState state, Room room) {
        state.setPhase(PHASE_NIGHT);
        state.setCurrentSeat(null);
        state.getData().put("speakers", new ArrayList<String>());
        state.getData().put("votes", new HashMap<String, String>());
        state.getData().put("wolfTarget", null);
        state.getData().put("seerTarget", null);
        state.getData().put("witchSaveTarget", null);
        state.getData().put("witchPoisonTarget", null);
        state.setRoundNumber(state.getRoundNumber() + 1);
        state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(resolveNightTime(room)));
        addLog(state, "system", "天黑请闭眼，第 " + state.getRoundNumber() + " 夜开始");
        autoFillNightActions(state, room);
        resolveNight(state, room, false);
    }

    private boolean resolveNight(GameState state, Room room, boolean forceTimeout) {
        if (!PHASE_NIGHT.equals(state.getPhase())) {
            return false;
        }
        Map<String, Object> data = state.getData();
        if (!forceTimeout && nightActionsPending(state)) {
            return false;
        }
        Set<String> deaths = new HashSet<>();
        String wolfTarget = (String) data.get("wolfTarget");
        if (wolfTarget != null) {
            deaths.add(wolfTarget);
        }

        if (Boolean.TRUE.equals(data.getOrDefault("witchAntidoteUsed", false))) {
            String saveTarget = (String) data.get("witchSaveTarget");
            deaths.remove(saveTarget);
        }

        String poisonTarget = (String) data.get("witchPoisonTarget");
        if (poisonTarget != null) {
            deaths.add(poisonTarget);
        }

        if (deaths.isEmpty()) {
            addLog(state, "night", "平安夜");
        } else {
            for (String playerId : deaths) {
                GamePlayerState victim = playerById(state, playerId);
                if (victim != null) {
                    victim.setAlive(false);
                    addLog(state, "night", victim.getDisplayName() + " 今晚死亡，身份为 " + victim.getRole());
                }
            }
        }

        if (checkWerewolfWinner(state)) {
            finishGame(state, room, determineWerewolfWinners(state));
            return true;
        }

        state.setPhase(PHASE_DAY_DISCUSS);
        state.setCurrentSeat(aliveSeats(state).stream().findFirst().orElse(null));
        state.getData().put("speakers", new ArrayList<String>());
        state.getData().put("votes", new HashMap<String, String>());
        state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(resolveDaySpeakTime(room)));
        addLog(state, "system", "天亮了，开始讨论阶段");
        autoAdvanceWerewolfDay(state, room);
        return true;
    }

    /**
     * 当玩家在白天阶段尝试执行夜晚操作时，填充缺失投票并强制结算，确保流程能够进入夜晚。
     */
    private boolean fastForwardToNight(GameState state, Room room) {
        boolean changed = false;
        if (PHASE_DAY_DISCUSS.equals(state.getPhase())) {
            changed = autoAdvanceWerewolfDay(state, room) || changed;
        }
        if (PHASE_DAY_VOTE.equals(state.getPhase())) {
            Map<String, String> votes = voteMap(state);
            List<GamePlayerState> alivePlayers = state.getPlayers().stream().filter(GamePlayerState::isAlive).toList();
            for (GamePlayerState p : alivePlayers) {
                if (!votes.containsKey(p.getPlayerId())) {
                    votes.put(p.getPlayerId(), "abstain");
                    addLog(state, "vote", p.getDisplayName() + " 未在时限内投票，视为弃票");
                    changed = true;
                }
            }
            state.getData().put("votes", votes);
            changed = resolveWerewolfVoting(state, room, true) || changed;
        }
        return changed;
    }

    private boolean autoAdvanceUndercover(GameState state, Room room) {
        boolean changed = false;

        if (PHASE_DESCRIPTION.equals(state.getPhase())) {
            GamePlayerState speaker = currentSpeaker(state);
            if (speaker != null && !speakerList(state).contains(speaker.getPlayerId())) {
                if (speaker.isAi()) {
                    AiDecisionResult decision = aiDecisionService.generateSpeech(state, speaker);
                    addLog(state, "speak", speaker.getDisplayName() + "（AI）：" + decision.content(), decision.logMetadata());
                    addSpeaker(state, speaker.getPlayerId());
                    moveUndercoverToNextSpeaker(state, room);
                    changed = true;
                } else if (isPhaseTimeout(state.getPhaseEndsAt())) {
                    if (isPlayerDisconnected(speaker)) {
                        markAiTakeover(state, speaker);
                        AiDecisionResult decision = aiDecisionService.generateSpeech(state, speaker);
                        addLog(state, "speak", speaker.getDisplayName() + "（托管）：" + decision.content(), decision.logMetadata());
                    } else {
                        addLog(state, "system", speaker.getDisplayName() + " 发言超时，自动跳过");
                    }
                    addSpeaker(state, speaker.getPlayerId());
                    moveUndercoverToNextSpeaker(state, room);
                    changed = true;
                }
            }
        }

        if (PHASE_VOTING.equals(state.getPhase())) {
            changed = fillDisconnectedVotes(state, false) || changed;
            boolean aiVoted = fillAiVotes(state);
            if (allAliveVoted(state)) {
                changed = resolveUndercoverVoting(state, room, false) || changed || aiVoted;
            } else if (isPhaseTimeout(state.getPhaseEndsAt())) {
                changed = resolveUndercoverVoting(state, room, true) || changed || aiVoted;
            } else {
                changed = aiVoted || changed;
            }
        }

        return changed;
    }

    private boolean autoAdvanceWerewolfDay(GameState state, Room room) {
        boolean changed = true;
        boolean anyChange = false;
        while (changed) {
            changed = false;
            if (PHASE_DAY_DISCUSS.equals(state.getPhase())) {
                GamePlayerState speaker = currentSpeaker(state);
                if (speaker != null && !speakerList(state).contains(speaker.getPlayerId())) {
                    if (speaker.isAi()) {
                        AiDecisionResult decision = aiDecisionService.generateSpeech(state, speaker);
                        addLog(state, "speak", speaker.getDisplayName() + "（AI）：" + decision.content(), decision.logMetadata());
                        addSpeaker(state, speaker.getPlayerId());
                        moveWerewolfToNextSpeaker(state, room);
                        changed = true;
                    } else if (isPhaseTimeout(state.getPhaseEndsAt())) {
                        if (isPlayerDisconnected(speaker)) {
                            markAiTakeover(state, speaker);
                            AiDecisionResult decision = aiDecisionService.generateSpeech(state, speaker);
                            addLog(state, "speak", speaker.getDisplayName() + "（托管）：" + decision.content(), decision.logMetadata());
                        } else {
                            addLog(state, "system", speaker.getDisplayName() + " 发言超时，自动跳过");
                        }
                        addSpeaker(state, speaker.getPlayerId());
                        moveWerewolfToNextSpeaker(state, room);
                        changed = true;
                    }
                }
            }
            if (PHASE_DAY_VOTE.equals(state.getPhase())) {
                changed = fillDisconnectedVotes(state, true) || changed;
                boolean aiVoted = fillAiVotes(state);
                if (allAliveVoted(state)) {
                    changed = resolveWerewolfVoting(state, room, false) || changed || aiVoted;
                } else if (isPhaseTimeout(state.getPhaseEndsAt())) {
                    changed = resolveWerewolfVoting(state, room, true) || changed || aiVoted;
                } else {
                    changed = aiVoted || changed;
                }
            }
            anyChange = anyChange || changed;
        }
        return anyChange;
    }

    private void moveUndercoverToNextSpeaker(GameState state, Room room) {
        List<Integer> aliveSeats = aliveSeats(state);
        if (aliveSeats.isEmpty()) {
            return;
        }
        List<String> speakers = speakerList(state);
        if (speakers.size() >= aliveSeats.size()) {
            state.setPhase(PHASE_VOTING);
            state.setCurrentSeat(null);
            state.getData().put("votes", new HashMap<String, String>());
            state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(resolveVoteTime(room)));
            addLog(state, "system", "进入投票阶段");
            return;
        }
        Integer current = state.getCurrentSeat();
        int idx = current == null ? 0 : aliveSeats.indexOf(current) + 1;
        if (idx >= aliveSeats.size()) {
            state.setPhase(PHASE_VOTING);
            state.setCurrentSeat(null);
            state.getData().put("votes", new HashMap<String, String>());
            state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(resolveVoteTime(room)));
            addLog(state, "system", "进入投票阶段");
            return;
        }
        state.setCurrentSeat(aliveSeats.get(idx));
        state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(resolveSpeakTime(room)));
    }

    private void moveWerewolfToNextSpeaker(GameState state, Room room) {
        List<Integer> aliveSeats = aliveSeats(state);
        if (aliveSeats.isEmpty()) {
            return;
        }
        List<String> speakers = speakerList(state);
        if (speakers.size() >= aliveSeats.size()) {
            state.setPhase(PHASE_DAY_VOTE);
            state.setCurrentSeat(null);
            state.getData().put("votes", new HashMap<String, String>());
            state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(resolveVoteTime(room)));
            addLog(state, "system", "开始投票");
            return;
        }
        Integer current = state.getCurrentSeat();
        int idx = current == null ? 0 : aliveSeats.indexOf(current) + 1;
        if (idx >= aliveSeats.size()) {
            state.setPhase(PHASE_DAY_VOTE);
            state.setCurrentSeat(null);
            state.getData().put("votes", new HashMap<String, String>());
            state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(resolveVoteTime(room)));
            addLog(state, "system", "开始投票");
            return;
        }
        state.setCurrentSeat(aliveSeats.get(idx));
        state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(resolveDaySpeakTime(room)));
    }

    private void addSpeaker(GameState state, String playerId) {
        List<String> speakers = speakerList(state);
        if (!speakers.contains(playerId)) {
            speakers.add(playerId);
            state.getData().put("speakers", speakers);
        }
    }

    private List<String> speakerList(GameState state) {
        Object val = state.getData().get("speakers");
        if (val instanceof List<?> list) {
            return new ArrayList<>(list.stream().map(Object::toString).toList());
        }
        return new ArrayList<>();
    }

    private boolean fillDisconnectedVotes(GameState state, boolean werewolfMode) {
        Map<String, String> votes = voteMap(state);
        boolean changed = false;
        for (GamePlayerState player : state.getPlayers()) {
            if (!player.isAlive() || player.isAi() || votes.containsKey(player.getPlayerId())) {
                continue;
            }
            if (!isPlayerDisconnected(player)) {
                continue;
            }
            votes.put(player.getPlayerId(), "abstain");
            String modePrefix = werewolfMode ? "放逐投票" : "卧底投票";
            addLog(state, "vote", player.getDisplayName() + " 离线，" + modePrefix + "自动弃票");
            changed = true;
        }
        if (changed) {
            state.getData().put("votes", votes);
        }
        return changed;
    }

    private void markAiTakeover(GameState state, GamePlayerState player) {
        player.setConnectionStatus(STATUS_AI_TAKEOVER);
        player.setDisconnectedAt(LocalDateTime.now());
        addLog(state, "system", player.getDisplayName() + " 暂时离线，AI 接管操作");
    }

    private boolean isPlayerDisconnected(GamePlayerState player) {
        return STATUS_DISCONNECTED.equals(player.getConnectionStatus()) || STATUS_AI_TAKEOVER.equals(player.getConnectionStatus());
    }

    private boolean hasConnectedHumanRole(GameState state, String role) {
        return state.getPlayers().stream()
                .anyMatch(p -> p.isAlive() && !p.isAi() && role.equals(p.getRole()) && !isPlayerDisconnected(p));
    }

    private boolean hasConnectedHumanWolf(GameState state) {
        return state.getPlayers().stream()
                .anyMatch(p -> p.isAlive() && !p.isAi() && p.getRole() != null && p.getRole().startsWith("WEREWOLF") && !isPlayerDisconnected(p));
    }

    private boolean isPhaseTimeout(LocalDateTime phaseEndsAt) {
        return phaseEndsAt != null && LocalDateTime.now().isAfter(phaseEndsAt);
    }

    private boolean fillAiVotes(GameState state) {
        Map<String, String> votes = voteMap(state);
        List<GamePlayerState> alivePlayers = state.getPlayers().stream().filter(GamePlayerState::isAlive).toList();
        boolean changed = false;
        for (GamePlayerState player : alivePlayers) {
            if (!player.isAi() || votes.containsKey(player.getPlayerId())) {
                continue;
            }
            List<GamePlayerState> targets = alivePlayers.stream().filter(p -> !p.getPlayerId().equals(player.getPlayerId())).toList();
            if (targets.isEmpty()) {
                continue;
            }
            AiDecisionResult decision = aiDecisionService.decideVote(state, player);
            String decidedTargetPlayerId = decision.targetPlayerId();
            String targetPlayerId = decidedTargetPlayerId;
            if (!StringUtils.hasText(decidedTargetPlayerId) || targets.stream().noneMatch(t -> t.getPlayerId().equals(decidedTargetPlayerId))) {
                targetPlayerId = targets.stream()
                        .min(Comparator.comparingInt(GamePlayerState::getSeatNumber))
                        .map(GamePlayerState::getPlayerId)
                        .orElse(null);
            }
            if (!StringUtils.hasText(targetPlayerId)) {
                continue;
            }
            votes.put(player.getPlayerId(), targetPlayerId);
            addLog(state, "vote", buildAiVoteLog(player.getDisplayName()), decision.logMetadata());
            changed = true;
        }
        state.getData().put("votes", votes);
        return changed;
    }

    private boolean allAliveVoted(GameState state) {
        Map<String, String> votes = voteMap(state);
        long alive = state.getPlayers().stream().filter(GamePlayerState::isAlive).count();
        return votes.size() >= alive;
    }

    private Map<String, String> voteMap(GameState state) {
        Object map = state.getData().get("votes");
        if (map instanceof Map<?, ?> raw) {
            Map<String, String> result = new HashMap<>();
            raw.forEach((k, v) -> result.put(k.toString(), v != null ? v.toString() : null));
            return result;
        }
        Map<String, String> votes = new HashMap<>();
        state.getData().put("votes", votes);
        return votes;
    }

    private Map<String, String> seerResultMap(GameState state) {
        Object map = state.getData().get("seerResults");
        if (map instanceof Map<?, ?> raw) {
            Map<String, String> result = new HashMap<>();
            raw.forEach((k, v) -> result.put(k.toString(), v != null ? v.toString() : null));
            return result;
        }
        Map<String, String> res = new HashMap<>();
        state.getData().put("seerResults", res);
        return res;
    }

    private boolean nightActionsPending(GameState state) {
        Map<String, Object> data = state.getData();
        boolean hasWolf = hasConnectedHumanWolf(state);
        boolean hasSeer = hasConnectedHumanRole(state, "SEER");
        boolean hasWitch = hasConnectedHumanRole(state, "WITCH");
        boolean wolfDone = data.get("wolfTarget") != null;
        boolean seerDone = data.get("seerTarget") != null;
        boolean witchDone = Boolean.TRUE.equals(data.getOrDefault("witchAntidoteUsed", false))
                || data.get("witchSaveTarget") != null
                || Boolean.TRUE.equals(data.getOrDefault("witchPoisonUsed", false))
                || data.get("witchPoisonTarget") != null;

        if (hasWolf && !wolfDone) return true;
        if (hasSeer && !seerDone) return true;
        if (hasWitch && !witchDone) return true;
        return false;
    }

    private void autoFillNightActions(GameState state, Room room) {
        Map<String, Object> data = state.getData();
        if (data.get("wolfTarget") == null && !hasConnectedHumanWolf(state)) {
            List<GamePlayerState> wolves = state.getPlayers().stream().filter(p -> p.isAlive() && p.isAi() && p.getRole().startsWith("WEREWOLF")).toList();
            if (!wolves.isEmpty()) {
                AiDecisionResult decision = aiDecisionService.decideNightAction(state, wolves.get(0));
                if ("WOLF_KILL".equals(decision.action()) && StringUtils.hasText(decision.targetPlayerId())) {
                    data.put("wolfTarget", decision.targetPlayerId());
                }
            }
        }
        if (data.get("seerTarget") == null && !hasConnectedHumanRole(state, "SEER")) {
            state.getPlayers().stream().filter(p -> p.isAlive() && p.isAi() && "SEER".equals(p.getRole())).findFirst().ifPresent(seer -> {
                AiDecisionResult decision = aiDecisionService.decideNightAction(state, seer);
                GamePlayerState target = playerById(state, decision.targetPlayerId());
                if ("SEER_CHECK".equals(decision.action()) && target != null && target.isAlive() && !target.getPlayerId().equals(seer.getPlayerId())) {
                    data.put("seerTarget", target.getPlayerId());
                    Map<String, String> res = seerResultMap(state);
                    res.put(seer.getPlayerId(), target.getRole().startsWith("WEREWOLF") ? "WOLF" : "GOOD");
                    state.getData().put("seerResults", res);
                }
            });
        }
        if (!hasConnectedHumanRole(state, "WITCH")) {
            state.getPlayers().stream().filter(p -> p.isAlive() && p.isAi() && "WITCH".equals(p.getRole())).findFirst().ifPresent(witch -> {
                AiDecisionResult decision = aiDecisionService.decideNightAction(state, witch);
                if ("WITCH_SAVE".equals(decision.action()) && !Boolean.TRUE.equals(data.getOrDefault("witchAntidoteUsed", false)) && data.get("wolfTarget") != null && decision.useHeal()) {
                    data.put("witchSaveTarget", data.get("wolfTarget"));
                    data.put("witchAntidoteUsed", true);
                } else if ("WITCH_POISON".equals(decision.action()) && data.get("witchPoisonTarget") == null && !Boolean.TRUE.equals(data.getOrDefault("witchPoisonUsed", false))) {
                    GamePlayerState target = playerById(state, decision.targetPlayerId());
                    if (target != null && target.isAlive() && !target.getPlayerId().equals(witch.getPlayerId())) {
                        data.put("witchPoisonTarget", target.getPlayerId());
                        data.put("witchPoisonUsed", true);
                    }
                }
            });
        }
    }

    private void finishGame(GameState state, Room room, Set<String> winnerIds) {
        state.setPhase(PHASE_SETTLEMENT);
        String winner = (String) state.getData().get("winner");
        if (winner == null) {
            boolean werewolfWin = winnerIds.stream().map(id -> playerById(state, id)).anyMatch(p -> p != null && p.getRole() != null && p.getRole().startsWith("WEREWOLF"));
            boolean undercoverWin = winnerIds.stream().map(id -> playerById(state, id)).anyMatch(p -> p != null && "UNDERCOVER".equals(p.getRole()));
            if (werewolfWin) {
                winner = "WEREWOLF";
            } else if (undercoverWin) {
                winner = "UNDERCOVER";
            } else {
                winner = "CIVILIAN";
            }
        }
        state.getData().put("winner", winner);
        String winnerText = switch (winner) {
            case "WEREWOLF" -> "狼人阵营";
            case "UNDERCOVER" -> "卧底阵营";
            default -> "好人/平民阵营";
        };
        addLog(state, "system", "游戏结束，获胜方：" + winnerText);
        state.setPhaseEndsAt(null);
        if (!Boolean.TRUE.equals(state.getData().get("statsRecorded"))) {
            statsService.recordResult(room.getGameId(), state.getPlayers(), winnerIds);
            state.getData().put("statsRecorded", true);
        }
        roomService.updateStatus(room.getId(), RoomStatus.WAITING);
        pushStateEvent(room.getId(), "SETTLEMENT", state);
    }

    private Set<String> determineUndercoverWinners(GameState state) {
        boolean undercoverWin = determineWinnerFlag(state);
        return state.getPlayers().stream()
                .filter(p -> p.isAlive() || PHASE_SETTLEMENT.equals(state.getPhase()))
                .filter(p -> undercoverWin ? "UNDERCOVER".equals(p.getRole()) : !"UNDERCOVER".equals(p.getRole()))
                .map(GamePlayerState::getPlayerId)
                .collect(Collectors.toSet());
    }

    private boolean determineWinnerFlag(GameState state) {
        long undercoverAlive = state.getPlayers().stream().filter(p -> p.isAlive() && "UNDERCOVER".equals(p.getRole())).count();
        long civilians = state.getPlayers().stream().filter(p -> p.isAlive() && !"UNDERCOVER".equals(p.getRole())).count();
        return undercoverAlive > 0 && undercoverAlive >= civilians;
    }

    private Set<String> determineWerewolfWinners(GameState state) {
        boolean werewolfWin = werewolfWinCondition(state);
        return state.getPlayers().stream()
                .filter(p -> werewolfWin ? p.getRole().startsWith("WEREWOLF") : !p.getRole().startsWith("WEREWOLF"))
                .map(GamePlayerState::getPlayerId)
                .collect(Collectors.toSet());
    }

    private boolean werewolfWinCondition(GameState state) {
        long wolves = state.getPlayers().stream().filter(p -> p.isAlive() && p.getRole().startsWith("WEREWOLF")).count();
        long others = state.getPlayers().stream().filter(p -> p.isAlive() && !p.getRole().startsWith("WEREWOLF")).count();
        return wolves > 0 && wolves >= others;
    }

    private boolean checkUndercoverWinner(GameState state) {
        if (state.getPlayers().stream().noneMatch(p -> p.isAlive() && "UNDERCOVER".equals(p.getRole()))) {
            return true;
        }
        return determineWinnerFlag(state);
    }

    private boolean checkWerewolfWinner(GameState state) {
        if (state.getPlayers().stream().noneMatch(p -> p.isAlive() && p.getRole().startsWith("WEREWOLF"))) {
            return true;
        }
        return werewolfWinCondition(state);
    }

    private boolean syncConnectionStatus(GameState state, String viewerId) {
        boolean changed = false;
        for (GamePlayerState player : state.getPlayers()) {
            if (player.isAi()) {
                if (!STATUS_ONLINE.equals(player.getConnectionStatus())) {
                    player.setConnectionStatus(STATUS_ONLINE);
                    changed = true;
                }
                continue;
            }
            boolean online = playerConnectionService.isOnline(player.getPlayerId());
            if (online) {
                if (STATUS_AI_TAKEOVER.equals(player.getConnectionStatus()) && player.getPlayerId().equals(viewerId)) {
                    player.setConnectionStatus(STATUS_ONLINE);
                    player.setLastActiveAt(LocalDateTime.now());
                    player.setDisconnectedAt(null);
                    addLog(state, "system", player.getDisplayName() + " 已回归");
                    changed = true;
                } else if (!STATUS_ONLINE.equals(player.getConnectionStatus())) {
                    player.setConnectionStatus(STATUS_ONLINE);
                    player.setLastActiveAt(LocalDateTime.now());
                    player.setDisconnectedAt(null);
                    changed = true;
                }
                continue;
            }
            if (!StringUtils.hasText(player.getConnectionStatus())) {
                player.setConnectionStatus(STATUS_ONLINE);
                player.setLastActiveAt(LocalDateTime.now());
                changed = true;
                continue;
            }
            if (player.getLastActiveAt() != null && Duration.between(player.getLastActiveAt(), LocalDateTime.now()).compareTo(disconnectThreshold) < 0) {
                continue;
            }
            if (!STATUS_AI_TAKEOVER.equals(player.getConnectionStatus()) && !STATUS_DISCONNECTED.equals(player.getConnectionStatus())) {
                player.setConnectionStatus(STATUS_DISCONNECTED);
                player.setDisconnectedAt(LocalDateTime.now());
                changed = true;
            }
        }
        return changed;
    }

    private void pushStateEvent(String roomId, String type, GameState state) {
        if (state == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("updatedAt", state.getUpdatedAt() != null ? state.getUpdatedAt().toString() : null);
        payload.put("logs", state.getLogs() != null ? state.getLogs().size() : 0);
        gamePushService.pushStateChange(roomId, new GameStateEvent(type, state.getPhase(), state.getRoundNumber(), state.getCurrentSeat(), payload));
    }

    private void pushPrivateAssignments(GameState state) {
        for (GamePlayerState player : state.getPlayers()) {
            if (!StringUtils.hasText(player.getPlayerId())) {
                continue;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("role", player.getRole());
            payload.put("word", player.getWord());
            payload.put("seatNumber", player.getSeatNumber());
            gamePushService.pushPrivate(player.getPlayerId(), new PrivateEvent("ROLE_ASSIGNED", payload));
        }
    }

    private void markPlayerOnline(GameState state, String playerId) {
        GamePlayerState player = playerById(state, playerId);
        if (player == null) {
            return;
        }
        player.setConnectionStatus(STATUS_ONLINE);
        player.setLastActiveAt(LocalDateTime.now());
        player.setDisconnectedAt(null);
    }

    private void ensurePhase(GameState state, String phase) {
        if (!phase.equals(state.getPhase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前阶段不支持该操作");
        }
    }

    private void ensureRole(GamePlayerState player, String role) {
        if (!role.equals(player.getRole())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前角色不可执行该操作");
        }
    }

    private void validateTargetAlive(GameState state, String playerId) {
        GamePlayerState target = playerById(state, playerId);
        if (target == null || !target.isAlive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "目标无效");
        }
    }

    private String resolvePlayerId(Room room, User user, String playerIdHeader) {
        if (user != null) {
            return user.getId();
        }
        if (playerIdHeader != null && room.getSeats().stream().anyMatch(s -> playerIdHeader.equals(s.getPlayerId()))) {
            return playerIdHeader;
        }
        return null;
    }

    private String requirePlayer(Room room, User user, String playerIdHeader) {
        String id = resolvePlayerId(room, user, playerIdHeader);
        if (id == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未找到你的座位信息");
        }
        return id;
    }

    private boolean isHost(Room room, String playerId) {
        if (playerId == null) return false;
        return room.getSeats().stream().anyMatch(s -> s.getPlayerId().equals(playerId) && s.isHost());
    }

    private GamePlayerState currentSpeaker(GameState state) {
        Integer seat = state.getCurrentSeat();
        if (seat == null) return null;
        return state.getPlayers().stream().filter(p -> p.getSeatNumber() == seat && p.isAlive()).findFirst().orElse(null);
    }

    private GamePlayerState playerById(GameState state, String playerId) {
        if (playerId == null) return null;
        return state.getPlayers().stream().filter(p -> playerId.equals(p.getPlayerId())).findFirst().orElse(null);
    }

    private List<Integer> aliveSeats(GameState state) {
        return state.getPlayers().stream().filter(GamePlayerState::isAlive).sorted(Comparator.comparingInt(GamePlayerState::getSeatNumber)).map(GamePlayerState::getSeatNumber).toList();
    }

    private int resolveSpeakTime(Room room) {
        Object value = room.getConfig().get("speakTime");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {}
        }
        return 60;
    }

    private int resolveVoteTime(Room room) {
        return 30;
    }

    private int resolveNightTime(Room room) {
        return 30;
    }

    private int resolveDaySpeakTime(Room room) {
        Object value = room.getConfig().get("speechTime");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {}
        }
        return 90;
    }

    private int resolveUndercoverCount(Room room, int playerCount) {
        Object mode = room.getConfig().get("spyMode");
        if ("manual".equals(mode)) {
            Object manual = room.getConfig().get("spyCount");
            if (manual instanceof Number n) {
                return Math.max(1, n.intValue());
            }
        }
        if (playerCount <= 6) return 1;
        return 2;
    }

    private boolean resolveBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    private void addLog(GameState state, String type, String message) {
        addLog(state, type, message, null);
    }

    private void addLog(GameState state, String type, String message, Map<String, Object> metadata) {
        List<GameLogEntry> logs = state.getLogs() == null ? new ArrayList<>() : new ArrayList<>(state.getLogs());
        GameLogEntry entry = new GameLogEntry(type, message);
        entry.setPhase(state.getPhase());
        entry.setRoundNumber(state.getRoundNumber());
        entry.setMetadata(metadata);
        logs.add(entry);
        state.setLogs(logs);
    }

    private String buildAiDescription(GameState state, GamePlayerState player) {
        return aiDecisionService.generateSpeech(state, player).content();
    }

    private String buildAiSuspicion(GameState state, GamePlayerState player) {
        return aiDecisionService.generateSpeech(state, player).content();
    }

    private String buildAiVoteLog(String displayName) {
        return formatTemplate(promptProperties.getAiTalk().getVoteLogTemplate(), "%s（AI）已完成投票", displayName);
    }

    private String formatTemplate(String template, String fallback, Object... args) {
        String effective = StringUtils.hasText(template) ? template : fallback;
        try {
            return String.format(effective, args);
        } catch (IllegalFormatException e) {
            return String.format(fallback, args);
        }
    }

    private List<String> buildWerewolfRoles(int playerSize, Map<String, Object> config) {
        List<String> roles = new ArrayList<>();
        if (playerSize <= 6) {
            roles.addAll(List.of("WEREWOLF", "WEREWOLF", "SEER", "WITCH", "HUNTER"));
            while (roles.size() < playerSize) roles.add("VILLAGER");
            return roles;
        }
        if (playerSize <= 9) {
            roles.addAll(List.of("WEREWOLF", "WEREWOLF", "WEREWOLF", "SEER", "WITCH", "HUNTER"));
            while (roles.size() < playerSize) roles.add("VILLAGER");
            return roles;
        }
        roles.addAll(List.of("WEREWOLF", "WEREWOLF", "WEREWOLF", "WEREWOLF", "SEER", "WITCH", "HUNTER"));
        while (roles.size() < playerSize) roles.add("VILLAGER");
        return roles;
    }

    private GameStateResponse buildWaitingResponse(Room room, String viewerId) {
        List<GamePlayerView> players = room.getSeats().stream()
                .map(seat -> new GamePlayerView(seat.getPlayerId(), seat.getDisplayName(), seat.getSeatNumber(), seat.isAi(), seat.getPersonaId(), seat.getAvatar(), true, null, null, STATUS_ONLINE))
                .toList();
        return new GameStateResponse(room.getId(), room.getGameId(), PHASE_WAITING, 0, null, null, null, viewerId, null, null, null, null, players, List.of(), Map.of("roomStatus", room.getStatus().name()), Map.of(), null);
    }

    private GameStateResponse buildResponse(String gameId, Room room, GameState state, String viewerId) {
        List<GamePlayerView> players = state.getPlayers().stream()
                .map(p -> {
                    boolean reveal = PHASE_SETTLEMENT.equals(state.getPhase());
                    String role = reveal ? p.getRole() : (viewerId != null && viewerId.equals(p.getPlayerId()) ? p.getRole() : null);
                    String word = reveal ? p.getWord() : (viewerId != null && viewerId.equals(p.getPlayerId()) ? p.getWord() : null);
                    return new GamePlayerView(p.getPlayerId(), p.getDisplayName(), p.getSeatNumber(), p.isAi(), p.getPersonaId(), p.getAvatar(), p.isAlive(), role, word, p.getConnectionStatus());
                }).toList();

        GamePlayerState me = viewerId == null ? null : playerById(state, viewerId);
        String winner = (String) state.getData().get("winner");
        String currentSpeaker = state.getCurrentSeat() == null ? null : players.stream().filter(p -> p.isAlive() && p.getSeatNumber() == state.getCurrentSeat()).map(GamePlayerView::getDisplayName).findFirst().orElse(null);

        Map<String, Object> extra = new HashMap<>();
        extra.put("civilianWord", PHASE_SETTLEMENT.equals(state.getPhase()) ? state.getData().get("civilianWord") : null);
        extra.put("undercoverWord", PHASE_SETTLEMENT.equals(state.getPhase()) ? state.getData().get("undercoverWord") : null);
        if ("werewolf".equals(gameId)) {
            extra.put("seerResults", seerResultMap(state));
            extra.put("lastNightDeaths", state.getLogs().stream()
                    .filter(log -> "night".equals(log.getType()) && log.getMessage().contains("死亡"))
                    .map(GameLogEntry::getMessage)
                    .toList());
        }

        PendingAction pendingAction = null;
        if ("werewolf".equals(gameId) && PHASE_NIGHT.equals(state.getPhase()) && me != null && me.isAlive()) {
            Map<String, Object> data = state.getData();
            if (me.getRole().startsWith("WEREWOLF") && data.get("wolfTarget") == null) {
                pendingAction = new PendingAction("WOLF_KILL", "请选择今晚要刀的目标", secondsLeft(state.getPhaseEndsAt()));
            } else if ("SEER".equals(me.getRole()) && data.get("seerTarget") == null) {
                pendingAction = new PendingAction("SEER_CHECK", "请选择查验的玩家", secondsLeft(state.getPhaseEndsAt()));
            } else if ("WITCH".equals(me.getRole()) && (!Boolean.TRUE.equals(data.getOrDefault("witchAntidoteUsed", false)) || !Boolean.TRUE.equals(data.getOrDefault("witchPoisonUsed", false)))) {
                pendingAction = new PendingAction("WITCH", "使用解药或毒药", secondsLeft(state.getPhaseEndsAt()));
            }
        }

        return new GameStateResponse(
                room.getId(),
                room.getGameId(),
                state.getPhase(),
                state.getRoundNumber(),
                state.getCurrentSeat(),
                currentSpeaker,
                winner,
                viewerId,
                me != null ? me.getSeatNumber() : null,
                me != null ? me.getWord() : null,
                me != null ? me.getRole() : null,
                state.getPhaseEndsAt(),
                players,
                state.getLogs(),
                extra,
                voteMap(state),
                pendingAction
        );
    }

    private long secondsLeft(LocalDateTime phaseEndsAt) {
        if (phaseEndsAt == null) return 0;
        return Math.max(0, Duration.between(LocalDateTime.now(), phaseEndsAt).toSeconds());
    }
}
