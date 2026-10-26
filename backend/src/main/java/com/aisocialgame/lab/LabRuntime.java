package com.aisocialgame.lab;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.RoomCodeGenerator;
import com.aisocialgame.service.ai.config.*;
import com.aisocialgame.service.v2.V2GameService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

@Service
public class LabRuntime {
    private final LabBudgetService budgets;
    public static final List<String> ACTIVE = List.of("WAITING","PLAYING");
    private final LabExperimentRepository experiments;
    private final LabMatchRepository matches;
    private final LabEnrollmentRepository enrollments;
    private final LabEvaluationRepository evaluations;
    private final RoomRepository rooms;
    private final GameStateRepository states;
    private final AiTurnJobRepository jobs;
    private final RoomCodeGenerator codes;
    private final V2GameService game;
    private final AiModelConfigService models;
    @org.springframework.beans.factory.annotation.Autowired private LabStrategyService strategies;

    public LabRuntime(LabExperimentRepository experiments,LabMatchRepository matches,LabEnrollmentRepository enrollments,
            LabEvaluationRepository evaluations,RoomRepository rooms,GameStateRepository states,AiTurnJobRepository jobs,
            RoomCodeGenerator codes,V2GameService game,AiModelConfigService models,LabBudgetService budgets) {
        this.experiments=experiments; this.matches=matches; this.enrollments=enrollments; this.evaluations=evaluations;
        this.rooms=rooms; this.states=states; this.jobs=jobs; this.codes=codes; this.game=game; this.models=models; this.budgets=budgets;
    }
    @Transactional
    public void advance(String id) {
        var experiment=experiments.lock(id).orElseThrow(LabService::missing);
        if(experiment.configuration.containsKey("activeScenario")) return;
        if("PAUSED".equals(experiment.status) && experiment.configuration.containsKey("manualReview")) return;
        var current=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,ACTIVE);
        if(!"STOPPING".equals(experiment.status) || !current.isEmpty()) budgets.pauseIfReached(experiment);
        if(LabBudgetService.paused(experiment)) for(var match:current) {
            if("PLAYING".equals(match.status)) game.pauseLabBudget(match.roomId);
            else match.snapshot.putIfAbsent("budgetStartsInMs",Math.max(0,match.startsAtEpochMs-System.currentTimeMillis()));
        }
        for(var match:current) {
            if("WAITING".equals(match.status)) {
                if(LabBudgetService.paused(experiment)) continue;
                if(!"RUNNING".equals(experiment.status)) { abortWaiting(match,"LAB_PAUSED_BEFORE_START"); continue; }
                if(match.startsAtEpochMs<=System.currentTimeMillis()) {
                    try { start(match); }
                    catch(ApiException failure) { abortWaiting(match,failure.getCode()==null?"LAB_START_FAILED":failure.getCode()); pause(experiment,match.reason); }
                }
            }
            if("PLAYING".equals(match.status)) {
                var state=states.findById(match.roomId).orElse(null);
                if(state!=null && state.getData().containsKey("labCallPause")) {
                    match.reason=text(state.getData().get("labCallPause")); matches.save(match);
                    pause(experiment,match.reason); experiment.configuration.put("callPaused",true);
                }
                if(state!=null && "SETTLEMENT".equals(state.getPhase())) {
                    collect(match,state);
                    if("ABORTED".equals(match.status) && !"STOPPING".equals(experiment.status)) pause(experiment,match.reason);
                }
            }
        }
        advanceReviewCursor(experiment);
        var active=matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,ACTIVE);
        boolean reviewPending=experiment.reviewCursor<experiment.nextSequence;
        if(LabBudgetService.paused(experiment) && active.isEmpty() && !reviewPending) {
            var evaluation=evaluations.findByExperimentIdOrderByCreatedAtDesc(id).stream().filter(row -> "RUNNING".equals(row.status)).findFirst().orElse(null);
            if(evaluation!=null && evaluation.nextGame>=evaluation.pairs*2) {
                evaluation.status="COMPLETED"; evaluations.save(evaluation);
                experiment.status=text(evaluation.configuration.get("returnStatus")); experiment.reason=null; experiment.configuration.remove("budgetPaused");
            } else if(evaluation==null && number(experiment.configuration.get("trainingCreated"),0)>=number(experiment.configuration.get("targetGames"),100)) {
                experiment.status="COMPLETED"; experiment.reason=null; experiment.configuration.remove("budgetPaused");
            }
        }
        if("STOPPING".equals(experiment.status)) {
            for(var pending:matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("FINISHED","REVIEW_FAILED","REVIEWING"))) {
                if("REVIEWING".equals(pending.status) && pending.reviewLeaseUntil!=null && pending.reviewLeaseUntil>System.currentTimeMillis()) continue;
                pending.status="REVIEWED"; pending.reason="STOPPED_BEFORE_REVIEW"; matches.save(pending);
            }
            advanceReviewCursor(experiment);
            if(active.isEmpty() && experiment.reviewCursor==experiment.nextSequence) {
                experiment.status="STOPPED";
                for(var evaluation:evaluations.findByExperimentIdOrderByCreatedAtDesc(id)) if("RUNNING".equals(evaluation.status)) {
                    evaluation.status="STOPPED"; evaluations.save(evaluation);
                }
            }
        } else if("PAUSING".equals(experiment.status) && active.isEmpty() && !reviewPending) experiment.status="PAUSED";
        if(!"RUNNING".equals(experiment.status)) { experiments.save(experiment); return; }
        var evaluation=evaluations.findByExperimentIdOrderByCreatedAtDesc(id).stream().filter(row -> "RUNNING".equals(row.status)).findFirst().orElse(null);
        int capacity=active.isEmpty() && matches.countByStatusIn(ACTIVE)<3?1:0;
        try {
            if(evaluation!=null) {
                while(capacity-->0 && evaluation.nextGame<evaluation.pairs*2) createMatch(experiment,evaluation);
                if(evaluation.nextGame>=evaluation.pairs*2 && matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,ACTIVE).isEmpty()) {
                    evaluation.status="COMPLETED"; experiment.status=text(evaluation.configuration.get("returnStatus"));
                }
                evaluations.save(evaluation);
            } else {
                if(experiment.reviewCursor<experiment.nextSequence) { experiments.saveAndFlush(experiment); return; }
                int target=number(experiment.configuration.get("targetGames"),100);
                int created=number(experiment.configuration.get("trainingCreated"),0);
                if(LabGameConfig.werewolf(experiment.configuration) && created>0 && created<target
                        && budgets.spentPoints(experiment.id)>=((Number)experiment.configuration.getOrDefault("trainingCheckpointPoints",900L)).longValue()) {
                    pause(experiment,"TRAINING_CHECKPOINT_REACHED");
                    experiments.saveAndFlush(experiment); return;
                }
                while(capacity-->0 && created<target) { createMatch(experiment,null); created++; }
                experiment.configuration.put("trainingCreated",created);
                if(created>=target && matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,ACTIVE).isEmpty()
                        && experiment.reviewCursor==experiment.nextSequence) experiment.status="COMPLETED";
            }
        } catch(ApiException failure) { pause(experiment,failure.getCode()==null?"LAB_CONFIGURATION_INVALID":failure.getCode()); }
        experiments.saveAndFlush(experiment);
    }
    private void createMatch(LabExperiment experiment,LabEvaluation evaluation) {
        var roster=new ArrayList<>(maps(evaluation==null?experiment.configuration.get("roster"):evaluation.configuration.get("roster")));
        for(var profile:roster) models.requireUsable(AiModelSelection.fromMap(map(profile.get("modelSelection"))));
        int pair=evaluation==null?0:evaluation.nextGame/2;
        var match=new LabMatch(); match.experimentId=experiment.id; match.sequenceNumber=experiment.nextSequence++;
        match.roomId=UUID.randomUUID().toString();
        match.randomSeed=evaluation==null?LabGameConfig.seed(((Number)experiment.configuration.getOrDefault("randomSeed",new Random().nextLong())).longValue(),"match:"+match.sequenceNumber):evaluation.randomSeed+pair;
        match.startsAtEpochMs=System.currentTimeMillis();
        var heads=map(evaluation==null?experiment.configuration.get("experiences"):evaluation.configuration.get("initialExperiences"));
        if(evaluation!=null) {
            match.evaluationId=evaluation.id;
            boolean candidateFirst=flag(evaluation.configuration.get("candidateFirst"));
            match.variant=(evaluation.nextGame%2==0)==candidateFirst?"CANDIDATE":"BASELINE";
            if("CANDIDATE".equals(match.variant) && evaluation.configuration.containsKey("candidate")) heads.put(evaluation.participantId,evaluation.configuration.get("candidate"));
            match.snapshot.put("pairIndex",pair); evaluation.nextGame++;
        }
        var players=new LinkedHashMap<String,Object>();
        var seats=new ArrayList<RoomSeat>();
        for(var profile:roster) {
            String participantId=LabService.participantId(profile),actorId="lab-"+participantId;
            var snapshot=new LinkedHashMap<>(profile); snapshot.put("participantId",participantId); snapshot.putAll(map(heads.get(participantId)));
            if(LabGameConfig.werewolf(experiment.configuration)) {
                var strategy=map(experiment.configuration.get("initialStrategy"));
                if(evaluation==null && !text(experiment.configuration.get("strategyHeadId")).isBlank())
                    strategy=strategies.strategySnapshot(text(experiment.configuration.get("strategyHeadId")));
                else if(evaluation!=null) {
                    strategy=map(evaluation.configuration.get("initialStrategy"));
                    if("CANDIDATE".equals(match.variant) && participantId.equals(evaluation.participantId)) strategy=map(evaluation.configuration.get("candidateStrategy"));
                }
                snapshot.put("strategySnapshot",strategy);
            }
            players.put(actorId,snapshot);
            seats.add(new RoomSeat(seats.size(),actorId,text(profile.get("name")),true,text(profile.get("personaId")),
                    text(map(profile.get("persona")).get("avatar")),true,false));
        }
        boolean werewolf=LabGameConfig.werewolf(experiment.configuration);
        Map<String,Object> configuration=werewolf?new LinkedHashMap<>(map(experiment.configuration.get("rules"))):new LinkedHashMap<>(Map.of("playerCount",roster.size(),"wordPack",experiment.configuration.get("wordPack"),
                "hasBlank",flag(experiment.configuration.get("hasBlank")),"speakTime",90,"aiDifficulty",2,"spyMode","auto"));
        String roomName="AI 实验 · "+experiment.name+" · "+match.sequenceNumber;
        var room=new Room(match.roomId,LabGameConfig.gameId(experiment.configuration),roomName.substring(0,Math.min(128,roomName.length())),
                RoomStatus.WAITING,roster.size(),false,null,"text",configuration);
        room.setSource("LAB"); room.setRoomCode(nextRoomCode()); room.setSeats(seats);
        room.getPrivateConfig().put("labSeed",match.randomSeed);
        if(werewolf) {
            room.getPrivateConfig().put("labRoleSeed",LabGameConfig.seed(match.randomSeed,"roles"));
            room.getPrivateConfig().put("labSeatSeed",LabGameConfig.seed(match.randomSeed,"seats"));
            match.snapshot.put("seeds",Map.of("seatSeed",LabGameConfig.seed(match.randomSeed,"seats"),"roleSeed",LabGameConfig.seed(match.randomSeed,"roles")));
        }
        room.getPrivateConfig().put("lab",Map.of("experimentId",experiment.id,"matchId",match.id,
                "labRunMode",evaluation==null?"TRAINING":"EVALUATION","modelSelection",map(roster.getFirst().get("modelSelection"))));
        if(werewolf && evaluation!=null && !text(evaluation.configuration.get("targetRole")).isBlank())
            room.getPrivateConfig().put("labRoleAssignments",roleAssignments(players,roster.size(),evaluation.participantId,text(evaluation.configuration.get("targetRole")),match.randomSeed));
        room.getPrivateConfig().put("labPlayers",players);
        match.snapshot.put("players",players); match.snapshot.put("rules",new LinkedHashMap<>(configuration));
        match.snapshot.put("coach",map(experiment.configuration.get("coach")));
        match.snapshot.put("gameId",room.getGameId());
        rooms.saveAndFlush(room); matches.saveAndFlush(match);
        if(evaluation==null) {
            var waiting=enrollments.findByExperimentIdAndStatusOrderByJoinedAtAsc(experiment.id,"WAITING");
            for(var enrollment:waiting.stream().limit(roster.size()-1L).toList()) {
                enrollment.status="OFFERED"; enrollment.matchId=match.id; enrollment.confirmUntil=System.currentTimeMillis()+30_000;
                match.startsAtEpochMs=enrollment.confirmUntil; enrollments.save(enrollment);
            }
        }
        matches.save(match);
    }
    private void start(LabMatch match) {
        var room=rooms.findByIdForUpdate(match.roomId).orElseThrow();
        if(room.getStatus()==RoomStatus.PLAYING) { match.status="PLAYING"; matches.save(match); return; }
        var entries=enrollments.findByMatchIdOrderByJoinedAtAscIdAsc(match.id);
        var confirmed=entries.stream().filter(entry -> "CONFIRMED".equals(entry.status)).toList();
        for(int seat=0;seat<confirmed.size();seat++) {
            var enrollment=confirmed.get(seat);
            room.getSeats().set(seat,new RoomSeat(seat,enrollment.userId,enrollment.displayName,false,null,enrollment.avatar,true,false));
            enrollment.status="PLAYING"; enrollments.save(enrollment);
        }
        for(var entry:entries) if("OFFERED".equals(entry.status)) { entry.status="CANCELLED"; enrollments.save(entry); }
        var snapshots=map(room.getPrivateConfig().get("labPlayers"));
        var activeIds=new HashSet<>(room.getSeats().stream().map(RoomSeat::getPlayerId).toList());
        snapshots.keySet().retainAll(activeIds); room.getPrivateConfig().put("labPlayers",snapshots);
        match.snapshot.put("players",snapshots);
        if(!match.snapshot.containsKey("seatOrder")) {
            var shuffled=new ArrayList<>(room.getSeats());
            Collections.shuffle(shuffled,new Random("werewolf".equals(room.getGameId())?LabGameConfig.seed(match.randomSeed,"seats"):match.randomSeed));
            for(int seat=0;seat<shuffled.size();seat++) shuffled.get(seat).setSeatNumber(seat);
            room.setSeats(shuffled); match.snapshot.put("seatOrder",shuffled.stream().map(RoomSeat::getPlayerId).toList());
        }
        rooms.saveAndFlush(room); game.startLab(match.roomId);
        states.findById(match.roomId).ifPresent(state -> match.snapshot.put("roleAssignments",state.getPlayers().stream()
                .collect(java.util.stream.Collectors.toMap(GamePlayerState::getPlayerId,GamePlayerState::getRole))));
        match.status="PLAYING"; matches.saveAndFlush(match);
    }
    private String nextRoomCode() {
        for(int attempt=0;attempt<32;attempt++) {
            String candidate=codes.next();
            if(!rooms.existsByRoomCode(candidate)) return candidate;
        }
        throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,"暂时无法分配实验房间码","LAB_ROOM_CODE_UNAVAILABLE",Map.of());
    }
    private void collect(LabMatch match,GameState state) {
        match.archiveId=text(state.getData().get("archiveId")); match.finishedAt=LocalDateTime.now();
        match.reason=state.getData().containsKey("labFailureReason")?text(state.getData().get("labFailureReason")):null;
        match.status=match.reason!=null?"ABORTED":match.evaluationId==null?"FINISHED":"REVIEWED";
        var turns=jobs.findByInstanceIdOrderByCreatedAtAsc(match.archiveId);
        var frozen=map(state.getData().get("labPlayers"));
        var winners=new HashSet<>(strings(state.getData().get("winnerIds")));
        var results=new ArrayList<Map<String,Object>>();
        for(var player:state.getPlayers()) {
            var row=new LinkedHashMap<String,Object>();
            row.put("actorId",player.getPlayerId()); row.put("ai",player.isAi()); row.put("name",player.getDisplayName());
            row.put("agentId",text(map(frozen.get(player.getPlayerId())).get("agentId")));
            row.put("participantId",LabService.participantId(map(frozen.get(player.getPlayerId()))));
            row.put("role",player.getRole()); row.put("won",winners.contains(player.getPlayerId()));
            row.put("successfulActions",turns.stream().filter(turn -> player.getPlayerId().equals(turn.getActorId()) && "SUCCEEDED".equals(turn.getStatus())).count());
            row.put("failedActions",turns.stream().filter(turn -> player.getPlayerId().equals(turn.getActorId()) && "FAILED".equals(turn.getStatus())).count());
            results.add(row);
        }
        match.snapshot.put("results",results); match.snapshot.put("winner",text(state.getData().get("winner")));
        matches.saveAndFlush(match);
        for(var entry:enrollments.findByMatchIdOrderByJoinedAtAscIdAsc(match.id)) if("PLAYING".equals(entry.status)) { entry.status="FINISHED"; enrollments.save(entry); }
    }
    private void abortWaiting(LabMatch match,String reason) {
        match.status="ABORTED"; match.reason=reason==null?"LAB_CANCELLED":reason; match.finishedAt=LocalDateTime.now(); matches.save(match);
        for(var entry:enrollments.findByMatchIdOrderByJoinedAtAscIdAsc(match.id)) if(Set.of("OFFERED","CONFIRMED").contains(entry.status)) { entry.status="CANCELLED"; enrollments.save(entry); }
    }
    private void advanceReviewCursor(LabExperiment experiment) {
        while(experiment.reviewCursor<experiment.nextSequence) {
            var match=matches.findByExperimentIdAndSequenceNumber(experiment.id,experiment.reviewCursor).orElseThrow();
            if(!Set.of("REVIEWED","ABORTED").contains(match.status)) break;
            experiment.reviewCursor++;
        }
    }
    public static void pause(LabExperiment experiment,String reason) { experiment.status="PAUSED"; experiment.reason=reason; }
    private static Map<String,String> roleAssignments(Map<String,Object> players,int count,String participantId,String role,long seed) {
        var roles=new ArrayList<>(count==9?List.of("WEREWOLF","WEREWOLF","WEREWOLF","SEER","WITCH","HUNTER","VILLAGER","VILLAGER","VILLAGER"):
                List.of("WEREWOLF","WEREWOLF","SEER","WITCH","VILLAGER","VILLAGER"));
        require(roles.remove(role),"目标身份不属于当前板子");
        Collections.shuffle(roles,new Random(LabGameConfig.seed(seed,"evaluation-roles")));
        var assigned=new LinkedHashMap<String,String>();
        String target="lab-"+participantId; require(players.containsKey(target),"被测席位不存在");
        assigned.put(target,role);
        for(String actor:players.keySet()) if(!actor.equals(target)) assigned.put(actor,roles.removeFirst());
        return assigned;
    }
}
