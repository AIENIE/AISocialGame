package com.aisocialgame.service.ai.v2;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

class ConversationValidationTest {
    @TempDir Path temp;
    static ConversationValidation.Freeze frozen;
    @BeforeAll static void freeze()throws Exception { frozen=ConversationValidation.freeze("SYNTHETIC_TEST"); }
    static AiTurnDecision mockDecision(MilestoneClosureScenarios.Sample sample) {
        var a=sample.adapter().fallback(sample.observation());
        return new AiTurnDecision(a,a.getContent(),Map.of(),List.of(),Map.of(),false,Map.of("calls",1,"repaired",false));
    }
    @Test void frozenNinetySixActuallySubmitAndPreservePilotOnResume()throws Exception {
        assertEquals(96,maps(frozen.manifest().get("samples")).size());assertEquals(12,strings(frozen.manifest().get("pilotIds")).size());
        var calls=new AtomicInteger();ConversationValidation.Generate generate=s->{calls.incrementAndGet();return mockDecision(s);};
        var pilot=new ConversationValidation(frozen.manifest(),frozen.bundle(),temp.resolve("pilot.json"),Map.of(),"PILOT",Set.of(),generate);pilot.run();assertEquals(12,calls.get());
        var previous=ConversationValidation.read(pilot.output);
        var remaining=new ConversationValidation(frozen.manifest(),frozen.bundle(),temp.resolve("remaining.json"),previous,"REMAINING",Set.of(),generate);remaining.run();assertEquals(96,calls.get());
        for(var row:remaining.rows.values()){assertEquals("GENERATED",row.get("status"),text(row.get("id")));assertEquals("APPLIED",map(row.get("coverage")).get("status"),text(row.get("id")));assertNotNull(row.get("memoryAfter"));}
        Path directory=Path.of("target/conversation-validation-mock");ConversationValidation.writeFreeze(frozen,directory);
        remaining.document.put("bundleSha256",frozen.manifest().get("bundleSha256"));ConversationValidation.atomic(directory.resolve("evidence.json"),remaining.document);
        var real=ConversationValidation.freeze("REAL_MODEL");ConversationValidation.writeFreeze(real,Path.of("target/conversation-validation-frozen"));
    }
    @Test void interruptionDoesNotRepayUncertainSampleOrOverwriteEvidence()throws Exception {
        var calls=new AtomicInteger();var pilot=new ConversationValidation(frozen.manifest(),frozen.bundle(),temp.resolve("first.json"),Map.of(),"PILOT",Set.of(),s->{calls.incrementAndGet();throw new ConversationValidation.Stop("CALL_BUDGET_EXHAUSTED");});
        assertThrows(ConversationValidation.Stop.class,pilot::run);assertEquals(1,calls.get());
        var resumed=new ConversationValidation(frozen.manifest(),frozen.bundle(),temp.resolve("second.json"),ConversationValidation.read(pilot.output),"PILOT",Set.of(),s->{calls.incrementAndGet();return mockDecision(s);});resumed.run();assertEquals(12,calls.get());assertEquals(1,resumed.rows.values().stream().filter(r->"NO_RESPONSE".equals(r.get("status"))).count());
        assertThrows(IllegalArgumentException.class,()->new ConversationValidation(frozen.manifest(),frozen.bundle(),pilot.output,Map.of(),"PILOT",Set.of(),ConversationValidationTest::mockDecision));
    }
    @Test void reservedBeforeCrashIsNeverRetriedAndResourceFallbackStopsBeforeApply()throws Exception {
        String id=strings(frozen.manifest().get("pilotIds")).getFirst();var calls=new AtomicInteger();
        var run=new ConversationValidation(frozen.manifest(),frozen.bundle(),temp.resolve("run.json"),Map.of(),"PILOT",Set.of(id),s->{calls.incrementAndGet();var a=s.adapter().fallback(s.observation());return new AiTurnDecision(a,a.getContent(),Map.of(),List.of(),Map.of(),true,Map.of("qualityFlags",List.of("MODEL_RESOURCE_EXHAUSTED")));});
        assertThrows(ConversationValidation.Stop.class,run::run);assertEquals(1,calls.get());assertEquals("NO_RESPONSE",run.rows.get(id).get("status"));assertTrue(run.rows.values().stream().noneMatch(r->"APPLIED".equals(map(r.get("coverage")).get("status"))));
    }
    @Test void partialSequenceKeepsGapWhileOtherIndependentSequencesFinish()throws Exception {
        var run=new ConversationValidation(frozen.manifest(),frozen.bundle(),temp.resolve("one.json"),Map.of(),"REMAINING",Set.of(),s->{if(s.id().startsWith("sequence:")&&s.id().endsWith(":2"))throw new ConversationValidation.Stop("STOP_TEST");return mockDecision(s);});
        assertThrows(ConversationValidation.Stop.class,run::run);
        var resume=new ConversationValidation(frozen.manifest(),frozen.bundle(),temp.resolve("two.json"),ConversationValidation.read(run.output),"REMAINING",Set.of(),ConversationValidationTest::mockDecision);resume.run();
        assertEquals(2,resume.rows.values().stream().filter(r->"PRIOR_SEQUENCE_INTERRUPTION".equals(r.get("reason"))).count());
        assertEquals(46,resume.rows.values().stream().filter(r->"SEQUENCE".equals(r.get("group"))&&!"NOT_RUN".equals(r.get("status"))).count());
    }
}
