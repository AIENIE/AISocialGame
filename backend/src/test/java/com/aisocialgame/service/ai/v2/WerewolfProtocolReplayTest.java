package com.aisocialgame.service.ai.v2;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.engine.v2.werewolf.WerewolfRuleSet;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.service.safety.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WerewolfProtocolReplayTest {
    @Test @EnabledIfEnvironmentVariable(named="AISG_WEREWOLF_REPLAY",matches=".+")
    void oldFailedRepliesAreRevalidatedWithoutAProviderCallOrHistoricalRewrite() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var source=Path.of(System.getenv("AISG_WEREWOLF_REPLAY"));byte[] original=Files.readAllBytes(source);
        var exported=map(json.readValue(original,Map.class));var client=mock(AiGrpcClient.class);var memories=new AiMemoryServiceV2(mock(com.aisocialgame.repository.AiPersonaMemoryRepository.class),new com.aisocialgame.repository.PersonaRepository());var safety=mock(AiSafetyService.class);
        when(safety.review(anyString(),any(AiSafetyContext.class))).thenAnswer(c->new AiSafetyResult("ALLOW","LOW","NONE","",c.getArgument(0),null));
        var generator=new AiTurnGenerator(client,new AppProperties(),memories,safety);var rules=new WerewolfRuleSet();var rows=new ArrayList<Map<String,Object>>();int normalized=0;
        for(var task:maps(map(exported.get("run")).get("tasks"))) {
            var diagnostics=map(map(task.get("result")).get("diagnostics"));var raw=map(diagnostics.get("rawOutput"));
            if(!"FAILED".equals(task.get("status"))||raw.isEmpty())continue;
            var observation=json.convertValue(map(map(task.get("diagnostics")).get("input")).get("observation"),VisibleObservation.class);
            var decoded=WerewolfAiProtocol.decode(observation,raw,WerewolfAiProtocol.references(observation));var errors=new ArrayList<String>();
            try {var decision=generator.parse(decoded.output());errors.addAll(AiGrounding.check(observation,decoded.output()));errors.addAll(generator.validate(rules,observation,decision));}
            catch(IllegalArgumentException malformed){errors.add("INVALID_JSON_FIELDS");}
            if(!decoded.normalizations().isEmpty())normalized++;
            rows.add(Map.of("taskId",task.get("id"),"requestId",task.get("requestId"),"historicalFlags",diagnostics.getOrDefault("qualityFlags",List.of()),
                    "afterErrors",errors,"normalizations",decoded.normalizations(),"acceptedAfterRepair",errors.isEmpty()));
        }
        assertEquals(30,rows.size());assertEquals(17,normalized);verifyNoInteractions(client);assertArrayEquals(original,Files.readAllBytes(source));
        var report=new LinkedHashMap<String,Object>();report.put("sourceSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original)));
        report.put("paidCalls",0);report.put("historicalRecordsChanged",false);report.put("replayedFailedTasks",rows.size());report.put("normalizedMissingHeal",normalized);
        report.put("acceptedAfterRepair",rows.stream().filter(r->Boolean.TRUE.equals(r.get("acceptedAfterRepair"))).count());report.put("rows",rows);
        String output=System.getenv("AISG_WEREWOLF_REPLAY_OUTPUT");assertNotNull(output);Files.writeString(Path.of(output),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
