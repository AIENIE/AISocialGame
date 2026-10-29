package com.aisocialgame.lab;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LabCoordinator {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LabCoordinator.class);
    private final LabExperimentRepository experiments;
    private final LabRuntime runtime;
    private final LabMatchRepository matches;
    private final LabCoachService coach;
    @org.springframework.beans.factory.annotation.Autowired private LabManualReviewService manualReviews;
    @org.springframework.beans.factory.annotation.Autowired private LabScenarioService scenarios;
    @org.springframework.beans.factory.annotation.Autowired private LabTrainingWorker training;
    private final boolean enabled;
    private final AtomicBoolean reviewing=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("lab-coach").daemon().factory());
    public LabCoordinator(LabExperimentRepository experiments,LabRuntime runtime,LabMatchRepository matches,LabCoachService coach,
                          @Value("${app.game.scheduler-enabled:true}") boolean enabled) {
        this.experiments=experiments; this.runtime=runtime; this.matches=matches; this.coach=coach; this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${app.lab.scheduler-delay-ms:1000}")
    public synchronized void pulse() {
        if(!enabled) return;
        var indexed=new java.util.LinkedHashMap<String,LabExperiment>();
        experiments.findTop32ByStatusInOrderByCreatedAtAsc(List.of("RUNNING","PAUSING","STOPPING")).forEach(experiment -> indexed.put(experiment.id,experiment));
        experiments.findByStatus("PAUSED").stream().filter(experiment->experiment.configuration.containsKey("manualReview") || experiment.configuration.containsKey("activeScenario") || experiment.configuration.containsKey("activeTrainingRunId"))
                .forEach(experiment->indexed.put(experiment.id,experiment));
        matches.findTop3ByStatusInOrderByCreatedAtAsc(LabRuntime.ACTIVE).forEach(match ->
                experiments.findById(match.experimentId).ifPresent(experiment -> indexed.put(experiment.id,experiment)));
        var active=List.copyOf(indexed.values());
        for(var experiment:active) {
            try { runtime.advance(experiment.id); }
            catch(RuntimeException failure) { log.warn("Lab scheduling deferred experimentId={} errorType={}",experiment.id,failure.getClass().getSimpleName()); }
        }
        if(reviewing.compareAndSet(false,true)) worker.execute(() -> {
            try {
                for(var experiment:active) {
                    if(Boolean.TRUE.equals(experiment.configuration.get("trainingV2Enabled"))) {if(training.process(experiment.id))break;}
                    else if(manualReviews.process(experiment.id) || scenarios.process(experiment.id) || coach.process(experiment.id)) break;
                }
            } catch(RuntimeException failure) { log.warn("Lab review deferred errorType={}",failure.getClass().getSimpleName()); }
            finally { reviewing.set(false); }
        });
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
