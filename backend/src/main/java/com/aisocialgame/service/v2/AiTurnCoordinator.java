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
                    try { runtime.tick(roomId); }
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
                if (AiJobDiagnostics.overdue(abandoned.getDiagnostics(), abandoned.getStartedAt(), java.time.Instant.now(), java.time.ZoneId.systemDefault()))
                    runtime.fail(abandoned.getId(), "RECOVERY_TIMEOUT", java.util.Map.of());
            } catch (Exception ex) { log.warn("AI recovery failed jobId={} errorType={}", abandoned.getId(), ex.getClass().getSimpleName()); }
        }
    }

    @Scheduled(fixedDelayString = "${app.game.scheduler-interval-ms:750}")
    public void dispatchPulse() {
        if (!enabled) return;
        for (AiTurnJob queued : jobs.findTop16ByStatusOrderByCreatedAtAsc("QUEUED")) {
            if (!slots.tryAcquire()) break;
            AiTurnJob claimed = null;
            boolean submitted = false;
            try {
                claimed = claim(queued.getId());
                if (claimed == null) continue;
                AiTurnJob dispatched = claimed;
                executor.execute(() -> {
                    try { run(dispatched); }
                    finally { slots.release(); }
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
                if (!submitted) slots.release();
            }
        }
    }
    public AiTurnJob claim(String id) {
        return transaction.execute(status -> {
            AiTurnJob job = jobs.findByIdForUpdate(id).orElse(null);
            if (job == null || !"QUEUED".equals(job.getStatus())) return null;
            if (safety!=null) {
                var persona=com.aisocialgame.engine.v2.RuleSupport.map(job.getObservation().get("persona"));
                var context=com.aisocialgame.service.safety.AiSafetyContext.source("AI_PLAYER").room(job.getRoomId(),null).user(job.getActorId(),job.getActorId())
                        .persona(com.aisocialgame.engine.v2.RuleSupport.text(persona.get("id"))).model(properties==null?null:properties.getAi().getDefaultModel());
                if (safety.blocksAutomation(context,job.getKind())) return null;
            }
            job.setStatus("RUNNING"); job.setStartedAt(LocalDateTime.now());
            AiJobDiagnostics.started(job, java.time.Instant.now()); return jobs.saveAndFlush(job);
        });
    }
    public void run(AiTurnJob job) {
        java.util.Map<String, Object> measurements = java.util.Map.of();
        boolean submitting = false;
        long submissionStarted = 0;
        try {
            VisibleObservation observation = JSON.convertValue(job.getObservation(), VisibleObservation.class);
            var rules = runtime.rules(observation.gameId());
            long remainingMillis = runtime.remainingTurnMillis(job.getId());
            if (remainingMillis <= 0) {
                runtime.fail(job.getId(), "NO_ACTION_BUDGET", java.util.Map.of()); return;
            }
            AiTurnDecision decision;
            try (var scope = com.aisocialgame.service.safety.AiCallScope.open(com.aisocialgame.service.safety.AiSafetyContext.source("AI_PLAYER")
                    .room(job.getRoomId(), observation.gameId()).user(job.getActorId(),job.getActorId()).persona(com.aisocialgame.engine.v2.RuleSupport.text(com.aisocialgame.engine.v2.RuleSupport.map(observation.persona()).get("id"))))) {
                decision = generator.generate(rules, observation, job.getId(), remainingMillis);
            }
            measurements = decision.diagnostics(); submitting = true; submissionStarted = System.nanoTime();
            runtime.complete(job.getId(), decision);
        } catch (Exception ex) {
            log.warn("AI turn failed jobId={} roomId={} errorType={}", job.getId(), job.getRoomId(), ex.getClass().getSimpleName());
            if (submitting) {
                measurements = new java.util.LinkedHashMap<>(measurements);
                measurements.put("submissionFailedMs", Math.max(0, (System.nanoTime() - submissionStarted) / 1_000_000));
            }
            if (ex instanceof AiTurnGenerator.GenerationFailure failure) measurements = failure.diagnostics();
            String reason = submitting ? "SUBMISSION_EXCEPTION" : ex instanceof AiTurnGenerator.GenerationFailure ? "INVALID_FALLBACK" : "EXECUTION_EXCEPTION";
            try { runtime.fail(job.getId(), reason, measurements); }
            catch (Exception recoveryError) { log.warn("AI recovery deferred jobId={} errorType={}", job.getId(), recoveryError.getClass().getSimpleName()); }
        }
    }
    @PreDestroy public void close() { clockExecutor.shutdownNow(); executor.shutdownNow(); }
}
