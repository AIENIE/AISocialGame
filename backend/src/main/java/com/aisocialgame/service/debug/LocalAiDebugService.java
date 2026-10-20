package com.aisocialgame.service.debug;

import com.aisocialgame.dto.*;
import com.aisocialgame.dto.LocalAiDebugView.*;
import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.AiTurnJob;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.RoomAccessPolicy;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;

@Service
@Transactional(readOnly = true)
public class LocalAiDebugService {
    private final LocalAiDebugRecorder recorder;
    private final RoomAccessPolicy access;
    private final RoomRepository rooms;
    private final AiTurnJobRepository jobs;
    private final LocalAiDebugTurnRepository records;
    private final AiDecisionTraceRepository traces;

    public LocalAiDebugService(LocalAiDebugRecorder recorder, RoomAccessPolicy access, RoomRepository rooms,
                               AiTurnJobRepository jobs, LocalAiDebugTurnRepository records, AiDecisionTraceRepository traces) {
        this.recorder = recorder; this.access = access; this.rooms = rooms; this.jobs = jobs; this.records = records; this.traces = traces;
    }

    public boolean available(String roomId, String userId) {
        if (!recorder.enabled()) return false;
        try { access.requireParticipant(roomId, userId); return true; }
        catch (ApiException denied) { return false; }
    }

    private void require(String gameId, String roomId, String userId) {
        if (!recorder.enabled()) throw missing();
        access.requireParticipant(roomId, userId);
        if (!rooms.findById(roomId).map(r -> gameId.equals(r.getGameId())).orElse(false)) throw missing();
    }

    public PagedResponse<Instance> instances(String gameId, String roomId, String userId, int page, int size) {
        require(gameId, roomId, userId);
        var result = jobs.debugInstances(roomId, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))));
        return new PagedResponse<>(result.getContent().stream().map(i -> new Instance(i.getInstanceId(), i.getStartedAt(), i.getTurnCount())).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    public Summary summary(String gameId, String roomId, String userId, String instanceId) {
        require(gameId, roomId, userId);
        var turns = turns(roomId, instanceId);
        Map<String, List<Turn>> rounds = new LinkedHashMap<>();
        Map<String, Actor> actors = new LinkedHashMap<>();
        for (var turn : turns) {
            rounds.computeIfAbsent(turn.round() + ":" + turn.phase(), key -> new ArrayList<>()).add(turn);
            actors.putIfAbsent(turn.actorId(), new Actor(turn.actorId(), turn.actorName(), turn.seatNumber(), turn.roleKey()));
        }
        return new Summary(instanceId, usage(turns), rounds.values().stream().map(list ->
                new Round(list.getFirst().round(), list.getFirst().phase(), usage(list), (int) list.stream().filter(LocalAiDebugService::problem).count())).toList(),
                List.copyOf(actors.values()), recorder.captureWarning());
    }

    public PagedResponse<Turn> turns(String gameId, String roomId, String userId, String instanceId, Integer round,
                                     String phase, String actorId, String status, int page, int size) {
        require(gameId, roomId, userId);
        var items = turns(roomId, instanceId).stream().filter(t -> round == null || t.round() == round)
                .filter(t -> empty(phase) || phase.equals(t.phase())).filter(t -> empty(actorId) || actorId.equals(t.actorId()))
                .filter(t -> empty(status) || status.equals(t.status())).sorted(Comparator.comparing(Turn::createdAt).reversed().thenComparing(Turn::id)).toList();
        int limit = Math.max(1, Math.min(size, 100)); int current = Math.max(0, page);
        int from = (int) Math.min(items.size(), (long) current * limit);
        return new PagedResponse<>(items.subList(from, Math.min(items.size(), from + limit)), current, limit, items.size());
    }

    public Detail detail(String gameId, String roomId, String userId, String jobId) {
        require(gameId, roomId, userId);
        AiTurnJob job = jobs.findById(jobId).filter(j -> roomId.equals(j.getRoomId())).orElseThrow(LocalAiDebugService::missing);
        var all = turns(roomId, job.getInstanceId());
        Turn turn = all.stream().filter(t -> jobId.equals(t.id())).findFirst().orElseThrow(LocalAiDebugService::missing);
        var stored = records.findById(jobId).filter(t -> roomId.equals(t.roomId) && gameId.equals(t.gameId));
        if (stored.isPresent()) return new Detail(turn, stored.get().payload);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("legacy", true); data.put("observationSnapshot", job.getObservation());
        data.put("generationMetrics", job.getDiagnostics().getOrDefault("generation", Map.of()));
        traces.findByRoomIdAndInstanceIdAndActorPlayerId(roomId, job.getInstanceId(), job.getActorId()).stream()
                .filter(t -> jobId.equals(t.getQuality().get("jobId"))).findFirst().ifPresent(trace -> {
                    data.put("outputSummary", trace.getOutputSummary() == null ? "" : trace.getOutputSummary());
                    data.put("parsedOutput", trace.getRawOutput());
                    data.put("inputSummary", trace.getInputSummary() == null ? "" : trace.getInputSummary());
                    data.put("beliefSnapshot", trace.getBeliefSnapshot()); data.put("memorySnapshot", trace.getMemorySnapshot());
                });
        return new Detail(turn, data);
    }

    private List<Turn> turns(String roomId, String instanceId) {
        if (empty(instanceId)) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择对局");
        var stored = new LinkedHashMap<String, Turn>();
        records.findByRoomIdAndInstanceIdOrderByCreatedAtAscIdAsc(roomId, instanceId).forEach(t -> stored.put(t.getId(), view(t)));
        List<Turn> result = new ArrayList<>();
        for (var ref : jobs.findByRoomIdAndInstanceIdOrderByCreatedAtAscIdAsc(roomId, instanceId)) {
            var known = stored.remove(ref.getId());
            if (known != null) result.add(withStatus(known, ref.getStatus(), RuleSupport.text(ref.getDiagnostics().get("terminalReason"))));
            else jobs.findById(ref.getId()).filter(j -> roomId.equals(j.getRoomId()) && instanceId.equals(j.getInstanceId()))
                    .ifPresent(j -> result.add(legacy(j)));
        }
        result.addAll(stored.values());
        result.sort(Comparator.comparing(Turn::createdAt).thenComparing(Turn::id));
        return result;
    }

    private static Turn view(LocalAiDebugTurnRepository.Metadata t) {
        return new Turn(t.getId(), t.getActorId(), t.getActorName(), t.getSeatNumber(), t.getRoleKey(), t.getRoundNumber(), t.getPhase(),
                t.getKind(), t.getStatus(), t.getTerminalReason(), t.getModelKey(), t.getPromptTokens(), t.getCompletionTokens(),
                t.getCacheReadTokens(), t.getCacheWriteTokens(), t.getReasoningTokens(), t.getUsageAuthoritative(), t.getUsageComplete(),
                t.getAttemptCount(), t.getResponseCount(), t.getLatencyMs(), t.getCreatedAt(), t.getUpdatedAt(), false);
    }
    private static Turn withStatus(Turn t, String status, String reason) {
        return new Turn(t.id(), t.actorId(), t.actorName(), t.seatNumber(), t.roleKey(), t.round(), t.phase(), t.kind(), status, reason,
                t.modelKey(), t.promptTokens(), t.completionTokens(), t.cacheReadTokens(), t.cacheWriteTokens(), t.reasoningTokens(),
                t.usageAuthoritative(), t.usageComplete(), t.attemptCount(), t.responseCount(), t.latencyMs(), t.createdAt(), t.updatedAt(), t.legacy());
    }
    private static Turn legacy(AiTurnJob job) {
        var observation = job.getObservation(); var self = RuleSupport.map(observation.get("self"));
        var generation = RuleSupport.map(job.getDiagnostics().get("generation"));
        String name = RuleSupport.text(self.get("displayName"));
        return new Turn(job.getId(), job.getActorId(), name.isBlank() ? job.getActorId() : name,
                self.get("seatNumber") instanceof Number n ? n.intValue() : null, RuleSupport.text(self.get("role")),
                RuleSupport.number(observation.get("round"), 0), RuleSupport.text(observation.get("phase")), job.getKind(), job.getStatus(),
                RuleSupport.text(job.getDiagnostics().get("terminalReason")), RuleSupport.text(generation.get("modelKey")),
                legacyCount(generation, "promptTokens"), legacyCount(generation, "completionTokens"), null, null, null, null,
                Boolean.TRUE.equals(generation.get("usageComplete")) && legacyCount(generation, "promptTokens") != null && legacyCount(generation, "completionTokens") != null, RuleSupport.number(generation.get("calls"), 0),
                RuleSupport.number(generation.get("responsesObserved"), 0), count(generation, "latencyMs"), job.getCreatedAt(),
                job.getCompletedAt() == null ? job.getCreatedAt() : job.getCompletedAt(), true);
    }
    private static Long legacyCount(Map<String, Object> source, String field) {
        Long value = count(source, field); return value == null || value == 0 ? null : value;
    }
    private static Long count(Map<String, Object> source, String field) { return source.get(field) instanceof Number n ? n.longValue() : null; }
    static Usage usage(List<Turn> turns) {
        boolean complete = !turns.isEmpty() && turns.stream().allMatch(Turn::usageComplete);
        Boolean authoritative = turns.isEmpty() || turns.stream().anyMatch(t -> t.usageAuthoritative() == null) ? null
                : turns.stream().allMatch(t -> Boolean.TRUE.equals(t.usageAuthoritative()));
        return new Usage(turns.size(), turns.stream().mapToInt(Turn::attemptCount).sum(), turns.stream().mapToInt(Turn::responseCount).sum(),
                sum(turns, Turn::promptTokens, false), sum(turns, Turn::completionTokens, false), sum(turns, Turn::cacheReadTokens, true),
                sum(turns, Turn::cacheWriteTokens, true), sum(turns, Turn::reasoningTokens, true), complete, authoritative);
    }
    private static Long sum(List<Turn> turns, Function<Turn, Long> field, boolean all) {
        if (turns.isEmpty() || all && turns.stream().anyMatch(t -> field.apply(t) == null)) return null;
        var values = turns.stream().map(field).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).sum();
    }
    private static boolean problem(Turn turn) { return Set.of("FAILED", "DISCARDED").contains(turn.status()) || turn.attemptCount() > 1; }
    private static boolean empty(String value) { return value == null || value.isBlank(); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "调试记录不存在"); }
}
