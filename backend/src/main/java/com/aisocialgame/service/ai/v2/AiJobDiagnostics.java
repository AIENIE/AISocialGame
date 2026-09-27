package com.aisocialgame.service.ai.v2;

import com.aisocialgame.model.AiTurnJob;
import java.time.*;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Durable measurements only: never copy model output, observations, or exception messages. */
public final class AiJobDiagnostics {
    private AiJobDiagnostics() {}
    private static final Set<String> GENERATION_KEYS = Set.of("latencyMs", "calls", "promptTokens", "completionTokens", "usageComplete", "responsesObserved",
            "qualityFlags", "promptVersion", "repaired", "initialBudgetMs", "inputFormatVersion", "inputSectionBytes", "repairRemainingMs", "fallbackMs", "submissionFailedMs");
    private static final Set<String> ATTEMPT_KEYS = Set.of("attempt", "flags", "failureStage", "inputPreparationMs", "inputBytes", "requestTimeoutMs", "requestRemainingMs",
            "rpcMs", "parseMs", "validationMs", "promptTokens", "completionTokens", "outputBytes");

    public static Map<String, Object> generation(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        GENERATION_KEYS.forEach(k -> { if (source.containsKey(k)) result.put(k, source.get(k)); });
        if (source.containsKey("attempts")) result.put("attempts", maps(source.get("attempts")).stream().map(a -> {
            Map<String, Object> sample = new LinkedHashMap<>(); ATTEMPT_KEYS.forEach(k -> { if (a.containsKey(k)) sample.put(k, a.get(k)); }); return sample;
        }).toList());
        return result;
    }
    public static void initialize(AiTurnJob job, Instant now, ZoneId zone) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("formatVersion", 1); d.put("createdEpochMs", now.toEpochMilli()); d.put("clockSource", "UTC_EPOCH");
        d.put("runtimeZone", zone.getId()); d.put("promptVersion", AiTurnGenerator.PROMPT_VERSION);
        d.putAll(AiBuildIdentity.current());
        job.setDiagnostics(d);
    }
    public static void started(AiTurnJob job, Instant now) {
        Map<String, Object> d = job.getDiagnostics(); d.put("startedEpochMs", now.toEpochMilli());
        age(d, "queuedEpochMs", now).ifPresent(v -> d.put("queueMs", v));
    }
    public static OptionalLong age(Map<String, Object> d, String key, Instant now) {
        if (!(d.get(key) instanceof Number n)) return OptionalLong.empty();
        long elapsed = now.toEpochMilli() - n.longValue();
        if (elapsed < 0) { d.put("clockAnomaly", "CLOCK_MOVED_BACKWARD"); return OptionalLong.empty(); }
        return OptionalLong.of(elapsed);
    }
    public static boolean overdue(AiTurnJob job, Instant now, ZoneId legacyZone) {
        return overdue(job.getDiagnostics(), job.getStartedAt(), now, legacyZone);
    }
    public static boolean overdue(Map<String, Object> diagnostics, LocalDateTime legacyStarted, Instant now, ZoneId legacyZone) {
        if (diagnostics != null && diagnostics.get("startedEpochMs") instanceof Number)
            return age(new LinkedHashMap<>(diagnostics), "startedEpochMs", now).orElse(-1) > 60_000;
        return legacyStarted != null && legacyStarted.isBefore(LocalDateTime.ofInstant(now, legacyZone).minusSeconds(60));
    }
    public static void completed(AiTurnJob job, String reason, Instant now) {
        Map<String, Object> d = job.getDiagnostics();
        d.put("completedEpochMs", now.toEpochMilli()); d.put("terminalStatus", job.getStatus()); d.put("terminalReason", reason);
        age(d, "createdEpochMs", now).ifPresent(v -> d.put("elapsedBeforeCommitMs", v));
        d.put("completionStage", "BEFORE_COMMIT");
    }
}
