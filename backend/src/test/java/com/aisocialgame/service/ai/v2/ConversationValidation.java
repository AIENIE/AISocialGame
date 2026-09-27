package com.aisocialgame.service.ai.v2;

import com.aisocialgame.model.*;
import com.aisocialgame.engine.v2.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Isolated validation only. Frozen observations/state, durable results, no replay of charged samples. */
final class ConversationValidation {
    static final ObjectMapper JSON=MilestoneClosureScenarios.JSON;
    static final String SET="conversation-validation-v1";
    static final Set<String> PILOTS=Set.of("undercover:direct_question","werewolf:counterevidence","turtle_soup:human_prepares_question");
    static final Set<String> STOP_FLAGS=Set.of("MODEL_RESOURCE_EXHAUSTED","MODEL_AUTH_REJECTED","CALL_BUDGET_EXHAUSTED","CALL_RATE_LIMITED","ADMIN_CONTROL",
            "SECRET_WORD_LEAK","INVISIBLE_EVIDENCE","PRIVATE_MEMORY_LEAK","HOST_PRIVATE_OUTPUT");
    static final class Stop extends RuntimeException { Stop(String reason){super(reason);} }
    static final class Prepared extends RuntimeException {}
    interface Generate { AiTurnDecision run(MilestoneClosureScenarios.Sample sample) throws Exception; }
    record Freeze(Map<String,Object> manifest,Map<String,Object> bundle) {}

    static Freeze freeze(String kind) throws Exception {
        var header=MilestoneClosureScenarios.header("conversation-"+UUID.randomUUID(),kind);header.put("evaluationSetVersion",SET);
        List<Map<String,Object>> singles=new ArrayList<>(),sequences=new ArrayList<>(),inventory=new ArrayList<>();List<String> pilots=new ArrayList<>();
        for(var s:new AiConversationScenarios().load()) {
            var meta=singleMeta(s.id(),s.personaId(),s.observation().gameId());inventory.add(meta);
            if(PILOTS.contains(s.id()))pilots.add(text(meta.get("id")));
            var seed=seed(s.session());seed.putAll(meta);seed.put("requiredEventId",s.requiredEventId());seed.put("observation",s.observation());
            seed.put("input",AiPromptProjection.project(s.session().rule,s.observation()));singles.add(seed);
        }
        var fixtures=new MilestoneClosureScenarios();var runner=new ClosureSequenceRunner(fixtures);
        var baselines=JSON.readValue(ClosureSequenceRunner.BASELINE.toFile(),Map.class);
        for(var rule:fixtures.rules) {
            var state=JSON.convertValue(baselines.get(rule.gameId()),GameState.class);
            var session=runner.new Session(rule,state,PersonaPresets.all().getFirst(),s->{throw new Prepared();});
            try{session.run();throw new IllegalStateException("Missing first opportunity");}catch(Prepared expected){}
            for(var persona:PersonaPresets.all()) {
                var copy=runner.new Session(rule,JSON.convertValue(state,GameState.class),persona,null);copy.now=session.now;copy.soupInitialized=session.soupInitialized;
                var turn=rule.pendingTurns(copy.state).stream().filter(t->t.actorId().equals(copy.actor)).findFirst().orElseThrow();
                var observation=copy.observations.build(copy.state,rule,turn);var seed=seed(copy);
                seed.put("gameId",rule.gameId());seed.put("personaId",persona.getId());seed.put("observation",observation);seed.put("input",AiPromptProjection.project(rule,observation));sequences.add(seed);
                for(int step=0;step<4;step++)inventory.add(sequenceMeta(rule.gameId(),persona.getId(),step));
            }
        }
        var bundle=new LinkedHashMap<String,Object>(header);bundle.put("singles",singles);bundle.put("sequences",sequences);
        var manifest=new LinkedHashMap<String,Object>(header);manifest.put("samples",inventory);manifest.put("pilotIds",pilots);
        manifest.put("sampleCount",96);manifest.put("maxDiscreteCalls",192);manifest.put("realCalls",0);
        return new Freeze(manifest,bundle);
    }
    static void writeFreeze(Freeze value,Path directory)throws Exception {
        Files.createDirectories(directory);Path bundle=directory.resolve("conversation-bundle.json");atomic(bundle,value.bundle());
        value.manifest().put("bundleSha256",sha(bundle));atomic(directory.resolve("conversation-manifest.json"),value.manifest());
    }
    static Map<String,Object> singleMeta(String scenario,String persona,String game) {
        return new LinkedHashMap<>(Map.of("id","conversation:"+scenario+":"+persona,"scenarioId",scenario,"gameId",game,"personaId",persona,"group","SINGLE","sequenceId","","stepId",""));
    }
    static Map<String,Object> sequenceMeta(String game,String persona,int step) {
        return new LinkedHashMap<>(Map.of("id","sequence:"+game+":"+persona+":"+(step+1),"scenarioId","continuity-v2","gameId",game,"personaId",persona,"group","SEQUENCE","sequenceId",game+":continuity-v2","stepId",ClosureSequenceRunner.STEPS.get(step)));
    }
    static Map<String,Object> seed(ClosureSequenceRunner.Session session) {
        var value=new LinkedHashMap<String,Object>();value.put("state",JSON.convertValue(session.state,Map.class));value.put("now",session.now.toString());value.put("soupInitialized",session.soupInitialized);return value;
    }
    static String sha(Path path)throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    static Map<String,Object> read(Path path)throws IOException {return JSON.readValue(path.toFile(),Map.class);}
    static void atomic(Path path,Object value)throws IOException {
        Path temp=path.resolveSibling(path.getFileName()+".pending");
        Files.write(temp,JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
        Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }

    final Map<String,Object> manifest,bundle,document;
    final LinkedHashMap<String,Map<String,Object>> rows=new LinkedHashMap<>();
    final Map<String,String> savedRows=new LinkedHashMap<>();
    final MilestoneClosureScenarios fixtures=new MilestoneClosureScenarios();
    final Path output;final Generate generate;final Set<String> preReserved;
    final String phase;
    ConversationValidation(Map<String,Object> manifest,Map<String,Object> bundle,Path output,Map<String,Object> prior,
            String phase,Set<String> preReserved,Generate generate)throws Exception {
        if(Files.exists(output))throw new IllegalArgumentException("NEW_EVIDENCE_PATH_REQUIRED");
        if(!Set.of("PILOT","REMAINING").contains(phase))throw new IllegalArgumentException("INVALID_PHASE");
        this.manifest=manifest;this.bundle=bundle;this.output=output;this.generate=generate;this.phase=phase;this.preReserved=Set.copyOf(preReserved);
        document=new LinkedHashMap<>();for(String key:List.of("evaluationSchemaVersion","evaluationSetVersion","batchId","evidenceKind","sourceFingerprint","buildId","promptVersion","inputFormatVersion","memoryFormatVersion","bundleSha256")){
            document.put(key,manifest.get(key));if(!prior.isEmpty()&&!Objects.equals(prior.get(key),manifest.get(key)))throw new IllegalArgumentException("RESUME_VERSION_MISMATCH");
        }
        var wanted=maps(manifest.get("samples"));
        for(var meta:wanted){var row=new LinkedHashMap<>(meta);row.put("status","NOT_RUN");if(rows.put(text(meta.get("id")),row)!=null)throw new IllegalArgumentException("DUPLICATE_SAMPLE");}
        Set<String> seen=new HashSet<>();
        for(var row:maps(prior.get("samples"))){String id=text(row.get("id"));if(!rows.containsKey(id)||!seen.add(id))throw new IllegalArgumentException("INVALID_RESUME_SAMPLE");for(String key:List.of("id","group","gameId","personaId","scenarioId","sequenceId","stepId"))if(!Objects.equals(row.get(key),rows.get(id).get(key)))throw new IllegalArgumentException("RESUME_METADATA_MISMATCH");rows.put(id,new LinkedHashMap<>(row));}
        for(var row:rows.values()) {
            if("STARTED".equals(row.get("status")) || preReserved.contains(text(row.get("id")))&&"NOT_RUN".equals(row.get("status"))) {
                row.put("status","NO_RESPONSE");row.put("reason","INTERRUPTED_OUTCOME_UNKNOWN");
            }
        }
        document.put("samples",new ArrayList<>(rows.values()));document.put("reviewType","CODING_AGENT_NOT_INDEPENDENT_HUMAN_BLIND_REVIEW");
        document.put("L4Passed",false);persist("READY");
    }
    void run() throws Exception {
        try {
            for(var frozen:maps(bundle.get("singles"))) {
                String id=text(frozen.get("id"));if("PILOT".equals(phase)!=strings(manifest.get("pilotIds")).contains(id))continue;
                if(!"NOT_RUN".equals(rows.get(id).get("status")))continue;
                var session=session(frozen,null);var observation=observe(session);
                assertFrozen(frozen,session.rule,observation);
                var coverage=new LinkedHashMap<String,Object>();coverage.put("status","NOT_APPLIED");coverage.put("phase",observation.phase());coverage.put("round",observation.round());
                var sample=new MilestoneClosureScenarios.Sample(id,"SINGLE",text(frozen.get("personaId")),session.rule,observation,Map.of("coverage",coverage));
                try {
                    var decision=evaluate(sample);
                    int before=maps(session.state.getData().get("events")).size();var receipt=session.rule.commitmentAction(session.state,session.actor,decision.action());
                    session.rule.apply(session.state,session.actor,decision.action(),session.now);session.memory.commit(session.state,session.actor,decision,observation,session.rule,before);
                    AiCommitments.submitted(session.state,receipt,decision.fallback()?"FALLBACK":"MODEL",before);session.rule.advance(session.state,session.now);AiCommitments.reconcile(session.state,session.rule,"OPPORTUNITY_LOST");AiRoundReflection.closeRounds(session.state);
                    coverage.put("status","APPLIED");coverage.put("action",JSON.convertValue(decision.action(),Map.class));coverage.put("origin",decision.fallback()?"LEGAL_FALLBACK":"MODEL");
                    coverage.put("producedEventIds",maps(session.state.getData().get("events")).stream().skip(before).filter(e->"PUBLIC".equals(e.get("visibility"))||strings(e.get("visibleTo")).contains(session.actor)).map(e->text(e.get("eventId"))).toList());
                    rows.get(id).put("memoryAfter",session.memory.snapshot(session.state,session.actor));persist("RUNNING");
                } catch(Stop stop){throw stop;} catch(Exception failure){coverage.put("status","SUBMISSION_FAILED");rows.get(id).put("reason","GENERATION_OR_SUBMISSION_EXCEPTION");persist("RUNNING");}
            }
            if("REMAINING".equals(phase))for(var frozen:maps(bundle.get("sequences"))) {
                List<String> ids=new ArrayList<>();for(int i=0;i<4;i++)ids.add(text(sequenceMeta(text(frozen.get("gameId")),text(frozen.get("personaId")),i).get("id")));
                if(ids.stream().anyMatch(id->!"NOT_RUN".equals(rows.get(id).get("status")))) {
                    for(String id:ids)if("NOT_RUN".equals(rows.get(id).get("status")))rows.get(id).put("reason","PRIOR_SEQUENCE_INTERRUPTION");
                    continue; // Never replay a partly consumed sequence or overwrite completed records.
                }
                var session=session(frozen,this::evaluate);assertFrozen(frozen,session.rule,observe(session));
                session.afterStep=()->{rows.get(ids.get(session.step-1)).put("memoryAfter",session.memory.snapshot(session.state,session.actor));persistUnchecked();};
                try{session.run();}
                catch(Stop stop){throw stop;}
                catch(Exception failure){for(String id:ids)if("NOT_RUN".equals(rows.get(id).get("status")))rows.get(id).put("reason","SEQUENCE_CANNOT_ADVANCE");}
                persist("RUNNING");
            }
            persist("PILOT".equals(phase)?"PILOT_REQUIRES_REVIEW":"COLLECTED_REQUIRES_REVIEW");
        }catch(Stop stop){document.put("stopReason",stop.getMessage());persist("STOPPED");throw stop;}
    }
    AiTurnDecision evaluate(MilestoneClosureScenarios.Sample sample)throws Exception {
        var row=rows.get(sample.id());if(row==null||!"NOT_RUN".equals(row.get("status")))throw new Stop("SAMPLE_ALREADY_ATTEMPTED");
        row.put("status","STARTED");row.put("observation",sample.observation());row.put("input",AiPromptProjection.project(sample.adapter(),sample.observation()));row.put("coverage",sample.coverage());persist("RUNNING");
        AiTurnDecision result;
        try{result=generate.run(sample);}catch(Stop stop){row.put("status","NO_RESPONSE");persist("STOPPED");throw stop;}
        catch(Exception failure){row.put("status","NO_RESPONSE");row.put("reason","GENERATION_EXCEPTION");persist("RUNNING");throw failure;}
        row.put("decision",result);row.put("status",result.fallback()?"LOCAL_FALLBACK":"GENERATED");persist("RUNNING");
        if(strings(result.diagnostics().get("qualityFlags")).stream().anyMatch(STOP_FLAGS::contains))throw new Stop("RESOURCE_AUTHORITY_OR_BOUNDARY_STOP");
        return result;
    }
    ClosureSequenceRunner.Session session(Map<String,Object> seed,MilestoneClosureScenarios.Evaluate evaluator) {
        var rule=fixtures.rules.stream().filter(r->r.gameId().equals(seed.get("gameId"))).findFirst().orElseThrow();
        var persona=PersonaPresets.all().stream().filter(p->p.getId().equals(seed.get("personaId"))).findFirst().orElseThrow();
        var session=new ClosureSequenceRunner(fixtures).new Session(rule,JSON.convertValue(seed.get("state"),GameState.class),persona,evaluator);
        session.now=LocalDateTime.parse(text(seed.get("now")));session.soupInitialized=Boolean.TRUE.equals(seed.get("soupInitialized"));return session;
    }
    static VisibleObservation observe(ClosureSequenceRunner.Session s){return s.observations.build(s.state,s.rule,s.rule.pendingTurns(s.state).stream().filter(t->s.actor.equals(t.actorId())).findFirst().orElseThrow());}
    static void assertFrozen(Map<String,Object> seed,GameRuleSet rule,VisibleObservation o) {
        if(!JSON.valueToTree(seed.get("observation")).equals(JSON.valueToTree(o))||!JSON.valueToTree(seed.get("input")).equals(JSON.valueToTree(AiPromptProjection.project(rule,o))))throw new Stop("FROZEN_INPUT_MISMATCH");
    }
    void persist(String status)throws IOException{
        // Each changed sample has an immutable external revision, including STARTED and failed outcomes.
        Path directory=output.resolveSibling(output.getFileName()+".samples");Files.createDirectories(directory);
        for(var entry:rows.entrySet()){
            byte[] bytes=JSON.writeValueAsBytes(entry.getValue());String digest;
            try{digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
            catch(java.security.NoSuchAlgorithmException impossible){throw new IOException("SHA256_UNAVAILABLE");}
            if(!digest.equals(savedRows.get(entry.getKey()))){Path revision=directory.resolve(digest+".json");if(!Files.exists(revision))Files.write(revision,bytes,StandardOpenOption.CREATE_NEW);savedRows.put(entry.getKey(),digest);}
        }
        document.put("sampleRevisionSha256",new LinkedHashMap<>(savedRows));document.put("status",status);atomic(output,document);
    }
    void persistUnchecked(){try{persist("RUNNING");}catch(IOException failure){throw new Stop("EVIDENCE_WRITE_FAILED");}}
}
