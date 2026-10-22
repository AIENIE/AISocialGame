package com.aisocialgame.lab;

import com.aisocialgame.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LabCallRecoveryTest {
    private ApiException failure(String code,Map<String,Object> details) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,code,code,details); }
    @Test void onlyConfirmedUnsentChangesTransportIdentityWithTwoFiveTenSecondBackoff() {
        var saved=new LinkedHashMap<String,Object>();long now=1_000;
        assertEquals("vote:0",LabCallRecovery.requestId("vote:0",saved));
        for(long delay:new long[]{2_000,5_000,10_000}) {
            assertEquals("AI_REQUEST_NOT_SENT",LabCallRecovery.defer(saved,failure("AI_REQUEST_NOT_SENT",Map.of("settlementState","RELEASED")),now));
            assertFalse(LabCallRecovery.ready(saved,now+delay-1));assertTrue(LabCallRecovery.ready(saved,now+delay));now+=delay;
        }
        assertEquals("vote:0:transport:3",LabCallRecovery.requestId("vote:0",saved));
        assertEquals("LAB_CALL_RETRIES_EXHAUSTED",LabCallRecovery.defer(saved,failure("AI_REQUEST_NOT_SENT",Map.of()),now));
    }
    @Test void UnknownOrRunningQueriesKeepOriginalIdentityAcrossRecreationAndPauseAfterTenMinutes() {
        var original=new LinkedHashMap<String,Object>();
        LabCallRecovery.defer(original,failure("AI_RESULT_PENDING",Map.of("remoteState","RUNNING")),10_000);
        var restored=new LinkedHashMap<>(original);
        assertEquals("vote:0",LabCallRecovery.requestId("vote:0",restored));
        assertEquals("AI_RESULT_UNKNOWN",LabCallRecovery.defer(restored,failure("AI_RESULT_UNKNOWN",Map.of()),20_000));
        assertEquals("LAB_CALL_NO_PROGRESS",LabCallRecovery.defer(restored,failure("AI_RESULT_UNKNOWN",Map.of()),610_000));
        assertEquals("vote:0",LabCallRecovery.requestId("vote:0",restored));
        assertEquals("LAB_CALL_UNCERTAIN",LabCallRecovery.defer(new LinkedHashMap<>(),failure("AI_RESULT_UNKNOWN",Map.of("remoteState","RECONCILIATION_REQUIRED")),1));
    }
    @Test void transientCommitConflictRetriesOnlyLocalWork() {
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        LabCallRecovery.commit(()->{if(attempts.incrementAndGet()<3) throw new org.springframework.dao.CannotAcquireLockException("fixture");});
        assertEquals(3,attempts.get());
    }
}
