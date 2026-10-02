package com.aisocialgame.service.ai.v2;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Requires a reviewed external approval file and the original cumulative journal. Never runs in L2. */
@EnabledIfEnvironmentVariable(named="AI_MILESTONE_CLOSURE_REAL",matches="1")
@SpringBootTest(classes=AiSocialGameApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:closure-real;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.grpc.client.channel.ai.target=${AI_GRPC_ADDR:static://127.0.0.1:19003}","spring.grpc.client.channel.ai.ssl.enabled=true",
    "app.grpc.ai-trust-cert-collection=",
    "app.external.aiservice-hmac-caller=${APP_EXTERNAL_AISERVICE_HMAC_CALLER:}",
    "app.external.aiservice-hmac-secret=${GRPC_SHARED_SECRET:}",
    "app.ai.default-model=${APP_AI_DEFAULT_MODEL:}","app.ai.system-user-id=${APP_AI_SYSTEM_USER_ID:1}",
    "app.game.scheduler-enabled=false","app.ai.validation-call-limit=0"
})
@ActiveProfiles("test")
class MilestoneClosureRealIntegrationTest {
    @MockitoSpyBean AiGrpcClient client;
    @Autowired AiTurnGenerator generator;
    String currentSample;
    Map<String,Object> frozenHeader;
    @Test void collectOnlyWithinApprovedCumulativeBudget() throws Exception {
        assertTrue(System.getProperty("os.name").startsWith("Windows"));
        assertEquals("local",System.getenv("ENV"));assertEquals("static://localaiservice.testhut.top:22011",System.getenv("AI_GRPC_ADDR"));
        assertEquals("TLS",System.getenv("AI_GRPC_NEGOTIATION_TYPE"));assertEquals("deepseek-flash",System.getenv("APP_AI_DEFAULT_MODEL"));
        Path approval=external("AI_CLOSURE_APPROVAL",true), journal=external("AI_REALISM_BUDGET_FILE",true), output=external("AI_CLOSURE_EVIDENCE",false);
        assertFalse(Files.exists(output),"Evidence must be a new file");
        Map<String,Object> grant=map(MilestoneClosureScenarios.JSON.readValue(approval.toFile(),Map.class));
        assertEquals(journal.toRealPath().toString(),text(grant.get("comparisonLedger")));
        assertFalse(text(grant.get("approvalReference")).isBlank());assertFalse(text(grant.get("approvedBy")).isBlank());
        int cap=number(grant.get("cumulativeComparisonLimit"),0);assertTrue(cap>=90);
        assertEquals(MilestoneClosureScenarios.sourceFingerprint(),grant.get("sourceFingerprint"));
        Path manifestPath=Path.of(Objects.requireNonNull(System.getenv("AI_CLOSURE_MANIFEST")));
        var manifest=map(MilestoneClosureScenarios.JSON.readValue(manifestPath.toFile(),Map.class));
        frozenHeader=MilestoneClosureScenarios.header(text(manifest.get("batchId")),"REAL_MODEL");
        frozenHeader.forEach((key,value)->assertEquals(value,manifest.get(key),key));
        assertEquals(frozenHeader.get("buildId"),AiBuildIdentity.current().get("buildId"));
        assertEquals(manifest.get("buildId"),grant.get("buildId"));assertEquals(manifest.get("batchId"),grant.get("batchId"));
        assertEquals("deepseek-flash",grant.get("model"));
        assertEquals(System.getenv("APP_EXTERNAL_AISERVICE_HMAC_CALLER"),grant.get("callerId"));
        assertTrue(java.time.Instant.parse(text(grant.get("expiresAt"))).isAfter(java.time.Instant.now()));
        assertTrue(java.nio.file.Files.isRegularFile(Path.of("src/test/resources/ai-realism/closure-sequence-states-v2.json")));
        assertTrue(List.of("PILOT","REMAINING").contains(System.getenv("AI_CLOSURE_PHASE")));
        var fixtures=new MilestoneClosureScenarios();var singles=fixtures.singles();
        Set<String> pilotIds=new LinkedHashSet<>();for(String game:List.of("undercover","werewolf","turtle_soup")) singles.stream().filter(s->s.observation().gameId().equals(game)).limit(4).forEach(s->pilotIds.add(s.id()));
        boolean remaining="REMAINING".equals(System.getenv("AI_CLOSURE_PHASE"));
        List<Map<String,Object>> results=new ArrayList<>();
        if(remaining) {
            Path pilot=external("AI_CLOSURE_PILOT_EVIDENCE",true),review=external("AI_CLOSURE_PILOT_REVIEW",true);
            // The exact same validator used by final reports runs before opening the chargeable ledger.
            var validator=new ProcessBuilder("python","../scripts/windows/support/closure_metrics.py","--mode","pilot","--manifest",manifestPath.toAbsolutePath().toString(),"--evidence",pilot.toString(),"--review",review.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertEquals(0,validator.waitFor(),"Pilot evidence/review failed strict shared validation");
            var previous=map(MilestoneClosureScenarios.JSON.readValue(pilot.toFile(),Map.class));
            results.addAll(maps(previous.get("samples")));

        }
        try(var ledger=new AiRealismComparisonLedger(journal,cap)) {
            int required=remaining?336:24;
            assertTrue(cap-ledger.consumed()>=required,"Approve sufficient cumulative headroom before issuing requests");
            doAnswer(invocation -> {ledger.reserve(output.toString(),currentSample,AiTurnGenerator.PROMPT_VERSION,invocation.getArgument(5));return invocation.callRealMethod();})
                    .when(client).chatCompletions(anyString(),anyLong(),anyString(),anyString(),anyList(),anyString(),anyInt());
            MilestoneClosureScenarios.Evaluate evaluate=sample -> {
                currentSample=sample.id();
                Map<String,Object> row=new LinkedHashMap<>(sample.metadata());row.put("coverage",sample.coverage());row.put("observation",sample.observation());row.put("expected",sample.expected());row.put("status","NO_RESPONSE");results.add(row);
                write(output,results,ledger.consumed(),"RUNNING");
                AiTurnDecision generated=generator.generate(sample.adapter(),sample.observation(),"closure-"+UUID.randomUUID());
                row.put("decision",generated);row.put("status",generated.fallback()?"LOCAL_FALLBACK":"GENERATED");write(output,results,ledger.consumed(),"RUNNING");
                var flags=strings(generated.diagnostics().get("qualityFlags"));
                if(flags.stream().anyMatch(f->f.contains("RESOURCE")||f.contains("AUTH")||f.contains("PERMISSION")||f.contains("BUDGET")||f.equals("CALL_RATE_LIMITED")||f.equals("ADMIN_CONTROL")))throw new IllegalStateException("STOP_RESOURCE_OR_AUTHORITY");
                return generated;
            };
            try {
                for(var sample:singles) if(remaining?!pilotIds.contains(sample.id()):pilotIds.contains(sample.id()))evaluate.run(sample);
                if(remaining){fixtures.sequences(evaluate);for(var sample:fixtures.hosts())evaluate.run(sample);}
                write(output,results,ledger.consumed(),remaining?"COLLECTED_REQUIRES_REVIEW":"PILOT_REQUIRES_REVIEW");
            } catch(Exception failure) {write(output,results,ledger.consumed(),"STOPPED");throw failure;}
        }
    }
    private static Path external(String name,boolean exists) throws Exception {
        String value=System.getenv(name);assertNotNull(value,name);Path path=Path.of(value);assertTrue(path.isAbsolute());
        Path repo=Path.of("..").toRealPath();Path parent=path.getParent().toRealPath();assertFalse(parent.startsWith(repo));
        if(exists){assertTrue(Files.isRegularFile(path));assertFalse(Files.isSymbolicLink(path));assertTrue(Files.size(path)<32_000_000);assertFalse(path.toRealPath().startsWith(repo));}
        return parent.resolve(path.getFileName());
    }
    private void write(Path path,List<Map<String,Object>> samples,int attempts,String status)throws Exception {
        var document=new LinkedHashMap<String,Object>(frozenHeader);document.put("status",status);document.put("cumulativeReservedCalls",attempts);document.put("samples",samples);document.put("reviewType","CODING_AGENT_NOT_INDEPENDENT_HUMAN_BLIND_REVIEW");
        MilestoneClosureScenarios.JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),document);
    }
}
