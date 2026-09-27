package com.aisocialgame.service.safety;

import com.aisocialgame.exception.ApiException;
import com.aisocialgame.model.AiCallUsage;
import com.aisocialgame.repository.AiCallUsageRepository;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;

@Service
public class AiCallAdmission {
    private static final String LOCK = "__safety_admission_lock__";
    private final AiCallUsageRepository records;
    private final TransactionTemplate tx;
    private final AiSafetyService safety;
    private final Environment config;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public AiCallAdmission(AiCallUsageRepository records, org.springframework.transaction.PlatformTransactionManager manager, AiSafetyService safety, Environment config) {
        this(records, manager, safety, config, Clock.systemUTC());
    }
    AiCallAdmission(AiCallUsageRepository records, org.springframework.transaction.PlatformTransactionManager manager, AiSafetyService safety, Environment config, Clock clock) {
        this.records=records; this.safety=safety; this.config=config; this.clock=clock;
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private synchronized void initialize() {
        try { tx.executeWithoutResult(s -> { if (!records.existsById(LOCK)) { AiCallUsage guard=new AiCallUsage(); guard.id=LOCK; guard.outcome="LOCK"; records.saveAndFlush(guard); } }); }
        catch (org.springframework.dao.DataIntegrityViolationException race) { if (!records.existsById(LOCK)) throw race; }
    }
    public void begin(String id, AiSafetyContext context) {
        safety.requireCallAllowed(context);
        initialize();
        boolean admitted=Boolean.TRUE.equals(tx.execute(s -> {
            records.lock(LOCK).orElseThrow();
            if (records.existsById(id)) throw new ApiException(HttpStatus.CONFLICT, "调用标识已使用");
            long now=clock.millis();
            var recent=records.findByStartedEpochMsGreaterThanEqualAndAdmittedTrue(now-60_000);
            AiCallUsage row=new AiCallUsage(); row.id=id; row.source=context.getSource(); row.userId=context.getUserId(); row.roomId=context.getRoomId(); row.personaId=context.getPersonaId(); row.modelKey=context.getModelKey(); row.startedEpochMs=now;
            row.admitted=count(recent,"project",row)<limit("requests","project",180)
                && count(recent,"model",row)<limit("requests","model",120)
                && (row.userId==null || count(recent,"user",row)<limit("requests","user",20))
                && (row.roomId==null || count(recent,"room",row)<limit("requests","room",30));
            row.outcome=row.admitted?"STARTED":"RATE_LIMITED"; records.saveAndFlush(row); return row.admitted;
        }));
        if (!admitted) { safety.recordOperational(context,"ABNORMAL_RATE","RATE_LIMIT"); throw new AiCallBlockedException(AiCallBlockedException.Reason.CALL_RATE_LIMITED); }
    }
    public void finish(String id, String outcome, Long input, Long output) {
        AiCallUsage anomaly=tx.execute(s -> {
            records.lock(LOCK).orElseThrow();
            AiCallUsage row=records.findById(id).orElseThrow();
            if (row.completedEpochMs!=null || !row.admitted) return null;
            row.completedEpochMs=clock.millis(); row.outcome=outcome;
            row.promptTokens=input!=null&&input>=0?input:null; row.completionTokens=output!=null&&output>=0?output:null; records.saveAndFlush(row);
            var recent=records.findByCompletedEpochMsGreaterThanEqualAndAdmittedTrue(clock.millis()-300_000);
            List<String> exceeded=new ArrayList<>();
            for (String scope:List.of("user","room","model","project")) {
                if ("user".equals(scope)&&row.userId==null || "room".equals(scope)&&row.roomId==null) continue;
                var group=recent.stream().filter(r -> same(scope,r,row)).toList();
                java.math.BigInteger tokens=group.stream().flatMap(r -> java.util.stream.Stream.of(r.promptTokens,r.completionTokens)).filter(Objects::nonNull).map(java.math.BigInteger::valueOf).reduce(java.math.BigInteger.ZERO,java.math.BigInteger::add);
                long defaultLimit=switch(scope){case "user"->100_000;case "room"->200_000;case "model"->1_000_000;default->1_500_000;};
                if (tokens.compareTo(java.math.BigInteger.valueOf(limit("tokens",scope,defaultLimit)))>0 && group.stream().noneMatch(r -> r.anomalyScopes!=null && Arrays.asList(r.anomalyScopes.split(",")).contains(scope))) exceeded.add(scope);
            }
            if(!exceeded.isEmpty()) {row.anomaly=true;row.anomalyScopes=String.join(",",exceeded);records.save(row);return row;}
            return null;
        });
        if (anomaly!=null) safety.recordOperational(AiSafetyContext.source(anomaly.source).room(anomaly.roomId,null).user(anomaly.userId,null).persona(anomaly.personaId).model(anomaly.modelKey),"COST_ANOMALY","ESCALATE");
    }
    private long limit(String kind,String scope,long fallback) { return Math.max(1,config.getProperty("app.ai.safety."+kind+"."+scope,Long.class,fallback)); }
    private long count(List<AiCallUsage> rows,String scope,AiCallUsage subject) { return rows.stream().filter(r -> same(scope,r,subject)).count(); }
    private boolean same(String scope,AiCallUsage a,AiCallUsage b) { return switch(scope){case "user" -> Objects.equals(a.userId,b.userId);case "room" -> Objects.equals(a.roomId,b.roomId);case "model" -> Objects.equals(a.modelKey,b.modelKey);default -> true;}; }
}
