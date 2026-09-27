package com.aisocialgame.service.ai.v2;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MilestoneClosureScenariosTest {
    @Test void freezeInventoryAndRunSequentialRulesWithoutModelCalls() throws Exception {
        var fixtures=new MilestoneClosureScenarios();var singles=fixtures.singles();var hosts=fixtures.hosts();
        assertEquals(120,singles.size());assertEquals(12,hosts.size());assertEquals(48,fixtures.sequenceIds().size());
        Set<String> seen=new HashSet<>();List<Map<String,Object>> rows=new ArrayList<>();
        for(var sample:singles) {assertTrue(seen.add(sample.id()));rows.add(sample.metadata());assertFalse(sample.observation().persona().isEmpty());}
        fixtures.sequences(sample -> { assertTrue(seen.add(sample.id())); rows.add(sample.metadata());return AiTurnDecision.fallback(sample.adapter().fallback(sample.observation())); });
        for(var sample:hosts) {assertTrue(seen.add(sample.id()));rows.add(sample.metadata());}
        assertEquals(180,seen.size());Files.createDirectories(Path.of("target"));
        var manifest=MilestoneClosureScenarios.header("closure-v2-"+UUID.randomUUID(),"REAL_MODEL");
        manifest.put("sampleCount",180);manifest.put("maxDiscreteCalls",360);manifest.put("realCalls",0);manifest.put("samples",rows);
        manifest.put("pilotIds",singles.stream().filter(s->singles.stream().filter(x->x.observation().gameId().equals(s.observation().gameId())).findFirst().orElseThrow().metadata().get("scenarioId").equals(s.metadata().get("scenarioId"))).map(MilestoneClosureScenarios.Sample::id).toList());
        MilestoneClosureScenarios.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/m1-m5-evaluation-manifest.json").toFile(),manifest);
    }
}
