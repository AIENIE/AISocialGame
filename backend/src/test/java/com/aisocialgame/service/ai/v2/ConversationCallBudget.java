package com.aisocialgame.service.ai.v2;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Additional per-batch ceilings over the original cumulative reservation journal. */
final class ConversationCallBudget {
    final AiRealismComparisonLedger ledger;
    final String variant,phase,output;
    final Instant expires;
    final java.util.function.Supplier<Instant> clock;
    final Set<String> previouslyReserved=new HashSet<>();
    int batchCalls,pilotCalls;
    final Set<String> pilots;
    ConversationCallBudget(AiRealismComparisonLedger ledger,Path journal,Map<String,Object> manifest,String phase,Instant expires,String output,java.util.function.Supplier<Instant> clock)throws Exception {
        this.ledger=ledger;this.phase=phase;this.expires=expires;this.output=output;this.clock=clock;
        variant=ConversationValidation.SET+":"+text(manifest.get("batchId"));pilots=Set.copyOf(strings(manifest.get("pilotIds")));
        for(String line:new String(ledger.snapshot(),java.nio.charset.StandardCharsets.UTF_8).lines().toList()){
            var row=ConversationValidation.JSON.readValue(line,Map.class);
            if(variant.equals(row.get("variant"))){batchCalls++;String id=text(row.get("scenarioId"));previouslyReserved.add(id);if(pilots.contains(id))pilotCalls++;}
        }
    }
    synchronized int reserve(String id,String request)throws Exception {
        if(!expires.isAfter(clock.get()))throw new ConversationValidation.Stop("AUTHORIZATION_EXPIRED");
        if(previouslyReserved.contains(id))throw new ConversationValidation.Stop("SAMPLE_PREVIOUSLY_RESERVED");
        if(batchCalls>=192 || (pilots.contains(id)&&pilotCalls>=24))throw new ConversationValidation.Stop("BATCH_CALL_LIMIT");
        if("PILOT".equals(phase)!=pilots.contains(id))throw new ConversationValidation.Stop("WRONG_COLLECTION_PHASE");
        int n=ledger.reserve(output,id,variant,request);batchCalls++;if(pilots.contains(id))pilotCalls++;return n;
    }
}
