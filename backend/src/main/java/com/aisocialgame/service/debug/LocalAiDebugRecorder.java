package com.aisocialgame.service.debug;

import com.aisocialgame.adminauth.AdminAuthPolicy;
import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.integration.grpc.dto.*;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.LocalAiDebugTurnRepository;
import com.aisocialgame.service.ai.config.AiModelSelection;
import com.aisocialgame.service.ai.v2.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import fireflychat.ai.v1.ChatCompletionsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.*;

/** Private diagnostic persistence. Failure here must never retry or change gameplay. */
@Service
public class LocalAiDebugRecorder {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LocalAiDebugRecorder.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final LocalAiDebugTurnRepository records;
    private final AdminAuthPolicy policy;
    private final TransactionTemplate transaction;
    private volatile String captureWarning;

    public LocalAiDebugRecorder(LocalAiDebugTurnRepository records, AdminAuthPolicy policy, PlatformTransactionManager manager) {
        this.records = records; this.policy = policy;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean enabled() { return AdminAuthPolicy.ENV_LOCAL.equals(policy.environment()); }
    public String captureWarning() { return captureWarning; }

    public void queued(AiTurnJob job) {
        if (!enabled()) return;
        try {
            var observation = JSON.convertValue(job.getObservation(), VisibleObservation.class);
            LocalAiDebugTurn initial = initial(job.getRoomId(), observation, job.getId());
            initial.createdAt = job.getCreatedAt();
            afterCommit(() -> write(job.getId(), () -> initial, row -> {}));
        } catch (RuntimeException failure) { captureFailed(job.getId(), failure); }
    }

    public void begin(String roomId, VisibleObservation observation, String jobId, int attempt,
                      List<AiChatMessageDto> messages, AiModelSelection selection, long timeoutMs) {
        if (roomId == null) return;
        write(jobId, () -> initial(roomId, observation, jobId), row -> {
            row.status = "RUNNING"; row.modelKey = selection.modelKey();
            row.payload.put("modelSelection", selection.toMap());
            var sample = attempt(row, attempt);
            sample.put("requestId", jobId + ":" + attempt);
            sample.put("messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());
            sample.put("state", "REQUESTING"); sample.put("requestTimeoutMs", timeoutMs);
            sample.putIfAbsent("startedAt", System.currentTimeMillis());
            sample.remove("flags"); sample.remove("failureStage");
            putAttempt(row, attempt, sample);
        });
    }

    public void response(String requestId, ChatCompletionsResponse response) { response(requestId, response, null); }

    public void response(String requestId, ChatCompletionsResponse response, Boolean budgetUsageAuthoritative) {
        if (!enabled()) return;
        int split = requestId.lastIndexOf(':');
        if (split < 1) return;
        int attempt;
        try { attempt = Integer.parseInt(requestId.substring(split + 1)); } catch (NumberFormatException invalid) { return; }
        int index = attempt;
        write(requestId.substring(0, split), () -> null, row -> {
            var sample = attempt(row, index);
            sample.put("requestId", requestId); sample.put("state", "RESPONSE");
            sample.put("receivedAt", System.currentTimeMillis()); sample.put("rawOutput", response.getContent());
            sample.put("modelKey", response.getModelKey()); row.modelKey = response.getModelKey();
            Boolean authority = response.hasUsageAuthoritative() ? Boolean.valueOf(response.getUsageAuthoritative()) : budgetUsageAuthoritative;
            if (authority != null) sample.put("usageAuthoritative", authority);
            boolean usageSourceKnown = sample.get("usageAuthoritative") instanceof Boolean;
            // Legacy scalar defaults cannot distinguish omitted usage from a real zero.
            if (usageSourceKnown || response.getPromptTokens() != 0) sample.put("promptTokens", response.getPromptTokens());
            if (usageSourceKnown || response.getCompletionTokens() != 0) sample.put("completionTokens", response.getCompletionTokens());
            if (response.hasReasoningTokens()) sample.put("reasoningTokens", response.getReasoningTokens());
            if (response.hasFinishReason()) sample.put("finishReason", response.getFinishReason());
            if (response.hasDebugInfo()) {
                var debug = response.getDebugInfo();
                sample.put("reasoningText", debug.getReasoningText()); sample.put("reasoningKind", debug.getReasoningKind().name());
                if (debug.hasCacheReadTokens()) sample.put("cacheReadTokens", debug.getCacheReadTokens());
                if (debug.hasCacheWriteTokens()) sample.put("cacheWriteTokens", debug.getCacheWriteTokens());
            } else sample.put("reasoningKind", "GATEWAY_NOT_PROVIDED");
            if (!sample.containsKey("cacheReadTokens") && response.getCacheTokens() > 0) sample.put("cacheReadTokens", response.getCacheTokens());
            putAttempt(row, index, sample);
        });
    }

    public void parsed(String jobId, int index, Map<String, Object> output) {
        write(jobId, () -> null, row -> {
            var sample = attempt(row, index); sample.put("parsedOutput", output); putAttempt(row, index, sample);
        });
    }

    public void evaluated(String jobId, int index, Map<String, Object> metrics, String stage) {
        write(jobId, () -> null, row -> {
            var sample = attempt(row, index);
            sample.put("flags", RuleSupport.strings(metrics.get("flags")));
            if (metrics.containsKey("rpcMs")) sample.put("latencyMs", metrics.get("rpcMs"));
            if (metrics.containsKey("failureStage")) sample.put("failureStage", stage);
            for (String key : List.of("budgetId", "settlementState", "parseMs", "validationMs"))
                if (metrics.containsKey(key)) sample.put(key, metrics.get(key));
            if (!sample.containsKey("receivedAt")) {
                List<String> flags = RuleSupport.strings(metrics.get("flags"));
                sample.put("state", flags.contains("AI_BUDGET_BUSY") ? "WAITING_BUDGET"
                        : flags.stream().anyMatch(Set.of("AI_CREDIT_INSUFFICIENT", "AI_REQUEST_NOT_SENT", "BUDGET_UNAVAILABLE", "INVALID_MODEL_CONFIG")::contains) ? "NOT_SENT" : "ERROR");
            }
            putAttempt(row, index, sample);
        });
    }

    public void finished(AiTurnJob job) {
        String id = job.getId(), status = job.getStatus();
        String reason = RuleSupport.text(job.getDiagnostics().get("terminalReason"));
        afterCommit(() -> write(id, () -> null, row -> { row.status = status; row.terminalReason = reason; }));
    }

    public void applied(String jobId, AiTurnDecision decision) {
        if (!enabled()) return;
        try {
            var action = RuleSupport.map(JSON.convertValue(decision.action(), Map.class));
            afterCommit(() -> write(jobId, () -> null, row -> {
                row.payload.put("appliedAction", action); row.payload.put("fallback", decision.fallback());
            }));
        } catch (RuntimeException failure) { captureFailed(jobId, failure); }
    }

    private static LocalAiDebugTurn initial(String roomId, VisibleObservation observation, String jobId) {
        LocalAiDebugTurn row = new LocalAiDebugTurn();
        row.id = jobId; row.roomId = roomId; row.gameId = observation.gameId(); row.instanceId = observation.instanceId();
        row.actorId = observation.actorId(); row.actorName = RuleSupport.text(observation.self().get("displayName"));
        if (row.actorName.isBlank()) row.actorName = RuleSupport.HOST.equals(row.actorId) ? "AI 主持人" : row.actorId;
        if (observation.self().get("seatNumber") instanceof Number seat) row.seatNumber = seat.intValue();
        row.roleKey = RuleSupport.text(observation.self().get("role")); row.phase = observation.phase();
        row.roundNumber = observation.round(); row.kind = observation.turnKind(); row.status = "QUEUED";
        row.createdAt = LocalDateTime.now(); row.updatedAt = row.createdAt;
        row.payload.put("actor", observation.self()); row.payload.put("persona", observation.persona());
        row.payload.put("promptVersion", AiTurnGenerator.PROMPT_VERSION);
        row.payload.put("inputFormatVersion", AiPromptProjection.VERSION);
        row.payload.put("attempts", new LinkedHashMap<String, Object>());
        return row;
    }

    private void write(String id, Supplier<LocalAiDebugTurn> initial, Consumer<LocalAiDebugTurn> operation) {
        if (!enabled()) return;
        try {
            transaction.executeWithoutResult(tx -> {
                LocalAiDebugTurn row = records.lock(id).orElseGet(initial);
                if (row == null) return;
                operation.accept(row); recompute(row); row.updatedAt = LocalDateTime.now(); records.saveAndFlush(row);
            });
        } catch (RuntimeException failure) {
            captureFailed(id, failure);
        }
    }

    private void captureFailed(String id, RuntimeException failure) {
        captureWarning = "CAPTURE_FAILED";
        LOG.warn("Local AI diagnostic capture failed jobId={} errorType={}", id, failure.getClass().getSimpleName());
    }

    private static Map<String, Object> attempt(LocalAiDebugTurn row, int index) {
        return new LinkedHashMap<>(RuleSupport.map(RuleSupport.map(row.payload.get("attempts")).get(String.valueOf(index))));
    }
    private static void putAttempt(LocalAiDebugTurn row, int index, Map<String, Object> sample) {
        var attempts = new LinkedHashMap<>(RuleSupport.map(row.payload.get("attempts")));
        sample.put("attempt", index); attempts.put(String.valueOf(index), sample); row.payload.put("attempts", attempts);
    }

    static void recompute(LocalAiDebugTurn row) {
        List<Map<String, Object>> attempts = RuleSupport.map(row.payload.get("attempts")).values().stream()
                .map(RuleSupport::map).toList();
        row.attemptCount = attempts.size();
        row.responseCount = (int) attempts.stream().filter(a -> a.containsKey("receivedAt")).count();
        var billable = attempts.stream().filter(a -> !Set.of("NOT_SENT", "WAITING_BUDGET").contains(a.getOrDefault("state", ""))).toList();
        row.usageComplete = !billable.isEmpty() && billable.stream().allMatch(a -> a.containsKey("promptTokens") && a.containsKey("completionTokens"));
        row.promptTokens = sum(billable, "promptTokens", false); row.completionTokens = sum(billable, "completionTokens", false);
        row.cacheReadTokens = sum(billable, "cacheReadTokens", true); row.cacheWriteTokens = sum(billable, "cacheWriteTokens", true);
        row.reasoningTokens = sum(billable, "reasoningTokens", true); row.latencyMs = sum(attempts, "latencyMs", false);
        row.usageAuthoritative = billable.isEmpty() || billable.stream().anyMatch(a -> !a.containsKey("usageAuthoritative")) ? null
                : billable.stream().allMatch(a -> Boolean.TRUE.equals(a.get("usageAuthoritative")));
    }
    private static Long sum(List<Map<String, Object>> attempts, String field, boolean requireAll) {
        if (attempts.isEmpty() || requireAll && attempts.stream().anyMatch(a -> !(a.get(field) instanceof Number))) return null;
        var values = attempts.stream().map(a -> a.get(field)).filter(Number.class::isInstance).map(Number.class::cast).toList();
        return values.isEmpty() ? null : values.stream().mapToLong(Number::longValue).sum();
    }
    private void afterCommit(Runnable operation) {
        if (!enabled()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { operation.run(); }
            });
        else operation.run();
    }
}
