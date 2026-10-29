package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.engine.v2.TurnRequest;
import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.model.*;
import com.aisocialgame.service.ai.v2.ObservationFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Holdout situations are constructed with the actual rules, never claimed to be human game data. */
@Service
public class LabScenarioFactory {
    private final WerewolfRuleSet rules;
    private final ObservationFactory observations;
    private final ObjectMapper json;
    public LabScenarioFactory(WerewolfRuleSet rules,ObservationFactory observations,ObjectMapper json) {
        this.rules=rules;this.observations=observations;this.json=json;
    }
    public List<Map<String,Object>> build(LabExperiment experiment) {
        if(number(experiment.configuration.get("playerCount"),0)!=9) return List.of();
        var roster=maps(experiment.configuration.get("roster"));
        var personas=new LinkedHashMap<String,Map<String,Object>>();
        roster.forEach(profile -> personas.putIfAbsent(text(profile.get("personaId")),profile));
        var result=new ArrayList<Map<String,Object>>();
        long seed=((Number)experiment.configuration.get("randomSeed")).longValue();
        for(var profile:personas.values()) for(String role:List.of("WEREWOLF","VILLAGER","SEER","WITCH","HUNTER")) {
            result.add(buildCase(experiment,roster,profile,role,LabGameConfig.seed(seed,"holdout:"+result.size()),result.size()));
        }
        return result;
    }
    private Map<String,Object> buildCase(LabExperiment experiment,List<Map<String,Object>> roster,Map<String,Object> target,
                                         String role,long seed,int ordinal) {
        String caseId="holdout-"+ordinal,actor="lab-"+LabService.participantId(target);
        var room=new Room("scenario-"+experiment.id+"-"+ordinal,"werewolf","规则构造保留题",RoomStatus.WAITING,9,false,null,"text",map(experiment.configuration.get("rules")));
        room.setSource("LAB");
        var seats=new ArrayList<RoomSeat>();var profiles=new LinkedHashMap<String,Object>();
        for(var profile:roster) {
            String id="lab-"+LabService.participantId(profile);
            // Scripted fixture setup uses human rule transitions; no model or normal room is advanced.
            seats.add(new RoomSeat(seats.size(),id,text(profile.get("name")),false,text(profile.get("personaId")),"",true,false));
            var frozen=new LinkedHashMap<>(profile);frozen.put("strategySnapshot",Map.of());profiles.put(id,frozen);
        }
        room.setSeats(seats);room.getPrivateConfig().put("labSeed",seed);room.getPrivateConfig().put("labRoleSeed",LabGameConfig.seed(seed,"roles"));
        room.getPrivateConfig().put("labPlayers",profiles);
        room.getPrivateConfig().put("lab",Map.of("experimentId",experiment.id,"matchId",experiment.id,"labRunMode","EVALUATION"));
        var roles=new ArrayList<>(List.of("WEREWOLF","WEREWOLF","WEREWOLF","VILLAGER","VILLAGER","VILLAGER","SEER","WITCH","HUNTER"));
        roles.remove(role);Collections.shuffle(roles,new Random(seed));var assigned=new LinkedHashMap<String,String>();assigned.put(actor,role);
        for(var seat:seats) if(!actor.equals(seat.getPlayerId())) assigned.put(seat.getPlayerId(),roles.removeFirst());
        room.getPrivateConfig().put("labRoleAssignments",assigned);
        LocalDateTime now=LocalDateTime.of(2026,1,1,0,0);
        var state=rules.initialize(room,now);
        boolean nightRole=Set.of("WEREWOLF","SEER","WITCH").contains(role);
        int transitions=0;
        while("NIGHT".equals(state.getPhase())) {
            require(transitions++<20,"构造题夜晚推进异常");
            String current=text(map(state.getData().get("werewolf")).get("nightActor"));
            if(nightRole && actor.equals(current)) break;
            var player=RuleSupport.player(state,current);var action=action("SKIP","",null);
            if("WEREWOLF".equals(player.getRole()) && Set.of("HUNTER","WITCH").contains(role)) {
                String victim="HUNTER".equals(role)?actor:state.getPlayers().stream().filter(p -> "VILLAGER".equals(p.getRole())).findFirst().orElseThrow().getPlayerId();
                action=action("NIGHT_ACTION","",victim);action.setNightAction("WOLF_KILL");
            }
            rules.apply(state,current,action,now);
        }
        if("VILLAGER".equals(role)) {
            while("DAY_DISCUSS".equals(state.getPhase())) {
                require(transitions++<40,"构造题发言推进异常");
                var current=state.getPlayers().stream().filter(p -> Objects.equals(p.getSeatNumber(),state.getCurrentSeat())).findFirst().orElseThrow();
                String statementText=actor.equals(current.getPlayerId())?"目前没有可核对的查验依据，我只能保留怀疑，不能确定他人的身份。"
                        :current.getSeatNumber()%2==0?"我自称预言家，建议先相信我的身份。":"自称预言家还不是证明，我想先核对公开发言和证据。";
                var statement=action("SPEAK",statementText,null);
                rules.apply(state,current.getPlayerId(),statement,now);
            }
            var questioner=state.getPlayers().stream().filter(p->!actor.equals(p.getPlayerId()) && !rules.legalActions(state,p.getPlayerId()).isEmpty()).findFirst().orElseThrow();
            rules.apply(state,questioner.getPlayerId(),action("ASK_PLAYER","你说缺少依据，为什么不能直接相信自称预言家的人？",actor),now);
        }
        require(!rules.legalActions(state,actor).isEmpty(),"构造题目标没有合法机会");
        for(var player:state.getPlayers()) player.setAi(true);
        var turn=rules.pendingTurns(state).stream().filter(t -> actor.equals(t.actorId())).findFirst().orElseThrow();
        var observation=observations.build(state,rules,turn);
        var result=new LinkedHashMap<String,Object>();
        result.put("caseId",caseId);result.put("sourceType","RULE_CONSTRUCTED_HOLDOUT");result.put("sourceGroup","werewolf-holdout-v1:"+role);
        result.put("personaId",target.get("personaId"));result.put("participantId",LabService.participantId(target));result.put("role",role);
        result.put("observation",json.convertValue(observation,Map.class));result.put("modelSelection",target.get("modelSelection"));
        result.put("seed",seed);result.put("semanticReview","NOT_REVIEWED");
        return result;
    }
}
