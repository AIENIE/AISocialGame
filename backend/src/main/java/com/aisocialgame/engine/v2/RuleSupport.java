package com.aisocialgame.engine.v2;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.GameLogEntry;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;
import java.util.*;

public final class RuleSupport {
    public static final String HOST = "$host";
    private RuleSupport() {}

    public static GameState newState(Room room, LocalDateTime now) {
        GameState state = new GameState(room.getId(), room.getGameId(), "WAITING");
        state.setPlayers(new ArrayList<>(room.getSeats().stream().sorted(Comparator.comparingInt(s -> s.getSeatNumber()))
                .map(s -> {
                    GamePlayerState p = new GamePlayerState(s.getPlayerId(), s.getDisplayName(), s.getSeatNumber(), s.isAi(), s.getPersonaId(), s.getAvatar());
                    p.setConnectionStatus("ONLINE"); p.setLastActiveAt(now); return p;
                }).toList()));
        state.getData().put("ruleVersion", 2);
        state.getData().put("promptVersion", com.aisocialgame.service.ai.v2.AiTurnGenerator.PROMPT_VERSION);
        state.getData().put("archiveId", "archive-" + UUID.randomUUID());
        state.getData().put("randomSeed", UUID.randomUUID().getMostSignificantBits());
        state.getData().put("rules", new LinkedHashMap<>(room.getConfig()));
        state.getData().put("events", new ArrayList<>());
        // New V2 instances write each event to game_events in the same transaction.
        // Legacy instances have no marker and must verify table completeness first.
        state.getData().put("eventTableComplete", true);
        state.getData().put("phaseSerial", 0);
        return state;
    }
    public static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m) m.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }
    public static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list.stream().filter(Map.class::isInstance).map(RuleSupport::map).toList()) : new ArrayList<>();
    }
    public static List<String> strings(Object value) {
        return value instanceof Collection<?> list ? list.stream().filter(Objects::nonNull).map(Object::toString).toList() : List.of();
    }
    public static int number(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { return fallback; }
    }
    public static String text(Object value) { return value == null ? "" : value.toString(); }
    public static boolean flag(Object value) { return Boolean.TRUE.equals(value) || "true".equals(value); }
    public static GamePlayerState player(GameState state, String id) {
        return state.getPlayers().stream().filter(p -> Objects.equals(p.getPlayerId(), id)).findFirst()
                .orElseThrow(() -> bad("你不在本局玩家席位中"));
    }
    public static List<GamePlayerState> alive(GameState state) {
        return state.getPlayers().stream().filter(GamePlayerState::isAlive).sorted(Comparator.comparingInt(GamePlayerState::getSeatNumber)).toList();
    }
    public static boolean automated(GamePlayerState player) {
        return player.isAi() || "AI_TAKEOVER".equals(player.getConnectionStatus());
    }
    public static void phase(GameState state, String phase, Integer seat, int seconds, LocalDateTime now) {
        state.setPhase(phase); state.setCurrentSeat(seat);
        state.setPhaseEndsAt(seconds > 0 ? now.plusSeconds(seconds) : null);
        state.getData().put("phaseSerial", number(state.getData().get("phaseSerial"), 0) + 1);
    }
    public static boolean expired(GameState state, LocalDateTime now) {
        return state.getPhaseEndsAt() != null && !now.isBefore(state.getPhaseEndsAt());
    }
    public static TurnRequest turn(GameState state, String actorId, String kind) {
        return turn(state, actorId, kind, "");
    }
    public static TurnRequest turn(GameState state, String actorId, String kind, String suffix) {
        String key = text(state.getData().get("archiveId")) + ":" + number(state.getData().get("phaseSerial"), 0)
                + ":" + state.getRoundNumber() + ":" + actorId + ":" + kind + ":" + suffix;
        return new TurnRequest(actorId, kind, key);
    }
    public static void event(GameState state, String type, String actorId, String targetId, String message, Map<String, Object> data) {
        event(state, type, actorId, targetId, message, data, "PUBLIC", List.of());
    }
    public static void event(GameState state, String type, String actorId, String targetId, String message,
                             Map<String, Object> data, String visibility, List<String> recipients) {
        Map<String, Object> event = new LinkedHashMap<>();
        String id = UUID.randomUUID().toString();
        event.put("eventId", id); event.put("type", type); event.put("actorId", actorId); event.put("targetId", targetId);
        event.put("message", message); event.put("phase", state.getPhase()); event.put("round", state.getRoundNumber());
        event.put("visibility", visibility); event.put("visibleTo", recipients); event.put("data", data == null ? Map.of() : new LinkedHashMap<>(data));
        event.put("time", LocalDateTime.now().toString());
        List<Map<String, Object>> events = maps(state.getData().get("events")); events.add(event); state.getData().put("events", events);
        if ("PUBLIC".equals(visibility) && message != null && !message.isBlank()) {
            GameLogEntry log = new GameLogEntry(type, message);
            log.setActorId(actorId); log.setTargetId(targetId); log.setRoundNumber(state.getRoundNumber()); log.setPhase(state.getPhase());
            log.setMetadata(com.aisocialgame.model.PublicLogMetadata.project(type, data, id, null));
            state.getLogs().add(log);
        }
    }
    public static void finish(GameState state, String winner, Collection<String> winnerIds) {
        state.getData().put("winner", winner); state.getData().put("winnerIds", new ArrayList<>(winnerIds));
        phase(state, "SETTLEMENT", null, 0, LocalDateTime.now());
        event(state, "settlement", null, null, "对局结束：" + winner, Map.of("winner", winner, "winnerIds", new ArrayList<>(winnerIds)));
    }
    public static String requireContent(PlayerAction action, int max) {
        String value = text(action.getContent()).strip();
        if (value.isBlank() || value.codePointCount(0, value.length()) > max) throw bad("发言需为1-" + max + "字");
        return value;
    }
    public static PlayerAction action(String type, String content, String target) {
        PlayerAction result = new PlayerAction(); result.setType(type); result.setContent(content); result.setTargetPlayerId(target); return result;
    }
    public static ApiException bad(String message) { return bad(message, "INVALID_ACTION"); }
    public static ApiException bad(String message, String code) { return new ApiException(HttpStatus.BAD_REQUEST, message, code, Map.of()); }
    public static void require(boolean condition, String message, String code) { if (!condition) throw bad(message, code); }
    public static void require(boolean condition, String message) { if (!condition) throw bad(message); }
}
