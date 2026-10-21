package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.PersonaRepository;
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

    public LabService(LabExperimentRepository experiments, LabAgentRepository agents, LabExperienceRepository experiences,
            LabMatchRepository matches, LabEnrollmentRepository enrollments, LabEvaluationRepository evaluations,
            PersonaRepository personas, AiModelConfigService models, LabBudgetService budgets, ObjectMapper json) {
        this.experiments=experiments; this.agents=agents; this.experiences=experiences; this.matches=matches;
        this.enrollments=enrollments; this.evaluations=evaluations; this.personas=personas; this.models=models;
        this.budgets=budgets; this.json=json;
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
        require(request.agentIds().size() == request.playerCount() && new HashSet<>(request.agentIds()).size() == request.playerCount(), "每个席位需要不同的 AI 档案");
        require(Set.of("daily","idiom","acg","tech").contains(request.wordPack()), "实验室仅支持内置词库");
        models.requireUsable(request.coach().snapshot());
        var experiment=new LabExperiment(); experiment.name=request.name().strip(); experiment.createdBy=operator;
        experiment.budgetPoints=request.budgetPoints();
        var roster=new ArrayList<Map<String,Object>>();
        var heads=new LinkedHashMap<String,Object>();
        for (String agentId:request.agentIds()) {
            var agent=agents.findById(agentId).orElseThrow(LabService::missing);
            models.requireUsable(AiModelSelection.fromMap(agent.modelSelection));
            var persona=PersonaPresets.complete(personas.findById(agent.personaId));
            Map<String,Object> profile=map(json.convertValue(persona,Map.class));
            var player=new LinkedHashMap<String,Object>();
            player.put("agentId",agent.id); player.put("name",agent.name); player.put("personaId",agent.personaId);
            player.put("persona",profile); player.put("modelSelection",new LinkedHashMap<>(agent.modelSelection));
            roster.add(player);
            Map<String,Object> initial=new LinkedHashMap<>(Map.of("experienceId","initial","experience","","sequenceNumber",0));
            String inherited=request.initialExperienceIds()==null ? null : request.initialExperienceIds().get(agentId);
            if(inherited!=null && !inherited.isBlank()) {
                var source=experiences.findById(inherited).orElseThrow(LabService::missing);
                require(agentId.equals(source.agentId), "经验与 AI 档案不匹配");
                initial.put("experienceId",source.id); initial.put("experience",source.summary);
            }
            heads.put(agentId,initial);
        }
        experiment.configuration.putAll(Map.of("playerCount",request.playerCount(),"targetGames",request.targetGames(),
                "concurrency",request.concurrency(),"wordPack",request.wordPack(),"hasBlank",request.hasBlank(),
                "roster",roster,"coach",request.coach().snapshot().toMap(),"initialExperiences",heads,
                "experiences",new LinkedHashMap<>(heads)));
        experiments.saveAndFlush(experiment);
        return detail(experiment.id,null,true);
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
            result.put("configuration",experiment.configuration);
            result.put("reviewFailed",!matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("REVIEW_FAILED")).isEmpty());
        }
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
    public Map<String,Object> control(String id, String action) {
        var experiment=experiments.lock(id).orElseThrow(LabService::missing);
        switch(action) {
            case "start", "resume" -> {
                require(Set.of("DRAFT","PAUSED").contains(experiment.status), "实验当前不能启动");
                require(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("REVIEW_FAILED")).isEmpty(),"请先重试失败的复盘");
                for(var profile:maps(experiment.configuration.get("roster"))) models.requireUsable(AiModelSelection.fromMap(map(profile.get("modelSelection"))));
                models.requireUsable(AiModelSelection.fromMap(map(experiment.configuration.get("coach"))));
                experiment.status="RUNNING"; experiment.reason=null;
            }
            case "pause" -> { require("RUNNING".equals(experiment.status),"实验尚未运行"); experiment.status="PAUSING"; }
            case "stop" -> { require(!Set.of("COMPLETED","STOPPED").contains(experiment.status),"实验已经结束"); experiment.status="STOPPING"; }
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
        require(heads.containsKey(source.agentId),"经验与实验阵容不匹配");
        var version=new LabExperience(); version.experimentId=id; version.agentId=source.agentId;
        version.sequenceNumber=experiences.latestSequence(id,source.agentId)+1; version.summary=source.summary;
        version.previousId=text(map(heads.get(source.agentId)).get("experienceId")); version.evidence=Map.of("rollbackOf",source.id);
        experiences.saveAndFlush(version); heads.put(source.agentId,experienceHead(version));
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
        require(Set.of("PAUSED","COMPLETED").contains(experiment.status),"请先完成或暂停训练");
        require(matches.findByExperimentIdAndStatusInOrderBySequenceNumberAsc(id,List.of("WAITING","PLAYING","FINISHED","REVIEWING","REVIEW_FAILED")).isEmpty(),"请先处理未完成对局或复盘");
        require(evaluations.findByExperimentIdOrderByCreatedAtDesc(id).stream().noneMatch(existing -> "RUNNING".equals(existing.status)),"已有评测正在运行");
        var candidate=experiences.findById(request.candidateExperienceId()).orElseThrow(LabService::missing);
        require(id.equals(candidate.experimentId) && request.agentId().equals(candidate.agentId),"经验与评测对象不匹配");
        require(maps(experiment.configuration.get("roster")).stream().anyMatch(profile -> request.agentId().equals(profile.get("agentId"))),"AI 不属于此实验");
        var evaluation=new LabEvaluation(); evaluation.experimentId=id; evaluation.agentId=request.agentId(); evaluation.pairs=request.pairs();
        evaluation.randomSeed=new Random().nextLong();
        evaluation.configuration.putAll(Map.of("roster",maps(experiment.configuration.get("roster")),
                "initialExperiences",map(experiment.configuration.get("initialExperiences")),
                "candidate",experienceHead(candidate),"returnStatus",experiment.status));
        evaluations.saveAndFlush(evaluation); experiment.status="RUNNING"; experiment.reason=null; experiments.save(experiment);
        return evaluationView(evaluation);
    }
    public Map<String,Object> evaluationView(LabEvaluation evaluation) {
        var rows=matches.findByEvaluationIdOrderBySequenceNumberAsc(evaluation.id);
        var result=new LinkedHashMap<String,Object>();
        result.put("id",evaluation.id); result.put("agentId",evaluation.agentId); result.put("status",evaluation.status);
        result.put("pairs",evaluation.pairs); result.put("completedGames",rows.stream().filter(row -> "REVIEWED".equals(row.status)).count());
        var usable=new HashSet<Integer>();
        for(var row:rows) if("REVIEWED".equals(row.status) && row.reason==null) {
            int pair=number(row.snapshot.get("pairIndex"),-1);
            if(rows.stream().filter(other -> number(other.snapshot.get("pairIndex"),-2)==pair && "REVIEWED".equals(other.status) && other.reason==null).count()==2) usable.add(pair);
        }
        result.put("validPairs",usable.size());
        var baseline=evaluationStats(rows,evaluation.agentId,"BASELINE",usable);
        var candidate=evaluationStats(rows,evaluation.agentId,"CANDIDATE",usable);
        result.put("baseline",baseline); result.put("candidate",candidate);
        result.put("winRateDifference",((Number)candidate.get("winRate")).doubleValue()-((Number)baseline.get("winRate")).doubleValue());
        return result;
    }
    private static Map<String,Object> evaluationStats(List<LabMatch> rows,String agentId,String variant,Set<Integer> usable) {
        var completed=rows.stream().filter(row -> variant.equals(row.variant) && Set.of("REVIEWED","ABORTED").contains(row.status)).toList();
        var players=completed.stream().filter(row -> usable.contains(number(row.snapshot.get("pairIndex"),-1)))
                .flatMap(row -> maps(row.snapshot.get("results")).stream()).filter(player -> agentId.equals(player.get("agentId"))).toList();
        var allActions=completed.stream().flatMap(row -> maps(row.snapshot.get("results")).stream())
                .filter(player -> agentId.equals(player.get("agentId"))).toList();
        long wins=players.stream().filter(player -> flag(player.get("won"))).count();
        long actions=allActions.stream().mapToLong(player -> number(player.get("successfulActions"),0)).sum();
        long failures=allActions.stream().mapToLong(player -> number(player.get("failedActions"),0)).sum();
        long failedGames=completed.stream().filter(row -> "ABORTED".equals(row.status) || row.reason!=null).count();
        var roles=new LinkedHashMap<String,Object>();
        for(String role:List.of("CIVILIAN","UNDERCOVER","BLANK")) {
            var group=players.stream().filter(player -> role.equals(player.get("role"))).toList();
            long roleWins=group.stream().filter(player -> flag(player.get("won"))).count();
            roles.put(role,Map.of("games",group.size(),"wins",roleWins,"winRate",group.isEmpty()?0d:(double)roleWins/group.size()));
        }
        return Map.of("games",players.size(),"wins",wins,"winRate",players.isEmpty()?0d:(double)wins/players.size(),
                "validActionRate",actions+failures==0?0d:(double)actions/(actions+failures),
                "failureRate",completed.isEmpty()?0d:(double)failedGames/completed.size(),"roles",roles,
                "attemptedGames",completed.size(),"failedGames",failedGames);
    }
    private Map<String,Object> summary(LabExperiment experiment) {
        var result=new LinkedHashMap<String,Object>();
        result.putAll(Map.of("id",experiment.id,"name",experiment.name,"status",experiment.status,"budgetPoints",experiment.budgetPoints,
                "createdAt",experiment.createdAt,"attemptedGames",number(experiment.configuration.get("trainingCreated"),0),"reviewedThrough",experiment.reviewCursor-1));
        if(experiment.reason!=null) result.put("reason",experiment.reason);
        for(String key:List.of("playerCount","targetGames","concurrency","wordPack","hasBlank")) result.put(key,experiment.configuration.get(key));
        result.put("cost",budgets.totals(experiment.id));
        return result;
    }
    public Map<String,Object> matchView(LabMatch match) {
        var result=new LinkedHashMap<String,Object>();
        result.putAll(Map.of("id",match.id,"sequenceNumber",match.sequenceNumber,"roomId",match.roomId,"status",match.status,
                "startsAtEpochMs",match.startsAtEpochMs,"createdAt",match.createdAt));
        if(match.archiveId!=null) result.put("archiveId",match.archiveId);
        if(match.reason!=null) result.put("reason",match.reason);
        if(match.variant!=null) result.put("variant",match.variant);
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
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"实验室记录不存在"); }
}
