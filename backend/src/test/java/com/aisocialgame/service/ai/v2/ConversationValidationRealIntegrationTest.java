package com.aisocialgame.service.ai.v2;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import java.nio.file.*;
import java.time.Instant;
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

@EnabledIfEnvironmentVariable(named="AI_CONVERSATION_REAL",matches="1")
@SpringBootTest(classes=AiSocialGameApplication.class,properties={
    "spring.datasource.url=jdbc:h2:mem:conversation-real;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.grpc.client.channel.ai.target=${AI_GRPC_ADDR:static://127.0.0.1:19003}","spring.grpc.client.channel.ai.ssl.enabled=true",
    "app.grpc.ai-trust-cert-collection=",
    "app.external.aiservice-hmac-caller=${APP_EXTERNAL_AISERVICE_HMAC_CALLER:}",
    "app.external.aiservice-hmac-secret=${APP_EXTERNAL_AISERVICE_HMAC_SECRET:}",
    "app.ai.default-model=${APP_AI_DEFAULT_MODEL:}","app.ai.system-user-id=${APP_AI_SYSTEM_USER_ID:1}",
    "app.game.scheduler-enabled=false","app.ai.validation-call-limit=0"
})
@ActiveProfiles("test")
class ConversationValidationRealIntegrationTest {
    @MockitoSpyBean AiGrpcClient client;
    @Autowired AiTurnGenerator generator;
    String currentSample;String fatal;
    ConversationValidation collector;
    @Test void collectWithFrozenInputsOriginalJournalAndExplicitGrant()throws Exception {
        assertTrue(System.getProperty("os.name").startsWith("Windows"));assertEquals("local",System.getenv("ENV"));
        assertEquals("static://localaiservice.testhut.top:22011",System.getenv("AI_GRPC_ADDR"));assertEquals("TLS",System.getenv("AI_GRPC_NEGOTIATION_TYPE"));assertEquals("deepseek-flash",System.getenv("APP_AI_DEFAULT_MODEL"));
        Path manifest=external("AI_CONVERSATION_MANIFEST",true),bundle=external("AI_CONVERSATION_BUNDLE",true),grantPath=external("AI_CONVERSATION_GRANT",true),
                journal=external("AI_REALISM_BUDGET_FILE",true),output=external("AI_CONVERSATION_OUTPUT",false),jar=Path.of(Objects.requireNonNull(System.getenv("AI_CONVERSATION_JAR")));
        String phase=System.getenv("AI_CONVERSATION_PHASE");assertTrue(Set.of("PILOT","REMAINING").contains(phase));
        var command=new ArrayList<>(List.of("python.exe","../scripts/windows/support/conversation_validation.py","--mode","gate","--root","..","--jar",jar.toString(),"--manifest",manifest.toString(),"--bundle",bundle.toString(),"--ledger",journal.toString(),"--grant",grantPath.toString(),"--phase",phase));
        command.addAll(List.of("--prerequisites",external("AI_CONVERSATION_PREREQUISITES",true).toString()));
        Map<String,Object> prior=Map.of();Path priorPath=null;
        if(System.getenv("AI_CONVERSATION_PRIOR")!=null){priorPath=external("AI_CONVERSATION_PRIOR",true);prior=ConversationValidation.read(priorPath);command.addAll(List.of("--prior",priorPath.toString()));}
        if("REMAINING".equals(phase)){
            Path pilot=external("AI_CONVERSATION_PILOT",true),review=external("AI_CONVERSATION_REVIEW",true);
            command.addAll(List.of("--evidence",pilot.toString(),"--review",review.toString()));
            if(prior.isEmpty()){priorPath=pilot;prior=ConversationValidation.read(pilot);}
        }
        var check=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();assertEquals(0,check.waitFor(),"Strict profile authorization gate failed");
        var m=ConversationValidation.read(manifest);var g=ConversationValidation.read(grantPath);
        assertEquals(m.get("buildId"),AiBuildIdentity.current().get("buildId"));assertEquals(g.get("callerId"),System.getenv("APP_EXTERNAL_AISERVICE_HMAC_CALLER"));
        try(var ledger=new AiRealismComparisonLedger(journal,((Number)g.get("cumulativeComparisonLimit")).intValue())){
            var budget=new ConversationCallBudget(ledger,journal,m,phase,Instant.parse(text(g.get("expiresAt"))),output.toString(),Instant::now);
            collector=new ConversationValidation(m,ConversationValidation.read(bundle),output,prior,phase,budget.previouslyReserved,s->{
                currentSample=s.id();fatal=null;
                var decision=generator.generate(s.adapter(),s.observation(),"conversation-"+UUID.randomUUID());
                if(fatal!=null)throw new ConversationValidation.Stop(fatal);
                return decision;
            });
            collector.document.put("manifestSha256",ConversationValidation.sha(manifest));collector.document.put("artifactSha256",ConversationValidation.sha(jar));
            if(priorPath!=null)collector.document.put("parentEvidenceSha256",ConversationValidation.sha(priorPath));
            collector.document.put("ledgerSha256Before",ledger.sha256());collector.document.put("cumulativeReservedCallsBefore",ledger.consumed());
            doAnswer(invocation->{
                String request=invocation.getArgument(5);
                try{
                    int count=budget.reserve(currentSample,request);
                    var row=collector.rows.get(currentSample);var receipts=new ArrayList<>(maps(row.get("reservations")));
                    receipts.add(Map.of("comparisonAttempt",count,"requestId",request));row.put("reservations",receipts);
                    collector.document.put("cumulativeReservedCalls",count);collector.persist("RUNNING");
                }catch(Exception error){fatal="RESERVATION_OR_EVIDENCE_STOP";throw new ConversationValidation.Stop(fatal);}
                return invocation.callRealMethod();
            }).when(client).chatCompletions(anyString(),anyLong(),anyString(),anyString(),anyList(),anyString(),anyInt());
            try{collector.run();}finally{
                collector.document.put("cumulativeReservedCalls",ledger.consumed());collector.document.put("ledgerSha256After",ledger.sha256());collector.persist(text(collector.document.get("status")));
            }
        }
    }
    static Path external(String key,boolean exists)throws Exception {
        Path path=Path.of(Objects.requireNonNull(System.getenv(key)));assertTrue(path.isAbsolute());
        Path repo=Path.of("..").toRealPath(),parent=path.getParent().toRealPath();assertFalse(parent.startsWith(repo));
        if(exists){assertTrue(Files.isRegularFile(path));assertFalse(Files.isSymbolicLink(path));assertTrue(Files.size(path)<32_000_000);assertFalse(path.toRealPath().startsWith(repo));}
        else assertFalse(Files.exists(path));return parent.resolve(path.getFileName());
    }
}
