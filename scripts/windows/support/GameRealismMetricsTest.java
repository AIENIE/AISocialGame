import java.util.*;

/** Offline arithmetic/flag regression; no database or model client. */
class GameRealismMetricsTest {
    public static void main(String[] args) throws Exception {
        closureInstanceIsolation();
        var flags = GameRealismMetrics.qualityFlags("[\"MODEL_TIMEOUT\",\"UNSUPPORTED_SELF_HISTORY\",\"DECISION_DEADLINE_EXCEEDED\"]");
        if (flags.unknown() != 0 || flags.malformed() || flags.known().size() != 3) throw new AssertionError("quality flags lost");
        var memoryFlags = GameRealismMetrics.qualityFlags("[\"INVALID_MEMORY_FIELDS\",\"CONFLICTING_MEMORY_FIELDS\",\"INVALID_MEMORY_CONFIDENCE\"]");
        if (memoryFlags.unknown() != 0 || memoryFlags.malformed() || memoryFlags.known().size() != 3) throw new AssertionError("memory flags lost");
        var commitmentFlags = GameRealismMetrics.qualityFlags("[\"INVALID_COMMITMENT_FIELDS\",\"INVALID_COMMITMENT_WITHDRAWAL\",\"COMMITMENT_WITHDRAWAL_IGNORED\",\"COMMITMENT_CAPACITY_REACHED\"]");
        if (commitmentFlags.unknown() != 0 || commitmentFlags.malformed() || commitmentFlags.known().size() != 4) throw new AssertionError("commitment flags lost");
        var summary = GameRealismMetrics.traceSummary(List.of(
                new GameRealismMetrics.Trace("room", "mock-only", false, 10L, 5L, 100L, true, true, "1", flags),
                new GameRealismMetrics.Trace("room", "mock-only", false, 20L, 5L, 200L, false, true, "2", GameRealismMetrics.qualityFlags("[]"))));
        if (!Double.valueOf(.5).equals(summary.get("fallbackRate")) || !Long.valueOf(3).equals(summary.get("generationAttemptsObserved")))
            throw new AssertionError("incorrect fallback/call denominator");
        if (!Long.valueOf(0).equals(summary.get("unclassifiedQualityFlagCount"))) throw new AssertionError("unknown flag");
        var now = java.time.Instant.parse("2026-09-19T02:00:00Z");
        var oldJob = new GameRealismMetrics.Job("old", "room", "actor", "RUNNING", java.time.LocalDateTime.parse("2026-09-18T18:00:00"), java.time.LocalDateTime.parse("2026-09-18T18:00:00"));
        var oldSnapshot = new GameRealismMetrics.Snapshot(now, java.time.LocalDateTime.ofInstant(now, java.time.ZoneId.of("Asia/Shanghai")), List.of(oldJob));
        var oldAges = GameRealismMetrics.jobSummary(oldSnapshot, oldSnapshot, null, false);
        if (oldAges.get("oldestRunningAgeMs") != null || !Long.valueOf(1).equals(oldAges.get("pendingUnverifiedClockCount"))) throw new AssertionError("legacy eight-hour offset was guessed");
        var diagnostics = GameRealismMetrics.diagnosticJson("{\"promptVersion\":\"social-v2.6\",\"terminalStatus\":\"FAILED\",\"terminalReason\":\"SUBMISSION_EXCEPTION\",\"queueMs\":400,\"generation\":{\"promptVersion\":\"social-v2.6\",\"usageComplete\":false,\"qualityFlags\":[\"MODEL_TIMEOUT\"],\"attempts\":[{\"rpcMs\":1000,\"inputBytes\":4000}]}}");
        var failed = new GameRealismMetrics.Job("f", "room", "actor", "FAILED", null, null, diagnostics);
        var report = GameRealismMetrics.diagnosticSummary(List.of(failed, oldJob));
        if (!Double.valueOf(1).equals(report.get("terminalDiagnosticCoverage"))) throw new AssertionError("terminal denominator");
        var distributions = (Map<?, ?>)report.get("distributions");
        var parse = (Map<?, ?>)distributions.get("parseMs");
        if (parse.get("mean") != null || !Integer.valueOf(0).equals(parse.get("sampleCount"))) throw new AssertionError("missing phase became zero");
        var rpc = (Map<?, ?>)distributions.get("rpcMs");
        if (!Double.valueOf(1000).equals(rpc.get("mean"))) throw new AssertionError("RPC measurement lost");
        var epoch = GameRealismMetrics.diagnosticJson("{\"startedEpochMs\":" + now.minusSeconds(61).toEpochMilli() + "}");
        var running = new GameRealismMetrics.Job("new", "room", "actor", "RUNNING", null, null, epoch);
        for (String zone : List.of("UTC", "Asia/Shanghai")) {
            var snapshot = new GameRealismMetrics.Snapshot(now, java.time.LocalDateTime.ofInstant(now, java.time.ZoneId.of(zone)), List.of(running));
            if (!Long.valueOf(61000).equals(GameRealismMetrics.jobSummary(snapshot, snapshot, null, false).get("oldestRunningAgeMs"))) throw new AssertionError("epoch depends on zone");
        }
        if (GameRealismMetrics.versionSummary(List.of(), List.of(failed, oldJob)).size() != 2) throw new AssertionError("versions mixed");
        if (GameRealismMetrics.diagnosticSummary(List.of()).get("terminalDiagnosticCoverage") != null) throw new AssertionError("empty denominator");
        System.out.println(GameRealismMetrics.JSON.writeValueAsString(report));
        System.out.println(GameRealismMetrics.JSON.writeValueAsString(summary));
    }
    static void closureInstanceIsolation() throws Exception {
        String room="10000000-0000-0000-0000-000000000001",instance="20000000-0000-0000-0000-000000000001",old="20000000-0000-0000-0000-000000000002";
        var evidence=GameRealismMetrics.JSON.createObjectNode();evidence.put("evaluationSchemaVersion",2);evidence.put("evaluationSetVersion","closure-v2");evidence.put("batchId","test");evidence.put("sourceFingerprint","a".repeat(64));evidence.put("buildId","b".repeat(64));evidence.put("promptVersion","social-v2.8");evidence.put("inputFormatVersion",3);evidence.put("memoryFormatVersion",4);evidence.put("evidenceKind","SYNTHETIC_TEST");
        evidence.putArray("scenarios").addObject().put("roomId",room).put("archiveId",instance);
        var q=GameRealismMetrics.JSON.createObjectNode();q.put("instanceId",instance);q.put("jobId","job-current");q.put("sourceFingerprint","a".repeat(64));q.put("buildId","b".repeat(64));q.put("promptVersion","social-v2.8");q.put("calls",1);q.putArray("flags");
        var d=q.deepCopy();d.put("terminalStatus","SUCCEEDED");d.put("terminalReason","MODEL_ACTION_APPLIED");
        Object[][] jobRows={{"job-current",room,instance,"actor","SUCCEEDED",null,null,d.toString()}};
        var historic=q.deepCopy();historic.put("instanceId",old);historic.put("jobId","job-old");
        var mismatched=q.deepCopy();mismatched.put("instanceId",old);
        Object[][] traceRows={{"trace-current",room,"mock-only",1L,2L,3L,false,q.toString()},
            {"trace-old",room,"mock-only",1000L,2000L,3L,true,historic.toString()},
            {"trace-mismatch",room,"mock-only",1L,2L,3L,false,mismatched.toString()},
            {"trace-legacy",room,"mock-only",null,null,3L,null,"{}"}};
        java.sql.Connection connection=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(GameRealismMetricsTest.class.getClassLoader(),new Class[]{java.sql.Connection.class},(p,m,a)->{
            if(m.getName().equals("prepareStatement")){
                String sql=(String)a[0];boolean jobs=sql.contains("FROM ai_turn_jobs");
                if(jobs&&!sql.contains("WHERE instance_id IN"))throw new AssertionError("Task scope widened to room");
                if(sql.contains("observation")||sql.contains("raw_output")||sql.contains("memory_snapshot"))throw new AssertionError("Secret data selected");
                return java.lang.reflect.Proxy.newProxyInstance(GameRealismMetricsTest.class.getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(p2,m2,a2)->{
                    if(m2.getName().equals("setString")) {if(!Objects.equals(a2[1],jobs?instance:room))throw new AssertionError("Wrong scope parameter");return null;}
                    if(m2.getName().equals("executeQuery"))return rows(jobs?jobRows:traceRows);
                    return null;
                });
            }
            return null;
        });
        var result=GameRealismMetrics.collectClosure(connection,evidence,List.of(),List.of(room));
        var links=(List<Map<String,Object>>)result.get("traceLinks");
        if(links.size()!=2||links.stream().anyMatch(t->"trace-old".equals(t.get("id"))))throw new AssertionError("Old game mixed into current metrics");
        if(links.stream().noneMatch(t->"trace-mismatch".equals(t.get("id"))))throw new AssertionError("Mismatched trace hidden instead of flagged downstream");
        if(!Long.valueOf(1).equals(result.get("legacyUnattributedTraceCount")))throw new AssertionError("Unknown association guessed");
        if(((List<?>)result.get("taskLinks")).size()!=1)throw new AssertionError("Task links lost");
    }
    static java.sql.ResultSet rows(Object[][] values){
        int[] cursor={-1};boolean[] wasNull={false};
        return (java.sql.ResultSet)java.lang.reflect.Proxy.newProxyInstance(GameRealismMetricsTest.class.getClassLoader(),new Class[]{java.sql.ResultSet.class},(p,m,a)->{
            if(m.getName().equals("next"))return ++cursor[0]<values.length;
            if(m.getName().equals("close"))return null;
            if(m.getName().equals("wasNull"))return wasNull[0];
            Object value=values[cursor[0]][(Integer)a[0]-1];wasNull[0]=value==null;
            return switch(m.getName()){case "getString"->value==null?null:value.toString();case "getLong"->value==null?0L:((Number)value).longValue();case "getBoolean"->value!=null&&(Boolean)value;case "getObject"->value;default->null;};
        });
    }

}
