package com.aisocialgame.lab;

import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.ai.config.*;
import com.aisocialgame.service.ai.v2.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LabScenarioFactoryTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final PersonaRepository personas=new PersonaRepository();
    final AiPersonaMemoryRepository memories=mock(AiPersonaMemoryRepository.class);
    final WerewolfRuleSet rules=new WerewolfRuleSet();
    final ObservationFactory observations=new ObservationFactory(personas,new AiMemoryServiceV2(memories,personas));
    final LabScenarioFactory factory=new LabScenarioFactory(rules,observations,json);

    @Test void actualRulesProduceTwentyLegalPerspectiveIsolatedCasesCoveringEveryPersonaAndRole() {
        when(memories.findByPersonaIdAndGameIdAndRoleKey(anyString(),anyString(),anyString())).thenReturn(Optional.empty());
        var experiment=experiment();var cases=factory.build(experiment);assertEquals(20,cases.size());
        assertEquals(20,cases.stream().map(row->row.get("caseId")).distinct().count());
        assertEquals(5,cases.stream().map(row->row.get("sourceGroup")).distinct().count(),"persona variants share a constructed source family, not independent games");
        for(String persona:List.of("ai1","ai2","ai3","ai4")) {
            var group=cases.stream().filter(row->persona.equals(row.get("personaId"))).toList();assertEquals(5,group.size());
            assertEquals(Set.of("WEREWOLF","VILLAGER","SEER","WITCH","HUNTER"),new HashSet<>(group.stream().map(row->text(row.get("role"))).toList()));
        }
        for(var row:cases) {
            var view=map(row.get("observation"));var actor=text(view.get("actorId"));String role=text(row.get("role"));
            assertEquals("RULE_CONSTRUCTED_HOLDOUT",row.get("sourceType"));assertEquals("NOT_REVIEWED",row.get("semanticReview"));
            assertEquals("werewolf",view.get("gameId"));assertNotEquals("SETTLEMENT",view.get("phase"));
            assertEquals(role,map(view.get("self")).get("role"));assertEquals(row.get("personaId"),map(view.get("persona")).get("id"));
            assertEquals("lab-"+row.get("participantId"),actor);assertFalse(maps(view.get("legalActions")).isEmpty());
            assertFalse(view.containsKey("outcomeTruth"));assertFalse(view.containsKey("labRoleAssignments"));
            assertFalse(map(view.get("memory")).containsKey("approvedExperience"));assertFalse(map(view.get("memory")).containsKey("strategy"));
            var privateFacts=map(view.get("privateFacts"));
            if(!"WEREWOLF".equals(role)) {assertFalse(privateFacts.containsKey("wolfTeam"));assertFalse(privateFacts.containsKey("wolfCouncil"));}
            if(!"SEER".equals(role)) assertFalse(privateFacts.containsKey("seerChecks"));
            if(!"WITCH".equals(role)) {assertFalse(privateFacts.containsKey("wolfTarget"));assertFalse(privateFacts.containsKey("antidoteRemaining"));}
            for(var player:maps(view.get("players"))) if(!actor.equals(player.get("playerId"))) {
                if("WEREWOLF".equals(role) && strings(privateFacts.get("wolfTeam")).contains(text(player.get("playerId")))) assertEquals("WEREWOLF",player.get("role"));
                else assertFalse(player.containsKey("role"),"other identity leaked: "+row.get("caseId"));
            }
            for(var event:maps(view.get("events"))) {
                assertNotEquals("SETTLEMENT",event.get("phase"));
                if("ROLE_ASSIGNED".equals(event.get("type"))) assertEquals(actor,event.get("actorId"));
                if("PRIVATE".equals(event.get("visibility"))) {
                    if("WOLF_COUNCIL".equals(event.get("type"))) assertEquals("WEREWOLF",role);
                    else assertEquals(actor,event.get("actorId"),"another actor private event leaked: "+event.get("type"));
                }
            }
            var actions=maps(view.get("legalActions"));
            switch(role) {
                case "WEREWOLF" -> assertTrue(actions.stream().anyMatch(action->"WOLF_KILL".equals(action.get("nightAction"))));
                case "SEER" -> assertTrue(actions.stream().anyMatch(action->"SEER_CHECK".equals(action.get("nightAction"))));
                case "WITCH" -> assertTrue(actions.stream().anyMatch(action->"WITCH_SAVE".equals(action.get("nightAction"))));
                case "HUNTER" -> assertTrue(actions.stream().anyMatch(action->"HUNTER_SHOOT".equals(action.get("type"))));
                case "VILLAGER" -> {
                    assertTrue(actions.stream().anyMatch(action->"ANSWER_PLAYER".equals(action.get("type"))));
                    assertTrue(maps(view.get("events")).stream().anyMatch(event->"ASK_PLAYER".equals(event.get("type")) && actor.equals(event.get("targetId"))));
                }
            }
        }
    }

    @Test void sixSeatQuickCheckDoesNotPretendToSupplyTheNineSeatHoldoutSuite() {
        var experiment=experiment();experiment.configuration.put("playerCount",6);assertTrue(factory.build(experiment).isEmpty());
    }

    private LabExperiment experiment() {
        var experiment=new LabExperiment();experiment.configuration.put("gameId","werewolf");experiment.configuration.put("playerCount",9);experiment.configuration.put("randomSeed",5678123L);
        experiment.configuration.put("rules",LabGameConfig.werewolfRules(9,Map.of()));
        var roster=new ArrayList<Map<String,Object>>();
        for(int seat=0;seat<9;seat++) {
            var persona=personas.findAll().get(seat%4);var profile=new LinkedHashMap<String,Object>();
            profile.put("participantId","scenario-participant-"+seat);profile.put("agentId","source-"+persona.getId());profile.put("personaId",persona.getId());profile.put("name",persona.getName()+seat);
            profile.put("persona",json.convertValue(persona,Map.class));profile.put("modelSelection",new AiModelSelection(AiFunctionCode.WEREWOLF,"fixture",AiModelSelection.ThinkingMode.ENABLED,"LOW",1).toMap());roster.add(profile);
        }
        experiment.configuration.put("roster",roster);return experiment;
    }
}
