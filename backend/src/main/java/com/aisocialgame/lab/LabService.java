package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.PersonaRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.service.v2.V2GameService;
import com.aisocialgame.service.ai.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

@Service
@Transactional(readOnly = true)
public class LabService {
    private final LabExperimentRepository experiments;
    private final LabAgentRepository agents;
    private final LabExperienceRepository experiences;
    private final LabMatchRepository matches;
    private final LabEnrollmentRepository enrollments;
    private final LabEvaluationRepository evaluations;
    private final PersonaRepository personas;
    private final AiModelConfigService models;
    private final LabBudgetService budgets;
    private final ObjectMapper json;
    private final RoomRepository rooms;
    private final V2GameService game;
    @org.springframework.beans.factory.annotation.Autowired private com.aisocialgame.integration.grpc.client.AiGrpcClient client;
    @org.springframework.beans.factory.annotation.Autowired private com.aisocialgame.config.AppProperties properties;
    @org.springframework.beans.factory.annotation.Autowired private LabStrategyService strategies;
    @org.springframework.beans.factory.annotation.Autowired private LabScenarioFactory scenarios;

    public LabService(LabExperimentRepository experiments, LabAgentRepository agents, LabExperienceRepository experiences,
            LabMatchRepository matches, LabEnrollmentRepository enrollments, LabEvaluationRepository evaluations,
            PersonaRepository personas, AiModelConfigService models, LabBudgetService budgets, ObjectMapper json,
            RoomRepository rooms, V2GameService game) {
        this.experiments=experiments; this.agents=agents; this.experiences=experiences; this.matches=matches;
        this.enrollments=enrollments; this.evaluations=evaluations; this.personas=personas; this.models=models;
        this.budgets=budgets; this.json=json;
        this.rooms=rooms; this.game=game;
    }
    public List<LabAgent> agents() { return agents.findAllByOrderByCreatedAtAsc(); }
    @Transactional
    public LabAgent saveAgent(String id, LabRequests.Agent request) {
        require(personas.findById(request.personaId()) != null, "AI 人格不存在");
        var selection=request.selection().snapshot(); models.requireUsable(selection);
        LabAgent agent=id == null ? new LabAgent() : agents.findById(id).orElseThrow(LabService::missing);
        agent.name=request.name().strip(); agent.personaId=request.personaId(); agent.modelSelection=selection.toMap();
        return agents.saveAndFlush(agent);
    }
    @Transactional
    public Map<String,Object> create(LabRequests.Experiment request, String operator) {
        require(request.participants()==null || request.agentIds()==null,"请仅提交一种实验阵容");
        var participants=request.participants();
        if(participants==null && request.agentIds()!=null) participants=request.agentIds().stream().map(agentId ->
                new LabRequests.Participant(agentId,request.initialExperienceIds()==null?null:request.initialExperienceIds().get(agentId))).toList();
        require(participants!=null && participants.size()==request.playerCount(),"每个席位需要一个 AI 档案");
        String gameId=request.gameId()==null?"undercover":request.gameId();
        require(Set.of("undercover","werewolf").contains(gameId),"实验玩法不支持");
        boolean werewolf="werewolf".equals(gameId);
        Map<String,Object> rules=werewolf?LabGameConfig.werewolfRules(request.playerCount(),request.rules()):Map.of();
        if(!werewolf) require(Set.of("daily","idiom","acg","tech").contains(text(request.wordPack())), "实验室仅支持内置词库");
        if(werewolf) require(request.learningMode()==null || "SHARED_STRATEGY".equals(request.learningMode()),"狼人杀采用共享策略学习");
        if(request.trainingCheckpointPoints()!=null) require(request.trainingCheckpointPoints()>0,"训练检查线必须为正整数");
        models.requireUsable(request.coach().snapshot());
        var experiment=new LabExperiment(); experiment.name=request.name().strip(); experiment.createdBy=operator;
        experiment.budgetPoints=request.budgetPoints();
        var roster=new ArrayList<Map<String,Object>>();
        var heads=new LinkedHashMap<String,Object>();
        var copyNumbers=new HashMap<String,Integer>();
        var counts=new HashMap<String,Long>();
        for(var participant:participants) counts.merge(participant.agentId(),1L,Long::sum);
        for (var participant:participants) {
            String agentId=participant.agentId();
            var agent=agents.findById(agentId).orElseThrow(LabService::missing);
            var selected=participant.selection()==null?AiModelSelection.fromMap(agent.modelSelection):participant.selection().snapshot();
            models.requireUsable(selected);
            var persona=PersonaPresets.complete(personas.findById(agent.personaId));
            Map<String,Object> profile=map(json.convertValue(persona,Map.class));
            var player=new LinkedHashMap<String,Object>();
            String participantId=UUID.randomUUID().toString();
            int copyNumber=copyNumbers.merge(agentId,1,Integer::sum);
            String name=counts.get(agentId)>1?agent.name.substring(0,Math.min(59,agent.name.length()))+" #"+copyNumber:agent.name;
            player.put("agentId",agent.id); player.put("participantId",participantId); player.put("name",name); player.put("personaId",agent.personaId);
            player.put("persona",profile); player.put("modelSelection",new LinkedHashMap<>(selected.toMap()));
            roster.add(player);
            Map<String,Object> initial=new LinkedHashMap<>(Map.of("experienceId","initial","experience","","sequenceNumber",0));
            String inherited=participant.initialExperienceId();
            if(werewolf) require(inherited==null || inherited.isBlank(),"狼人杀请使用共享策略版本");
            if(inherited!=null && !inherited.isBlank()) {
                var source=experiences.findById(inherited).orElseThrow(LabService::missing);
                require(agentId.equals(source.agentId), "经验与 AI 档案不匹配");
                initial.put("experienceId",source.id); initial.put("experience",source.summary);
            }
            heads.put(participantId,initial);
        }
        experiment.configuration.putAll(Map.of("playerCount",request.playerCount(),"targetGames",request.targetGames(),
                "wordPack",werewolf?"":request.wordPack(),"hasBlank",!werewolf && request.hasBlank(),
                "roster",roster,"coach",request.coach().snapshot().toMap(),"initialExperiences",heads,
                "experiences",new LinkedHashMap<>(heads)));
        experiment.configuration.put("gameId",gameId);
        experiment.configuration.put("randomSeed",request.randomSeed()==null?new Random().nextLong():request.randomSeed());
        if(werewolf) {
            require(roster.stream().map(row -> row.get("modelSelection")).distinct().count()==1,"首期共享策略需要所有席位使用相同模型及思考档位");
            experiment.configuration.put("rules",rules);
            experiment.configuration.put("learningMode","SHARED_STRATEGY");
            experiment.configuration.put("trainingCheckpointPoints",request.trainingCheckpointPoints()==null?900L:request.trainingCheckpointPoints());
            experiment.configuration.put("trainingDecisionMillis",150_000);
            experiment.configuration.put("trainingRequestSeconds",120);
            if(request.initialStrategyVersionId()!=null && !request.initialStrategyVersionId().isBlank()) {
                var initial=strategies.strategySnapshot(request.initialStrategyVersionId());
                require(LabStrategyService.compatible(initial,rules,map(roster.getFirst().get("modelSelection"))),"初始策略与本实验规则或模型不兼容");
                experiment.configuration.put("initialStrategy",initial);
                experiment.configuration.put("strategyHeadId",request.initialStrategyVersionId());
            }
            experiment.configuration.put("scenarioManifest",scenarios.build(experiment));
            experiment.configuration.put("scenarioManifestVersion","werewolf-holdout-v1");
        }
        experiments.saveAndFlush(experiment);
        return detail(experiment.id,null,true);
    }
    @Transactional
    public void deleteAgent(String id) {
        agents.delete(agents.findById(id).orElseThrow(LabService::missing));
    }
    public Map<String,Object> list(int page, int size) {
        var result=experiments.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(0,page),Math.clamp(size,1,50)));
        return Map.of("items",result.getContent().stream().map(this::summary).toList(),"total",result.getTotalElements(),"page",result.getNumber());
    }
    public Map<String,Object> detail(String id, String userId, boolean administrator) {
        var experiment=experiments.findById(id).orElseThrow(LabService::missing);
        var result=new LinkedHashMap<>(summary(experiment));
        result.put("activeMatches",matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("WAITING","PLAYING")).stream().map(this::matchView).toList());
        result.put("evaluations",evaluations.findByExperimentIdOrderByCreatedAtDesc(id).stream().map(this::evaluationView).toList());
        if(userId!=null) result.put("enrollment",enrollments.findByExperimentIdAndUserId(id,userId).map(this::enrollmentView).orElse(Map.of()));
        if(administrator) {
            var configuration=new LinkedHashMap<>(experiment.configuration);
            configuration.remove("concurrency"); configuration.put("roster",roster(configuration));
            result.put("configuration",configuration);
            result.put("reviewFailed",!matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("REVIEW_FAILED")).isEmpty());
            matches.findFirstByExperimentIdAndReasonIsNotNullOrderBySequenceNumberDesc(id).ifPresent(match -> result.put("latestFailureMatchId",match.id));
        }
        return result;
    }
    public Map<String,Object> observe(String id,String matchId) {
        var match=matches.findById(matchId).orElseThrow(LabService::missing);
        require(id.equals(match.experimentId),"对局不属于此实验");
        var room=rooms.findById(match.roomId).orElseThrow(LabService::missing);
        require(room.isLab(),"不是实验室对局");
        var result=new LinkedHashMap<String,Object>(); result.put("match",matchView(match));
        result.put("room",Map.of("id",room.getId(),"gameId",room.getGameId(),"name",room.getName(),"roomCode",text(room.getRoomCode())));
        try { result.put("state",game.labSpectatorState(match.roomId)); }
        catch(ApiException failure) { if(match.archiveId==null || !"ROOM_EXPIRED".equals(failure.getCode())) throw failure; }
        return result;
    }
    public Map<String,Object> matchPage(String id, int page, int size, boolean administrator) {
        experiments.findById(id).orElseThrow(LabService::missing);
        var result=matches.findByExperimentIdOrderBySequenceNumberDesc(id,PageRequest.of(Math.max(0,page),Math.clamp(size,1,50)));
        return Map.of("items",result.getContent().stream().map(match -> {
            var view=new LinkedHashMap<>(matchView(match));
            if(administrator) view.put("snapshot",match.snapshot);
            return view;
        }).toList(),"total",result.getTotalElements(),"page",result.getNumber());
    }
    public Map<String,Object> experiencePage(String id,int page) {
        experiments.findById(id).orElseThrow(LabService::missing);
        var result=experiences.findByExperimentIdOrderByCreatedAtDesc(id,PageRequest.of(Math.max(0,page),30));
        return Map.of("items",result.getContent(),"total",result.getTotalElements(),"page",result.getNumber());
    }
    @Transactional
    public Map<String,Object> updateBudget(String id, long points) {
        var experiment=experiments.lock(id).orElseThrow(LabService::missing);
        long spent=budgets.spentPoints(id);
        if(points<1 || points<spent) throw new ApiException(HttpStatus.BAD_REQUEST,"预算不能低于当前已扣积分", "LAB_BUDGET_BELOW_SPENT",
                Map.of("spentPoints",spent,"minimumPoints",Math.max(1,spent)));
        // A room can freeze between settlement and the experiment coordinator's next pulse.
        // Serialize with that room before raising the limit so an existing pause still requires explicit resume.
        boolean preservePause=budgets.pauseIfReached(experiment) || LabBudgetService.paused(experiment);
        if(Set.of("RUNNING","PAUSING","STOPPING","PAUSED").contains(experiment.status))
            for(var match:matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("PLAYING")))
                preservePause=game.hasRecordedLabBudgetPause(match.roomId) || preservePause;
        if(preservePause) LabBudgetService.markPaused(experiment);
        experiment.budgetPoints=points;
        if(experiment.configuration.containsKey("activeScenario") && spent>=points) {
            String operation=text(experiment.configuration.get("activeScenario"));var runs=map(experiment.configuration.get("scenarioRuns"));var run=map(runs.get(operation));
            var current=map(run.get("current"));
            if("RUNNING".equals(run.get("status")) && current.get("deadlineEpochMs") instanceof Number deadline) {current.put("remainingMillis",Math.max(0,deadline.longValue()-System.currentTimeMillis()));run.put("current",current);}
            run.put("status","PAUSED");run.put("reason",LabBudgetService.EXHAUSTED);
            runs.put(operation,run);experiment.configuration.put("scenarioRuns",runs);LabBudgetService.markPaused(experiment);
        }
        if(budgets.pauseIfReached(experiment) || preservePause) pauseBudgetMatches(experiment.id);
        experiments.saveAndFlush(experiment);
        return detail(id,null,true);
    }
    private void pauseBudgetMatches(String id) {
        for(var match:matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("WAITING","PLAYING"))) {
            if("PLAYING".equals(match.status)) game.pauseLabBudget(match.roomId);
            else match.snapshot.putIfAbsent("budgetStartsInMs",Math.max(0,match.startsAtEpochMs-System.currentTimeMillis()));
        }
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public List<Map<String,Object>> preflight(String id) {
        var experiment=experiments.findById(id).orElseThrow(LabService::missing);
        var checked=new ArrayList<Map<String,Object>>();
        var selections=new LinkedHashSet<AiModelSelection>();
        for(var player:roster(experiment.configuration)) selections.add(AiModelSelection.fromMap(map(player.get("modelSelection"))));
        for(var selection:selections) checked.add(client.preflight(properties.getProjectKey(),properties.getAi().getSystemUserId(),selection,8192));
        checked.add(client.preflight(properties.getProjectKey(),properties.getAi().getSystemUserId(),AiModelSelection.fromMap(map(experiment.configuration.get("coach"))),32768));
        return checked;
    }
    @Transactional
    public Map<String,Object> control(String id, String action) {
        var experiment=experiments.lock(id).orElseThrow(LabService::missing);
        switch(action) {
            case "start", "resume" -> {
                if(experiment.configuration.containsKey("activeScenario")) {
                    budgets.requireRemaining(experiment);
                    String operation=text(experiment.configuration.get("activeScenario"));
                    var runs=map(experiment.configuration.get("scenarioRuns"));var run=map(runs.get(operation));
                    require("PAUSED".equals(run.get("status")),"情境评测正在运行");
                    run.put("status","RUNNING");run.remove("reason");
                    var current=map(run.get("current"));
                    if(!current.isEmpty()) {current.put("deadlineEpochMs",System.currentTimeMillis()+Math.max(0,number(current.get("remainingMillis"),50_000)));run.put("current",current);}
                    runs.put(operation,run);experiment.configuration.put("scenarioRuns",runs);experiment.reason=null;experiment.configuration.remove("budgetPaused");
                    break;
                }
                require(!experiment.configuration.containsKey("manualReview"),"请等待单次复盘完成，实验保持暂停");
                require(Set.of("DRAFT","PAUSED").contains(experiment.status), "实验当前不能启动");
                require(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("REVIEW_FAILED")).isEmpty(),"请先重试失败的复盘");
                budgets.requireRemaining(experiment);
                for(var profile:maps(experiment.configuration.get("roster"))) models.requireUsable(AiModelSelection.fromMap(map(profile.get("modelSelection"))));
                models.requireUsable(AiModelSelection.fromMap(map(experiment.configuration.get("coach"))));
                experiment.status="RUNNING"; experiment.reason=null;
                experiment.configuration.remove("budgetPaused"); experiment.configuration.remove("callPaused");
                for(var match:matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("WAITING","PLAYING"))) {
                    if("PLAYING".equals(match.status)) { game.resumeLabBudget(match.roomId); match.reason=null; matches.save(match); }
                    else if(match.snapshot.remove("budgetStartsInMs") instanceof Number remaining)
                        match.startsAtEpochMs=System.currentTimeMillis()+remaining.longValue();
                }
            }
            case "pause" -> {
                if(experiment.configuration.containsKey("activeScenario")) {
                    String operation=text(experiment.configuration.get("activeScenario"));var runs=map(experiment.configuration.get("scenarioRuns"));var run=map(runs.get(operation));
                    var current=map(run.get("current"));
                    if("RUNNING".equals(run.get("status")) && current.get("deadlineEpochMs") instanceof Number deadline) {current.put("remainingMillis",Math.max(0,deadline.longValue()-System.currentTimeMillis()));run.put("current",current);}
                    run.put("status","PAUSED");run.put("reason","ADMIN_PAUSED");runs.put(operation,run);experiment.configuration.put("scenarioRuns",runs);
                } else {require("RUNNING".equals(experiment.status),"实验尚未运行");experiment.status="PAUSING";}
            }
            case "stop" -> {
                require(!Set.of("COMPLETED","STOPPED").contains(experiment.status),"实验已经结束");
                if(experiment.configuration.containsKey("activeScenario")) {
                    String operation=text(experiment.configuration.remove("activeScenario"));var runs=map(experiment.configuration.get("scenarioRuns"));var run=map(runs.get(operation));
                    run.put("status","STOPPED");run.put("reason","ADMIN_STOPPED");runs.put(operation,run);experiment.configuration.put("scenarioRuns",runs);
                }
                if(budgets.isPaused(id) || Boolean.TRUE.equals(experiment.configuration.get("callPaused"))) for(var match:matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("WAITING","PLAYING"))) {
                    if("PLAYING".equals(match.status)) match.archiveId=game.stopLabBudget(match.roomId);
                    match.status="ABORTED"; match.reason="LAB_STOPPED_BY_ADMIN"; match.finishedAt=LocalDateTime.now();
                    for(var entry:enrollments.findByMatchIdOrderByJoinedAtAscIdAsc(match.id))
                        if(Set.of("PLAYING","OFFERED","CONFIRMED").contains(entry.status)) { entry.status="CANCELLED"; enrollments.save(entry); }
                    matches.save(match);
                }
                experiment.configuration.remove("budgetPaused"); experiment.status="STOPPING";
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,"未知实验操作");
        }
        experiments.saveAndFlush(experiment); return detail(id,null,true);
    }
    @Transactional
    public void rollback(String id, String experienceId) {
        var experiment=experiments.lock(id).orElseThrow(LabService::missing);
        require(Set.of("DRAFT","PAUSED","COMPLETED").contains(experiment.status),"请先暂停实验");
        var source=experiences.findById(experienceId).orElseThrow(LabService::missing);
        require(id.equals(source.experimentId),"经验不属于此实验");
        var heads=map(experiment.configuration.get("experiences"));
        require(heads.containsKey(source.participantId),"经验与实验阵容不匹配");
        var version=new LabExperience(); version.experimentId=id; version.agentId=source.agentId;
        version.participantId=source.participantId;
        version.sequenceNumber=experiences.latestSequence(id,source.participantId)+1; version.summary=source.summary;
        version.previousId=text(map(heads.get(source.participantId)).get("experienceId")); version.evidence=Map.of("rollbackOf",source.id);
        experiences.saveAndFlush(version); heads.put(source.participantId,experienceHead(version));
        experiment.configuration.put("experiences",heads); experiments.save(experiment);
    }
    @Transactional
    public Map<String,Object> enroll(String id, User user) {
        var experiment=experiments.lock(id).orElseThrow(LabService::missing);
        require(Set.of("DRAFT","RUNNING","PAUSING","PAUSED").contains(experiment.status),"实验不再接受报名");
        require(evaluations.findByExperimentIdOrderByCreatedAtDesc(id).stream().noneMatch(evaluation -> "RUNNING".equals(evaluation.status)),"对照评测只运行纯 AI 对局");
        var enrollment=enrollments.findByExperimentIdAndUserId(id,user.getId()).orElseGet(LabEnrollment::new);
        if(enrollment.experimentId!=null && "WAITING".equals(enrollment.status)) return enrollmentView(enrollment);
        require(!Set.of("OFFERED","CONFIRMED","PLAYING").contains(enrollment.status),"你已报名或正在参战");
        enrollment.experimentId=id; enrollment.userId=user.getId(); enrollment.displayName=user.getNickname(); enrollment.avatar=user.getAvatar();
        enrollment.status="WAITING"; enrollment.matchId=null; enrollment.confirmUntil=null; enrollment.joinedAt=LocalDateTime.now();
        return enrollmentView(enrollments.saveAndFlush(enrollment));
    }
    @Transactional
    public Map<String,Object> enrollmentAction(String id, User user, boolean confirm) {
        experiments.lock(id).orElseThrow(LabService::missing);
        var enrollment=enrollments.findByExperimentIdAndUserId(id,user.getId()).orElseThrow(LabService::missing);
        if(confirm) {
            if("CONFIRMED".equals(enrollment.status)) return enrollmentView(enrollment);
            require("OFFERED".equals(enrollment.status) && enrollment.confirmUntil!=null && System.currentTimeMillis()<enrollment.confirmUntil,"确认窗口已结束");
            enrollment.status="CONFIRMED";
        } else {
            require(!"PLAYING".equals(enrollment.status),"本局开始后不能更换席位");
            enrollment.status="CANCELLED";
        }
        return enrollmentView(enrollments.saveAndFlush(enrollment));
    }
    @Transactional
    public Map<String,Object> evaluate(String id, LabRequests.Evaluation request) {
        var experiment=experiments.lock(id).orElseThrow(LabService::missing);
        if(request.operationId()!=null && !request.operationId().isBlank()) {
            var existing=evaluations.findByExperimentIdOrderByCreatedAtDesc(id).stream()
                    .filter(row -> request.operationId().equals(row.configuration.get("operationId"))).findFirst();
            if(existing.isPresent()) {
                require(Objects.equals(existing.get().configuration.get("request"),map(json.convertValue(request,Map.class))),"同一操作 ID 的评测参数不同");
                return evaluationView(existing.get());
            }
        }
        require(Set.of("PAUSED","COMPLETED").contains(experiment.status),"请先完成或暂停训练");
        require(!experiment.configuration.containsKey("activeScenario") && !experiment.configuration.containsKey("manualReview"),"请先结束独立评测或复盘任务");
        require(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("WAITING","PLAYING","FINISHED","REVIEWING","REVIEW_FAILED")).isEmpty(),"请先处理未完成对局或复盘");
        budgets.requireRemaining(experiment);
        require(evaluations.findByExperimentIdOrderByCreatedAtDesc(id).stream().noneMatch(existing -> "RUNNING".equals(existing.status)),"已有评测正在运行");
        boolean shared=LabGameConfig.werewolf(experiment.configuration);
        require(shared?request.candidateExperienceId()==null && request.candidateStrategyVersionId()!=null:
                request.candidateStrategyVersionId()==null && request.candidateExperienceId()!=null,"请指定当前玩法的候选经验类型");
        var candidate=shared?null:experiences.findById(request.candidateExperienceId()).orElseThrow(LabService::missing);
        var strategy=shared?strategies.strategySnapshot(request.candidateStrategyVersionId()):Map.<String,Object>of();
        String target=request.participantId();
        if(target==null || target.isBlank()) {
            var compatible=roster(experiment.configuration).stream().filter(profile -> text(profile.get("agentId")).equals(request.agentId())).toList();
            require(compatible.size()==1,"请选择具体的 AI 副本"); target=participantId(compatible.getFirst());
        }
        if(!shared) require(id.equals(candidate.experimentId) && target.equals(candidate.participantId),"经验与评测对象不匹配");
        else require(id.equals(strategy.get("experimentId")),"策略与实验不匹配");
        String participantId=target;
        require(roster(experiment.configuration).stream().anyMatch(profile -> participantId.equals(participantId(profile))),"AI 不属于此实验");
        var profile=roster(experiment.configuration).stream().filter(row -> participantId.equals(participantId(row))).findFirst().orElseThrow();
        var evaluation=new LabEvaluation(); evaluation.experimentId=id; evaluation.agentId=text(profile.get("agentId"));
        evaluation.participantId=participantId; evaluation.pairs=request.pairs();
        evaluation.randomSeed=request.randomSeed()==null?new Random().nextLong():request.randomSeed();
        evaluation.configuration.putAll(Map.of("roster",maps(experiment.configuration.get("roster")),
                "initialExperiences",map(experiment.configuration.get("initialExperiences")),
                "returnStatus",experiment.status));
        evaluation.configuration.put("gameId",LabGameConfig.gameId(experiment.configuration));
        if(shared) {
            evaluation.configuration.put("candidateStrategy",strategy);
            evaluation.configuration.put("initialStrategy",map(experiment.configuration.get("initialStrategy")));
            String role=text(request.targetRole());
            require(role.isBlank() || Set.of("WEREWOLF","VILLAGER","SEER","WITCH","HUNTER").contains(role),"被测身份无效");
            require(!"HUNTER".equals(role) || number(experiment.configuration.get("playerCount"),0)==9,"六人标准板没有猎人");
            evaluation.configuration.put("targetRole",role);
            evaluation.configuration.put("candidateFirst",new Random(LabGameConfig.seed(evaluation.randomSeed,"variant-order")).nextBoolean());
        } else evaluation.configuration.put("candidate",experienceHead(candidate));
        if(request.operationId()!=null) {
            evaluation.configuration.put("operationId",request.operationId());
            evaluation.configuration.put("request",map(json.convertValue(request,Map.class)));
        }
        evaluations.saveAndFlush(evaluation); experiment.status="RUNNING"; experiment.reason=null;
        experiment.configuration.remove("budgetPaused"); experiments.save(experiment);
        return evaluationView(evaluation);
    }
    public Map<String,Object> evaluationView(LabEvaluation evaluation) {
        var rows=matches.findByEvaluationIdOrderBySequenceNumberAsc(evaluation.id);
        var result=new LinkedHashMap<String,Object>();
        result.put("id",evaluation.id); result.put("agentId",evaluation.agentId); result.put("participantId",evaluation.participantId); result.put("status",evaluation.status);
        result.put("gameId",LabGameConfig.gameId(evaluation.configuration));
        result.put("candidateStrategyVersionId",text(map(evaluation.configuration.get("candidateStrategy")).get("id")));
        result.put("targetRole",text(evaluation.configuration.get("targetRole")));
        result.put("sampleWarning","先导样本不证明稳定能力提升；相关席位和行动不作为独立对局。");
        if(evaluation.configuration.containsKey("operationId")) result.put("operationId",evaluation.configuration.get("operationId"));
        result.put("pairs",evaluation.pairs); result.put("completedGames",rows.stream().filter(row -> "REVIEWED".equals(row.status)).count());
        var usable=new HashSet<Integer>();
        for(var row:rows) if("REVIEWED".equals(row.status) && row.reason==null) {
            int pair=number(row.snapshot.get("pairIndex"),-1);
            if(rows.stream().filter(other -> number(other.snapshot.get("pairIndex"),-2)==pair && "REVIEWED".equals(other.status) && other.reason==null)
                    .map(other->other.variant).collect(java.util.stream.Collectors.toSet()).equals(Set.of("BASELINE","CANDIDATE"))) usable.add(pair);
        }
        result.put("validPairs",usable.size());
        var baseline=evaluationStats(rows,evaluation.participantId,"BASELINE",usable);
        var candidate=evaluationStats(rows,evaluation.participantId,"CANDIDATE",usable);
        result.put("baseline",baseline); result.put("candidate",candidate);
        result.put("winRateDifference",((Number)candidate.get("winRate")).doubleValue()-((Number)baseline.get("winRate")).doubleValue());
        int improved=0,regressed=0,same=0;
        for(int pair:usable) {
            boolean base=rows.stream().filter(row->number(row.snapshot.get("pairIndex"),-1)==pair && "BASELINE".equals(row.variant))
                    .flatMap(row->maps(row.snapshot.get("results")).stream()).anyMatch(player->evaluation.participantId.equals(participantId(player)) && flag(player.get("won")));
            boolean next=rows.stream().filter(row->number(row.snapshot.get("pairIndex"),-1)==pair && "CANDIDATE".equals(row.variant))
                    .flatMap(row->maps(row.snapshot.get("results")).stream()).anyMatch(player->evaluation.participantId.equals(participantId(player)) && flag(player.get("won")));
            if(base==next) same++;else if(next) improved++;else regressed++;
        }
        var paired=new LinkedHashMap<String,Object>();paired.put("sampleSize",usable.size());paired.put("candidateOnlyWins",improved);paired.put("baselineOnlyWins",regressed);paired.put("sameOutcome",same);
        if(!usable.isEmpty()) {
            double difference=(improved-regressed)/(double)usable.size(),radius=Math.sqrt(2*Math.log(40)/usable.size());
            paired.put("difference",difference);paired.put("interval95",Map.of("lower",Math.max(-1,difference-radius),"upper",Math.min(1,difference+radius),"method","HOEFFDING_PAIRED_CONSERVATIVE"));
        }
        result.put("pairedOutcomes",paired);
        return result;
    }
    private static Map<String,Object> evaluationStats(List<LabMatch> rows,String participantId,String variant,Set<Integer> usable) {
        var completed=rows.stream().filter(row -> variant.equals(row.variant) && Set.of("REVIEWED","ABORTED").contains(row.status)).toList();
        var players=completed.stream().filter(row -> usable.contains(number(row.snapshot.get("pairIndex"),-1)))
                .flatMap(row -> maps(row.snapshot.get("results")).stream()).filter(player -> participantId.equals(participantId(player))).toList();
        var allActions=completed.stream().flatMap(row -> maps(row.snapshot.get("results")).stream())
                .filter(player -> participantId.equals(participantId(player))).toList();
        long wins=players.stream().filter(player -> flag(player.get("won"))).count();
        long actions=allActions.stream().mapToLong(player -> number(player.get("successfulActions"),0)).sum();
        long failures=allActions.stream().mapToLong(player -> number(player.get("failedActions"),0)).sum();
        long failedGames=completed.stream().filter(row -> "ABORTED".equals(row.status) || row.reason!=null).count();
        var roles=new LinkedHashMap<String,Object>();
        for(String role:List.of("CIVILIAN","UNDERCOVER","BLANK","WEREWOLF","VILLAGER","SEER","WITCH","HUNTER")) {
            var group=players.stream().filter(player -> role.equals(player.get("role"))).toList();
            long roleWins=group.stream().filter(player -> flag(player.get("won"))).count();
            roles.put(role,Map.of("games",group.size(),"wins",roleWins,"winRate",group.isEmpty()?0d:(double)roleWins/group.size()));
        }
        int attempted=(int)rows.stream().filter(row->variant.equals(row.variant)).count();
        return Map.of("games",players.size(),"wins",wins,"winRate",players.isEmpty()?0d:(double)wins/players.size(),
                "validActionRate",actions+failures==0?0d:(double)actions/(actions+failures),
                "failureRate",completed.isEmpty()?0d:(double)failedGames/completed.size(),"roles",roles,
                "attemptedGames",attempted,"failedGames",failedGames,"unfinishedGames",attempted-completed.size());
    }
    private Map<String,Object> summary(LabExperiment experiment) {
        var result=new LinkedHashMap<String,Object>();
        result.putAll(Map.of("id",experiment.id,"name",experiment.name,"status",experiment.status,"budgetPoints",experiment.budgetPoints,
                "createdAt",experiment.createdAt,"attemptedGames",number(experiment.configuration.get("trainingCreated"),0),"reviewedThrough",experiment.reviewCursor-1));
        if(experiment.reason!=null) result.put("reason",experiment.reason);
        result.put("gameId",LabGameConfig.gameId(experiment.configuration));
        for(String key:List.of("playerCount","targetGames","wordPack","hasBlank")) result.put(key,experiment.configuration.get(key));
        result.put("cost",budgets.totals(experiment.id));
        result.put("budgetPaused",LabBudgetService.paused(experiment));
        result.put("completedGames",matches.countByExperimentIdAndEvaluationIdIsNullAndStatusIn(experiment.id,List.of("FINISHED","REVIEWING","REVIEW_FAILED","REVIEWED")));
        result.put("reviewedGames",matches.countByExperimentIdAndEvaluationIdIsNullAndStatusIn(experiment.id,List.of("REVIEWED")));
        result.put("abortedGames",matches.countByExperimentIdAndEvaluationIdIsNullAndStatusIn(experiment.id,List.of("ABORTED")));
        return result;
    }
    public Map<String,Object> matchView(LabMatch match) {
        var result=new LinkedHashMap<String,Object>();
        result.putAll(Map.of("id",match.id,"sequenceNumber",match.sequenceNumber,"roomId",match.roomId,"status",match.status,
                "startsAtEpochMs",match.startsAtEpochMs,"createdAt",match.createdAt));
        if(match.archiveId!=null) result.put("archiveId",match.archiveId);
        if(match.reason!=null) result.put("reason",match.reason);
        if(match.variant!=null) result.put("variant",match.variant);
        result.put("gameId",match.snapshot.getOrDefault("gameId","undercover"));
        if(match.snapshot.containsKey("results")) result.put("results",match.snapshot.get("results"));
        return result;
    }
    private Map<String,Object> enrollmentView(LabEnrollment enrollment) {
        var result=new LinkedHashMap<String,Object>(); result.put("status",enrollment.status);
        if(enrollment.matchId!=null) result.put("matchId",enrollment.matchId);
        if(enrollment.confirmUntil!=null) result.put("confirmUntil",enrollment.confirmUntil);
        return result;
    }
    public static Map<String,Object> experienceHead(LabExperience experience) {
        return Map.of("experienceId",experience.id,"experience",experience.summary,"sequenceNumber",experience.sequenceNumber);
    }
    public static String participantId(Map<String,Object> profile) {
        return text(profile.getOrDefault("participantId",profile.get("agentId")));
    }
    public static List<Map<String,Object>> roster(Map<String,Object> configuration) {
        return maps(configuration.get("roster")).stream().map(profile -> {
            var normalized=new LinkedHashMap<>(profile); normalized.put("participantId",participantId(profile)); return (Map<String,Object>)normalized;
        }).toList();
    }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"实验室记录不存在"); }
}
