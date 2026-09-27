package com.aisocialgame.service.safety;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.repository.*;
import com.aisocialgame.exception.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=AiSocialGameApplication.class)
@ActiveProfiles("test")
class AiCallAdmissionTest {
    @Autowired AiCallUsageRepository rows;
    @Autowired AiSafetyEventRepository events;
    @Autowired AiSafetyControlRepository controls;
    @Autowired AiSafetyService safety;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoBean AiGrpcClient client;
    final MutableClock clock=new MutableClock();
    AiCallAdmission service;
    @BeforeEach void setup() {
        rows.deleteAll(); controls.deleteAll();
        service=new AiCallAdmission(rows,manager,safety,new MockEnvironment().withProperty("app.ai.safety.requests.project","5").withProperty("app.ai.safety.tokens.user","10"),clock);
    }
    AiSafetyContext context() { return AiSafetyContext.source("TEST").user("usage-user",null).room("usage-room",null).model("mock"); }
    @Test void concurrentRequestsCannotOverbookAndRestartPreservesWindow() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<12;i++) { String id="parallel-"+i; results.add(pool.submit(() -> {try {service.begin(id,context());return true;}catch(ApiException denied){return false;}})); }
            int accepted=0;for(var result:results) if(result.get(20,TimeUnit.SECONDS)) accepted++;
            assertEquals(5,accepted);
            var restarted=new AiCallAdmission(rows,manager,safety,new MockEnvironment().withProperty("app.ai.safety.requests.project","5"),clock);
            assertThrows(ApiException.class,()->restarted.begin("restart",context()));
            clock.now+=60_001; restarted.begin("later",context());
            assertThrows(ApiException.class,()->restarted.begin("later",context()));
        } finally { pool.shutdownNow(); }
    }
    @Test void usageMissingIsUnknownAndAnomalyAndAuditSurviveBusinessRollback() {
        long before=events.count();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.begin("known",context());service.finish("known","RESPONSE",8L,5L);
            service.begin("unknown",context());service.finish("unknown","NO_RESPONSE",null,null);
            status.setRollbackOnly();
        });
        assertTrue(events.count()>before);
        assertNull(rows.findById("unknown").orElseThrow().promptTokens);
        assertTrue(rows.findById("known").orElseThrow().anomaly);
        service.finish("known","NO_RESPONSE",null,null);
        assertEquals(8L,rows.findById("known").orElseThrow().promptTokens);
    }
    @Test void disabledModelIsRejectedBeforeAttemptReservation() {
        var control=safety.createControl("MODEL","mock","DISABLE_AI","test",null,"admin");
        try { assertThrows(ApiException.class,()->service.begin("blocked",context())); assertFalse(rows.existsById("blocked")); }
        finally { safety.disableControl(control.getId()); }
        service.begin("allowed",context());
    }
    static final class MutableClock extends Clock {
        long now=Instant.parse("2026-09-22T00:00:00Z").toEpochMilli();
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}
    }
}
