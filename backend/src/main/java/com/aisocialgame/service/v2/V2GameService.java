package com.aisocialgame.service.v2;

import com.aisocialgame.dto.*;
import com.aisocialgame.dto.ws.GameStateEvent;
import com.aisocialgame.dto.ws.PrivateEvent;
import com.aisocialgame.engine.v2.*;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.*;
import com.aisocialgame.service.ai.v2.*;
import com.aisocialgame.service.safety.*;
import com.aisocialgame.websocket.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class V2GameService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(V2GameService.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> EMOJIS = Set.of("👍", "🤔", "😂", "😱", "😡", "😭", "😎", "💀");
    private static final Set<String> QUICK_PHRASES = Set.of("我同意", "有点可疑", "等等", "继续说", "我反对", "快投票", "有點可疑", "繼續說", "我反對", "I agree", "Suspicious", "Wait", "Go on", "I object", "Vote now");
    private final Map<String, GameRuleSet> ruleSets = new LinkedHashMap<>();
    private final RoomRepository rooms;
    private final GameStateRepository states;
    private final AiTurnJobRepository jobs;
    private final TransactionTemplate transaction;
    private final ObservationFactory observations;
    private final AiMemoryServiceV2 memories;
    private final GameEventRecorder recorder;
    private final ReplayArchiveService archives;
    private final StatsService stats;
    private final GamePushService push;
    private final PlayerConnectionService connections;
    private final AiSafetyService safety;
    private final AiDecisionTraceRepository traces;
    private final boolean enabled;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.aisocialgame.config.AppProperties appProperties;

    public V2GameService(List<GameRuleSet> rules, RoomRepository rooms, GameStateRepository states, AiTurnJobRepository jobs,
                         org.springframework.transaction.PlatformTransactionManager manager, ObservationFactory observations,
                         AiMemoryServiceV2 memories, GameEventRecorder recorder, ReplayArchiveService archives, StatsService stats,
                         GamePushService push, PlayerConnectionService connections, AiSafetyService safety, AiDecisionTraceRepository traces,
                         @Value("${app.game.v2-enabled:true}") boolean enabled) {
        rules.forEach(r -> { if (ruleSets.put(r.gameId(), r) != null) throw new IllegalStateException("Duplicate v2 game " + r.gameId()); });
        this.rooms = rooms; this.states = states; this.jobs = jobs; this.transaction = new TransactionTemplate(manager);
        this.observations = observations; this.memories = memories; this.recorder = recorder; this.archives = archives; this.stats = stats;
        this.push = push; this.connections = connections; this.safety = safety; this.traces = traces; this.enabled = enabled;
    }
    public boolean newGamesEnabled() { return enabled; }
    public boolean hasState(String roomId) { return states.existsById(roomId); }
    public boolean isV2(String roomId) { return states.findRoutingSnapshotByRoomId(roomId).map(s -> RuleSupport.number(s.getData().get("ruleVersion"), 1) == 2).orElse(false); }
    public GameRuleSet rules(String gameId) { return Optional.ofNullable(ruleSets.get(gameId)).orElseThrow(() -> RuleSupport.bad("玩法规则尚未就绪")); }

    /** Read the current persisted opportunity before remote work, without retaining a transaction. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public long remainingTurnMillis(String jobId) {
        AiTurnJob job = jobs.findById(jobId).orElse(null);
        if (job == null || !"RUNNING".equals(job.getStatus())) return 0;
        GameState state = states.findById(job.getRoomId()).orElse(null);
        if (state == null || !Objects.equals(job.getInstanceId(), state.getData().get("archiveId"))) return 0;
        GameRuleSet rules = rules(state.getGameId());
        if (!rules.isTurnCurrent(state, new TurnRequest(job.getActorId(), job.getKind(), job.getTurnKey()))) return 0;
        return turnBudgetMillis(state.getPhaseEndsAt(), LocalDateTime.now());
    }
    static long turnBudgetMillis(LocalDateTime deadline, LocalDateTime now) {
        if (deadline == null) return AiTurnGenerator.MAX_DECISION_MILLIS;
        return Math.min(AiTurnGenerator.MAX_DECISION_MILLIS, Math.max(0, Duration.between(now, deadline).toMillis() - 1_000));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public GameStateResponse state(String gameId, String roomId, User viewer) {
        Room room = room(roomId, gameId, false);
        GameState state = states.findById(roomId).orElse(null);
        RoomAccessPolicy.requireRead(room, state, viewer == null ? null : viewer.getId());
        if (viewer != null) connections.markActive(viewer.getId(), roomId);
        return response(room, state, viewer == null ? null : viewer.getId());
    }
    public GameStateResponse start(String gameId, String roomId, User user) {
        return transaction.execute(status -> {
            Room room = room(roomId, gameId, true);
            requireHost(room, user);
            GameState previous = states.findByIdForUpdate(roomId).orElse(null);
            if (previous != null && !"SETTLEMENT".equals(previous.getPhase())) {
                RuleSupport.require(RuleSupport.number(previous.getData().get("ruleVersion"), 1) == 2, "请先完成当前旧版本对局");
                return response(room, previous, user.getId());
            }
            GameRuleSet rules = rules(gameId);
            var configuration=GameConfiguration.validate(rules.definition(),room.getConfig()); RuleSupport.require(configuration.valid(),configuration.message());
            var validation = rules.validateStart(room); RuleSupport.require(validation.valid(), validation.message());
            GameState fresh = rules.initialize(room, LocalDateTime.now());
            RoomAccessPolicy.snapshot(fresh, room);
            GameState state = fresh;
            if (previous != null) {
                previous.setData(fresh.getData()); previous.setLogs(fresh.getLogs()); previous.setPlayers(fresh.getPlayers());
                previous.setPhase(fresh.getPhase()); previous.setRoundNumber(fresh.getRoundNumber()); previous.setCurrentSeat(fresh.getCurrentSeat());
                previous.setPhaseEndsAt(fresh.getPhaseEndsAt()); previous.setCreatedAt(LocalDateTime.now()); state = previous;
            }
            RuleSupport.event(state, "start", null, null, "对局开始，按房间展示的规则进行。", Map.of("ruleVersion", 2));
            room.setStatus(RoomStatus.PLAYING); rooms.save(room);
            persist(room, state, rules, true); prepareJob(state, rules);
            return response(room, state, user.getId());
        });
    }
    public GameStateResponse action(String gameId, String roomId, User user, PlayerAction action) {
        if (user == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        return transaction.execute(status -> {
            Room room = room(roomId, gameId, true);
            GameState state = states.findByIdForUpdate(roomId).orElseThrow(() -> RuleSupport.bad("游戏尚未开始"));
            if (roomControls(state).contains("PAUSE_ROOM") || roomControls(state).contains("BLOCK")) throw new ApiException(HttpStatus.CONFLICT,"房间已暂停");
            syncSafetyClock(state);
            RuleSupport.require(RuleSupport.number(state.getData().get("ruleVersion"), 1) == 2, "对局版本不匹配");
            GameRuleSet rules = rules(gameId);
            GamePlayerState actor = RuleSupport.player(state, user.getId());
            String requestKey = user.getId() + ":" + RuleSupport.text(action.getRequestId());
            List<String> handled = new ArrayList<>(RuleSupport.strings(state.getData().get("handledActions")));
            if (action.getRequestId() != null && handled.contains(requestKey)) return response(room, state, user.getId());
            if (action.getExpectedPhaseToken() != null && !action.getExpectedPhaseToken().equals(viewerPhaseToken(state, rules, user.getId()))) throw new ApiException(HttpStatus.CONFLICT, "局面已更新，请重新选择操作", "PHASE_CHANGED", Map.of());
            String type = RuleSupport.text(action.getType()).toUpperCase(Locale.ROOT); action.setType(type);
            RuleSupport.require(!Set.of("HOST_VERDICT", "PASS").contains(type), "内部动作不能由玩家提交");
            if (action.getContent() != null && !action.getContent().isBlank()) {
                String safe = safety.requireAllowedInput(action.getContent(), AiSafetyContext.source(AiSafetyService.SOURCE_GAME_SPEECH).room(roomId, gameId).user(user.getId(), user.getId()).metadata("visibility","NIGHT_ACTION".equals(type)?"PRIVATE":"PUBLIC"));
                action.setContent(safe);
            }
            actor.setConnectionStatus("ONLINE"); actor.setDisconnectedAt(null); actor.setLastActiveAt(LocalDateTime.now()); connections.markActive(user.getId(), roomId);
            var receipt = rules.commitmentAction(state, user.getId(), action);
            int actionStart = RuleSupport.maps(state.getData().get("events")).size();
            AiCommitments.reconcile(state, rules, "OPPORTUNITY_LOST");
            rules.apply(state, user.getId(), action, LocalDateTime.now());
            memories.normalizeOnAction(state, user.getId());
            AiCommitments.submitted(state, receipt, "HUMAN", actionStart);
            if (action.getRequestId() != null) { handled.add(requestKey); state.getData().put("handledActions", handled.subList(Math.max(0, handled.size() - 256), handled.size())); }
            rules.advance(state, LocalDateTime.now()); syncSafetyClock(state); persist(room, state, rules, false); prepareJob(state, rules);
            return response(room, state, user.getId());
        });
    }
    public boolean acceptSideChat(String roomId, String actorId, String type, String content) {
        GameStateRepository.RoutingSnapshot current = states.findRoutingSnapshotByRoomId(roomId).orElse(null);
        if (current == null || RuleSupport.number(current.getData().get("ruleVersion"), 1) != 2 || "SETTLEMENT".equals(current.getPhase())) return true;
        if ("TEXT".equals(type) && "turtle_soup".equals(current.getGameId())) {
            User user = new User(); user.setId(actorId);
            action(current.getGameId(), roomId, user, RuleSupport.action("DISCUSS", content, null)); return true;
        }
        if ("TEXT".equals(type) || "NIGHT".equals(current.getPhase()) || "DEATH_ACTION".equals(current.getPhase())) return false;
        if (!("EMOJI".equals(type) && EMOJIS.contains(content)) && !("QUICK_PHRASE".equals(type) && QUICK_PHRASES.contains(content))) return false;
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Room room = rooms.findByIdForUpdate(roomId).orElseThrow(); GameState state = states.findByIdForUpdate(roomId).orElseThrow();
            GamePlayerState player = state.getPlayers().stream().filter(p -> p.getPlayerId().equals(actorId) && p.isAlive()).findFirst().orElse(null);
            if (player == null || Set.of("NIGHT", "DEATH_ACTION", "SETTLEMENT").contains(state.getPhase())) return false;
            safety.requireAllowedInput(content, AiSafetyContext.source(AiSafetyService.SOURCE_GAME_SPEECH).room(roomId, state.getGameId()).user(actorId, actorId));
            RuleSupport.event(state, "reaction", actorId, null, player.getDisplayName() + "：" + content, Map.of("reactionType", type));
            persist(room, state, rules(state.getGameId()), false); return true;
        }));
    }
    public void tick(String roomId) {
        transaction.executeWithoutResult(status -> {
            Room room = rooms.findByIdForUpdateSkipLocked(roomId).orElse(null); if (room == null) return;
            GameState state = states.findByIdForUpdateSkipLocked(roomId).orElse(null);
            if (state == null || RuleSupport.number(state.getData().get("ruleVersion"), 1) != 2 || "SETTLEMENT".equals(state.getPhase())) return;
            int beforeSafety=RuleSupport.maps(state.getData().get("events")).size();
            boolean frozen=syncSafetyClock(state);
            if (beforeSafety!=RuleSupport.maps(state.getData().get("events")).size()) persist(room,state,rules(state.getGameId()),true);
            if (frozen) { states.save(state); return; }
            GameRuleSet rules = rules(state.getGameId());
            int eventCount = RuleSupport.maps(state.getData().get("events")).size(); String token = phaseToken(state);
            String publicBefore = serializedPublicView(room, state);
            updateConnections(state);
            rules.advance(state, LocalDateTime.now());
            AiCommitments.reconcile(state, rules, "TIMEOUT");
            boolean changed = eventCount != RuleSupport.maps(state.getData().get("events")).size() || !token.equals(phaseToken(state));
            if (changed) persist(room, state, rules, !publicBefore.equals(serializedPublicView(room, state)));
            prepareJob(state, rules);
        });
    }
    public void complete(String jobId, AiTurnDecision decision) {
        long submissionStarted = System.nanoTime();
        AiTurnJob lookup = jobs.findById(jobId).orElse(null); if (lookup == null) return;
        transaction.executeWithoutResult(status -> {
            Room room = rooms.findByIdForUpdate(lookup.getRoomId()).orElse(null);
            GameState state = states.findByIdForUpdate(lookup.getRoomId()).orElse(null);
            AiTurnJob job = jobs.findByIdForUpdate(jobId).orElseThrow();
            if (!"RUNNING".equals(job.getStatus())) return;
            job.getDiagnostics().put("generation", AiJobDiagnostics.generation(decision.diagnostics()));
            job.getDiagnostics().put("fallback", decision.fallback());
            if (room == null || state == null || !job.getInstanceId().equals(state.getData().get("archiveId"))) { finishJob(job, "DISCARDED", "INSTANCE_UNAVAILABLE"); return; }
            if (automationBlocked(state,job.getActorId())) { syncSafetyClock(state); states.save(state); finishJob(job,"DISCARDED","ADMIN_CONTROL"); return; }
            GameRuleSet rules = rules(state.getGameId());
            TurnRequest turn = new TurnRequest(job.getActorId(), job.getKind(), job.getTurnKey());
            if (!rules.isTurnCurrent(state, turn)) { finishJob(job, "DISCARDED", "TURN_OBSOLETE"); prepareJob(state, rules); return; }
            LocalDateTime now = LocalDateTime.now();
            if (state.getPhaseEndsAt() != null && !now.isBefore(state.getPhaseEndsAt())) {
                rules.advance(state, now); AiCommitments.reconcile(state, rules, "TIMEOUT"); finishJob(job, "DISCARDED", "TURN_EXPIRED");
                persist(room, state, rules, false); prepareJob(state, rules); return;
            }
            VisibleObservation observation = JSON.convertValue(job.getObservation(), VisibleObservation.class);
            if(!decision.speech().isBlank()) {
                var review=safety.review(decision.speech(),AiSafetyContext.source("AI_PLAYER").room(state.getRoomId(),state.getGameId())
                        .user(job.getActorId(),job.getActorId()).persona(RuleSupport.text(observation.persona().get("id")))
                        .model(appProperties==null?null:appProperties.getAi().getDefaultModel()).metadata("visibility","NIGHT_ACTION".equals(decision.action().getType())?"PRIVATE":"PUBLIC"));
                if(review.blocked() || review.redacted()) {finishJob(job,"DISCARDED","CONTENT_REVIEW_FAILED");return;}
            }
            int before = RuleSupport.maps(state.getData().get("events")).size();
            var receipt = rules.commitmentAction(state, job.getActorId(), decision.action());
            AiCommitments.reconcile(state, rules, "OPPORTUNITY_LOST");
            rules.apply(state, job.getActorId(), decision.action(), LocalDateTime.now());
            decorateEvents(state, before, job.getActorId(), decision);
            memories.commit(state, job.getActorId(), decision, observation, rules, before);
            String origin = !decision.fallback() ? "MODEL" : RuleSupport.strings(decision.diagnostics().get("qualityFlags")).stream().anyMatch(f -> f.contains("TIMEOUT") || f.contains("DEADLINE")) ? "TIMEOUT_FALLBACK" : "LEGAL_FALLBACK";
            AiCommitments.submitted(state, receipt, origin, before);
            rules.advance(state, LocalDateTime.now());
            AiCommitments.reconcile(state, rules, "OPPORTUNITY_LOST");
            job.getDiagnostics().put("submissionProcessingMs", Math.max(0, (System.nanoTime() - submissionStarted) / 1_000_000));
            finishJob(job, "SUCCEEDED", decision.fallback() ? "FALLBACK_APPLIED" : "MODEL_ACTION_APPLIED");
            AiRoundReflection.closeRounds(state);
            recordTrace(state, job, observation, decision, before);
            persist(room, state, rules, false); prepareJob(state, rules);
        });
    }
    public void fail(String jobId) { fail(jobId, "EXECUTION_EXCEPTION", Map.of()); }
    public void fail(String jobId, String reason, Map<String, Object> generation) {
        AiTurnJob lookup = jobs.findById(jobId).orElse(null); if (lookup == null) return;
        transaction.executeWithoutResult(status -> {
            Room room = rooms.findByIdForUpdate(lookup.getRoomId()).orElse(null);
            GameState state = states.findByIdForUpdate(lookup.getRoomId()).orElse(null);
            AiTurnJob job = jobs.findByIdForUpdate(jobId).orElse(null);
            if (job == null || !"RUNNING".equals(job.getStatus())) return;
            if ("RECOVERY_TIMEOUT".equals(reason) && !AiJobDiagnostics.overdue(job, java.time.Instant.now(), java.time.ZoneId.systemDefault())) return;
            if (!generation.isEmpty()) job.getDiagnostics().put("generation", AiJobDiagnostics.generation(generation));
            job.getDiagnostics().put("failureReason", reason);
            if (room == null || state == null || !job.getInstanceId().equals(state.getData().get("archiveId"))) { finishJob(job, "DISCARDED", "INSTANCE_UNAVAILABLE"); return; }
            if (automationBlocked(state,job.getActorId())) { syncSafetyClock(state); states.save(state); finishJob(job,"DISCARDED","ADMIN_CONTROL"); return; }
            GameRuleSet rules = rules(state.getGameId());
            TurnRequest turn = new TurnRequest(job.getActorId(), job.getKind(), job.getTurnKey());
            if (rules.isTurnCurrent(state, turn)) {
                LocalDateTime now = LocalDateTime.now();
                // A genuinely expired phase must use its clock transition; submitting SKIP
                // through a rule's normal action path can itself reject an expired action.
                if (state.getPhaseEndsAt() != null && !now.isBefore(state.getPhaseEndsAt())) {
                    rules.advance(state, now); AiCommitments.reconcile(state, rules, "TIMEOUT");
                } else {
                    var receipt = rules.commitmentAction(state, turn.actorId(), RuleSupport.action("SKIP", "", null));
                    int actionStart = RuleSupport.maps(state.getData().get("events")).size();
                    AiCommitments.reconcile(state, rules, "OPPORTUNITY_LOST");
                    rules.onAiFailure(state, turn, now);
                    AiCommitments.submitted(state, receipt, "LEGAL_FALLBACK", actionStart);
                }
                finishJob(job, "FAILED", reason); persist(room, state, rules, false);
            } else finishJob(job, "DISCARDED", "TURN_OBSOLETE");
            prepareJob(state, rules);
        });
    }
    private void prepareJob(GameState state, GameRuleSet rules) {
        if ("SETTLEMENT".equals(state.getPhase()) || jobs.existsByRoomIdAndStatusIn(state.getRoomId(), List.of("QUEUED", "RUNNING"))) return;
        for (TurnRequest turn : rules.pendingTurns(state)) {
            if (automationBlocked(state,turn.actorId())) continue;
            String id = hash(turn.key());
            int resume=0;
            while (jobs.existsById(id)) {
                var previous=jobs.findById(id).orElseThrow();
                if (!"ADMIN_CONTROL".equals(previous.getDiagnostics().get("terminalReason"))) break;
                id=hash(turn.key()+":control-resume:"+(++resume));
            }
            if (jobs.existsById(id)) continue;
            AiTurnJob job = new AiTurnJob(); job.setId(id); job.setRoomId(state.getRoomId()); job.setInstanceId(RuleSupport.text(state.getData().get("archiveId")));
            job.setActorId(turn.actorId()); job.setKind(turn.kind()); job.setTurnKey(turn.key());
            AiJobDiagnostics.initialize(job, java.time.Instant.now(), java.time.ZoneId.systemDefault());
            long observing = System.nanoTime();
            job.setObservation(RuleSupport.map(JSON.convertValue(observations.build(state, rules, turn), Map.class)));
            job.getDiagnostics().put("observationMs", Math.max(0, (System.nanoTime() - observing) / 1_000_000));
            job.getDiagnostics().put("queuedEpochMs", java.time.Instant.now().toEpochMilli());
            jobs.save(job); break;
        }
    }
    private void persist(Room room, GameState state, GameRuleSet rules, boolean forcePush) {
        AiCommitments.reconcile(state, rules, "OPPORTUNITY_LOST");
        AiRoundReflection.closeRounds(state);
        List<Map<String, Object>> events = RuleSupport.maps(state.getData().get("events"));
        int recorded = RuleSupport.number(state.getData().get("recordedEventCount"), 0);
        boolean publicChanged = forcePush;
        Set<String> privateRecipients = new HashSet<>();
        for (int i = recorded; i < events.size(); i++) {
            Map<String, Object> e = events.get(i); Map<String, Object> data = RuleSupport.map(e.get("data"));
            data.put("eventId", e.get("eventId")); data.put("message", e.get("message")); data.put("ruleVersion", 2);
            GameEvent event = recorder.record(state, RuleSupport.text(e.get("type")), (String) e.get("actorId"), (String) e.get("targetId"),
                    GameEventVisibility.valueOf(RuleSupport.text(e.get("visibility"))), RuleSupport.strings(e.get("visibleTo")), data);
            event.setPhase(RuleSupport.text(e.get("phase"))); event.setRoundNumber(RuleSupport.number(e.get("round"), state.getRoundNumber()));
            publicChanged |= "PUBLIC".equals(e.get("visibility"));
            if ("PRIVATE".equals(e.get("visibility"))) privateRecipients.addAll(RuleSupport.strings(e.get("visibleTo")));
        }
        state.getData().put("recordedEventCount", events.size());
        if (!publicChanged) state.getPlayers().stream()
                .filter(player -> !player.isAi() && !rules.legalActions(state, player.getPlayerId()).isEmpty())
                .map(GamePlayerState::getPlayerId).forEach(privateRecipients::add);
        if (publicChanged) {
            state.getData().put("publicViewVersion", RuleSupport.number(state.getData().get("publicViewVersion"), 0) + 1);
        } else if (!privateRecipients.isEmpty()) {
            Map<String, Object> privateVersions = new LinkedHashMap<>(RuleSupport.map(state.getData().get("privateViewVersions")));
            for (String recipient : privateRecipients)
                privateVersions.put(recipient, RuleSupport.number(privateVersions.get(recipient), 0) + 1);
            state.getData().put("privateViewVersions", privateVersions);
        }
        if ("SETTLEMENT".equals(state.getPhase()) && !Boolean.TRUE.equals(state.getData().get("v2Settled"))) {
            state.getData().put("winnerIds", new ArrayList<>(rules.winningPlayerIds(state)));
            memories.finishGame(state);
            if (!"custom".equals(room.getConfig().get("wordPack"))) stats.recordResult(String.valueOf(state.getData().get("archiveId")), state.getGameId(), state.getPlayers(), rules.winningPlayerIds(state));
            archives.archiveFinishedGame(state, room); state.getData().put("v2Settled", true);
            room.setStatus(RoomStatus.WAITING); rooms.save(room);
        }
        // The event table is the complete history for new V2 instances. Keep only
        // the live public window in the mutable state row after event persistence.
        if (Boolean.TRUE.equals(state.getData().get("eventTableComplete")) && state.getLogs().size() > 100)
            state.setLogs(new ArrayList<>(state.getLogs().subList(state.getLogs().size() - 100, state.getLogs().size())));
        states.saveAndFlush(state);
        if (publicChanged) publishAfterCommit(room, state);
        else privateRefreshAfterCommit(state, rules, privateRecipients);
    }
    private List<String> roomControls(GameState state) {
        return safety.controls(AiSafetyContext.source("GAME").room(state.getRoomId(),state.getGameId()));
    }
    private boolean automationBlocked(GameState state,String actorId) {
        String persona=state.getPlayers().stream().filter(p -> p.getPlayerId().equals(actorId)).map(p -> p.getPersonaId()==null?"":p.getPersonaId()).findFirst().orElse("");
        var context=AiSafetyContext.source("AI_PLAYER").room(state.getRoomId(),state.getGameId()).user(actorId,actorId).persona(persona)
                .model(appProperties==null?null:appProperties.getAi().getDefaultModel());
        String kind=rules(state.getGameId()).pendingTurns(state).stream().filter(t -> actorId.equals(t.actorId())).map(TurnRequest::kind).findFirst().orElse("");
        return safety.blocksAutomation(context,kind);
    }
    /** Mutating transition only, never called by state reads. Preserve a deadline while automation is suspended. */
    private boolean syncSafetyClock(GameState state) {
        var controls=roomControls(state);
        boolean frozen=controls.stream().anyMatch(a -> List.of("PAUSE_ROOM","FORCE_OBSERVE","BLOCK").contains(a));
        var data=state.getData();
        if (frozen && (!data.containsKey("safetyClock") || !phaseToken(state).equals(RuleSupport.map(data.get("safetyClock")).get("phaseToken")))) {
            boolean newlyPaused = !data.containsKey("safetyClock");
            var clock=new LinkedHashMap<String,Object>(); clock.put("phaseToken",phaseToken(state));
            if (state.getPhaseEndsAt()!=null) clock.put("remainingMs",Math.max(0,Duration.between(LocalDateTime.now(),state.getPhaseEndsAt()).toMillis()));
            data.put("safetyClock",clock); state.setPhaseEndsAt(null);
            if (newlyPaused) RuleSupport.event(state,"SAFETY_PAUSED",null,null,"房间自动流程已暂停。",Map.of());
        } else if (!frozen && data.containsKey("safetyClock")) {
            var clock=RuleSupport.map(data.remove("safetyClock"));
            if (phaseToken(state).equals(clock.get("phaseToken")) && clock.get("remainingMs") instanceof Number n) state.setPhaseEndsAt(LocalDateTime.now().plusNanos(n.longValue()*1_000_000));
            RuleSupport.event(state,"SAFETY_RESUMED",null,null,"房间自动流程已恢复。",Map.of());
        }
        return frozen;
    }
    private void publishAfterCommit(Room room, GameState state) {
        Runnable publish = () -> {
            push.pushStateChange(room.getId(), new GameStateEvent("V2_STATE", state.getPhase(), state.getRoundNumber(), state.getCurrentSeat(),
                    Map.of("roomId", room.getId(), "viewVersion", publicVersion(state))));
        };
        afterCommit(publish);
    }
    private void privateRefreshAfterCommit(GameState state, GameRuleSet rules, Set<String> eventRecipients) {
        List<String> recipients = state.getPlayers().stream().filter(p -> !p.isAi() && (eventRecipients.contains(p.getPlayerId()) || !rules.legalActions(state, p.getPlayerId()).isEmpty())).map(GamePlayerState::getPlayerId).toList();
        afterCommit(() -> recipients.forEach(id -> push.pushPrivate(id, new PrivateEvent("V2_REFRESH",
                Map.of("roomId", state.getRoomId(), "viewVersion", viewVersion(state, id))))));
    }
    private String publicVersion(GameState state) {
        return RuleSupport.text(state.getData().get("archiveId")) + ":" + RuleSupport.number(state.getData().get("publicViewVersion"), 0);
    }
    private String viewVersion(GameState state, String viewerId) {
        int privateVersion = viewerId == null ? 0 : RuleSupport.number(RuleSupport.map(state.getData().get("privateViewVersions")).get(viewerId), 0);
        return publicVersion(state) + ":" + privateVersion;
    }
    private void decorateEvents(GameState state, int start, String actorId, AiTurnDecision decision) {
        if (RuleSupport.HOST.equals(actorId) || decision.presentation().isEmpty() || decision.speech().isBlank()) return;
        List<Map<String, Object>> events = RuleSupport.maps(state.getData().get("events")); Set<String> decorated = new HashSet<>();
        for (int i = start; i < events.size(); i++) {
            Map<String, Object> event = events.get(i);
            if (actorId.equals(event.get("actorId")) && RuleSupport.text(event.get("message")).contains(decision.speech())) {
                Map<String, Object> data = RuleSupport.map(event.get("data")); data.put("presentation", decision.presentation()); event.put("data", data);
                if ("PUBLIC".equals(event.get("visibility"))) decorated.add(RuleSupport.text(event.get("eventId")));
            }
        }
        state.getData().put("events", events);
        state.getLogs().stream().filter(l -> decorated.contains(RuleSupport.text(l.getMetadata().get("eventId")))).forEach(l -> l.getMetadata().put("presentation", decision.presentation()));
    }
    private void recordTrace(GameState state, AiTurnJob job, VisibleObservation observation, AiTurnDecision decision, int firstEvent) {
        AiDecisionTrace trace = new AiDecisionTrace(); Map<String, Object> metrics = decision.diagnostics();
        trace.setRoomId(state.getRoomId()); trace.setInstanceId(job.getInstanceId()); trace.setGameId(state.getGameId()); trace.setPhase(observation.phase()); trace.setRoundNumber(observation.round());
        trace.setActorPlayerId(job.getActorId()); trace.setAction(job.getKind()); trace.setRoleKey(RuleSupport.text(observation.self().get("role")));
        trace.setPersonaId(state.getPlayers().stream().filter(p -> p.getPlayerId().equals(job.getActorId())).map(p -> p.getPersonaId() == null ? "takeover" : p.getPersonaId()).findFirst().orElse("host"));
        trace.setFallback(decision.fallback()); trace.setValidDecision(true); trace.setModelKey(RuleSupport.text(metrics.get("modelKey")));
        trace.setPromptTokens(RuleSupport.number(metrics.get("promptTokens"), 0)); trace.setCompletionTokens(RuleSupport.number(metrics.get("completionTokens"), 0));
        trace.setLatencyMs(RuleSupport.number(metrics.get("latencyMs"), 0)); trace.setTargetPlayerId(decision.action().getTargetPlayerId()); trace.setNightAction(decision.action().getNightAction());
        trace.setInputSummary("v2/" + observation.phase() + "/" + job.getKind() + "/events=" + observation.events().size());
        trace.setOutputSummary(decision.speech().substring(0, Math.min(decision.speech().length(), 500)));
        Map<String, Object> committedMemory = memories.snapshot(state, job.getActorId());
        trace.setBeliefSnapshot(RuleSupport.map(observation.memory().get("beliefs"))); trace.setMemorySnapshot(committedMemory); trace.setRawOutput(RuleSupport.map(metrics.get("rawOutput")));
        List<String> qualityFlags = new ArrayList<>(RuleSupport.strings(metrics.get("qualityFlags")));
        qualityFlags.addAll(RuleSupport.strings(committedMemory.get("commitmentQualityFlags")));
        Map<String, Integer> commitmentStatuses = new LinkedHashMap<>();
        RuleSupport.maps(committedMemory.get("commitments")).forEach(e -> commitmentStatuses.merge(RuleSupport.text(e.get("status")), 1, Integer::sum));
        Map<String, Object> quality = new LinkedHashMap<>(Map.of("flags", qualityFlags.stream().distinct().toList(), "fallback", decision.fallback(),
                "calls", RuleSupport.number(metrics.get("calls"), 0), "promptVersion", RuleSupport.text(metrics.getOrDefault("promptVersion", AiTurnGenerator.PROMPT_VERSION)),
                "attempts", RuleSupport.maps(metrics.get("attempts")), "repaired", Boolean.TRUE.equals(metrics.get("repaired")), "jobId", job.getId(),
                "commitmentStatuses", commitmentStatuses, "memoryFormatVersion", AiMemoryEntries.FORMAT_VERSION, "memorySnapshotStage", "POST_ACTION"));
        quality.put("presetVersion", RuleSupport.number(observation.persona().get("presetVersion"), 0));
        quality.put("instanceId", job.getInstanceId());
        quality.putAll(com.aisocialgame.service.ai.v2.AiBuildIdentity.current());
        var generatedEvents=RuleSupport.maps(state.getData().get("events"));
        quality.put("eventIds",generatedEvents.stream().skip(firstEvent).map(e -> RuleSupport.text(e.get("eventId"))).toList());
        quality.put("diagnostics", new LinkedHashMap<>(job.getDiagnostics()));
        trace.setQuality(quality);
        // The action, memory, successful job and its trace must commit together.
        // A best-effort afterCommit callback could lose evidence for a successful move.
        traces.save(trace);
        for(int i=firstEvent;i<generatedEvents.size();i++) {
            var event=generatedEvents.get(i);var data=RuleSupport.map(event.get("data"));
            data.put("aiTraceId",trace.getId());data.put("aiFallback",decision.fallback());event.put("data",data);
        }
        state.getData().put("events",generatedEvents);
    }
    private void updateConnections(GameState state) {
        LocalDateTime now = LocalDateTime.now();
        for (GamePlayerState player : state.getPlayers()) {
            if (player.isAi()) continue;
            if (connections.isOnline(player.getPlayerId())) {
                if (!"ONLINE".equals(player.getConnectionStatus())) RuleSupport.event(state, "connection", player.getPlayerId(), null, player.getDisplayName() + "已回到对局", Map.of("connectionStatus", "ONLINE"));
                player.setConnectionStatus("ONLINE"); player.setDisconnectedAt(null); continue;
            }
            LocalDateTime last = player.getLastActiveAt() == null ? now : player.getLastActiveAt();
            if (last.plusSeconds(60).isAfter(now)) continue;
            if (player.getDisconnectedAt() == null) player.setDisconnectedAt(now);
            String next = player.getDisconnectedAt().plusSeconds(30).isAfter(now) ? "DISCONNECTED" : "AI_TAKEOVER";
            if (!next.equals(player.getConnectionStatus())) {
                player.setConnectionStatus(next);
                RuleSupport.event(state, "connection", player.getPlayerId(), null, player.getDisplayName() + ("AI_TAKEOVER".equals(next) ? "暂由 AI 托管" : "暂时离线"), Map.of("connectionStatus", next));
            }
        }
    }
    private GameStateResponse response(Room room, GameState state, String viewerId) {
        if (state == null) return new GameStateResponse(room.getId(), room.getGameId(), "WAITING", 0, null, null, null, viewerId, null, null, null, null, List.of(), List.of(), Map.of("ruleVersion", 2, "hostUserId", RuleSupport.text(room.getHostUserId())), Map.of(), null);
        GameRuleSet rules = rules(state.getGameId());
        GamePlayerState viewer = state.getPlayers().stream().filter(p -> p.getPlayerId().equals(viewerId)).findFirst().orElse(null);
        List<GamePlayerView> players = state.getPlayers().stream().map(p -> new GamePlayerView(p.getPlayerId(), p.getDisplayName(), p.getSeatNumber(), p.isAi(), p.getPersonaId(), p.getAvatar(), p.isAlive(), rules.visibleRole(state, p, viewerId), rules.visibleWord(state, p, viewerId), p.getConnectionStatus())).toList();
        Map<String, Object> extra = new LinkedHashMap<>(rules.publicData(state));
        if (viewer != null) extra.putAll(rules.privateData(state, viewerId));
        extra.put("ruleVersion", 2); extra.put("archiveId", state.getData().get("archiveId")); extra.put("phaseToken", viewerPhaseToken(state, rules, viewerId));
        extra.put("viewVersion", viewVersion(state, viewerId));
        com.aisocialgame.service.SettlementView.add(extra, state, viewerId);
        extra.put("hostUserId", RuleSupport.text(room.getHostUserId()));
        extra.put("legalActions", viewer == null ? List.of() : rules.legalActions(state, viewerId));
        boolean night = Set.of("NIGHT", "DEATH_ACTION").contains(state.getPhase()); Integer seat = night ? null : state.getCurrentSeat();
        String speaker = seat == null ? null : players.stream().filter(p -> p.getSeatNumber() == seat).map(GamePlayerView::getDisplayName).findFirst().orElse(null);
        Map<String, String> votes = new LinkedHashMap<>(); RuleSupport.map(extra.get("votes")).forEach((k, v) -> votes.put(k, RuleSupport.text(v)));
        List<GameLogEntry> visibleLogs = state.getLogs();
        visibleLogs = visibleLogs.subList(Math.max(0, visibleLogs.size() - 100), visibleLogs.size());
        return new GameStateResponse(room.getId(), state.getGameId(), state.getPhase(), state.getRoundNumber(), seat, speaker, (String) state.getData().get("winner"), viewerId, viewer == null ? null : viewer.getSeatNumber(), viewer == null ? null : rules.visibleWord(state, viewer, viewerId), viewer == null ? null : rules.visibleRole(state, viewer, viewerId), night ? null : state.getPhaseEndsAt(), players, visibleLogs, extra, votes, null);
    }
    private Room room(String roomId, String gameId, boolean lock) {
        Room room = (lock ? rooms.findByIdForUpdate(roomId) : rooms.findById(roomId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "房间不存在"));
        RuleSupport.require(room.getGameId().equals(gameId), "房间与玩法不匹配"); return room;
    }
    private void requireHost(Room room, User user) {
        if (user == null || !(user.getId().equals(room.getHostUserId()) || room.getSeats().stream().anyMatch(s -> s.isHost() && user.getId().equals(s.getPlayerId())))) throw new ApiException(HttpStatus.FORBIDDEN, "只有房主可以开始游戏");
    }
    private String phaseToken(GameState state) { return state.getData().get("archiveId") + ":" + RuleSupport.number(state.getData().get("phaseSerial"), 0); }
    private String viewerPhaseToken(GameState state, GameRuleSet rules, String viewerId) {
        boolean privateWindow = Set.of("NIGHT", "DEATH_ACTION").contains(state.getPhase());
        boolean canAct = viewerId != null && !rules.legalActions(state, viewerId).isEmpty();
        String version = privateWindow && !canAct ? state.getData().get("archiveId") + ":" + state.getPhase() + ":" + state.getRoundNumber() : phaseToken(state);
        return hash(version + ":" + RuleSupport.text(viewerId) + ":" + state.getData().get("randomSeed"));
    }
    private String serializedPublicView(Room room, GameState state) {
        try { return JSON.writeValueAsString(response(room, state, null)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Unable to snapshot public state", ex); }
    }
    private void finishJob(AiTurnJob job, String status, String reason) {
        job.setStatus(status); job.setCompletedAt(LocalDateTime.now());
        AiJobDiagnostics.completed(job, reason, java.time.Instant.now()); jobs.saveAndFlush(job);
        Object created = job.getDiagnostics().get("createdEpochMs");
        afterCommit(() -> {
            long end = java.time.Instant.now().toEpochMilli();
            Long elapsed = created instanceof Number n && end >= n.longValue() ? end - n.longValue() : null;
            log.info("AI task committed jobId={} status={} reason={} endToEndAfterCommitMs={}", job.getId(), status, reason, elapsed);
        });
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { task.run(); } });
        else task.run();
    }
}
