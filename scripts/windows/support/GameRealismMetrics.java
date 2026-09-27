import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Local, scoped SELECT-only metrics. Never exports prompts, outputs, memory, roles, words or credentials. */
class GameRealismMetrics {
    static final String RUN_ID = "game-realism-v2-live-20260912";
    static final int LIVE_CEILING = 210, MAX_ROOMS = 12, MAX_ROWS = 100_000;
    static final long PENDING_INTERVAL_MS = 65_000;
    static final Set<String> GAME_IDS = Set.of("undercover", "werewolf", "turtle_soup");
    static final List<String> JOB_STATES = List.of("SUCCEEDED", "FAILED", "DISCARDED", "QUEUED", "RUNNING", "UNKNOWN_STATUS");
    static final Set<String> SPEECH_TYPES = Set.of("SPEAK", "SPEECH", "LAST_WORDS", "ASK_PLAYER", "ANSWER_PLAYER", "TURTLE_SOUP_DISCUSSION", "TURTLE_SOUP_QUESTION_PENDING");
    static final Set<String> QUALITY_FLAGS = Set.of("CALL_BUDGET_EXHAUSTED", "CALL_RATE_LIMITED", "ADMIN_CONTROL", "INVALID_JSON_ENVELOPE", "INVALID_JSON_FIELDS", "LOCAL_GENERATION_ERROR", "MODEL_UNAVAILABLE",
            "MODEL_TIMEOUT", "MODEL_RESOURCE_EXHAUSTED", "MODEL_AUTH_REJECTED", "MODEL_REQUEST_REJECTED",
            "UNGROUNDED_BLANK_ASSERTION", "UNSUPPORTED_SELF_HISTORY", "CONTRADICTED_SELF_QUOTE",
            "DECISION_DEADLINE_EXCEEDED", "UNSUPPORTED_MEMORY_UPDATE", "UNKNOWN_BELIEF_PLAYER", "UNGROUNDED_RELATIONSHIP", "UNGROUNDED_EMOTION",
            "INVALID_COMMITMENT_FIELDS", "INVALID_COMMITMENT_WITHDRAWAL", "COMMITMENT_WITHDRAWAL_IGNORED", "COMMITMENT_CAPACITY_REACHED",
            "INVALID_MEMORY_FIELDS", "CONFLICTING_MEMORY_FIELDS", "INVALID_MEMORY_CONFIDENCE",
            "MISSING_ACTION", "ILLEGAL_ACTION", "ILLEGAL_TARGET", "MISSING_TARGET", "EMPTY_SPEECH", "CONTENT_TOO_LONG",
            "SPEECH_ACTION_MISMATCH", "ILLEGAL_ABSTENTION", "INVISIBLE_EVIDENCE", "INVALID_PRESENTATION", "INVALID_EMOTION",
            "INVALID_GESTURE", "INVALID_INTENSITY", "HOST_PRIVATE_OUTPUT", "REPEATED_SPEECH", "CONTENT_REVIEW_FAILED",
            "SECRET_WORD_LEAK", "VOTE_MUST_REMAIN_PRIVATE", "EMPTY_GAME_SPEECH", "INVALID_MEMORY_UPDATE", "INVALID_MEMORY",
            "PRIVATE_MEMORY_LEAK", "INVISIBLE_MEMORY_EVIDENCE", "INVALID_RELATIONSHIP", "INVALID_BELIEF");
    static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final Pattern MODEL_TEXT = Pattern.compile("[A-Za-z0-9._:/@+\\-]{1,128}");
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public static void main(String[] args) {
        try {
            if (args.length != 5) throw failure("INVALID_ARGUMENTS");
            if (!args[4].matches("jdbc:mysql://localbase\\.testhut\\.top:[0-9]{1,5}/aisocialgame\\?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=30000")) throw failure("UNVERIFIED_TARGET");
            Path repository = Path.of(args[3]).toRealPath();
            Path environment = inputPath(args[0], repository, 1024 * 1024, false);
            Path evidenceFile = inputPath(args[1], repository, 16 * 1024 * 1024, true);
            Path outputFile = outputPath(args[2], repository);
            if (environment.equals(evidenceFile) || outputFile.equals(environment) || outputFile.equals(evidenceFile)) throw failure("PATH_ALIAS");
            Map<String, String> environmentValues = readEnvironment(environment);
            JsonNode evidence = JSON.readTree(Files.readAllBytes(evidenceFile));
            List<Scenario> scenarios = scenarios(evidence);
            List<String> roomIds = scenarios.stream().map(Scenario::roomId).filter(Objects::nonNull).toList();
            Map<String, Object> result;
            try (Connection connection = DriverManager.getConnection(args[4],
                    required(environmentValues, "SPRING_DATASOURCE_USERNAME"), required(environmentValues, "SPRING_DATASOURCE_PASSWORD"))) {
                connection.setReadOnly(true);
                connection.setAutoCommit(true);
                if (!"aisocialgame".equals(connection.getCatalog())) throw failure("UNEXPECTED_DATABASE");
                result = evidence.path("evaluationSchemaVersion").asInt() == 2 ? collectClosure(connection, evidence, scenarios, roomIds) : collect(connection, evidence, scenarios, roomIds);
                if(evidence.path("evaluationSchemaVersion").asInt()==2)result.put("evidenceSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(evidenceFile))));
            }
            // CREATE_NEW also closes the validation/write race; existing reports are never replaced.
            Files.write(outputFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println("METRICS_WRITTEN");
        } catch (SQLException error) {
            String sqlState = error.getSQLState();
            if (sqlState == null || !sqlState.matches("[A-Z0-9]{1,10}")) sqlState = "UNKNOWN";
            System.err.println("METRICS_FAILED DATABASE_ERROR SQLSTATE=" + sqlState + " CODE=" + error.getErrorCode());
            System.exit(1);
        } catch (SafeFailure error) {
            System.err.println("METRICS_FAILED " + error.code); System.exit(1);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); System.err.println("METRICS_FAILED INTERRUPTED"); System.exit(1);
        } catch (Exception error) {
            // Do not print exception messages: JSON, filesystem and driver exceptions can contain private data.
            System.err.println("METRICS_FAILED INPUT_OR_HELPER_ERROR"); System.exit(1);
        }
    }

    static final List<String> CLOSURE_HEADER=List.of("evaluationSchemaVersion","evaluationSetVersion","batchId","sourceFingerprint","buildId","promptVersion","inputFormatVersion","memoryFormatVersion","evidenceKind");
    static Map<String,Object> collectClosure(Connection connection,JsonNode evidence,List<Scenario> scenarios,List<String> rooms)throws SQLException {
        List<String> instances=new ArrayList<>();List<Map<String,Object>> scope=new ArrayList<>();
        for(JsonNode scenario:evidence.path("scenarios")){
            String id=text(scenario.get("archiveId")),room=text(scenario.get("roomId"));
            if(id==null||!UUID_TEXT.matcher(id).matches()||instances.contains(id)||room==null)throw failure("INVALID_INSTANCE_SCOPE");
            instances.add(id);scope.add(map("roomId",room,"archiveId",id));
        }
        scope.sort(Comparator.comparing(m->m.get("roomId").toString()+":"+m.get("archiveId")));
        if(instances.isEmpty())throw failure("EMPTY_INSTANCE_SCOPE");
        List<Map<String,Object>> taskLinks=new ArrayList<>(),traceLinks=new ArrayList<>();List<Job> jobs=new ArrayList<>();List<Trace> traces=new ArrayList<>();Set<String> jobIds=new HashSet<>();
        String sql="SELECT id,room_id,instance_id,actor_id,status,created_at,started_at,diagnostics FROM ai_turn_jobs WHERE instance_id IN ("+placeholders(instances.size())+") LIMIT 100001";
        try(var query=scoped(connection,sql,instances);var rows=query.executeQuery()){
            while(rows.next()){
                if(jobs.size()>=MAX_ROWS)throw failure("JOB_ROW_LIMIT_EXCEEDED");
                String id=rows.getString(1);var d=diagnosticJson(rows.getString(8));jobIds.add(id);
                jobs.add(new Job(id,rows.getString(2),rows.getString(4),rows.getString(5),rows.getObject(6,LocalDateTime.class),rows.getObject(7,LocalDateTime.class),d));
                taskLinks.add(map("id",id,"roomId",rows.getString(2),"instanceId",rows.getString(3),"status",rows.getString(5),"terminalStatus",text(d.get("terminalStatus")),"terminalReason",text(d.get("terminalReason")),"buildId",text(d.get("buildId")),"sourceFingerprint",text(d.get("sourceFingerprint")),"promptVersion",text(d.get("promptVersion"))));
            }
        }
        // Project only non-secret linkage fields. Same-room history is excluded by instance/job identity below.
        sql="SELECT id,room_id,model_key,prompt_tokens,completion_tokens,latency_ms,fallback,"+
            "CASE WHEN JSON_VALID(quality) THEN JSON_OBJECT('instanceId',JSON_EXTRACT(quality,'$.instanceId'),'jobId',JSON_EXTRACT(quality,'$.jobId'),'buildId',JSON_EXTRACT(quality,'$.buildId'),'sourceFingerprint',JSON_EXTRACT(quality,'$.sourceFingerprint'),'promptVersion',JSON_EXTRACT(quality,'$.promptVersion'),'flags',JSON_EXTRACT(quality,'$.flags'),'calls',JSON_EXTRACT(quality,'$.calls')) ELSE NULL END FROM ai_decision_traces WHERE room_id IN ("+placeholders(rooms.size())+") LIMIT 100001";
        long unattributed=0,read=0;
        try(var query=scoped(connection,sql,rooms);var rows=query.executeQuery()){
            while(rows.next()){
                if(++read>MAX_ROWS)throw failure("TRACE_ROW_LIMIT_EXCEEDED");var q=diagnosticJson(rows.getString(8));String instance=text(q.get("instanceId")),job=text(q.get("jobId"));
                if(!instances.contains(instance)&&!jobIds.contains(job)){if(instance==null)unattributed++;continue;}
                Boolean fallback=booleanValue(rows,7);String model=rows.getString(3);
                traces.add(new Trace(rows.getString(2),model,model!=null&&!MODEL_TEXT.matcher(model).matches(),longValue(rows,4),longValue(rows,5),longValue(rows,6),fallback,true,q.path("calls").asText(),qualityFlags(q.path("flags").toString()),version(text(q.get("promptVersion"))),job));
                traceLinks.add(map("id",rows.getString(1),"roomId",rows.getString(2),"instanceId",instance,"jobId",job,"fallback",fallback,"buildId",text(q.get("buildId")),"sourceFingerprint",text(q.get("sourceFingerprint")),"promptVersion",text(q.get("promptVersion"))));
            }
        }
        var snapshot=new Snapshot(Instant.now(),LocalDateTime.now(),jobs);
        Set<String> hostIds=new HashSet<>();jobs.stream().filter(j->"$host".equals(j.actorId)).forEach(j->hostIds.add(j.id));
        var result=map("schemaVersion",3,"algorithmVersion","closure-instance-metrics-v3","instances",scope,"taskLinks",taskLinks,"traceLinks",traceLinks,
            "legacyUnattributedTraceCount",unattributed,"diagnostics",diagnosticSummary(jobs),"jobs",jobSummary(snapshot,snapshot,null,false),"traces",traceSummary(traces),"hostTraces",traceSummary(traces.stream().filter(t->hostIds.contains(t.jobId)).toList()),"byPromptVersion",versionSummary(traces,jobs),"flags",List.of());
        for(String key:CLOSURE_HEADER)result.put(key,JSON.convertValue(evidence.get(key),Object.class));
        return result;
    }

    static Map<String, Object> collect(Connection connection, JsonNode evidence, List<Scenario> scenarios, List<String> roomIds) throws Exception {
        Instant started = Instant.now();
        Snapshot first = jobs(connection, roomIds);
        Snapshot last = first;
        boolean observedTwice = first.jobs.stream().anyMatch(Job::pending);
        if (observedTwice) { Thread.sleep(PENDING_INTERVAL_MS); last = jobs(connection, roomIds); }
        List<Trace> traces = traces(connection, roomIds);
        Long budgetConsumed = budget(connection);
        long liveCeiling=LIVE_CEILING;
        if(evidence.hasNonNull("sourceFingerprint")) {
            long observed=evidence.path("budgetAfter").path("limit").asLong(-1);
            if(observed>0 && RUN_ID.equals(evidence.path("budgetAfter").path("runId").asText()))liveCeiling=observed;
        }
        Set<String> flags = new TreeSet<>();
        if (roomIds.isEmpty()) flags.add("EMPTY_ROOM_SCOPE");
        if (budgetConsumed == null) flags.add("BUDGET_ROW_MISSING");
        else if (budgetConsumed > liveCeiling) flags.add("LIVE_BUDGET_EXCEEDS_CEILING");
        List<Map<String, Object>> roomReports = new ArrayList<>();
        Repetition allRepetition = new Repetition();
        for (Scenario scenario : scenarios) {
            if (scenario.roomId == null) continue;
            List<Trace> roomTraces = traces.stream().filter(t -> t.roomId.equals(scenario.roomId)).toList();
            Map<String, Object> jobReport = jobSummary(first, last, scenario.roomId, observedTwice);
            Set<String> aiActors = new HashSet<>();
            for (Job job : first.jobs) if (job.roomId.equals(scenario.roomId) && job.actorId != null && !job.actorId.equals("$host")) aiActors.add(job.actorId);
            for (Job job : last.jobs) if (job.roomId.equals(scenario.roomId) && job.actorId != null && !job.actorId.equals("$host")) aiActors.add(job.actorId);
            Repetition repetition = repetition(scenario.publicLogs, aiActors);
            allRepetition.add(repetition);
            Map<String, Object> traceReport = traceSummary(roomTraces);
            attachCoverage(traceReport, jobReport);
            roomReports.add(map("roomId", scenario.roomId, "gameId", scenario.gameId, "humanCount", scenario.humanCount,
                    "playerCount", scenario.playerCount, "evidenceStatus", scenario.status, "traces", traceReport,
                    "jobs", jobReport, "diagnostics", diagnosticSummary(last.jobs.stream().filter(j -> j.roomId.equals(scenario.roomId)).toList()), "aiPublicSpeechRepetition", repetition.report(),
                    "evidenceBudgetBefore", evidenceBudget(scenario.budgetBefore), "evidenceBudgetAfter", evidenceBudget(scenario.budgetAfter)));
        }
        Map<String, Object> allJobs = jobSummary(first, last, null, observedTwice);
        Map<String, Object> allTraces = traceSummary(traces);
        Set<String> hostJobs=new HashSet<>();last.jobs.stream().filter(j -> "$host".equals(j.actorId)).forEach(j -> hostJobs.add(j.id));
        Map<String,Object> hostTraces=traceSummary(traces.stream().filter(t -> hostJobs.contains(t.jobId)).toList());
        attachCoverage(allTraces, allJobs);
        if (allRepetition.missingEventIds > 0) flags.add("PUBLIC_LOG_EVENT_IDS_MISSING");
        if (allRepetition.missingActorIds > 0) flags.add("PUBLIC_LOG_ACTOR_IDS_MISSING");
        List<Map<String, Object>> byModel = new ArrayList<>();
        TreeSet<String> modelKeys = new TreeSet<>();
        for (Trace trace : traces) if (trace.modelKey != null) modelKeys.add(trace.modelKey);
        for (String model : modelKeys) byModel.add(map("modelKey", model, "traces", traceSummary(traces.stream().filter(t -> model.equals(t.modelKey)).toList())));
        return map("schemaVersion", 2, "algorithmVersion", "local-realism-metrics-v2", "collectedAt", Instant.now().toString(),
                "collectionStartedAt", started.toString(), "localClockZone", ZoneId.systemDefault().getId(),
                "scope", map("roomCount", roomIds.size(), "scenarioCount", scenarios.size(), "scenariosWithoutRoom", scenarios.size() - roomIds.size(),
                        "database", "aisocialgame", "runtimePlane", "windows-local"),
                "persistentLiveBudget", map("runId", RUN_ID, "ceiling", liveCeiling, "rowPresent", budgetConsumed != null,
                        "consumed", budgetConsumed, "remaining", budgetConsumed == null ? null : Math.max(0L, liveCeiling - budgetConsumed),
                        "evidenceBefore", evidenceBudget(evidence.get("budgetBefore")), "evidenceAfter", evidenceBudget(evidence.get("budgetAfter"))),
                "diagnostics", diagnosticSummary(last.jobs), "byPromptVersion", versionSummary(traces, last.jobs), "traces", allTraces, "hostTraces",hostTraces,"actualModelKeys", modelKeys, "byModel", byModel, "jobs", allJobs,
                "aiPublicSpeechRepetition", allRepetition.report(), "rooms", roomReports, "flags", flags,
                "definitions", definitions());
    }

    static List<Trace> traces(Connection connection, List<String> roomIds) throws Exception {
        if (roomIds.isEmpty()) return List.of();
        String sql = "SELECT room_id,model_key,prompt_tokens,completion_tokens,latency_ms,fallback,JSON_VALID(quality),"
                + "CASE WHEN JSON_VALID(quality) THEN JSON_UNQUOTE(JSON_EXTRACT(quality,'$.calls')) ELSE NULL END,"
                + "CASE WHEN JSON_VALID(quality) THEN JSON_EXTRACT(quality,'$.flags') ELSE NULL END, "
                + "CASE WHEN JSON_VALID(quality) THEN JSON_UNQUOTE(JSON_EXTRACT(quality,'$.promptVersion')) ELSE NULL END, "
                + "CASE WHEN JSON_VALID(quality) THEN JSON_UNQUOTE(JSON_EXTRACT(quality,'$.jobId')) ELSE NULL END "
                + "FROM ai_decision_traces WHERE room_id IN (" + placeholders(roomIds.size()) + ") LIMIT 100001";
        List<Trace> result = new ArrayList<>();
        try (PreparedStatement query = scoped(connection, sql, roomIds); ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                if (result.size() >= MAX_ROWS) throw failure("TRACE_ROW_LIMIT_EXCEEDED");
                String model = rows.getString(2);
                boolean invalidModel = model != null && !model.isBlank() && !MODEL_TEXT.matcher(model).matches();
                if (model == null || model.isBlank() || invalidModel) model = null;
                result.add(new Trace(rows.getString(1), model, invalidModel, longValue(rows, 3), longValue(rows, 4), longValue(rows, 5),
                        booleanValue(rows, 6), Boolean.TRUE.equals(booleanValue(rows, 7)), rows.getString(8), qualityFlags(rows.getString(9)), version(rows.getString(10)), rows.getString(11)));
            }
        }
        return result;
    }

    static Snapshot jobs(Connection connection, List<String> roomIds) throws SQLException {
        if (roomIds.isEmpty()) return new Snapshot(Instant.now(), LocalDateTime.now(), List.of());
        List<Job> result = new ArrayList<>();
        boolean hasDiagnostics;
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "ai_turn_jobs", "diagnostics")) { hasDiagnostics = columns.next(); }
        String sql = "SELECT id,room_id,actor_id,status,created_at,started_at," + (hasDiagnostics ? "diagnostics" : "NULL") + " FROM ai_turn_jobs WHERE room_id IN (" + placeholders(roomIds.size()) + ") LIMIT 100001";
        try (PreparedStatement query = scoped(connection, sql, roomIds); ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                if (result.size() >= MAX_ROWS) throw failure("JOB_ROW_LIMIT_EXCEEDED");
                String status = rows.getString(4);
                if (status == null || !JOB_STATES.contains(status)) status = "UNKNOWN_STATUS";
                result.add(new Job(rows.getString(1), rows.getString(2), rows.getString(3), status,
                        rows.getObject(5, LocalDateTime.class), rows.getObject(6, LocalDateTime.class), diagnosticJson(rows.getString(7))));
            }
        }
        return new Snapshot(Instant.now(), LocalDateTime.now(), result);
    }

    static Long budget(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT consumed FROM ai_call_budgets WHERE id=?")) {
            query.setQueryTimeout(30); query.setString(1, RUN_ID);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                Long value = longValue(rows, 1);
                if (value == null || value < 0 || rows.next()) throw failure("INVALID_BUDGET_ROW");
                return value;
            }
        }
    }

    static Map<String, Object> traceSummary(List<Trace> traces) {
        long calls = 0, missingCalls = 0, invalidCalls = 0, prompt = 0, completion = 0, fallback = 0, fallbackKnown = 0;
        long invalidQuality = 0, zeroUsage = 0, missingUsage = 0, invalidUsage = 0, missingLatency = 0, invalidLatency = 0, missingModel = 0, invalidModel = 0, unknownFlags = 0, malformedFlags = 0;
        List<Long> latencies = new ArrayList<>();
        Map<String, Long> flagCounts = new TreeMap<>(), callHistogram = new LinkedHashMap<>();
        for (int i = 0; i <= 2; i++) callHistogram.put(Integer.toString(i), 0L);
        for (Trace trace : traces) {
            if (!trace.validQuality) invalidQuality++;
            if (trace.calls == null || trace.calls.equals("null")) missingCalls++;
            else if (!trace.calls.matches("[012]")) invalidCalls++;
            else { calls += Integer.parseInt(trace.calls); callHistogram.merge(trace.calls, 1L, Long::sum); }
            if (trace.promptTokens == null || trace.completionTokens == null) missingUsage++;
            else if (trace.promptTokens < 0 || trace.completionTokens < 0) invalidUsage++;
            else {
                prompt = Math.addExact(prompt, trace.promptTokens); completion = Math.addExact(completion, trace.completionTokens);
                if (trace.promptTokens == 0 && trace.completionTokens == 0) zeroUsage++;
            }
            if (trace.latencyMs == null) missingLatency++;
            else if (trace.latencyMs < 0) invalidLatency++;
            else latencies.add(trace.latencyMs);
            if (trace.fallback != null) { fallbackKnown++; if (trace.fallback) fallback++; }
            if (trace.modelKey == null) missingModel++;
            if (trace.invalidModel) invalidModel++;
            for (String flag : trace.flags.known) flagCounts.merge(flag, 1L, Long::sum);
            unknownFlags += trace.flags.unknown; if (trace.flags.malformed) malformedFlags++;
        }
        latencies.sort(Long::compareTo);
        Set<String> flags = new TreeSet<>();
        if (missingCalls + invalidCalls > 0) flags.add("TRACE_CALL_COUNTS_INCOMPLETE");
        if (missingUsage + invalidUsage + zeroUsage > 0) flags.add("TOKEN_USAGE_MAY_BE_INCOMPLETE");
        if (missingLatency + invalidLatency > 0) flags.add("TRACE_LATENCIES_INCOMPLETE");
        if (missingModel > 0) flags.add("MODEL_KEYS_INCOMPLETE");
        if (unknownFlags + malformedFlags > 0) flags.add("QUALITY_FLAGS_NOT_FULLY_CLASSIFIED");
        return map("count", traces.size(), "generationAttemptsObserved", calls, "callsHistogram", callHistogram,
                "missingCallCountTraces", missingCalls, "invalidCallCountTraces", invalidCalls,
                "fallbackTraceCount", fallback, "fallbackKnownTraceCount", fallbackKnown, "fallbackRate", ratio(fallback, fallbackKnown),
                "promptTokensObserved", prompt, "completionTokensObserved", completion, "totalTokensObserved", Math.addExact(prompt, completion),
                "zeroUsageTraceCount", zeroUsage, "missingUsageTraceCount", missingUsage, "invalidUsageTraceCount", invalidUsage,
                "latencySampleCount", latencies.size(), "meanGenerationLatencyMs", latencies.isEmpty() ? null : latencies.stream().mapToLong(Long::longValue).average().orElseThrow(),
                "p95GenerationLatencyMs", latencies.isEmpty() ? null : latencies.get((int)Math.ceil(latencies.size() * .95) - 1),
                "missingLatencyTraceCount", missingLatency, "invalidLatencyTraceCount", invalidLatency,
                "missingModelKeyTraceCount", missingModel, "invalidModelKeyTraceCount", invalidModel, "invalidQualityTraceCount", invalidQuality,
                "qualityFlagCounts", flagCounts, "unclassifiedQualityFlagCount", unknownFlags, "malformedQualityFlagsTraceCount", malformedFlags, "flags", flags);
    }

    static Map<String, Object> jobSummary(Snapshot first, Snapshot last, String roomId, boolean observedTwice) {
        Map<String, Long> statuses = new LinkedHashMap<>(); JOB_STATES.forEach(s -> statuses.put(s, 0L));
        Map<String, Job> initial = new HashMap<>();
        for (Job job : first.jobs) if (roomId == null || roomId.equals(job.roomId)) initial.put(job.id, job);
        long persistentQueued = 0, persistentRunning = 0, continuouslyPending = 0, runningOverdue = 0, invalidClock = 0, missingTime = 0, unverifiedClock = 0;
        Long oldestQueued = null, oldestRunning = null;
        for (Job job : last.jobs) {
            if (roomId != null && !roomId.equals(job.roomId)) continue;
            statuses.merge(job.status, 1L, Long::sum);
            if (observedTwice && job.pending() && initial.containsKey(job.id) && initial.get(job.id).pending()) {
                continuouslyPending++;
                if (job.status.equals(initial.get(job.id).status)) {
                    if (job.status.equals("QUEUED")) persistentQueued++;
                    if (job.status.equals("RUNNING")) persistentRunning++;
                }
            }
            if (!job.pending()) continue;
            JsonNode timestamp = job.diagnostics.path(job.status.equals("RUNNING") ? "startedEpochMs" : "queuedEpochMs");
            if (!timestamp.isIntegralNumber() || !timestamp.canConvertToLong()) { unverifiedClock++; if (job.startedAt == null && job.createdAt == null) missingTime++; continue; }
            long age = last.instant.toEpochMilli() - timestamp.longValue();
            if (age < 0) { invalidClock++; continue; }
            if (job.status.equals("RUNNING")) {
                oldestRunning = oldestRunning == null ? age : Math.max(oldestRunning, age);
                if (age > 60_000) runningOverdue++;
            } else oldestQueued = oldestQueued == null ? age : Math.max(oldestQueued, age);
        }
        return map("count", statuses.values().stream().mapToLong(Long::longValue).sum(), "statusCounts", statuses,
                "firstSnapshotAt", first.instant.toString(), "lastSnapshotAt", last.instant.toString(), "observedTwice", observedTwice,
                "observationIntervalMs", Duration.between(first.instant, last.instant).toMillis(),
                "continuouslyPendingCount", observedTwice ? continuouslyPending : null,
                "persistentQueuedCount", observedTwice ? persistentQueued : null, "persistentRunningCount", observedTwice ? persistentRunning : null,
                "runningOlderThan60SecondsCount", runningOverdue, "oldestQueuedAgeMs", oldestQueued, "oldestRunningAgeMs", oldestRunning,
                "pendingUnverifiedClockCount", unverifiedClock, "pendingMissingTimestampCount", missingTime, "pendingFutureTimestampCount", invalidClock);
    }

    static JsonNode diagnosticJson(String raw) {
        if (raw == null || raw.length() > 100_000) return JSON.createObjectNode();
        try { JsonNode n = JSON.readTree(raw); return n != null && n.isObject() ? n : JSON.createObjectNode(); }
        catch (Exception ignored) { return JSON.createObjectNode(); }
    }
    static String version(String value) { return value != null && value.matches("social-v[0-9]+\\.[0-9]+") ? value : "LEGACY_UNKNOWN"; }
    static String jobVersion(Job job) { return version(job.diagnostics.path("generation").path("promptVersion").asText(job.diagnostics.path("promptVersion").asText())); }
    static List<Map<String, Object>> versionSummary(List<Trace> traces, List<Job> jobs) {
        Set<String> versions = new TreeSet<>(); traces.forEach(t -> versions.add(t.promptVersion)); jobs.forEach(j -> versions.add(jobVersion(j)));
        List<Map<String, Object>> result = new ArrayList<>();
        for (String v : versions) {
            List<Trace> ts = traces.stream().filter(t -> v.equals(t.promptVersion)).toList();
            List<Job> js = jobs.stream().filter(j -> v.equals(jobVersion(j))).toList();
            Set<String> succeeded = new HashSet<>(); js.stream().filter(j -> "SUCCEEDED".equals(j.status)).forEach(j -> succeeded.add(j.id));
            long covered = succeeded.stream().filter(id -> ts.stream().anyMatch(t -> id.equals(t.jobId))).count();
            result.add(map("promptVersion", v, "traces", traceSummary(ts), "tasks", diagnosticSummary(js),
                    "succeededJobs", succeeded.size(), "succeededJobsWithTrace", covered, "identityTraceCoverage", ratio(covered, succeeded.size())));
        }
        return result;
    }
    static Map<String, Object> diagnosticSummary(List<Job> jobs) {
        Set<String> reasons = Set.of("CONTENT_REVIEW_FAILED", "ADMIN_CONTROL", "MODEL_ACTION_APPLIED", "FALLBACK_APPLIED", "INSTANCE_UNAVAILABLE", "TURN_OBSOLETE", "TURN_EXPIRED", "RECOVERY_TIMEOUT", "DISPATCH_REJECTED", "NO_ACTION_BUDGET", "SUBMISSION_EXCEPTION", "EXECUTION_EXCEPTION", "INVALID_FALLBACK");
        Map<String, Long> terminalReasons = new TreeMap<>(), generationFailures = new TreeMap<>();
        Map<String, List<Long>> samples = new TreeMap<>();
        long terminal = 0, covered = 0, generated = 0, fallback = 0, incompleteUsage = 0;
        for (Job job : jobs) {
            JsonNode d = job.diagnostics, g = d.path("generation");
            if (!job.pending() && !"UNKNOWN_STATUS".equals(job.status)) {
                terminal++;
                String reason = d.path("terminalReason").asText();
                if (reasons.contains(reason) && job.status.equals(d.path("terminalStatus").asText())) covered++;
                terminalReasons.merge(reasons.contains(reason) ? reason : "UNKNOWN", 1L, Long::sum);
            }
            if (g.isObject()) {
                generated++;
                if (!g.path("usageComplete").asBoolean(false)) incompleteUsage++;
                for (JsonNode flag : g.path("qualityFlags")) {
                    String name = flag.asText(); generationFailures.merge(QUALITY_FLAGS.contains(name) ? name : "UNKNOWN", 1L, Long::sum);
                }
            }
            if (d.path("fallback").asBoolean(false)) fallback++;
            for (String key : List.of("observationMs", "queueMs", "submissionProcessingMs", "elapsedBeforeCommitMs")) measurement(samples, key, d.path(key));
            for (String key : List.of("latencyMs", "fallbackMs", "initialBudgetMs", "repairRemainingMs", "submissionFailedMs")) measurement(samples, key, g.path(key));
            for (JsonNode attempt : g.path("attempts")) {
                for (String key : List.of("inputPreparationMs", "rpcMs", "parseMs", "validationMs", "inputBytes", "outputBytes", "requestTimeoutMs", "requestRemainingMs"))
                    measurement(samples, key, attempt.path(key));
            }
            JsonNode sections = g.path("inputSectionBytes");
            for (String key : List.of("instruction", "observation", "informationSources", "continuity", "outputExamples")) measurement(samples, "inputSectionBytes." + key, sections.path(key));
        }
        Map<String, Object> distributions = new TreeMap<>();
        for (String key : List.of("observationMs", "queueMs", "submissionProcessingMs", "elapsedBeforeCommitMs", "latencyMs", "fallbackMs", "inputPreparationMs", "rpcMs", "parseMs", "validationMs", "inputBytes", "outputBytes")) samples.computeIfAbsent(key, k -> new ArrayList<>());
        samples.forEach((key, values) -> { values.sort(Long::compare); distributions.put(key, map("sampleCount", values.size(),
                "mean", values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).average().orElseThrow(),
                "p95", values.isEmpty() ? null : values.get((int)Math.ceil(values.size() * .95) - 1))); });
        return map("taskCount", jobs.size(), "terminalCount", terminal, "terminalWithDiagnostics", covered,
                "terminalDiagnosticCoverage", ratio(covered, terminal), "terminalReasons", terminalReasons,
                "generationSamples", generated, "generationFailures", generationFailures, "incompleteUsageSamples", incompleteUsage,
                "observedFallbackCount", fallback, "distributions", distributions);
    }
    static void measurement(Map<String, List<Long>> samples, String key, JsonNode value) {
        if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0)
            samples.computeIfAbsent(key, k -> new ArrayList<>()).add(value.longValue());
    }

    @SuppressWarnings("unchecked")
    static void attachCoverage(Map<String, Object> traces, Map<String, Object> jobs) {
        long succeeded = ((Map<String, Long>)jobs.get("statusCounts")).get("SUCCEEDED");
        long count = ((Number)traces.get("count")).longValue();
        traces.put("traceToSucceededJobRatio", ratio(count, succeeded));
        if (count != succeeded) ((Set<String>)traces.get("flags")).add("TRACE_JOB_COUNTS_DIFFER");
    }

    static Repetition repetition(JsonNode publicLogs, Set<String> aiActors) {
        Repetition result = new Repetition();
        Set<String> eventIds = new HashSet<>();
        Map<String, Set<String>> speechByActor = new HashMap<>();
        if (publicLogs == null || !publicLogs.isArray()) return result;
        for (JsonNode entry : publicLogs) {
            if (!SPEECH_TYPES.contains(entry.path("type").asText())) continue;
            String actor = text(entry.get("actorId"));
            if (actor == null || actor.isBlank()) { result.missingActorIds++; continue; }
            if (!aiActors.contains(actor)) continue;
            String eventId = text(entry.path("metadata").get("eventId"));
            if (eventId == null || eventId.isBlank()) result.missingEventIds++;
            else if (!eventIds.add(eventId)) { result.repeatedEventRecords++; continue; }
            result.aiPublicEvents++;
            String message = text(entry.get("message"));
            if (message == null) { result.missingMessages++; continue; }
            String normalized = normalizeSpeech(message);
            if (normalized.codePointCount(0, normalized.length()) < 10) { result.shortMessages++; continue; }
            result.eligible++;
            if (!speechByActor.computeIfAbsent(actor, unused -> new HashSet<>()).add(normalized)) result.duplicates++;
        }
        return result;
    }

    static String normalizeSpeech(String message) {
        String text = Normalizer.normalize(message, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
        int colon = text.indexOf(':');
        // Public logs prepend the display name (or "display name 问主持"). Do not strip long clauses.
        if (colon >= 0 && text.codePointCount(0, colon) <= 40) text = text.substring(colon + 1);
        return text.replaceAll("[\\p{P}\\p{Z}\\p{Cf}\\s]", "");
    }

    static FlagSummary qualityFlags(String value) {
        if (value == null || value.equals("null")) return new FlagSummary(List.of(), 0, false);
        try {
            JsonNode array = JSON.readTree(value);
            if (!array.isArray() || array.size() > 256) return new FlagSummary(List.of(), 0, true);
            List<String> known = new ArrayList<>(); int unknown = 0;
            for (JsonNode flag : array) {
                if (flag.isTextual() && QUALITY_FLAGS.contains(flag.textValue())) known.add(flag.textValue()); else unknown++;
            }
            return new FlagSummary(known, unknown, false);
        } catch (Exception ignored) { return new FlagSummary(List.of(), 0, true); }
    }

    static List<Scenario> scenarios(JsonNode evidence) {
        if (evidence == null || !evidence.isObject() || !evidence.path("schemaVersion").isIntegralNumber() || !evidence.path("schemaVersion").canConvertToInt()
                || evidence.path("schemaVersion").intValue() != 1 || !evidence.path("scenarios").isArray()) throw failure("INVALID_EVIDENCE_SCHEMA");
        JsonNode entries = evidence.get("scenarios");
        if (entries.size() > MAX_ROOMS) throw failure("ROOM_SCOPE_LIMIT_EXCEEDED");
        List<Scenario> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (JsonNode entry : entries) {
            String gameId = text(entry.get("gameId")), roomId = text(entry.get("roomId")), status = text(entry.get("status"));
            if (!entry.isObject() || !GAME_IDS.contains(gameId == null ? "" : gameId)) throw failure("INVALID_GAME_ID");
            if (entry.hasNonNull("roomId") && (roomId == null || !UUID_TEXT.matcher(roomId).matches())) throw failure("INVALID_ROOM_ID");
            if (roomId != null) { roomId = UUID.fromString(roomId).toString(); if (!ids.add(roomId)) throw failure("DUPLICATE_ROOM_ID"); }
            if (!Set.of("pending", "running", "passed", "failed", "not_run").contains(status == null ? "" : status)) throw failure("INVALID_SCENARIO_STATUS");
            Integer humans = boundedInteger(entry.get("humanCount"), 1, 12), players = boundedInteger(entry.get("playerCount"), 1, 12);
            if (humans == null || players == null || humans > players) throw failure("INVALID_PLAYER_COUNTS");
            JsonNode logs = entry.get("publicLogs");
            if (logs != null && (!logs.isArray() || logs.size() > MAX_ROWS)) throw failure("INVALID_PUBLIC_LOG_LIST");
            result.add(new Scenario(roomId, gameId, status, humans, players, logs, entry.get("budgetBefore"), entry.get("budgetAfter")));
        }
        return result;
    }

    static Object evidenceBudget(JsonNode budget) {
        if (budget == null || !budget.isObject()) return null;
        Integer limit = boundedInteger(budget.get("limit"), 0, Integer.MAX_VALUE), consumed = boundedInteger(budget.get("consumed"), 0, Integer.MAX_VALUE);
        if (!RUN_ID.equals(text(budget.get("runId"))) || limit == null || consumed == null || !budget.path("enabled").isBoolean())
            return map("validForFixedRun", false);
        return map("validForFixedRun", true, "enabled", budget.get("enabled").booleanValue(), "limit", limit, "consumed", consumed);
    }

    static Map<String, Object> definitions() {
        return map("generationAttemptsObserved", "Sum of valid quality.calls (0..2) on persisted room traces. Budget rejection can count as a generator attempt; failed/discarded jobs may have no trace. This is not charged RPC usage.",
                "persistentLiveBudget", "The fixed run's durable consumed value includes all intercepted chat attempts, including AI seat naming and any other rooms using that run. It is not scoped to the evidence rooms. Legacy ceiling is 210. Frozen closure evidence reports the observed API ceiling; this report does not authorize a budget change. Comparison allowance is separate.",
                "tokens", "Sum of nonnegative prompt/completion counts on persisted traces. Counts cover observed model responses only. Zero or missing usage does not prove zero billable tokens; failed/discarded/unpersisted decisions may be absent.",
                "latency", "Whole generation latency (including repair/validation), not per-RPC latency. Mean and nearest-rank p95 use nonnegative persisted trace samples. Empty denominators are null.",
                "fallbackRate", "Fallback traces divided by traces with a known fallback boolean. SUCCEEDED jobs can contain fallback decisions; validDecision is deliberately not a model quality score.",
                "traceToSucceededJobRatio", "Persisted trace count / SUCCEEDED job count. social-v2.2 writes traces and successful jobs in the same transaction; historical versions may differ. Reads are sequential, so a mismatch is diagnostic and does not alone prove loss. This count ratio is not per-job identity coverage.",
                "pending", "If any scoped job was QUEUED/RUNNING initially, query again after at least 65 seconds. Continuously pending means the same job ID was pending in both snapshots; persistentQueued/Running additionally require the same status. This does not prove a hung job. QUEUED has no TTL; RUNNING over 60 seconds is a recovery-age indicator.",
                "clock", "Pending ages use explicit UTC epoch diagnostics only. Legacy LocalDateTime ages are unverified and omitted, never shifted by eight hours. Future timestamps are anomalies; epoch time still depends on clock synchronization.",
                "repetition", "Only whitelisted public speech events whose actor has a scoped AI job (excluding $host). Includes automated takeovers. Deduplicate eventId when present, then NFKC/lowercase, remove a leading <=40-code-point colon prefix, punctuation/spacing/format controls. Ignore normalized text shorter than 10 code points. Rate is repeated normalized statements beyond the first / eligible statements within each room and actor; this measures exact repetition, not semantic similarity. No text, actor IDs or hashes are exported.",
                "privacy", "SELECT only scoped trace aggregates, minimal job identifiers/timestamps and the fixed budget row. Never select trace prompts, rawOutput, input/output summaries, memory, roles or hidden words. Report only numeric aggregates, validated model/room/game IDs and fixed enum flags.");
    }

    static Path inputPath(String value, Path repository, long maximumBytes, boolean requireExternal) throws Exception {
        Path supplied = Path.of(value);
        if (!supplied.isAbsolute()) throw failure("PATH_MUST_BE_ABSOLUTE");
        Path path = supplied.toRealPath();
        if ((requireExternal && path.startsWith(repository)) || !Files.isRegularFile(path) || Files.size(path) > maximumBytes) throw failure("INVALID_INPUT_PATH");
        return path;
    }
    static Path outputPath(String value, Path repository) throws Exception {
        Path supplied = Path.of(value);
        if (!supplied.isAbsolute() || supplied.getFileName() == null) throw failure("PATH_MUST_BE_ABSOLUTE");
        Path normalized = supplied.normalize(), parent = normalized.getParent();
        if (parent == null) throw failure("INVALID_OUTPUT_PATH");
        Path path = parent.toRealPath().resolve(normalized.getFileName());
        if (path.startsWith(repository) || Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw failure("OUTPUT_MUST_BE_NEW_AND_EXTERNAL");
        return path;
    }
    static Map<String, String> readEnvironment(Path path) throws Exception {
        Map<String, String> values = new HashMap<>();
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int split = line.indexOf('='); if (split < 1) throw failure("INVALID_ENVIRONMENT_FILE");
            String key = line.substring(0, split).strip().replaceFirst("^export\\s+", ""), value = line.substring(split + 1);
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) throw failure("INVALID_ENVIRONMENT_FILE");
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
            if (values.putIfAbsent(key, value) != null) throw failure("DUPLICATE_ENVIRONMENT_KEY");
        }
        for (String key : List.of("ENV", "APP_ENV", "SPRING_PROFILES_ACTIVE"))
            if (values.containsKey(key) && !"local".equals(values.get(key))) throw failure("NONLOCAL_ENVIRONMENT");
        if (values.containsKey("AIENIE_RUNTIME_PLANE") && !"windows-local".equals(values.get("AIENIE_RUNTIME_PLANE"))) throw failure("NONLOCAL_ENVIRONMENT");
        return values;
    }
    static String required(Map<String, String> values, String key) {
        String value = values.get(key); if (value == null || value.isBlank()) throw failure("MISSING_DATABASE_CONFIGURATION"); return value;
    }
    static String placeholders(int size) { if (size < 1 || size > MAX_ROOMS) throw failure("INVALID_ROOM_SCOPE"); return String.join(",", Collections.nCopies(size, "?")); }
    static PreparedStatement scoped(Connection connection, String sql, List<String> ids) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql); statement.setQueryTimeout(30);
        for (int i = 0; i < ids.size(); i++) statement.setString(i + 1, ids.get(i)); return statement;
    }
    static Long longValue(ResultSet rows, int index) throws SQLException { long value = rows.getLong(index); return rows.wasNull() ? null : value; }
    static Boolean booleanValue(ResultSet rows, int index) throws SQLException { boolean value = rows.getBoolean(index); return rows.wasNull() ? null : value; }
    static String text(JsonNode node) { return node != null && node.isTextual() ? node.textValue() : null; }
    static Integer boundedInteger(JsonNode node, int min, int max) { return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= min && node.intValue() <= max ? node.intValue() : null; }
    static Double ratio(long numerator, long denominator) { return denominator == 0 ? null : (double)numerator / denominator; }
    static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < entries.length; i += 2) result.put((String)entries[i], entries[i + 1]); return result;
    }
    static SafeFailure failure(String code) { return new SafeFailure(code); }
    static class SafeFailure extends RuntimeException { final String code; SafeFailure(String code) { this.code = code; } }
    record Scenario(String roomId, String gameId, String status, int humanCount, int playerCount, JsonNode publicLogs, JsonNode budgetBefore, JsonNode budgetAfter) {}
    record FlagSummary(List<String> known, long unknown, boolean malformed) {}
    record Trace(String roomId, String modelKey, boolean invalidModel, Long promptTokens, Long completionTokens, Long latencyMs, Boolean fallback, boolean validQuality, String calls, FlagSummary flags, String promptVersion, String jobId) {
        Trace(String roomId, String modelKey, boolean invalidModel, Long promptTokens, Long completionTokens, Long latencyMs, Boolean fallback, boolean validQuality, String calls, FlagSummary flags) {
            this(roomId, modelKey, invalidModel, promptTokens, completionTokens, latencyMs, fallback, validQuality, calls, flags, "LEGACY_UNKNOWN", null);
        }
    }
    record Job(String id, String roomId, String actorId, String status, LocalDateTime createdAt, LocalDateTime startedAt, JsonNode diagnostics) {
        Job(String id, String roomId, String actorId, String status, LocalDateTime createdAt, LocalDateTime startedAt) { this(id, roomId, actorId, status, createdAt, startedAt, JSON.createObjectNode()); }
        boolean pending() { return status.equals("QUEUED") || status.equals("RUNNING"); }
    }
    record Snapshot(Instant instant, LocalDateTime localTime, List<Job> jobs) {}
    static class Repetition {
        long aiPublicEvents, eligible, duplicates, shortMessages, missingMessages, missingEventIds, missingActorIds, repeatedEventRecords;
        void add(Repetition other) {
            aiPublicEvents += other.aiPublicEvents; eligible += other.eligible; duplicates += other.duplicates; shortMessages += other.shortMessages;
            missingMessages += other.missingMessages; missingEventIds += other.missingEventIds; missingActorIds += other.missingActorIds; repeatedEventRecords += other.repeatedEventRecords;
        }
        Map<String, Object> report() { return map("aiPublicEventCount", aiPublicEvents, "eligibleStatementCount", eligible, "duplicateExcessCount", duplicates,
                "exactRepetitionRate", ratio(duplicates, eligible), "shortStatementCount", shortMessages, "missingMessageCount", missingMessages,
                "missingEventIdCount", missingEventIds, "speechEventsMissingActorIdCount", missingActorIds, "repeatedEventRecordsRemoved", repeatedEventRecords); }
    }
}
