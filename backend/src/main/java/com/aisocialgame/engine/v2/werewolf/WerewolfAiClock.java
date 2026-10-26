package com.aisocialgame.engine.v2.werewolf;

import com.aisocialgame.engine.v2.TurnRequest;
import com.aisocialgame.model.GameState;
import com.aisocialgame.lab.LabCallRecovery;
import com.aisocialgame.lab.LabRunPolicy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** One durable automated opportunity; queue time never consumes execution time. */
public final class WerewolfAiClock {
    public static final int QUEUE_SECONDS = 60;
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
        next.put("queueEndsAt", now.plusSeconds(LabRunPolicy.pureWerewolfTraining(state) ? LabCallRecovery.MAX_WAIT_MS / 1000 : QUEUE_SECONDS).toString());
        state.getData().put(KEY, next);
        return true;
    }

    public static LocalDateTime begin(GameState state, TurnRequest turn, LocalDateTime now) {
        var clock = map(state.getData().get(KEY));
        if (!turn.key().equals(clock.get("turnKey"))) return null;
        if (LabRunPolicy.werewolfEvaluation(state) && waitingForOriginal(state))
            return recoveryExpired(state,now) ? null : now.plusSeconds(45);
        if (expired(state, now)) return null;
        if (waitingForOriginal(state)) return now.plusNanos(LabRunPolicy.TRAINING_DECISION_MILLIS * 1_000_000);
        long remaining = clock.get("executionRemainingMs") instanceof Number n ? n.longValue() : LabRunPolicy.decisionMillis(state);
        clock.putIfAbsent("executionEndsAt", now.plusNanos(remaining * 1_000_000).toString());
        clock.remove("executionRemainingMs"); clock.remove("awaitingOriginal");
        clock.remove("queueEndsAt");
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
    public static boolean waitingForOriginal(GameState state) {
        return (LabRunPolicy.pureWerewolfTraining(state) || LabRunPolicy.werewolfEvaluation(state)) && flag(map(state.getData().get(KEY)).get("awaitingOriginal"));
    }
    public static boolean recoveryExpired(GameState state,LocalDateTime now) {
        String end=text(map(state.getData().get(KEY)).get("recoveryEndsAt"));
        return !end.isBlank() && !now.isBefore(LocalDateTime.parse(end));
    }
    public static long remainingExecutionMillis(GameState state, LocalDateTime now) {
        var clock = map(state.getData().get(KEY));
        if (clock.get("executionRemainingMs") instanceof Number n) return Math.max(0, n.longValue());
        if (clock.containsKey("executionEndsAt")) return Math.max(0, Duration.between(now, LocalDateTime.parse(text(clock.get("executionEndsAt")))).toMillis());
        return LabRunPolicy.decisionMillis(state);
    }
    /** Persist remaining execution time before relinquishing the worker; queued/result waits do not spend it. */
    public static void suspend(GameState state, LocalDateTime now, boolean awaitingOriginal) {
        if (LabRunPolicy.werewolfEvaluation(state)) {
            var clock=map(state.getData().get(KEY));if(clock.isEmpty()) return;
            if(awaitingOriginal) {clock.put("awaitingOriginal",true);clock.putIfAbsent("recoveryEndsAt",now.plusNanos(LabCallRecovery.MAX_WAIT_MS*1_000_000).toString());}
            else {clock.remove("awaitingOriginal");clock.remove("recoveryEndsAt");}
            state.getData().put(KEY,clock);return;
        }
        if (!LabRunPolicy.pureWerewolfTraining(state)) return;
        var clock = map(state.getData().get(KEY));
        if (clock.isEmpty()) return;
        clock.put("executionRemainingMs", remainingExecutionMillis(state, now));
        clock.remove("executionEndsAt");
        clock.put("awaitingOriginal", awaitingOriginal);
        clock.putIfAbsent("queueEndsAt", now.plusNanos(LabCallRecovery.MAX_WAIT_MS * 1_000_000).toString());
        state.getData().put(KEY, clock);
    }
    public static void clear(GameState state) { state.getData().remove(KEY); }
    /** A recovered invalid reply may need a correction; only that new generation restarts the execution clock. */
    public static void resumeExecution(GameState state, LocalDateTime now, long remainingMillis) {
        if (!waitingForOriginal(state)) return;
        var clock=map(state.getData().get(KEY));
        if (LabRunPolicy.werewolfEvaluation(state)) {
            clock.remove("awaitingOriginal");clock.remove("recoveryEndsAt");state.getData().put(KEY,clock);return;
        }
        long remaining=Math.max(0,Math.min(remainingExecutionMillis(state,now),remainingMillis));
        clock.put("executionEndsAt",now.plusNanos(remaining*1_000_000).toString());
        clock.remove("executionRemainingMs");clock.remove("awaitingOriginal");clock.remove("queueEndsAt");
        state.getData().put(KEY,clock);
    }
    public static void resumeWaiting(GameState state, LocalDateTime now) {
        if(LabRunPolicy.werewolfEvaluation(state) && waitingForOriginal(state)) {
            var clock=map(state.getData().get(KEY));clock.put("recoveryEndsAt",now.plusNanos(LabCallRecovery.MAX_WAIT_MS*1_000_000).toString());state.getData().put(KEY,clock);return;
        }
        if (!LabRunPolicy.pureWerewolfTraining(state)) return;
        var clock = map(state.getData().get(KEY));
        if (!clock.isEmpty() && !clock.containsKey("executionEndsAt")) {
            clock.put("queueEndsAt",now.plusNanos(LabCallRecovery.MAX_WAIT_MS * 1_000_000).toString());
            state.getData().put(KEY,clock);
        }
    }
    public static void shift(GameState state, long millis) {
        var clock = map(state.getData().get(KEY));
        for (String key : new String[]{"queueEndsAt", "executionEndsAt", "recoveryEndsAt"})
            if (clock.containsKey(key)) clock.put(key, LocalDateTime.parse(text(clock.get(key))).plusNanos(millis * 1_000_000).toString());
        if (!clock.isEmpty()) state.getData().put(KEY, clock);
    }
}
