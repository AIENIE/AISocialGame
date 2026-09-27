package com.aisocialgame.service.ai.v2;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ConversationCallBudgetTest {
    @TempDir Path temp;
    @Test void pilotExpiryCumulativeAndInterruptedReservationsBlockBeforeRpc()throws Exception {
        Path file=temp.resolve("synthetic-only.jsonl");Files.createFile(file);var m=Map.<String,Object>of("batchId","test","pilotIds",List.of("pilot"));
        Instant now=Instant.parse("2026-09-22T00:00:00Z");
        try(var ledger=new AiRealismComparisonLedger(file,30)){
            var budget=new ConversationCallBudget(ledger,file,m,"PILOT",now.plusSeconds(10),"test",()->now);
            for(int i=0;i<24;i++)budget.reserve("pilot","request"+i);
            assertThrows(ConversationValidation.Stop.class,()->budget.reserve("pilot","25"));assertEquals(24,ledger.consumed());
            var expired=new ConversationCallBudget(ledger,file,m,"REMAINING",now,"test",()->now);
            assertThrows(ConversationValidation.Stop.class,()->expired.reserve("other","r"));assertEquals(24,ledger.consumed());
            var reopened=new ConversationCallBudget(ledger,file,m,"PILOT",now.plusSeconds(10),"test",()->now);
            assertThrows(ConversationValidation.Stop.class,()->reopened.reserve("pilot","r"));
        }
        try(var ledger=new AiRealismComparisonLedger(file,24)){
            var budget=new ConversationCallBudget(ledger,file,m,"REMAINING",now.plusSeconds(10),"test",()->now);
            assertThrows(java.io.IOException.class,()->budget.reserve("other","r"));assertEquals(24,ledger.consumed());
        }
    }
}
