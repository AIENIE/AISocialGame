package com.aisocialgame.service.ai.v2;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.engine.v2.LegalAction;
import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatMessageDto;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.service.safety.*;
import com.aisocialgame.service.ai.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Remote work only; the caller must not hold a game transaction while invoking this service. */
@Service
public class AiTurnGenerator {
    public static final String PROMPT_VERSION = "social-v2.10";
    public static final String UNDERCOVER_PROMPT_VERSION = "undercover-v1.2";
    public static final long MAX_DECISION_MILLIS = 50_000;
    public static final long UNDERCOVER_DECISION_MILLIS = 89_000;
    private static final int MAX_REQUEST_SECONDS = 45;
    public static final Set<String> EMOTIONS = Set.of("neutral", "curious", "tense", "relieved", "uncertain", "amused", "determined", "disappointed");
    public static final Set<String> GESTURES = Set.of("none", "pause", "nod", "shake_head", "frown", "smile", "sigh", "lean_in");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AiGrpcClient client;
    private final AppProperties properties;
    private final AiMemoryServiceV2 memories;
    private final AiSafetyService safety;
    @Autowired private AiModelConfigService modelConfigs;
    @Autowired private com.aisocialgame.service.debug.LocalAiDebugRecorder debugRecorder;
    private final String playerSystem;
    private final String hostSystem;
    private final String undercoverSystem;
    private final LongSupplier nanoTime;

    public static String promptVersion(String gameId) { return "undercover".equals(gameId) ? UNDERCOVER_PROMPT_VERSION : PROMPT_VERSION; }

    @Autowired
    public AiTurnGenerator(AiGrpcClient client, AppProperties properties, AiMemoryServiceV2 memories, AiSafetyService safety) {
        this(client, properties, memories, safety, System::nanoTime);
    }
    AiTurnGenerator(AiGrpcClient client, AppProperties properties, AiMemoryServiceV2 memories, AiSafetyService safety, LongSupplier nanoTime) {
        this.client = client; this.properties = properties; this.memories = memories; this.safety = safety;
        this.nanoTime = Objects.requireNonNull(nanoTime);
        undercoverSystem = resource("game-knowledge/undercover-player-system.txt");
        playerSystem = resource("game-knowledge/social-player-system.txt"); hostSystem = resource("game-knowledge/turtle-host-system.txt");
    }
    public AiTurnDecision generate(GameAiAdapter adapter, VisibleObservation observation, String jobId) {
        return generate(adapter, observation, jobId, "undercover".equals(observation.gameId()) ? UNDERCOVER_DECISION_MILLIS : MAX_DECISION_MILLIS);
    }
    /** All attempts share one monotonic budget; a runtime caller may further restrict it. */
    public AiTurnDecision generate(GameAiAdapter adapter, VisibleObservation observation, String jobId, long remainingTurnMillis) {
        return generate(adapter, observation, jobId, remainingTurnMillis, () -> true);
    }
    public AiTurnDecision generate(GameAiAdapter adapter, VisibleObservation observation, String jobId, long remainingTurnMillis,
                                   java.util.function.BooleanSupplier turnCurrent) {
        return generate(adapter, observation, jobId, remainingTurnMillis, turnCurrent, Map.of());
    }
    public AiTurnDecision generate(GameAiAdapter adapter, VisibleObservation observation, String jobId, long remainingTurnMillis,
                                   java.util.function.BooleanSupplier turnCurrent, Map<String,Object> checkpoint) {
        return generate(adapter,observation,jobId,remainingTurnMillis,turnCurrent,checkpoint,saved -> {});
    }
    public AiTurnDecision generate(GameAiAdapter adapter, VisibleObservation observation, String jobId, long remainingTurnMillis,
                                   java.util.function.BooleanSupplier turnCurrent, Map<String,Object> checkpoint,
                                   java.util.function.Consumer<Map<String,Object>> saveCheckpoint) {
        boolean laboratory = !RuleSupport.map(observation.memory().get("lab")).isEmpty();
        boolean undercover = "undercover".equals(observation.gameId());
        long started = nanoTime.getAsLong();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(undercover ? UNDERCOVER_DECISION_MILLIS : MAX_DECISION_MILLIS, remainingTurnMillis)));
        List<String> failures = List.of();
        int promptTokens = 0, completionTokens = 0, calls = 0, responses = 0;
        String modelKey = "";
        Map<String, Object> lastOutput = Map.of(), basePayload = null;
        List<Map<String, Object>> attempts = new ArrayList<>();
        Map<String, Object> stages = new LinkedHashMap<>();
        AiModelSelection selected = checkpoint.containsKey("modelSelection")
                ? AiModelSelection.fromMap(RuleSupport.map(checkpoint.get("modelSelection")))
                : modelConfigs == null ? AiModelSelection.defaults(properties.getAi().getDefaultModel())
                : modelConfigs.resolve(observation.gameId(), observation.actorId());
        stages.put("modelSelection", selected.toMap());
        stages.put("initialBudgetMs", TimeUnit.NANOSECONDS.toMillis(budgetNanos));
        stages.put("inputFormatVersion", AiPromptProjection.version(observation.gameId()));
        stages.put("promptVersion", promptVersion(observation.gameId()));
        int resume = RuleSupport.number(checkpoint.get("nextAttempt"), 0);
        if (!checkpoint.isEmpty()) {
            calls = RuleSupport.number(checkpoint.get("calls"), 0); responses = RuleSupport.number(checkpoint.get("responses"), 0);
            promptTokens = RuleSupport.number(checkpoint.get("promptTokens"), 0); completionTokens = RuleSupport.number(checkpoint.get("completionTokens"), 0);
            modelKey = RuleSupport.text(checkpoint.get("modelKey")); lastOutput = RuleSupport.map(checkpoint.get("lastOutput"));
            failures = RuleSupport.strings(checkpoint.get("failures")); attempts.addAll(RuleSupport.maps(checkpoint.get("attempts")));
        }
        for (int attempt = resume; attempt < (undercover ? 4 : 2); attempt++) {
            if (undercover && !turnCurrent.getAsBoolean()) {
                failures = List.of("TURN_OBSOLETE"); break;
            }
            long remaining = remainingNanos(started, budgetNanos);
            if (attempt > 0) stages.put("repairRemainingMs", TimeUnit.NANOSECONDS.toMillis(remaining));
            if (remaining < TimeUnit.SECONDS.toNanos(attempt == 0 ? 1 : 5)) {
                failures = deadlineFailure(failures); break;
            }
            var transport = new LinkedHashMap<String,Object>();
            if (attempt == resume) for(String key:List.of("transportAttempt","waitingSinceEpochMs","retryAfterEpochMs","lastCallState"))
                if(checkpoint.containsKey(key)) transport.put(key,checkpoint.get(key));
            String requestId=com.aisocialgame.lab.LabCallRecovery.requestId(jobId+":"+attempt,transport);
            Map<String, Object> sample = new LinkedHashMap<>(); sample.put("attempt", attempt);
            sample.put("transportAttempt",RuleSupport.number(transport.get("transportAttempt"),0));
            attempts.add(sample);
            String stage = "INPUT_PREPARATION";
            String budgetDeferred = null;
            try {
                long preparation = nanoTime.getAsLong();
                Map<String, Object> payload;
                String user;
                try {
                    if (basePayload == null) {
                        basePayload = AiPromptProjection.project(adapter, observation);
                        Map<String, Object> sizes = new LinkedHashMap<>();
                        for (var entry : basePayload.entrySet()) sizes.put(entry.getKey(), JSON.writeValueAsBytes(entry.getValue()).length);
                        stages.put("inputSectionBytes", sizes);
                    }
                    payload = new LinkedHashMap<>(basePayload);
                    if (attempt > 0) {
                        Map<String, Object> previous = new LinkedHashMap<>(lastOutput);
                        if (undercover) previous.keySet().retainAll(Set.of("action", "speech", "presentation", "evidenceEventIds"));
                        payload.put("previousOutput", previous); payload.put("correction", correction(failures, undercover));
                        if (checkpoint.containsKey("forbiddenFeedback")) payload.put("forbiddenFeedback", checkpoint.get("forbiddenFeedback"));
                    }
                    user = JSON.writeValueAsString(payload);
                } finally { sample.put("inputPreparationMs", elapsed(preparation)); }
                String system = undercover ? undercoverSystem : RuleSupport.HOST.equals(observation.actorId()) ? hostSystem : playerSystem;
                List<AiChatMessageDto> messages = List.of(new AiChatMessageDto("system", system), new AiChatMessageDto("user", user));
                sample.put("inputBytes", system.getBytes(StandardCharsets.UTF_8).length + user.getBytes(StandardCharsets.UTF_8).length);
                int requestSeconds = (int) Math.min(undercover ? 89 : MAX_REQUEST_SECONDS, TimeUnit.NANOSECONDS.toSeconds(remainingNanos(started, budgetNanos)));
                if (requestSeconds <= 0) { failures = deadlineFailure(failures); break; }
                sample.put("requestTimeoutMs", requestSeconds * 1000);
                sample.put("requestRemainingMs", TimeUnit.NANOSECONDS.toMillis(remainingNanos(started, budgetNanos)));
                if (undercover && !turnCurrent.getAsBoolean()) { failures = List.of("TURN_OBSOLETE"); break; }
                sample.put("requestId", requestId);
                if (debugRecorder != null) debugRecorder.begin(AiCallScope.context(properties.getAi().getSystemUserId(), selected.modelKey()).getRoomId(),
                        observation, jobId, attempt, messages, selected, requestSeconds * 1000L);
                if(laboratory) saveCheckpoint.accept(checkpoint(attempt,calls,responses,promptTokens,completionTokens,modelKey,lastOutput,failures,
                        attempts.subList(0,attempts.size()-1),selected,checkpoint,transport));
                calls++;
                AiChatResult response;
                stage = "RPC";
                long rpc = nanoTime.getAsLong();
                try {
                    String session = observation.instanceId() + ":" + observation.actorId();
                    response = undercover ? client.undercoverChatCompletions(properties.getProjectKey(), properties.getAi().getSystemUserId(), session, selected,
                            messages, requestId, requestSeconds) : client.chatCompletions(properties.getProjectKey(), properties.getAi().getSystemUserId(), session, selected,
                            messages, requestId, requestSeconds);
                } finally { sample.put("rpcMs", elapsed(rpc)); }
                if (response.finishReason() != null) sample.put("finishReason", response.finishReason());
                if (response.reasoningTokens() != null) sample.put("reasoningTokens", response.reasoningTokens());
                if (response.budgetId() != null) sample.put("budgetId", response.budgetId());
                if (response.settlementState() != null) sample.put("settlementState", response.settlementState());
                stage = "PARSE";
                responses++;
                promptTokens += response.promptTokens(); completionTokens += response.completionTokens(); modelKey = response.modelKey();
                sample.put("promptTokens", response.promptTokens()); sample.put("completionTokens", response.completionTokens());
                String raw = response.content() == null ? "" : response.content().strip();
                sample.put("outputBytes", (response.content() == null ? "" : response.content()).getBytes(StandardCharsets.UTF_8).length);
                if (undercover && Set.of("length","incomplete").contains(RuleSupport.text(response.finishReason()).toLowerCase(Locale.ROOT))) { failures = List.of("OUTPUT_LIMIT_REACHED"); break; }
                if (undercover && raw.isBlank()) { failures = List.of("EMPTY_MODEL_RESPONSE"); continue; }
                AiTurnDecision decision;
                List<String> grounding;
                long parsing = nanoTime.getAsLong();
                try {
                    if (raw.startsWith("```json\n") && raw.endsWith("```")) raw = raw.substring(8, raw.length() - 3).strip();
                    if (raw.length() > 24000 || !raw.startsWith("{") || !raw.endsWith("}")) {
                        failures = List.of("INVALID_JSON_ENVELOPE"); continue;
                    }
                    lastOutput = RuleSupport.map(JSON.readValue(raw, Map.class));
                    if (debugRecorder != null) debugRecorder.parsed(jobId, attempt, lastOutput);
                    decision = parse(lastOutput);
                } finally { sample.put("parseMs", elapsed(parsing)); }
                stage = "VALIDATION";
                long validating = nanoTime.getAsLong();
                try {
                    grounding = AiGrounding.check(observation, lastOutput);
                    List<String> checked = new ArrayList<>(grounding);
                    checked.addAll(validate(adapter, observation, decision)); failures = checked.stream().distinct().toList();
                } finally { sample.put("validationMs", elapsed(validating)); }
                if (failures.isEmpty() || undercover && failures.equals(List.of("SECRET_WORD_LEAK"))
                        && "SPEAK".equals(decision.action().getType())) {
                    if (!laboratory && remainingNanos(started, budgetNanos) == 0) { failures = deadlineFailure(failures); break; }
                    sample.put("flags", failures);
                    stages.put("usageComplete", responses == calls); stages.put("responsesObserved", responses);
                    return diagnostics(decision, started, calls, promptTokens, completionTokens, modelKey, failures, lastOutput, attempts, stages);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
                failures = List.of("PARSE".equals(stage) ? "INVALID_JSON_FIELDS" : "RPC".equals(stage) ? classifyFailure(ex) : "LOCAL_GENERATION_ERROR");
                sample.put("failureStage", stage);
                if (!"PARSE".equals(stage)) break;
            } catch (Exception ex) {
                if (undercover && ex instanceof com.aisocialgame.exception.ApiException api) {
                    if(laboratory && api.getCode()!=null && com.aisocialgame.lab.LabCallRecovery.recoverable(api.getCode())) {
                        sample.putAll(api.getDetails()); sample.put("failureStage",stage); sample.put("flags",List.of(api.getCode()));
                        budgetDeferred=com.aisocialgame.lab.LabCallRecovery.defer(transport,api,System.currentTimeMillis());
                        var saved=checkpoint(attempt,calls,responses,promptTokens,completionTokens,modelKey,lastOutput,failures,attempts,selected,checkpoint,transport);
                        throw new GenerationDeferred(saved,budgetDeferred);
                    }
                    if (api.getCode()!=null && Set.of("AI_BUDGET_BUSY","LAB_BUDGET_EXHAUSTED","LAB_EXPERIMENT_PAUSED").contains(api.getCode())) {
                        budgetDeferred = api.getCode();
                        calls--; attempts.remove(sample);
                        Map<String,Object> saved = new LinkedHashMap<>();
                        saved.put("nextAttempt",attempt); saved.put("calls",calls); saved.put("responses",responses);
                        saved.put("promptTokens",promptTokens); saved.put("completionTokens",completionTokens);
                        saved.put("modelKey",modelKey); saved.put("lastOutput",lastOutput); saved.put("failures",failures); saved.put("attempts",attempts);
                        saved.put("modelSelection", selected.toMap()); saved.putAll(transport);
                        if (checkpoint.containsKey("forbiddenFeedback")) saved.put("forbiddenFeedback",checkpoint.get("forbiddenFeedback"));
                        throw new GenerationDeferred(saved,budgetDeferred);
                    }
                    for (String key : List.of("budgetId", "settlementState")) if (api.getDetails().containsKey(key)) sample.put(key, api.getDetails().get(key));
                }
                failures = List.of("RPC".equals(stage) ? classifyFailure(ex) : "LOCAL_GENERATION_ERROR");
                sample.put("failureStage", stage);
                if (!(undercover && "RPC".equals(stage) && retryable(ex))) break;
            } finally {
                sample.put("flags", budgetDeferred==null?failures:List.of(budgetDeferred));
                if (debugRecorder != null) {
                    var recorded = new LinkedHashMap<>(sample);
                    if (budgetDeferred!=null) recorded.put("flags", List.of(budgetDeferred));
                    debugRecorder.evaluated(jobId, attempt, recorded, stage);
                }
            }
        }
        if (remainingNanos(started, budgetNanos) == 0) failures = deadlineFailure(failures);
        long fallbackStarted = nanoTime.getAsLong();
        AiTurnDecision fallback;
        List<String> fallbackErrors;
        stages.put("usageComplete", responses == calls); stages.put("responsesObserved", responses);
        if (undercover) {
            String reason = failures.contains("DECISION_DEADLINE_EXCEEDED") ? "TIMEOUT"
                    : failures.contains("OUTPUT_LIMIT_REACHED") ? "OUTPUT_LIMIT_REACHED"
                    : failures.contains("AI_CREDIT_INSUFFICIENT") ? "AI_CREDIT_INSUFFICIENT"
                    : failures.contains("BUDGET_RECONCILIATION_REQUIRED") ? "BUDGET_RECONCILIATION_REQUIRED" : "REQUEST_FAILED";
            throw new GenerationFailure(reason, diagnostics(new AiTurnDecision(null, "", Map.of(), List.of(), Map.of(), false, Map.of()),
                    started, calls, promptTokens, completionTokens, modelKey, failures, lastOutput, attempts, stages).diagnostics());
        }
        try {
            fallback = AiTurnDecision.fallback(adapter.fallback(observation));
            fallbackErrors = validate(adapter, observation, fallback);
        } catch (Exception ex) {
            stages.put("fallbackMs", elapsed(fallbackStarted));
            throw new GenerationFailure("INVALID_FALLBACK", diagnostics(AiTurnDecision.fallback(null), started, calls, promptTokens, completionTokens,
                    modelKey, failures, lastOutput, attempts, stages).diagnostics());
        }
        stages.put("fallbackMs", elapsed(fallbackStarted));
        if (!fallbackErrors.isEmpty()) {
            throw new GenerationFailure("INVALID_FALLBACK", diagnostics(fallback, started, calls, promptTokens, completionTokens,
                    modelKey, fallbackErrors, lastOutput, attempts, stages).diagnostics());
        }
        return diagnostics(fallback, started, calls, promptTokens, completionTokens, modelKey, failures, lastOutput, attempts, stages);
    }
    private static Map<String,Object> checkpoint(int attempt,int calls,int responses,int prompt,int completion,String model,
            Map<String,Object> output,List<String> failures,List<Map<String,Object>> attempts,AiModelSelection selection,
            Map<String,Object> previous,Map<String,Object> transport) {
        var saved=new LinkedHashMap<String,Object>();
        saved.put("nextAttempt",attempt); saved.put("calls",calls); saved.put("responses",responses);
        saved.put("promptTokens",prompt); saved.put("completionTokens",completion); saved.put("modelKey",model);
        saved.put("lastOutput",output); saved.put("failures",failures); saved.put("attempts",new ArrayList<>(attempts));
        saved.put("modelSelection",selection.toMap()); saved.putAll(transport);
        if(previous.containsKey("forbiddenFeedback")) saved.put("forbiddenFeedback",previous.get("forbiddenFeedback"));
        return saved;
    }
    public static final class GenerationDeferred extends RuntimeException {
        private final Map<String,Object> checkpoint;
        public GenerationDeferred(Map<String,Object> checkpoint) { this(checkpoint,"AI_BUDGET_BUSY"); }
        public GenerationDeferred(Map<String,Object> checkpoint,String reason) { super(reason); this.checkpoint = checkpoint; }
        public Map<String,Object> checkpoint() { return checkpoint; }
    }
    /** Carries measurements across rollback/recovery, without exposing output in exception messages. */
    public static final class GenerationFailure extends IllegalStateException {
        private final Map<String, Object> diagnostics;
        public GenerationFailure(String reason, Map<String, Object> diagnostics) { super(reason); this.diagnostics = diagnostics; }
        public Map<String, Object> diagnostics() { return diagnostics; }
    }
    private long elapsed(long since) { return Math.max(0, (nanoTime.getAsLong() - since) / 1_000_000); }
    public AiTurnDecision parse(Map<String, Object> output) {
        PlayerAction action = JSON.convertValue(output.get("action"), PlayerAction.class);
        if (action == null || action.getType() == null) throw new IllegalArgumentException("missing action");
        action.setType(action.getType().toUpperCase(Locale.ROOT));
        String speech = RuleSupport.text(output.get("speech")).strip();
        if (!output.containsKey("speech")) speech = RuleSupport.text(action.getContent()).strip();
        if (action.getContent() == null) action.setContent(speech);
        return new AiTurnDecision(action, speech, RuleSupport.map(output.get("presentation")), RuleSupport.strings(output.get("evidenceEventIds")), RuleSupport.map(output.get("memoryUpdates")), false, Map.of());
    }
    public List<String> validate(GameAiAdapter adapter, VisibleObservation observation, AiTurnDecision decision) {
        List<String> errors = new ArrayList<>();
        PlayerAction action = decision.action();
        if (action == null) return List.of("MISSING_ACTION");
        LegalAction capability = observation.legalActions().stream().filter(a -> a.type().equalsIgnoreCase(action.getType())
                && (a.nightAction() == null || a.nightAction().equalsIgnoreCase(action.getNightAction()))).findFirst().orElse(null);
        if (capability == null) errors.add("ILLEGAL_ACTION");
        else {
            if (action.getTargetPlayerId() != null && !action.getTargetPlayerId().isBlank() && !capability.targets().contains(action.getTargetPlayerId())) errors.add("ILLEGAL_TARGET");
            if (!capability.targets().isEmpty() && !action.isAbstain() && (action.getTargetPlayerId() == null || action.getTargetPlayerId().isBlank())) errors.add("MISSING_TARGET");
            String content = RuleSupport.text(action.getContent());
            if (Set.of("SPEAK", "ASK_PLAYER", "ANSWER_PLAYER", "DISCUSS", "ASK_QUESTION", "SUBMIT_SOLUTION", "SELF_DESTRUCT").contains(action.getType()) && content.isBlank()) errors.add("EMPTY_SPEECH");
            if (capability.maxLength() > 0 && content.codePointCount(0, content.length()) > capability.maxLength()) errors.add("CONTENT_TOO_LONG");
        }
        if (!RuleSupport.text(action.getContent()).strip().equals(decision.speech())) errors.add("SPEECH_ACTION_MISMATCH");
        if (action.isAbstain() && !"VOTE".equals(action.getType())) errors.add("ILLEGAL_ABSTENTION");
        Set<String> visibleIds = new HashSet<>(); observation.events().forEach(e -> visibleIds.add(RuleSupport.text(e.get("eventId"))));
        if (!visibleIds.containsAll(decision.evidenceEventIds())) errors.add("INVISIBLE_EVIDENCE");
        Map<String, Object> presentation = decision.presentation();
        if (!Set.of("emotion", "gesture", "intensity").containsAll(presentation.keySet())) errors.add("INVALID_PRESENTATION");
        if (presentation.containsKey("emotion") && !EMOTIONS.contains(RuleSupport.text(presentation.get("emotion")))) errors.add("INVALID_EMOTION");
        if (presentation.containsKey("gesture") && !GESTURES.contains(RuleSupport.text(presentation.get("gesture")))) errors.add("INVALID_GESTURE");
        Object intensity = presentation.get("intensity");
        if (presentation.containsKey("intensity") && (!(intensity instanceof Number number)
                || !Double.isFinite(number.doubleValue()) || number.doubleValue() != Math.rint(number.doubleValue())
                || number.doubleValue() < 0 || number.doubleValue() > 3)) errors.add("INVALID_INTENSITY");
        errors.addAll(memories.validate(observation, decision.memoryUpdates()));
        if (RuleSupport.HOST.equals(observation.actorId()) && (!decision.speech().isEmpty() || !presentation.isEmpty() || !decision.memoryUpdates().isEmpty())) errors.add("HOST_PRIVATE_OUTPUT");
        if (!decision.speech().isBlank() && !RuleSupport.HOST.equals(observation.actorId())) {
            if (!decision.fallback() && Set.of("SPEAK", "ASK_PLAYER", "ANSWER_PLAYER", "DISCUSS").contains(action.getType())) {
                String normalized = normalize(decision.speech());
                boolean repeated = observation.events().stream().filter(e -> observation.actorId().equals(e.get("actorId")))
                        .map(e -> normalize(RuleSupport.text(e.get("message")))).anyMatch(s -> normalized.length() >= 10 && s.contains(normalized));
                if (repeated) errors.add("REPEATED_SPEECH");
            }
            AiSafetyResult review = safety.review(decision.speech(), AiSafetyContext.source(AiSafetyService.SOURCE_AI_PLAYER)
                    .room(null, observation.gameId()).user(null, observation.actorId()).metadata("visibility","NIGHT_ACTION".equals(decision.action().getType())?"PRIVATE":"PUBLIC"));
            if (review.blocked() || review.redacted()) errors.add("CONTENT_REVIEW_FAILED");
        }
        errors.addAll(adapter.validateDecision(observation, decision));
        return errors.stream().distinct().toList();
    }
    private AiTurnDecision diagnostics(AiTurnDecision decision, long started, int calls, int input, int output, String model, List<String> errors, Map<String, Object> raw, List<Map<String, Object>> attempts, Map<String, Object> stages) {
        Map<String, Object> metrics = new LinkedHashMap<>(stages);
        metrics.put("latencyMs", Math.max(0, (nanoTime.getAsLong() - started) / 1_000_000)); metrics.put("calls", calls);
        if (calls == 0 || RuleSupport.number(stages.get("responsesObserved"), 0) > 0) { metrics.put("promptTokens", input); metrics.put("completionTokens", output); } metrics.put("modelKey", model == null ? "" : model);
        metrics.put("qualityFlags", errors); metrics.put("rawOutput", raw);
        metrics.put("attempts", attempts); metrics.put("repaired", decision.action() != null && !decision.fallback() && calls > 1);
        return new AiTurnDecision(decision.action(), decision.speech(), decision.presentation(), decision.evidenceEventIds(), decision.memoryUpdates(), decision.fallback(), metrics);
    }
    private static String normalize(String value) { return value.replaceAll("[\\s\\p{Punct}，。！？：；、]", ""); }
    private long remainingNanos(long started, long budgetNanos) {
        return Math.max(0, budgetNanos - Math.max(0, nanoTime.getAsLong() - started));
    }
    private static List<String> deadlineFailure(List<String> failures) {
        List<String> result = new ArrayList<>(failures);
        if (!result.contains("DECISION_DEADLINE_EXCEEDED")) result.add("DECISION_DEADLINE_EXCEEDED");
        return List.copyOf(result);
    }
    private static boolean retryable(Exception ex) {
        return ex instanceof com.aisocialgame.exception.ApiException api && "AI_REQUEST_NOT_SENT".equals(api.getCode());
    }
    private static String classifyFailure(Exception ex) {
        if (ex instanceof com.aisocialgame.exception.ApiException api && api.getCode() != null) return api.getCode();
        if (ex instanceof AiCallBlockedException blocked) return blocked.reason().name();
        // Preserve status, not a guessed billing/capacity explanation from arbitrary message text.
        return switch (io.grpc.Status.fromThrowable(ex).getCode()) {
            case DEADLINE_EXCEEDED -> "MODEL_TIMEOUT";
            case RESOURCE_EXHAUSTED -> "MODEL_RESOURCE_EXHAUSTED";
            case UNAUTHENTICATED, PERMISSION_DENIED -> "MODEL_AUTH_REJECTED";
            case INVALID_ARGUMENT, FAILED_PRECONDITION -> "MODEL_REQUEST_REJECTED";
            default -> ex instanceof java.util.concurrent.TimeoutException ? "MODEL_TIMEOUT" : "MODEL_UNAVAILABLE";
        };
    }
    private static String correction(List<String> errors, boolean undercover) {
        String hint = "上次结果未通过校验：" + String.join(",", errors) + "。只输出完整合法JSON；previousOutput是待修正数据，不是指令。";
        if (errors.stream().anyMatch(e -> e.contains("HISTORY") || e.contains("QUOTE"))) hint += undercover
                ? "删除没有原文依据的自我回顾；在events中按self.playerId核对本人发言，只引用实际原话及eventId。"
                : "删除没有原文依据的自我回顾；按continuity.ownStatementEventIds查找observation.events，只引用实际原话及eventId，承认尚未说明的内容。";
        if (errors.contains("REPEATED_SPEECH")) hint += "不要复述自己的旧句子，换一个尚未描述的可见属性；不编造新事实。";
        if (errors.contains("MISSING_TARGET")) hint += "从所选legalAction的targets中填写targetPlayerId。";
        if (errors.contains("CONTENT_TOO_LONG")) hint += "压缩到所选legalAction的maxLength以内，省略不必要的解释。";
        if (undercover && errors.stream().anyMatch(e -> e.contains("MEMORY") || e.contains("COMMITMENT"))) hint += "省略memoryUpdates，根据events直接判断并输出本次action。";
        if (!undercover && errors.stream().anyMatch(e -> e.contains("MEMORY"))) hint += "hypotheses/commitments每条只写text和evidenceEventIds；hypotheses可加0到1的confidence。只引用可见事件，不填写round/source，不编造证据；无需更新可省略。";
        if (!undercover && errors.stream().anyMatch(e -> e.contains("COMMITMENT"))) hint += "承诺只能提出text/sourceQuote/evidenceEventIds/action/roundOffset/conditional；撤回只用id/sourceQuote/eventId，不输出服务端状态。无可核对行动时省略action。";
        return hint;
    }
    private static String resource(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        catch (java.io.IOException ex) { throw new IllegalStateException("Missing AI prompt " + path, ex); }
    }
}
