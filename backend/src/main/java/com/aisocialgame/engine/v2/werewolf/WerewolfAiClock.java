package com.aisocialgame.engine.v2.werewolf;

import com.aisocialgame.engine.v2.TurnRequest;
import com.aisocialgame.model.GameState;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** One durable automated opportunity; queue time never consumes execution time. */
public final class WerewolfAiClock {
    public static final int QUEUE_SECONDS = 60;
    public static final int EXECUTION_SECONDS = 50;
    private static final String KEY = "werewolfAiClock";
    private WerewolfAiClock() {}

    public static boolean synchronize(GameState state, WerewolfRuleSet rules, LocalDateTime now) {
        var pending = rules.pendingTurns(state);
        var clock = map(state.getData().get(KEY));
        if (pending.stream().anyMatch(t -> t.key().equals(clock.get("turnKey")))) return false;
        if (pending.isEmpty()) return state.getData().remove(KEY) != null;
        var turn = pending.getFirst();
        var next = new LinkedHashMap<String,Object>();
        next.put("actorId", turn.actorId()); next.put("turnKey", turn.key()); next.put("kind", turn.kind());
        next.put("queueEndsAt", now.plusSeconds(QUEUE_SECONDS).toString());
        state.getData().put(KEY, next);
        return true;
    }

    public static LocalDateTime begin(GameState state, TurnRequest turn, LocalDateTime now) {
        var clock = map(state.getData().get(KEY));
        if (!turn.key().equals(clock.get("turnKey")) || expired(state, now)) return null;
        clock.putIfAbsent("executionEndsAt", now.plusSeconds(EXECUTION_SECONDS).toString());
        state.getData().put(KEY, clock);
        return deadline(state);
    }

    public static boolean owns(GameState state, String actorId) {
        return actorId.equals(map(state.getData().get(KEY)).get("actorId"));
    }
    public static TurnRequest turn(GameState state) {
        var clock = map(state.getData().get(KEY));
        return clock.isEmpty() ? null : new TurnRequest(text(clock.get("actorId")), text(clock.get("kind")), text(clock.get("turnKey")));
    }
    public static LocalDateTime deadline(GameState state) {
        var clock = map(state.getData().get(KEY));
        String value = text(clock.getOrDefault("executionEndsAt", clock.get("queueEndsAt")));
        return value.isBlank() ? null : LocalDateTime.parse(value);
    }
    public static boolean expired(GameState state, LocalDateTime now) {
        var end = deadline(state); return end != null && !now.isBefore(end);
    }
    public static boolean running(GameState state) { return map(state.getData().get(KEY)).containsKey("executionEndsAt"); }
    public static void clear(GameState state) { state.getData().remove(KEY); }
    public static void shift(GameState state, long millis) {
        var clock = map(state.getData().get(KEY));
        for (String key : new String[]{"queueEndsAt", "executionEndsAt"})
            if (clock.containsKey(key)) clock.put(key, LocalDateTime.parse(text(clock.get(key))).plusNanos(millis * 1_000_000).toString());
        if (!clock.isEmpty()) state.getData().put(KEY, clock);
    }
}
