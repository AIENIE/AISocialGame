package com.aisocialgame.service.v2;

import com.aisocialgame.model.AiTurnJob;
import com.aisocialgame.repository.AiTurnJobRepository;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.service.ai.v2.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiTurnCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AiTurnCoordinator.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final AiTurnJobRepository jobs;
    private final GameStateRepository states;
    private final V2GameService runtime;
    private final AiTurnGenerator generator;
    private final TransactionTemplate transaction;
    private final ExecutorService executor;
    private final ExecutorService undercoverExecutor;
    private final Semaphore undercoverSlots = new Semaphore(8);
    private String lastDispatchedRoom = "";
    private final java.util.Set<String> activeJobs=ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> labModels=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor clockExecutor;
    private final java.util.Set<String> clockPending = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> clockCursor = new AtomicReference<>("");
    private final int clockBatchSize;
    private final Semaphore slots;
    private final boolean enabled;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.aisocialgame.service.safety.AiSafetyService safety;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.aisocialgame.config.AppProperties properties;
    @Autowired private com.aisocialgame.service.ai.config.AiModelConfigService modelConfigs;

    @Autowired
    public AiTurnCoordinator(AiTurnJobRepository jobs, GameStateRepository states, V2GameService runtime, AiTurnGenerator generator,
                             org.springframework.transaction.PlatformTransactionManager manager,
                             @Value("${app.game.ai-workers:4}") int workers,
                             @Value("${app.game.scheduler-enabled:true}") boolean enabled,
                             @Value("${app.game.clock-workers:4}") int clockWorkers,
                             @Value("${app.game.clock-batch-size:64}") int clockBatchSize) {
        this.jobs = jobs; this.states = states; this.runtime = runtime; this.generator = generator;
        this.transaction = new TransactionTemplate(manager); this.enabled = enabled;
        int size = Math.max(1, Math.min(workers, 8)); slots = new Semaphore(size);
        executor = Executors.newFixedThreadPool(size, r -> { Thread t = new Thread(r, "game-ai-turn"); t.setDaemon(true); return t; });
        undercoverExecutor = Executors.newFixedThreadPool(8, r -> { Thread t = new Thread(r, "undercover-ai-turn"); t.setDaemon(true); return t; });
        int clockSize = Math.max(1, Math.min(clockWorkers, 8));
        this.clockBatchSize = Math.max(1, Math.min(clockBatchSize, 256));
        clockExecutor = new ThreadPoolExecutor(clockSize, clockSize, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(this.clockBatchSize), r -> {
                    Thread thread = new Thread(r, "game-room-clock"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    AiTurnCoordinator(AiTurnJobRepository jobs, GameStateRepository states, V2GameService runtime, AiTurnGenerator generator,
                      org.springframework.transaction.PlatformTransactionManager manager, int workers, boolean enabled) {
        this(jobs, states, runtime, generator, manager, workers, enabled, 4, 64);
    }

    /** Test/manual entry point; scheduled work uses independent lanes below. */
    public void pulse() {
        clockPulse();
        recoveryPulse();
        dispatchPulse();
    }

    @Scheduled(fixedDelayString = "${app.game.scheduler-interval-ms:750}")
    public void clockPulse() {
        if (!enabled) return;
        java.util.List<String> roomIds = states.findActiveRoomIdsAfter(clockCursor.get(), PageRequest.of(0, clockBatchSize));
        if (roomIds.isEmpty()) {
            clockCursor.set("");
            return;
        }
        clockCursor.set(roomIds.size() < clockBatchSize ? "" : roomIds.getLast());
        for (String roomId : roomIds) {
            if (!clockPending.add(roomId)) continue;
            try {
                clockExecutor.execute(() -> {
                    try {
                        // Recover before this room's deadline, without letting its database lock block unrelated room clocks.
                        for (var abandoned : jobs.findRunningRecoveryCandidatesInRoom(roomId))
                            if (Boolean.TRUE.equals(abandoned.getDiagnostics().get("laboratory")) && !activeJobs.contains(abandoned.getId()))
                                runtime.recoverLabJob(abandoned.getId());
                        runtime.tick(roomId);
                    }
                    catch (Exception ex) { log.warn("Game clock failed roomId={} errorType={}", roomId, ex.getClass().getSimpleName()); }
                    finally { clockPending.remove(roomId); }
                });
            } catch (RejectedExecutionException rejected) {
                clockPending.remove(roomId);
                log.debug("Game clock deferred roomId={} reason=CAPACITY", roomId);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.game.scheduler-interval-ms:750}")
    public void recoveryPulse() {
        if (!enabled) return;
        // Recover abandoned calls through rule-specific expiry; never publish unvalidated fallback text.
        for (var abandoned : jobs.findRecoveryCandidates("RUNNING", PageRequest.of(0, clockBatchSize))) {
            try {
                if(Boolean.TRUE.equals(abandoned.getDiagnostics().get("laboratory")) || "werewolf".equals(abandoned.getDiagnostics().get("gameId")) && !AiJobDiagnostics.overdue(abandoned.getDiagnostics(),abandoned.getStartedAt(),java.time.Instant.now(),java.time.ZoneId.systemDefault())) {
                    if(!activeJobs.contains(abandoned.getId())) runtime.recoverLabJob(abandoned.getId());
                    continue;
                }
                if (AiJobDiagnostics.overdue(abandoned.getDiagnostics(), abandoned.getStartedAt(), java.time.Instant.now(), java.time.ZoneId.systemDefault()))
                    runtime.fail(abandoned.getId(), "RECOVERY_TIMEOUT", java.util.Map.of());
            } catch (Exception ex) { log.warn("AI recovery failed jobId={} errorType={}", abandoned.getId(), ex.getClass().getSimpleName()); }
        }
    }

    @Scheduled(fixedDelayString = "${app.game.scheduler-interval-ms:750}")
    public synchronized void dispatchPulse() {
        if (!enabled) return;
        var byRoom = new java.util.TreeMap<String,java.util.ArrayDeque<AiTurnJob>>();
        for (AiTurnJob job : jobs.findByStatusOrderByCreatedAtAsc("QUEUED"))
            byRoom.computeIfAbsent(job.getRoomId(), ignored -> new java.util.ArrayDeque<>()).add(job);
        var rooms = new java.util.ArrayList<>(byRoom.keySet());
        rooms.sort(java.util.Comparator.comparing((String room) -> room.compareTo(lastDispatchedRoom) <= 0).thenComparing(java.util.Comparator.naturalOrder()));
        var fair = new java.util.ArrayList<AiTurnJob>();
        while (byRoom.values().stream().anyMatch(q -> !q.isEmpty())) for (String room : rooms) {
            var queue = byRoom.get(room); if (!queue.isEmpty()) fair.add(queue.remove());
        }
        for (AiTurnJob queued : fair) {
            boolean undercover = "undercover".equals(queued.getObservation().get("gameId"));
            Semaphore permits = undercover ? undercoverSlots : slots;
            ExecutorService workers = undercover ? undercoverExecutor : executor;
            String model=isLab(queued)?labModel(queued):"werewolf".equals(queued.getObservation().get("gameId")) ?
                    (queued.getDiagnostics().containsKey("modelSelection") ? selectedModel(queued) : modelConfigs.resolve("werewolf",queued.getActorId()).modelKey()) : null;
            var saved=com.aisocialgame.engine.v2.RuleSupport.map(queued.getObservation().get("generationCheckpoint"));
            if(!com.aisocialgame.lab.LabCallRecovery.ready(saved,System.currentTimeMillis())) continue;
            if(model!=null && !labModels.add(model)) continue;
            if (!permits.tryAcquire()) { if(model!=null) labModels.remove(model); continue; }
            activeJobs.add(queued.getId());
            AiTurnJob claimed = null;
            boolean submitted = false;
            try {
                claimed = claim(queued.getId());
                if (claimed == null) continue;
                AiTurnJob dispatched = claimed;
                lastDispatchedRoom = queued.getRoomId();
                workers.execute(() -> {
                    try { run(dispatched); }
                    finally { activeJobs.remove(dispatched.getId()); if(model!=null) labModels.remove(model); permits.release(); }
                });
                submitted = true;
            } catch (Exception ex) {
                log.warn("AI dispatch failed jobId={} errorType={}", queued.getId(), ex.getClass().getSimpleName());
                if (claimed != null) {
                    try { runtime.fail(claimed.getId(), "DISPATCH_REJECTED", java.util.Map.of()); }
                    catch (Exception recoveryError) { log.warn("AI recovery deferred jobId={} errorType={}", claimed.getId(), recoveryError.getClass().getSimpleName()); }
                }
            } finally {
                // The worker owns a permit only after execute accepted its task.
                if (!submitted) { activeJobs.remove(queued.getId()); if(model!=null) labModels.remove(model); permits.release(); }
            }
        }
    }
    public AiTurnJob claim(String id) {
        return transaction.execute(status -> {
            AiTurnJob job = jobs.findByIdForUpdate(id).orElse(null);
            if (job == null || !"QUEUED".equals(job.getStatus())) return null;
            if(runtime.labBudgetPaused(job.getRoomId())) return null;
            if(runtime.validationBudgetPaused(job.getRoomId())) return null;
            if(!com.aisocialgame.lab.LabCallRecovery.ready(com.aisocialgame.engine.v2.RuleSupport.map(job.getObservation().get("generationCheckpoint")),System.currentTimeMillis())) return null;
            if (modelConfigs != null && !job.getDiagnostics().containsKey("modelSelection")) {
                var prior = com.aisocialgame.engine.v2.RuleSupport.map(com.aisocialgame.engine.v2.RuleSupport.map(job.getObservation().get("generationCheckpoint")).get("modelSelection"));
                var frozen = com.aisocialgame.engine.v2.RuleSupport.map(com.aisocialgame.engine.v2.RuleSupport.map(com.aisocialgame.engine.v2.RuleSupport.map(job.getObservation().get("memory")).get("lab")).get("modelSelection"));
                if (prior.isEmpty() && !frozen.isEmpty()) prior = frozen;
                job.getDiagnostics().put("modelSelection", prior.isEmpty() ? modelConfigs.resolve(
                        com.aisocialgame.engine.v2.RuleSupport.text(V2GameService.observationPayload(job).get("gameId")), job.getActorId()).toMap() : prior);
            }
            if (safety!=null) {
                var persona=com.aisocialgame.engine.v2.RuleSupport.map(job.getObservation().get("persona"));
                var context=com.aisocialgame.service.safety.AiSafetyContext.source("AI_PLAYER").room(job.getRoomId(),null).user(job.getActorId(),job.getActorId())
                        .persona(com.aisocialgame.engine.v2.RuleSupport.text(persona.get("id"))).model(selectedModel(job));
                if (safety.blocksAutomation(context,job.getKind())) return null;
            }
            if (job.getDiagnostics().remove("budgetWaitStartedEpochMs") instanceof Number waiting) {
                long previous = job.getDiagnostics().get("budgetWaitMs") instanceof Number n ? n.longValue() : 0;
                job.getDiagnostics().put("budgetWaitMs", previous + Math.max(0,System.currentTimeMillis()-waiting.longValue()));
            }
            job.getDiagnostics().remove("waitingReason");
            if(isLab(job)) job.getDiagnostics().put("laboratory",true);
            job.setStatus("RUNNING"); job.setStartedAt(LocalDateTime.now());
            if ("undercover".equals(job.getObservation().get("gameId")))
                job.getDiagnostics().putIfAbsent("executionDeadlineEpochMs", System.currentTimeMillis()+AiTurnGenerator.UNDERCOVER_DECISION_MILLIS);
            if (!job.getDiagnostics().containsKey("startedEpochMs")) AiJobDiagnostics.started(job, java.time.Instant.now());
            return jobs.saveAndFlush(job);
        });
    }
    public void run(AiTurnJob job) {
        java.util.Map<String, Object> measurements = java.util.Map.of();
        boolean submitting = false;
        long submissionStarted = 0;
        try {
            VisibleObservation observation = JSON.convertValue(V2GameService.observationPayload(job), VisibleObservation.class);
            var rules = runtime.rules(observation.gameId());
            if ("werewolf".equals(observation.gameId()) && !runtime.beginWerewolfTurn(job.getId())) {
                if (runtime.validationBudgetPaused(job.getRoomId())) runtime.defer(job.getId(), com.aisocialgame.engine.v2.RuleSupport.map(job.getObservation().get("generationCheckpoint")), AiValidationBudgetService.EXHAUSTED);
                else if (runtime.labBudgetPaused(job.getRoomId())) runtime.defer(job.getId(), com.aisocialgame.engine.v2.RuleSupport.map(job.getObservation().get("generationCheckpoint")), "LAB_EXPERIMENT_PAUSED");
                else runtime.fail(job.getId(), job.getDiagnostics().containsKey("executionDeadlineEpochMs") ? "TIMEOUT" : "QUEUE_TIMEOUT", java.util.Map.of());
                return;
            }
            long remainingMillis = runtime.remainingTurnMillis(job.getId());
            if (remainingMillis <= 0) {
                runtime.fail(job.getId(), "NO_ACTION_BUDGET", java.util.Map.of()); return;
            }
            AiTurnDecision decision;
            var checkpoint = new java.util.LinkedHashMap<>(com.aisocialgame.engine.v2.RuleSupport.map(job.getObservation().get("generationCheckpoint")));
            if (job.getDiagnostics().containsKey("modelSelection")) checkpoint.put("modelSelection", job.getDiagnostics().get("modelSelection"));
            try (var labScope = com.aisocialgame.lab.LabCallContext.open(com.aisocialgame.engine.v2.RuleSupport.map(com.aisocialgame.engine.v2.RuleSupport.map(observation.memory()).get("lab")), "TURN");
                 var scope = com.aisocialgame.service.safety.AiCallScope.open(com.aisocialgame.service.safety.AiSafetyContext.source("AI_PLAYER")
                    .room(job.getRoomId(), observation.gameId()).user(job.getActorId(),job.getActorId()).persona(com.aisocialgame.engine.v2.RuleSupport.text(com.aisocialgame.engine.v2.RuleSupport.map(observation.persona()).get("id"))).model(selectedModel(job)))) {
                decision = isLab(job) || "werewolf".equals(observation.gameId())
                        ? generator.generate(rules, observation, job.getId(), remainingMillis, () -> runtime.remainingTurnMillis(job.getId()) > 0, checkpoint,
                            saved -> runtime.checkpoint(job.getId(),saved))
                        : generator.generate(rules, observation, job.getId(), remainingMillis, () -> runtime.remainingTurnMillis(job.getId()) > 0, checkpoint);
            }
            measurements = decision.diagnostics(); submitting = true; submissionStarted = System.nanoTime();
            com.aisocialgame.lab.LabCallRecovery.commit(() -> runtime.complete(job.getId(), decision));
        } catch (AiTurnGenerator.GenerationDeferred deferred) {
            com.aisocialgame.lab.LabCallRecovery.commit(() -> runtime.defer(job.getId(), deferred.checkpoint(), deferred.getMessage()));
        } catch (Exception ex) {
            log.warn("AI turn failed jobId={} roomId={} errorType={}", job.getId(), job.getRoomId(), ex.getClass().getSimpleName());
            if (submitting) {
                measurements = new java.util.LinkedHashMap<>(measurements);
                measurements.put("submissionFailedMs", Math.max(0, (System.nanoTime() - submissionStarted) / 1_000_000));
            }
            if (ex instanceof AiTurnGenerator.GenerationFailure failure) measurements = failure.diagnostics();
            else if (submitting) {
                measurements = new java.util.LinkedHashMap<>(measurements);
                measurements.put("failureStage","SUBMISSION");
                measurements.put("failureCode",ex instanceof com.aisocialgame.exception.ApiException api && api.getCode()!=null ? api.getCode() : "UNEXPECTED_SUBMISSION_FAILURE");
            }
            String reason = submitting ? "SUBMISSION_EXCEPTION" : ex instanceof AiTurnGenerator.GenerationFailure failure ? failure.getMessage() : "EXECUTION_EXCEPTION";
            try { runtime.fail(job.getId(), reason, measurements); }
            catch (Exception recoveryError) { log.warn("AI recovery deferred jobId={} errorType={}", job.getId(), recoveryError.getClass().getSimpleName()); }
        }
    }
    private boolean isLab(AiTurnJob job) {
        return !com.aisocialgame.engine.v2.RuleSupport.map(com.aisocialgame.engine.v2.RuleSupport.map(job.getObservation().get("memory")).get("lab")).isEmpty();
    }
    private String labModel(AiTurnJob job) {
        var lab=com.aisocialgame.engine.v2.RuleSupport.map(com.aisocialgame.engine.v2.RuleSupport.map(job.getObservation().get("memory")).get("lab"));
        return com.aisocialgame.engine.v2.RuleSupport.text(com.aisocialgame.engine.v2.RuleSupport.map(lab.get("modelSelection")).get("modelKey"));
    }
    private String selectedModel(AiTurnJob job) {
        var selected = com.aisocialgame.engine.v2.RuleSupport.map(job.getDiagnostics().get("modelSelection"));
        return selected.containsKey("modelKey") ? com.aisocialgame.engine.v2.RuleSupport.text(selected.get("modelKey"))
                : properties == null ? null : properties.getAi().getDefaultModel();
    }
    @PreDestroy public void close() { clockExecutor.shutdownNow(); executor.shutdownNow(); undercoverExecutor.shutdownNow(); }
}
