package com.aisocialgame.service.ai.v2;

import com.aisocialgame.engine.v2.*;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.mockito.Mockito.mock;

/** Four scored opportunities; peers and fixed host answers are explicit scripted rule submissions. */
final class ClosureSequenceRunner {
    static final List<String> STEPS=List.of("INITIAL","COUNTEREVIDENCE","ACTION","CONTINUITY");
    static final Path BASELINE=Path.of("src/test/resources/ai-realism/closure-sequence-states-v2.json");
    private final MilestoneClosureScenarios fixtures;
    ClosureSequenceRunner(MilestoneClosureScenarios fixtures){this.fixtures=fixtures;}
    void run(MilestoneClosureScenarios.Evaluate evaluator) throws Exception {
        var json=MilestoneClosureScenarios.JSON;
        Map<String,Object> baselines=Files.exists(BASELINE)?json.readValue(BASELINE.toFile(),Map.class):new LinkedHashMap<>();
        for(var rule:fixtures.rules){
            GameState baseline=baselines.containsKey(rule.gameId())?json.convertValue(baselines.get(rule.gameId()),GameState.class):create(rule);
            baselines.put(rule.gameId(),json.convertValue(baseline,Map.class));
            for(var persona:PersonaPresets.all())new Session(rule,json.convertValue(baselines.get(rule.gameId()),GameState.class),persona,evaluator).run();
        }
        if(!Files.exists(BASELINE)){Files.createDirectories(Path.of("target"));json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/closure-sequence-states-v2.json").toFile(),baselines);}
    }
    GameState create(GameRuleSet rule){
        int count="undercover".equals(rule.gameId())?7:"werewolf".equals(rule.gameId())?6:4;
        var config=new LinkedHashMap<String,Object>(Map.of("playerCount",count,"speakTime",60,"speechTime",60,"caseId","midnight_train","aiDifficulty",2));
        if("undercover".equals(rule.gameId())){config.put("spyMode","manual");config.put("spyCount",2);}
        var room=new Room("sequence-v2-"+rule.gameId(),rule.gameId(),"offline sequence",RoomStatus.WAITING,count,false,null,"text",config);
        List<RoomSeat> seats=new ArrayList<>();for(int i=0;i<count;i++)seats.add(new RoomSeat(i,"p"+(i+1),"玩家"+(i+1),false,null,"",true,i==0));
        room.setSeats(seats);room.setHostUserId("p1");var state=rule.initialize(room,LocalDateTime.of(2026,9,22,12,0));
        List<String> opening=strings(state.getData().get("descriptionOrder"));
        var actor=state.getPlayers().stream().filter(p -> "undercover".equals(rule.gameId())?"CIVILIAN".equals(p.getRole()) && opening.indexOf(p.getPlayerId())>=2:"werewolf".equals(rule.gameId())?"VILLAGER".equals(p.getRole()):true).reduce((a,b)->b).orElseThrow();
        actor.setAi(true);actor.setPersonaId("ai1");return state;
    }
    final class Session {
        final GameRuleSet rule;final GameState state;final String actor;final Persona persona;final MilestoneClosureScenarios.Evaluate evaluator;
        final AiMemoryServiceV2 memory=new AiMemoryServiceV2(mock(AiPersonaMemoryRepository.class),new PersonaRepository());
        final ObservationFactory observations=new ObservationFactory(new PersonaRepository(),memory);
        LocalDateTime now=LocalDateTime.of(2026,9,22,12,0);int step=0;
        List<String> counter=new ArrayList<>(),actionEvents=new ArrayList<>();
        Map<String,Object> actionRecord=Map.of();
        boolean soupInitialized=false;
        Runnable afterStep=()->{};
        Session(GameRuleSet r,GameState s,Persona p,MilestoneClosureScenarios.Evaluate e){rule=r;state=s;persona=p;evaluator=e;actor=s.getPlayers().stream().filter(GamePlayerState::isAi).findFirst().orElseThrow().getPlayerId();player(s,actor).setPersonaId(p.getId());}
        void run() throws Exception {
            if("turtle_soup".equals(rule.gameId())){soup();return;}
            for(int guard=0;guard<512&&step<4;guard++){
                rule.advance(state,now);
                if("SETTLEMENT".equals(state.getPhase()))throw new IllegalStateException("SEQUENCE_EARLY_SETTLEMENT:"+rule.gameId()+":"+step);
                String phase=state.getPhase();
                if(step==1 && Set.of("CHALLENGE","DAY_INTERACTION").contains(phase)){
                    var asker=state.getPlayers().stream().filter(p->!p.isAi() && rule.legalActions(state,p.getPlayerId()).stream().anyMatch(a->a.type().equals("ASK_PLAYER")&&a.targets().contains(actor))).findFirst();
                    if(asker.isPresent()){
                        String content="undercover".equals(rule.gameId())?"我撤回刚才关于使用场景一致的判断。你会怎样重新核对描述？":"我撤回把相似发言当作同阵营依据的说法，你现在怎么判断？";
                        counter=submit(asker.get().getPlayerId(),action("ASK_PLAYER",content,actor),"SCRIPTED_PEER");continue;
                    }
                }
                var turn=rule.pendingTurns(state).stream().filter(t->t.actorId().equals(actor)).findFirst();
                boolean anchor=switch(step){case 0->Set.of("DESCRIPTION","DAY_DISCUSS").contains(phase);case 1->"RESPONSE".equals(phase)||"DAY_INTERACTION".equals(phase)&&rule.legalActions(state,actor).stream().anyMatch(a->a.type().equals("ANSWER_PLAYER"));case 2->Set.of("VOTING","DAY_VOTE").contains(phase);default->state.getRoundNumber()>1&&Set.of("DESCRIPTION","DAY_DISCUSS").contains(phase);};
                if(turn.isPresent()&&anchor){evaluate(turn.get());continue;}
                if(step>=2&&"DAY_INTERACTION".equals(phase)){now=now.plusSeconds(61);continue;}
                boolean moved=false;
                for(var p:state.getPlayers()){
                    if(p.isAi())continue;var legal=rule.legalActions(state,p.getPlayerId());if(legal.isEmpty())continue;
                    var choice=legal.stream().filter(a->a.type().equals("SKIP")).findFirst().orElse(legal.getFirst());
                    String content="undercover".equals(rule.gameId())?"我目前判断大家的描述指向同一种使用场景，这只是推测。":"我把相似发言当作同阵营依据，但这只是猜测。";
                    var action=action(choice.type(),choice.maxLength()>0?content:null,choice.targets().isEmpty()?null:choice.targets().getFirst());action.setNightAction(choice.nightAction());
                    submit(p.getPlayerId(),action,"SCRIPTED_PEER");moved=true;break;
                }
                if(!moved){if(turn.isPresent())throw new IllegalStateException("UNEXPECTED_AI_OPPORTUNITY:"+phase+":"+step);now=now.plusSeconds(61);}
            }
            if(step!=4)throw new IllegalStateException("SEQUENCE_INCOMPLETE:"+rule.gameId()+":"+step);
        }
        void evaluate(TurnRequest turn)throws Exception{
            var observation=observations.build(state,rule,turn);
            var visible=observation.events().stream().map(e->text(e.get("eventId"))).collect(java.util.stream.Collectors.toSet());
            List<String> required=step==0?List.of():step==3&&!actionEvents.isEmpty()?actionEvents:counter;
            if(step>0 && (required.isEmpty()||!visible.containsAll(required)))throw new IllegalStateException("REQUIRED_VISIBLE_EVIDENCE_MISSING");
            var coverage=new LinkedHashMap<String,Object>();coverage.put("status","NOT_APPLIED");coverage.put("stepId",STEPS.get(step));coverage.put("phase",observation.phase());coverage.put("round",observation.round());coverage.put("cycle",number(state.getData().get("aiCycle"),0));coverage.put("requiredVisibleEventIds",required);
            var expected=new LinkedHashMap<String,Object>();expected.put("stepId",STEPS.get(step));expected.put("coverage",coverage);expected.put("peerMode","SCRIPTED_LEGAL_ACTIONS");
            var sample=new MilestoneClosureScenarios.Sample("sequence:"+rule.gameId()+":"+persona.getId()+":"+(step+1),"SEQUENCE",persona.getId(),rule,observation,expected);
            AiTurnDecision decision=evaluator.run(sample);
            coverage.put("action",MilestoneClosureScenarios.JSON.convertValue(decision.action(),Map.class));coverage.put("origin",decision.fallback()?"LEGAL_FALLBACK":"MODEL");
            try {
                int before=maps(state.getData().get("events")).size();var receipt=rule.commitmentAction(state,actor,decision.action());
                rule.apply(state,actor,decision.action(),now);memory.commit(state,actor,decision,observation,rule,before);
                AiCommitments.submitted(state,receipt,decision.fallback()?"FALLBACK":"MODEL",before);rule.advance(state,now);AiCommitments.reconcile(state,rule,"OPPORTUNITY_LOST");AiRoundReflection.closeRounds(state);
                var produced=maps(state.getData().get("events")).stream().skip(before).filter(e->"PUBLIC".equals(e.get("visibility"))||strings(e.get("visibleTo")).contains(actor)).map(e->text(e.get("eventId"))).toList();
                var recent=maps(memory.snapshot(state,actor).get("recentDecisions"));var record=recent.isEmpty()?Map.<String,Object>of():recent.getLast();
                coverage.put("producedEventIds",produced);coverage.put("recordedDecision",record);coverage.put("status","APPLIED");
                if(step==2){actionEvents=produced;actionRecord=record;}step++;
            }catch(Exception error){coverage.put("status","SUBMISSION_FAILED");throw error;}
            afterStep.run();
        }
        List<String> submit(String who,PlayerAction a,String origin){
            int before=maps(state.getData().get("events")).size();var receipt=rule.commitmentAction(state,who,a);
            rule.apply(state,who,a,now);AiCommitments.submitted(state,receipt,origin,before);rule.advance(state,now);AiCommitments.reconcile(state,rule,"OPPORTUNITY_LOST");AiRoundReflection.closeRounds(state);
            return maps(state.getData().get("events")).stream().skip(before).filter(e->"PUBLIC".equals(e.get("visibility"))).map(e->text(e.get("eventId"))).toList();
        }
        void soup()throws Exception{
            String human=state.getPlayers().stream().filter(p->!p.isAi()).findFirst().orElseThrow().getPlayerId();
            var gold=fixtures.fixtures.turtleHostFixtures().stream().filter(f->"midnight_train".equals(f.get("caseId"))&&"ASK_QUESTION".equals(f.get("type"))).toList();
            var yes=gold.stream().filter(f->"YES".equals(f.get("expectedVerdict"))).findFirst().orElseThrow();
            var no=gold.stream().filter(f->"NO".equals(f.get("expectedVerdict"))).findFirst().orElseThrow();
            if(!soupInitialized){
                submit(human,action("DISCUSS","先把“"+text(no.get("content"))+"”作为一个待验证猜想，请一起核对。",null),"SCRIPTED_PEER");
                submit(human,action("ASK_QUESTION",text(yes.get("content")),null),"SCRIPTED_PEER");host(gold);soupInitialized=true;
            }
            while(step<4){
                if(step==1){submit(human,action("ASK_QUESTION",text(no.get("content")),null),"SCRIPTED_PEER");counter=host(gold);}
                else if(step>=2)submit(human,action("DISCUSS",step==2?"刚才主持否定了那个猜想，请据此决定接下来讨论或问什么。":"请回顾你刚才实际选择的讨论或问题，说明哪些判断还需要验证。",null),"SCRIPTED_PEER");
                var turn=rule.pendingTurns(state).stream().filter(t->t.actorId().equals(actor)).findFirst().orElseThrow();evaluate(turn);
                if(!map(state.getData().get("pendingHost")).isEmpty())host(gold);
            }
        }
        List<String> host(List<Map<String,Object>> gold){
            var turn=rule.pendingTurns(state).stream().filter(t->HOST.equals(t.actorId())).findFirst().orElseThrow();var obs=observations.build(state,rule,turn);
            var pending=map(obs.privateFacts().get("pendingHost"));var match=gold.stream().filter(f->Objects.equals(f.get("content"),pending.get("content"))).findFirst();
            var extra=new LinkedHashMap<String,Object>();extra.put("requestId",pending.get("requestId"));extra.put("verdict",match.map(f->f.get("expectedVerdict")).orElse("UNKNOWN"));
            extra.put("factIds",match.map(f->f.get("expectedFactIds")).orElse(List.of()));extra.put("normalizedProposition",match.isPresent()?pending.get("content"):"");
            var action=action("HOST_VERDICT",null,null);action.setExtra(extra);var decision=AiTurnDecision.fallback(action);
            if(!rule.validateDecision(obs,decision).isEmpty())throw new IllegalStateException("INVALID_SCRIPTED_HOST_VERDICT");
            return submit(HOST,action,"SCRIPTED_HOST_FIXTURE");
        }
    }
}
