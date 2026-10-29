package com.aisocialgame.lab;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.model.*;
import com.aisocialgame.service.ai.v2.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** New source recipes, built exclusively through legal engine transitions. No provider calls. */
final class LabEvaluationMaterials {
    static final String VERSION="rules-evidence-eval-v2.1";
    private static final String TARGET="evaluation-target";
    private final WerewolfRuleSet rules;
    private final ObservationFactory observations;
    private final ObjectMapper json;
    private record Recipe(String id,String role,String phase,int round,String rubric) {}
    private static final List<Recipe> HOLDOUT=List.of(
        new Recipe("seer_two_night_record","SEER","DAY_DISCUSS",2,"区分两夜本人查验结果与公开声明；不得交换查验对象或编造第三次查验，是否公开可合理选择。"),
        new Recipe("witch_antidote_spent","WITCH","NIGHT",2,"解药已在上一夜使用；当前仅可按实际资源使用毒药或留药，不得声称还能救人。"),
        new Recipe("witch_second_night_self","WITCH","NIGHT",2,"本桌只允许首夜自救；第二夜解药仍在也不能救自己，依据实际合法动作选择，不强制毒人。"),
        new Recipe("hunter_poisoned_last_words","HUNTER","LAST_WORDS",1,"本人中毒死亡没有开枪权限；可以留下有依据的遗言，不得声称开枪或知道其他人真实身份。"),
        new Recipe("villager_two_ballots","VILLAGER","DAY_DISCUSS",3,"比较两轮真实公开票型，区分一致、改票和弃票；票型不直接证明阵营，不编造投票。"),
        new Recipe("two_seers_conflicting_checks","VILLAGER","DAY_INTERACTION",1,"两名玩家提出不同的查验声明；核对原话和先后，不能把自称或查验声明当作系统确认。"),
        new Recipe("wolves_disagree_on_target","WEREWOLF","NIGHT",1,"两个队友提出不同刀口；准确识别分歧并选合法非队友目标，允许有理由赞同任一方。"),
        new Recipe("wolf_specific_claim_challenge","WEREWOLF","DAY_INTERACTION",1,"狼人面对自己具体查验声明的质询，可合法维持伪装；回应实际问题，不把事后真相当作证据。")
    );
    LabEvaluationMaterials(WerewolfRuleSet rules,ObservationFactory observations,ObjectMapper json) {this.rules=rules;this.observations=observations;this.json=json;}
    List<Map<String,Object>> build(LabExperiment experiment,String runId) {
        var roster=maps(experiment.configuration.get("roster"));var personas=new LinkedHashMap<String,Map<String,Object>>();
        roster.forEach(p->personas.putIfAbsent(text(p.get("personaId")),p));var result=new ArrayList<Map<String,Object>>();
        var calibrations=List.of(new Recipe("calibration_heal","WITCH","NIGHT",1,"校准救人字段"),new Recipe("calibration_skip","WITCH","NIGHT",1,"校准主动留药"),
                new Recipe("calibration_check","SEER","NIGHT",1,"校准合法查验目标"),new Recipe("calibration_wolf","WEREWOLF","NIGHT",1,"校准刀口及事件引用"),
                new Recipe("calibration_reply","VILLAGER","DAY_INTERACTION",1,"校准定向回答及当前问题引用"));
        int index=0;var profiles=new ArrayList<>(personas.values());
        for(var recipe:calibrations)result.add(buildCase(experiment,runId,recipe,profiles.get(index++%profiles.size()),"CALIBRATION"));
        for(var recipe:HOLDOUT)for(var profile:profiles)result.add(buildCase(experiment,runId,recipe,profile,"HOLDOUT"));
        return result;
    }
    private Map<String,Object> buildCase(LabExperiment experiment,String runId,Recipe recipe,Map<String,Object> profile,String purpose) {
        var roster=maps(experiment.configuration.get("roster"));long seed=LabGameConfig.seed(((Number)experiment.configuration.get("randomSeed")).longValue(),VERSION+":"+recipe.id);
        var room=new Room("eval-fixture-"+UUID.nameUUIDFromBytes((runId+recipe.id).getBytes(StandardCharsets.UTF_8)),"werewolf","独立复测构造来源",RoomStatus.WAITING,9,false,null,"text",map(experiment.configuration.get("rules")));
        room.setSource("LAB");var seats=new ArrayList<RoomSeat>();var profiles=new LinkedHashMap<String,Object>();
        for(int i=0;i<9;i++) {String actor=i==4?TARGET:"evaluation-player-"+i;var p=new LinkedHashMap<>(i==4?profile:roster.get(i));p.put("strategySnapshot",Map.of());profiles.put(actor,p);
            seats.add(new RoomSeat(i,actor,"复测席位"+(i+1),false,text(p.get("personaId")),"",true,false));}
        room.setSeats(seats);room.getPrivateConfig().put("labSeed",seed);room.getPrivateConfig().put("labRoleSeed",LabGameConfig.seed(seed,"roles"));room.getPrivateConfig().put("labPlayers",profiles);
        room.getPrivateConfig().put("lab",Map.of("experimentId",experiment.id,"matchId",runId,"labRunMode","EVALUATION"));
        var remaining=new ArrayList<>(List.of("WEREWOLF","WEREWOLF","WEREWOLF","VILLAGER","VILLAGER","VILLAGER","SEER","WITCH","HUNTER"));remaining.remove(recipe.role);
        var assigned=new LinkedHashMap<String,String>();for(var seat:seats)assigned.put(seat.getPlayerId(),TARGET.equals(seat.getPlayerId())?recipe.role:remaining.removeFirst());
        room.getPrivateConfig().put("labRoleAssignments",assigned);LocalDateTime now=LocalDateTime.of(2026,1,1,0,0);var state=rules.initialize(room,now);
        var actions=new ArrayList<Map<String,Object>>();boolean asked=false;
        for(int step=0;step<240;step++) {
            require(!"SETTLEMENT".equals(state.getPhase()),"复测构造在目标机会前结束："+recipe.id);
            var legal=rules.legalActions(state,TARGET);
            boolean atPhase=recipe.phase.equals(state.getPhase())&&state.getRoundNumber()==recipe.round&&!legal.isEmpty();
            if(atPhase&&("NIGHT".equals(recipe.phase)||"LAST_WORDS".equals(recipe.phase)
                    ||"DAY_DISCUSS".equals(recipe.phase)&&Objects.equals(state.getCurrentSeat(),4)
                    ||"DAY_INTERACTION".equals(recipe.phase)&&asked))break;
            String actor=null;PlayerAction action=null;
            if("NIGHT".equals(state.getPhase())) {
                actor=text(map(state.getData().get("werewolf")).get("nightActor"));action=night(state,recipe,actor);
            } else if("DAY_DISCUSS".equals(state.getPhase())) {
                actor=state.getPlayers().stream().filter(p->Objects.equals(p.getSeatNumber(),state.getCurrentSeat())).findFirst().orElseThrow().getPlayerId();
                action=action("SPEAK",statement(state,recipe,actor),null);
            } else if("DAY_INTERACTION".equals(state.getPhase())&&recipe.round==state.getRoundNumber()&&"DAY_INTERACTION".equals(recipe.phase)&&!asked) {
                actor=state.getPlayers().stream().filter(p->!TARGET.equals(p.getPlayerId())&&rules.legalActions(state,p.getPlayerId()).stream().anyMatch(a->"ASK_PLAYER".equals(a.type())&&a.targets().contains(TARGET)))
                        .findFirst().orElseThrow().getPlayerId();
                String question="wolf_specific_claim_challenge".equals(recipe.id)?"你刚才声称昨夜查验了6号是好人，具体是什么时候查的？请回应这个查验声明。":"两个人给出了不同的查验声明，你能确认哪一个是真的吗？请核对两人的原话与不确定之处。";
                action=action("ASK_PLAYER",question,TARGET);asked=true;
            } else {
                var pending=state.getPlayers().stream().filter(p->!rules.legalActions(state,p.getPlayerId()).isEmpty()).findFirst();
                if(pending.isPresent()&&!"DAY_INTERACTION".equals(state.getPhase())) {
                    actor=pending.get().getPlayerId();var choices=rules.legalActions(state,actor);
                    var vote=choices.stream().filter(a->"VOTE".equals(a.type())).findFirst();
                    if(vote.isPresent()) {
                        action=action("VOTE","",null);action.setAbstain(true);
                        if("villager_two_ballots".equals(recipe.id)&&!TARGET.equals(actor)) {
                            int seat=player(state,actor).getSeatNumber();String target=(state.getRoundNumber()==1?seat%2:seat%3)==0?"evaluation-player-6":"evaluation-player-7";
                            if(vote.get().targets().contains(target)){action.setAbstain(false);action.setTargetPlayerId(target);}
                        }
                    } else if(choices.stream().anyMatch(a->"SKIP".equals(a.type())))action=action("SKIP","",null);
                    else {var first=choices.getFirst();action=action(first.type(),"目前依据有限，我保留判断。",first.targets().isEmpty()?null:first.targets().getFirst());action.setNightAction(first.nightAction());}
                }
            }
            if(action!=null&&!text(actor).isBlank()) {
                rules.apply(state,actor,action,now);actions.add(Map.of("actorId",actor,"action",json.convertValue(action,Map.class),"at",now.toString()));
            } else {
                require(state.getPhaseEndsAt()!=null,"构造缺少推进期限："+recipe.id);now=state.getPhaseEndsAt().plusSeconds(1);rules.advance(state,now);actions.add(Map.of("kind","RULE_TIMEOUT","at",now.toString()));
            }
            require(step<239,"复测构造未收敛："+recipe.id);
        }
        for(var p:state.getPlayers())p.setAi(true);
        var turn=rules.pendingTurns(state).stream().filter(t->TARGET.equals(t.actorId())).findFirst().orElseThrow();
        var observation=map(json.convertValue(observations.build(state,rules,turn),Map.class));var data=new LinkedHashMap<String,Object>();
        data.put("title",recipe.id);data.put("focus",purpose.equals("CALIBRATION")?"PROTOCOL_CALIBRATION":"RULES_AND_EVIDENCE");data.put("observation",observation);
        data.put("originalPromptStatus","NOT_SENT_RULE_CONSTRUCTED");data.put("actualPrompt",List.of());data.put("actualAction",Map.of());
        data.put("source",Map.of("templateId",recipe.id,"materialSetVersion",VERSION));data.put("construction",Map.of("actions",actions,"seed",seed,"roleAssignments",assigned,"fixtureInstanceId",state.getData().get("archiveId")));
        data.put("outcomeTruth",Map.of("status","NOT_PLAYED"));data.put("expectedRubric",Map.of("source","RULE_CONSTRUCTED","principles",List.of(recipe.rubric),"allowReasonedAlternatives",true));
        data.put("validEvidenceIds",LabTrainingCaseFactory.evidenceIds(observation));
        if(purpose.equals("CALIBRATION"))data.put("calibration",calibration(recipe,observation));
        String id=UUID.nameUUIDFromBytes((runId+"\0"+purpose+"\0"+recipe.id+"\0"+profile.get("personaId")).getBytes(StandardCharsets.UTF_8)).toString();
        var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("runId",runId);row.put("purpose",purpose);row.put("sourceType","RULE_CONSTRUCTED");row.put("sourceGroup",VERSION+":"+recipe.id);
        row.put("personaId",profile.get("personaId"));row.put("participantId",LabService.participantId(profile));row.put("data",data);row.put("contentHash",LabTrainingInput.fingerprint(data));return LabTrainingInput.freezeMap(row);
    }
    private PlayerAction night(GameState state,Recipe recipe,String actor) {
        var role=player(state,actor).getRole();var skip=action("SKIP","",null);int round=state.getRoundNumber();
        if("WEREWOLF".equals(role)) {
            if(Set.of("villager_two_ballots","two_seers_conflicting_checks","calibration_reply").contains(recipe.id))return skip;
            String victim=("witch_second_night_self".equals(recipe.id)&&round==2)||"calibration_heal".equals(recipe.id)||"calibration_skip".equals(recipe.id)?TARGET:
                    state.getPlayers().stream().filter(p->p.isAlive()&&"VILLAGER".equals(p.getRole())).findFirst().orElseThrow().getPlayerId();
            if(Set.of("wolves_disagree_on_target","calibration_wolf").contains(recipe.id)&&player(state,actor).getSeatNumber()==1)
                victim=state.getPlayers().stream().filter(p->p.isAlive()&&"VILLAGER".equals(p.getRole())&&!p.getPlayerId().equals(victimId(state))).skip(1).findFirst()
                        .orElseGet(()->state.getPlayers().stream().filter(p->p.isAlive()&&"VILLAGER".equals(p.getRole())).reduce((a,b)->b).orElseThrow()).getPlayerId();
            var result=action("NIGHT_ACTION","建议按当前可见情况选择刀口。",victim);result.setNightAction("WOLF_KILL");return result;
        }
        if("SEER".equals(role)&&TARGET.equals(actor)&&"seer_two_night_record".equals(recipe.id)) {
            String target=state.getPlayers().stream().filter(p->p.isAlive()&&(round==1?"WEREWOLF":"VILLAGER").equals(p.getRole())).findFirst().orElseThrow().getPlayerId();
            var result=action("NIGHT_ACTION","",target);result.setNightAction("SEER_CHECK");return result;
        }
        if("WITCH".equals(role)) {
            if("hunter_poisoned_last_words".equals(recipe.id)){var result=action("NIGHT_ACTION","",TARGET);result.setNightAction("WITCH_POISON");return result;}
            if(TARGET.equals(actor)&&round==1&&"witch_antidote_spent".equals(recipe.id)) {
                var legal=rules.legalActions(state,actor).stream().filter(a->"WITCH_SAVE".equals(a.nightAction())).findFirst().orElseThrow();
                var result=action("NIGHT_ACTION","",legal.targets().getFirst());result.setNightAction("WITCH_SAVE");result.setUseHeal(true);return result;
            }
        }
        return skip;
    }
    private String victimId(GameState state) {return text(map(state.getData().get("werewolf")).get("wolfTarget"));}
    private String statement(GameState state,Recipe recipe,String actor) {
        int seat=player(state,actor).getSeatNumber();
        if(TARGET.equals(actor)&&"wolf_specific_claim_challenge".equals(recipe.id))return "我自称预言家，昨夜查验了6号是好人。";
        if(Set.of("two_seers_conflicting_checks","calibration_reply").contains(recipe.id)&&seat==6)return "我自称预言家，我声明昨夜查验1号是好人。";
        if(Set.of("two_seers_conflicting_checks","calibration_reply").contains(recipe.id)&&seat==7)return "我也自称预言家，我声明昨夜查验1号是狼人。";
        return "我目前只能依据公开发言与投票判断，还不能确定他人阵营。";
    }
    private Map<String,Object> calibration(Recipe recipe,Map<String,Object> observation) {
        var legal=maps(observation.get("legalActions"));String type="calibration_skip".equals(recipe.id)?"SKIP":"calibration_reply".equals(recipe.id)?"ANSWER_PLAYER":"NIGHT_ACTION";
        String night=switch(recipe.id){case "calibration_heal"->"WITCH_SAVE";case "calibration_check"->"SEER_CHECK";case "calibration_wolf"->"WOLF_KILL";default->"";};
        var capability=legal.stream().filter(a->type.equals(a.get("type"))&&(night.isBlank()||night.equals(a.get("nightAction")))).findFirst().orElseThrow();
        var result=new LinkedHashMap<String,Object>();result.put("type",type);if(!night.isBlank())result.put("nightAction",night);
        if(!strings(capability.get("targets")).isEmpty())result.put("targetPlayerId",strings(capability.get("targets")).getFirst());
        if(Set.of("calibration_reply","calibration_wolf").contains(recipe.id)) {
            var event=maps(observation.get("events")).stream().filter(e->("calibration_reply".equals(recipe.id)?"ASK_PLAYER":"WOLF_COUNCIL").equals(e.get("type"))).reduce((a,b)->b).orElseThrow();result.put("evidenceEventIds",List.of(event.get("eventId")));
        }
        return result;
    }
}
