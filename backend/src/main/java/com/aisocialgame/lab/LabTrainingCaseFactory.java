package com.aisocialgame.lab;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.ai.v2.ObservationFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Immutable teaching material. Constructed positions use only the real engine's legal transitions. */
@Service
public class LabTrainingCaseFactory {
    private final WerewolfRuleSet rules;
    private final ObservationFactory observations;
    private final AiTurnJobRepository jobs;
    private final AiDecisionTraceRepository traces;
    private final LocalAiDebugTurnRepository debug;
    private final GameStateRepository states;
    private final ObjectMapper json;
    private final Map<String,Object> curriculum;
    private static final String TARGET="training-target";
    private static final int TARGET_SEAT=4;

    public LabTrainingCaseFactory(WerewolfRuleSet rules,ObservationFactory observations,AiTurnJobRepository jobs,
            AiDecisionTraceRepository traces,LocalAiDebugTurnRepository debug,GameStateRepository states,ObjectMapper json) {
        this.rules=rules;this.observations=observations;this.jobs=jobs;this.traces=traces;this.debug=debug;this.states=states;this.json=json;
        try(var input=LabTrainingCaseFactory.class.getResourceAsStream("/lab/werewolf-training-v2.json")) {
            if(input==null) throw new IllegalStateException("Training curriculum missing");
            curriculum=map(json.readValue(input,Map.class));
        } catch(java.io.IOException failure) {throw new IllegalStateException("Cannot read training curriculum",failure);}
    }

    public List<Map<String,Object>> build(LabExperiment experiment,String runId) {
        require("werewolf".equals(experiment.configuration.get("gameId")),"训练材料仅支持狼人杀");
        require(number(experiment.configuration.get("playerCount"),0)==9,"训练材料需要九人标准板");
        var roster=maps(experiment.configuration.get("roster"));require(roster.size()==9,"训练阵容不完整");
        var personas=new LinkedHashMap<String,Map<String,Object>>();
        roster.forEach(profile->personas.putIfAbsent(text(profile.get("personaId")),profile));
        require(personas.size()==4,"训练需要四个人格");
        var result=new ArrayList<Map<String,Object>>();
        var selected=new HashMap<String,Integer>();personas.keySet().forEach(id->selected.put(id,0));
        var archives=new LinkedHashSet<>(strings(experiment.configuration.get("trainingSourceArchives")));
        archives.addAll(strings(curriculum.get("developmentArchives")));
        var sourceJobs=new LinkedHashMap<String,List<AiTurnJob>>();
        var consumed=new HashSet<String>();var firstKind=new HashMap<String,String>();
        for(String archive:archives) {
            var archived=jobs.findByInstanceIdOrderByCreatedAtAsc(archive);sourceJobs.put(archive,archived);
            // One immutable representative records why an entire incompatible board is not teaching material.
            for(var job:archived) {
                String persona=text(map(job.getObservation().get("persona")).get("id"));
                if(!personas.containsKey(persona) || !validSource(job) || compatibleRules(experiment,job)) continue;
                var trace=matchingTrace(job);if(trace.isEmpty())continue;
                var data=realCase(job,trace.get());data.put("exclusionReason","INCOMPATIBLE_RULES");
                data.put("excludedSourceGroup","game:"+archive);
                result.add(caseRow(runId,"EXCLUDED","game:"+archive,"REAL_GAME",personas.get(persona),"rules:"+archive,data));
                break;
            }
        }
        // One conversation example and one different decision opportunity per persona where available.
        // Priority changes selection only; it never labels an historical answer correct or incorrect.
        for(int slot=0;slot<2;slot++) for(String archive:archives) {
            int targetSlot=slot;
            if(selected.values().stream().allMatch(count->count>targetSlot)) break;
            var ordered=new ArrayList<>(sourceJobs.computeIfAbsent(archive,jobs::findByInstanceIdOrderByCreatedAtAsc));
            ordered.sort(Comparator.comparingInt(job->priority(job,targetSlot,firstKind)));
            for(var job:ordered) {
                var view=job.getObservation();String persona=text(map(view.get("persona")).get("id"));
                if(!selected.containsKey(persona) || selected.get(persona)>slot || consumed.contains(job.getId())
                        || !validSource(job) || !compatibleRules(experiment,job)) continue;
                var trace=matchingTrace(job);if(trace.isEmpty()) continue;
                var data=realCase(job,trace.get());
                var row=caseRow(runId,"DEVELOPMENT","game:"+archive,"REAL_GAME",personas.get(persona),job.getId(),data);
                // Leave room for the real lesson system/schema. The caller measures the entire final payload again.
                if(!new LabTrainingInput().prepare(" ".repeat(4096),Map.of("case",data)).ready()) {
                    data.put("exclusionReason","TOO_LARGE");result.add(caseRow(runId,"EXCLUDED","game:"+archive,"REAL_GAME",personas.get(persona),job.getId(),data));
                    consumed.add(job.getId());
                    continue;
                }
                result.add(row);consumed.add(job.getId());firstKind.putIfAbsent(persona,job.getKind());selected.compute(persona,(key,count)->count+1);
            }
        }
        // Actual records are never impersonated or rewritten to another personality.
        var development=maps(curriculum.get("developmentTemplates"));
        for(var profile:personas.values()) {
            int count=selected.get(text(profile.get("personaId")));
            for(int slot=count;slot<2;slot++) {
                var template=development.get(slot);
                var setup=construct(experiment,roster,template,runId);
                result.add(constructedCase(experiment,runId,profile,template,setup,"DEVELOPMENT"));
            }
        }
        // One frozen source position per template; the four variants change only the target persona.
        for(var template:maps(curriculum.get("holdoutTemplates"))) {
            var setup=construct(experiment,roster,template,runId);
            for(var profile:personas.values()) result.add(constructedCase(experiment,runId,profile,template,setup,"HOLDOUT"));
        }
        return List.copyOf(result);
    }

    private boolean validSource(AiTurnJob job) {
        var view=job.getObservation();return "SUCCEEDED".equals(job.getStatus()) && "werewolf".equals(view.get("gameId"))
                && Objects.equals(job.getActorId(),view.get("actorId")) && Objects.equals(job.getInstanceId(),view.get("instanceId"))
                && Objects.equals(job.getKind(),view.get("turnKind"));
    }
    private boolean compatibleRules(LabExperiment experiment,AiTurnJob job) {
        var expected=map(experiment.configuration.get("rules"));var actual=map(job.getObservation().get("rules"));
        return List.of("template","playerCount","witchRule","winCondition","hasLastWords").stream()
                .allMatch(key->text(expected.get(key)).equals(text(actual.get(key))));
    }
    private Optional<AiDecisionTrace> matchingTrace(AiTurnJob job) {
        return traces.findByRoomIdAndInstanceIdAndActorPlayerId(job.getRoomId(),job.getInstanceId(),job.getActorId()).stream()
                .filter(t->job.getId().equals(text(t.getQuality().get("jobId"))) && t.isValidDecision() && !t.isFallback()
                        && t.getRawOutput().containsKey("action")).findFirst();
    }

    private int priority(AiTurnJob job,int slot,Map<String,String> firstKind) {
        String persona=text(map(job.getObservation().get("persona")).get("id"));
        boolean speech=Set.of("SPEAK","ASK_PLAYER","ANSWER_PLAYER","LAST_WORDS").contains(job.getKind());
        if(slot==0) return speech?("WEREWOLF".equals(map(job.getObservation().get("self")).get("role"))?0:1):2;
        return Objects.equals(firstKind.get(persona),job.getKind())?3:"NIGHT_ACTION".equals(job.getKind())?0:speech?1:2;
    }

    private Map<String,Object> realCase(AiTurnJob job,AiDecisionTrace trace) {
        var data=new LinkedHashMap<String,Object>();var observation=copy(job.getObservation());
        data.put("title","真实决策："+job.getKind());data.put("focus",focus(observation));data.put("observation",observation);
        data.put("source",Map.of("jobId",job.getId(),"archiveId",job.getInstanceId(),"roomId",job.getRoomId(),"traceId",trace.getId()));
        var attempt=new LinkedHashMap<String,Object>();
        debug.findById(job.getId()).filter(d->job.getInstanceId().equals(d.instanceId) && job.getActorId().equals(d.actorId))
                .ifPresent(d->map(d.payload.get("attempts")).entrySet().stream()
                        .filter(e->!maps(map(e.getValue()).get("messages")).isEmpty())
                        .sorted(Comparator.comparingInt(e->number(e.getKey(),-1)))
                        .forEach(e->{attempt.clear();attempt.putAll(map(e.getValue()));}));
        var prompt=maps(attempt.get("messages"));
        data.put("originalPromptStatus",prompt.isEmpty()?"MISSING":"EXACT");data.put("actualPrompt",prompt);
        data.put("originalPromptRequestId",text(attempt.get("requestId")));
        data.put("actualAction",copy(map(trace.getRawOutput().get("action"))));data.put("actualOutput",copy(trace.getRawOutput()));
        data.put("originalDiagnostics",copy(job.getDiagnostics()));
        var outcome=new LinkedHashMap<String,Object>();outcome.put("status","SOURCE_NOT_AVAILABLE");
        states.findById(job.getRoomId()).filter(s->job.getInstanceId().equals(text(s.getData().get("archiveId"))))
                .ifPresent(s->{outcome.put("status","RECORDED");outcome.put("winner",s.getData().getOrDefault("winner",""));
                    outcome.put("results",s.getData().getOrDefault("results",List.of()));
                    var generated=new HashSet<>(strings(trace.getQuality().get("eventIds")));
                    outcome.put("generatedEvents",maps(s.getData().get("events")).stream().filter(e->generated.contains(text(e.get("eventId")))).toList());});
        data.put("outcomeTruth",outcome);data.put("validEvidenceIds",evidenceIds(observation));
        data.put("expectedRubric",Map.of("source","REVIEW_REQUIRED","principles",List.of(
                "只依据该角色行动时可见信息评价，不用终局输赢倒推行动优劣。",
                "合理的原行动可以保留；证据不足时保留判断、留药、放弃开枪不自动算错。",
                "狼人合法伪装不算事实幻觉，区分角色策略与规则误解。",
                "原提示缺失时不能断言模型忽略了未保存的提示。")));
        return data;
    }
    private String focus(Map<String,Object> observation) {
        if("WEREWOLF".equals(map(observation.get("self")).get("role")) && !"NIGHT".equals(observation.get("phase"))) return "LEGAL_DECEPTION";
        if("NIGHT".equals(observation.get("phase")) || "DEATH_ACTION".equals(observation.get("phase"))) return "SKILL_JUDGMENT";
        return "EVIDENCE_AND_UNCERTAINTY";
    }

    private record Setup(GameState state,List<Map<String,Object>> actions,long seed) {}
    private Setup construct(LabExperiment experiment,List<Map<String,Object>> roster,Map<String,Object> template,String runId) {
        String templateId=text(template.get("id")),role=text(template.get("role")),stage=text(template.get("stage"));
        long seed=LabGameConfig.seed(((Number)experiment.configuration.get("randomSeed")).longValue(),"training-v2:"+templateId);
        var room=new Room("training-fixture-"+UUID.nameUUIDFromBytes((runId+templateId).getBytes(StandardCharsets.UTF_8)),"werewolf","规则构造训练材料",
                RoomStatus.WAITING,9,false,null,"text",map(experiment.configuration.get("rules")));room.setSource("LAB");
        var seats=new ArrayList<RoomSeat>();var profiles=new LinkedHashMap<String,Object>();
        for(int index=0;index<9;index++) {
            String actor=index==TARGET_SEAT?TARGET:"training-player-"+index;var profile=new LinkedHashMap<>(roster.get(index));
            profile.put("strategySnapshot",Map.of());profiles.put(actor,profile);
            seats.add(new RoomSeat(index,actor,"训练席位"+(index+1),false,text(profile.get("personaId")),"",true,false));
        }
        room.setSeats(seats);room.getPrivateConfig().put("labSeed",seed);room.getPrivateConfig().put("labRoleSeed",seed);
        room.getPrivateConfig().put("labPlayers",profiles);room.getPrivateConfig().put("lab",Map.of("experimentId",experiment.id,"matchId",runId,"labRunMode","EVALUATION"));
        var remaining=new ArrayList<>(List.of("WEREWOLF","WEREWOLF","WEREWOLF","VILLAGER","VILLAGER","VILLAGER","SEER","WITCH","HUNTER"));remaining.remove(role);
        var assigned=new LinkedHashMap<String,String>();for(var seat:seats) assigned.put(seat.getPlayerId(),TARGET.equals(seat.getPlayerId())?role:remaining.removeFirst());
        room.getPrivateConfig().put("labRoleAssignments",assigned);
        LocalDateTime now=LocalDateTime.of(2026,1,1,0,0);var state=rules.initialize(room,now);var actions=new ArrayList<Map<String,Object>>();
        int guard=0;
        while("NIGHT".equals(state.getPhase())) {
            require(guard++<24,"训练题夜晚构造未收敛");String actor=text(map(state.getData().get("werewolf")).get("nightActor"));
            if("NIGHT".equals(stage) && TARGET.equals(actor)) break;
            var action=action("SKIP","",null);String actorRole=player(state,actor).getRole();
            if("WEREWOLF".equals(actorRole) && (Set.of("WITCH","HUNTER").contains(role) || "wolf_night_coordination".equals(templateId))) {
                String victim=Set.of("witch_save_choice","hunter_last_choice").contains(templateId)?TARGET:
                        state.getPlayers().stream().filter(p->"VILLAGER".equals(p.getRole())).findFirst().orElseThrow().getPlayerId();
                action=action("NIGHT_ACTION","",victim);action.setNightAction("WOLF_KILL");
            } else if("SEER".equals(actorRole) && TARGET.equals(actor) && "seer_checked_claim".equals(templateId)) {
                action=action("NIGHT_ACTION","",state.getPlayers().stream().filter(p->"WEREWOLF".equals(p.getRole())).findFirst().orElseThrow().getPlayerId());action.setNightAction("SEER_CHECK");
            }
            apply(state,actor,action,now,actions);
        }
        if(Set.of("SPEAK","ANSWER","VOTE").contains(stage)) {
            while("DAY_DISCUSS".equals(state.getPhase())) {
                require(guard++<50,"训练题白天构造未收敛");String actor=state.getPlayers().stream().filter(p->Objects.equals(p.getSeatNumber(),state.getCurrentSeat())).findFirst().orElseThrow().getPlayerId();
                if("SPEAK".equals(stage) && TARGET.equals(actor)) break;
                String statement=TARGET.equals(actor)?("WEREWOLF".equals(role)?"我是好人，但目前不能确认其他人的身份。":"我还没有足够依据确定他人的身份。"):
                        "peaceful_night_board".equals(templateId)?"平安夜会不会是守卫守中了？":
                        "development_vote_uncertainty".equals(templateId)?"我自称预言家，但暂时不愿给出查验细节。":"有人自称预言家，我们需要核对他的具体依据。";
                apply(state,actor,action("SPEAK",statement,null),now,actions);
            }
            if("ANSWER".equals(stage)) {
                String asking=state.getPlayers().stream().filter(p->!TARGET.equals(p.getPlayerId()) && rules.legalActions(state,p.getPlayerId()).stream().anyMatch(a->"ASK_PLAYER".equals(a.type())&&a.targets().contains(TARGET)))
                        .findFirst().orElseThrow().getPlayerId();
                apply(state,asking,action("ASK_PLAYER","你刚才的判断有什么公开依据？还存在什么不确定之处？",TARGET),now,actions);
            } else if("VOTE".equals(stage)) {
                while("DAY_INTERACTION".equals(state.getPhase())) {
                    require(guard++<60,"训练题投票构造未收敛");now=state.getPhaseEndsAt().plusSeconds(1);rules.advance(state,now);
                    actions.add(Map.of("kind","RULE_TIMEOUT","at",now.toString()));
                }
            }
        }
        require(!rules.legalActions(state,TARGET).isEmpty(),"训练题目标没有合法行动");
        return new Setup(state,List.copyOf(actions),seed);
    }
    private void apply(GameState state,String actor,PlayerAction action,LocalDateTime now,List<Map<String,Object>> actions) {
        rules.apply(state,actor,action,now);actions.add(Map.of("actorId",actor,"action",json.convertValue(action,Map.class),"at",now.toString()));
    }
    private Map<String,Object> constructedCase(LabExperiment experiment,String runId,Map<String,Object> target,Map<String,Object> template,Setup setup,String purpose) {
        var state=json.convertValue(json.convertValue(setup.state(),Map.class),GameState.class);
        var frozen=map(state.getData().get("labPlayers"));var profile=new LinkedHashMap<>(target);profile.put("strategySnapshot",Map.of());frozen.put(TARGET,profile);state.getData().put("labPlayers",frozen);
        for(var p:state.getPlayers()) {p.setAi(true);if(TARGET.equals(p.getPlayerId()))p.setPersonaId(text(target.get("personaId")));}
        var turn=rules.pendingTurns(state).stream().filter(t->TARGET.equals(t.actorId())).findFirst().orElseThrow();
        var observation=map(json.convertValue(observations.build(state,rules,turn),Map.class));var data=new LinkedHashMap<String,Object>();
        data.put("title",template.get("title"));data.put("focus",template.get("focus"));data.put("observation",observation);
        data.put("originalPromptStatus","NOT_SENT_RULE_CONSTRUCTED");data.put("actualPrompt",List.of());data.put("actualAction",Map.of());
        data.put("source",Map.of("jobId","","archiveId","","roomId","","templateId",template.get("id")));
        data.put("construction",Map.of("ruleVersion",2,"seed",setup.seed(),"actions",setup.actions(),"fixtureInstanceId",state.getData().get("archiveId")));
        data.put("outcomeTruth",Map.of("status","NOT_PLAYED","note","规则构造到行动前，没有模型作答或真实终局"));
        data.put("expectedRubric",Map.of("source","RULE_CONSTRUCTED","principles",List.of(template.get("rubric")),"allowReasonedAlternatives",true));
        data.put("validEvidenceIds",evidenceIds(observation));
        return caseRow(runId,purpose,"template:werewolf-training-v2:"+template.get("id"),"RULE_CONSTRUCTED",target,text(template.get("id")),data);
    }
    private Map<String,Object> caseRow(String runId,String purpose,String group,String type,Map<String,Object> profile,String sourceKey,Map<String,Object> data) {
        String id=UUID.nameUUIDFromBytes((runId+"\0"+purpose+"\0"+sourceKey+"\0"+profile.get("personaId")).getBytes(StandardCharsets.UTF_8)).toString();
        var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("runId",runId);row.put("purpose",purpose);row.put("sourceGroup",group);row.put("sourceType",type);
        row.put("personaId",profile.get("personaId"));row.put("participantId",LabService.participantId(profile));row.put("data",data);row.put("contentHash",LabTrainingInput.fingerprint(data));
        return LabTrainingInput.freezeMap(row);
    }
    public static List<String> evidenceIds(Map<String,Object> observation) {
        var ids=new LinkedHashSet<String>();maps(observation.get("events")).forEach(e->{String id=text(e.get("eventId"));if(!id.isBlank())ids.add(id);});
        map(observation.get("rules")).keySet().forEach(key->ids.add("RULE:"+key));
        for(int i=0;i<strings(observation.get("knowledge")).size();i++) ids.add("KNOWLEDGE:"+i);
        return List.copyOf(ids);
    }
    private Map<String,Object> copy(Map<String,Object> value) {return json.convertValue(value,Map.class);}
}
