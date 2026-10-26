package com.aisocialgame.lab;

import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WerewolfLabReviewTest {
    @Test void coachUsesActualDecisionVisibilityExperienceAndPlayerFacingSeatNumbers() throws Exception {
        var jobs=mock(AiTurnJobRepository.class);var traces=mock(AiDecisionTraceRepository.class);var strategies=mock(LabStrategyService.class);
        var json=new ObjectMapper().findAndRegisterModules();var review=new WerewolfLabReview(jobs,traces,strategies,json);
        var experiment=new LabExperiment();var match=new LabMatch();match.archiveId="archive-fixture";match.roomId="room";
        match.snapshot.put("players",Map.of("actor",Map.of()));match.snapshot.put("coach",Map.of("modelKey","fixture"));
        var job=new AiTurnJob();job.setId("original-job");job.setActorId("actor");job.setStatus("SUCCEEDED");job.setKind("SPEAK");
        job.setObservation(Map.of("phase","DAY_DISCUSS","self",Map.of("role","VILLAGER","seatNumber",0),"players",List.of(Map.of("playerId","actor","seatNumber",0),Map.of("playerId","other","seatNumber",1)),
                "memory",Map.of("strategy",Map.of("versionId","used-v1")),"events",List.of(Map.of("eventId","visible-at-decision","message","1号说自己缺少证据"))));
        when(jobs.findByInstanceIdOrderByCreatedAtAsc(match.archiveId)).thenReturn(List.of(job));when(traces.findByRoomIdAndInstanceIdAndActorPlayerId(anyString(),anyString(),anyString())).thenReturn(List.of());
        var state=new GameState();state.getData().put("events",List.of(Map.of("eventId","future-hidden","message","事后揭晓")));
        var request=review.request(experiment,match,state);var input=json.readValue(text(maps(request.get("messages")).get(1).get("content")),Map.class);
        var decision=maps(input.get("decisions")).getFirst();assertEquals(1,map(decision.get("self")).get("seatNumber"));assertEquals(2,maps(decision.get("players")).get(1).get("seatNumber"));
        assertEquals(0,map(job.getObservation().get("self")).get("seatNumber"));assertEquals("used-v1",map(decision.get("usedStrategy")).get("versionId"));
        assertEquals(List.of("visible-at-decision"),decision.get("visibleEventIds"));assertFalse(map(input.get("eventCatalog")).containsKey("future-hidden"));
        assertTrue(input.containsKey("outcomeTruth"));assertTrue(text(input.get("seatNumbering")).contains("1起始"));
    }
}
