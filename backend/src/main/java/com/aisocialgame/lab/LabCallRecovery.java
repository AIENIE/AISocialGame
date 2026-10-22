package com.aisocialgame.lab;

import com.aisocialgame.exception.ApiException;
import java.util.*;
import static com.aisocialgame.engine.v2.RuleSupport.*;

/** Durable transport state shared by player actions and coach reviews. Never guesses whether a request was sent. */
public final class LabCallRecovery {
    private LabCallRecovery() {}
    public static final long MAX_WAIT_MS=600_000;
    private static final long[] BACKOFF={2_000,5_000,10_000};
    public static String requestId(String actionId,Map<String,Object> checkpoint) {
        int transport=number(checkpoint.get("transportAttempt"),0);
        return transport==0?actionId:actionId+":transport:"+transport;
    }
    public static boolean recoverable(String code) {
        return Set.of("AI_REQUEST_NOT_SENT","AI_RESULT_PENDING","AI_RESULT_UNKNOWN","AI_USAGE_UNKNOWN","BUDGET_RECONCILIATION_REQUIRED","AI_BUDGET_BUSY").contains(code);
    }
    /** Returns a waiting reason; terminal reasons pause the experiment with this same checkpoint. */
    public static String defer(Map<String,Object> checkpoint,ApiException failure,long now) {
        checkpoint.putIfAbsent("waitingSinceEpochMs",now);
        checkpoint.put("lastCallState",new LinkedHashMap<>(failure.getDetails()));
        String code=failure.getCode();
        long wait=2_000;
        if("AI_REQUEST_NOT_SENT".equals(code)) {
            int attempt=number(checkpoint.get("transportAttempt"),0);
            checkpoint.put("transportAttempt",attempt+1);
            if(attempt>=BACKOFF.length) return "LAB_CALL_RETRIES_EXHAUSTED";
            wait=BACKOFF[attempt];
        }
        checkpoint.put("retryAfterEpochMs",now+wait);
        if("RECONCILIATION_REQUIRED".equals(failure.getDetails().get("remoteState"))) return "LAB_CALL_UNCERTAIN";
        if(now-((Number)checkpoint.get("waitingSinceEpochMs")).longValue()>=MAX_WAIT_MS) return "LAB_CALL_NO_PROGRESS";
        return code;
    }
    public static boolean blocked(String reason) {
        return Set.of("LAB_CALL_RETRIES_EXHAUSTED","LAB_CALL_UNCERTAIN","LAB_CALL_NO_PROGRESS").contains(reason);
    }
    public static boolean ready(Map<String,Object> checkpoint,long now) {
        return !(checkpoint.get("retryAfterEpochMs") instanceof Number n) || n.longValue()<=now;
    }
    /** Retry only local transaction work, after its transaction has rolled back. */
    public static void commit(Runnable operation) {
        for(int attempt=0;;attempt++) {
            try { operation.run(); return; }
            catch(org.springframework.dao.TransientDataAccessException conflict) {
                if(attempt>=4) throw conflict;
                try { Thread.sleep(50L*(attempt+1)); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw conflict; }
            }
        }
    }
}
