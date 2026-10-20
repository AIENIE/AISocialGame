package com.aisocialgame;

import com.aisocialgame.adminauth.AdminAuthPolicy;
import com.aisocialgame.dto.LocalAiDebugView;
import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.dto.AiChatMessageDto;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.RoomAccessPolicy;
import com.aisocialgame.service.ai.config.AiModelSelection;
import com.aisocialgame.service.ai.v2.*;
import com.aisocialgame.service.debug.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import fireflychat.ai.v1.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:localdebug;DB_CLOSE_DELAY=-1;MODE=MySQL", "app.ai.budget-reconcile-delay-ms=600000"})
@ActiveProfiles("test")
class LocalAiDebugTest {
    @Autowired LocalAiDebugRecorder recorder;
    @Autowired LocalAiDebugTurnRepository records;
    @Autowired AiTurnJobRepository jobs;
    @Autowired PlatformTransactionManager manager;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final RoomAccessPolicy access = mock(RoomAccessPolicy.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final AiDecisionTraceRepository traces = mock(AiDecisionTraceRepository.class);
    private String roomId, instance;
    private LocalAiDebugService service;

    @BeforeEach void setup() {
        roomId = UUID.randomUUID().toString(); instance = UUID.randomUUID().toString();
        Room room = new Room(); room.setId(roomId); room.setGameId("undercover");
        when(rooms.findById(roomId)).thenReturn(Optional.of(room));
        service = new LocalAiDebugService(recorder, access, rooms, jobs, records, traces);
    }

    private VisibleObservation observation(String actor, int round) {
        return new VisibleObservation("undercover", instance, "DESCRIPTION", round, actor, "SPEAK",
                Map.of("playerId", actor, "displayName", "角色 " + actor, "seatNumber", 1, "role", "SPY"), List.of(), Map.of(),
                List.of(), Map.of("word", "私有词"), List.of(), Map.of("id", "persona"), Map.of(), List.of());
    }
    private AiTurnJob job(String actor, int round) {
        var job = new AiTurnJob(); job.setId(UUID.randomUUID().toString()); job.setRoomId(roomId); job.setInstanceId(instance);
        job.setActorId(actor); job.setKind("SPEAK"); job.setTurnKey(job.getId());
        job.setObservation(RuleSupport.map(json.convertValue(observation(actor, round), Map.class)));
        job.setDiagnostics(new LinkedHashMap<>()); jobs.saveAndFlush(job); recorder.queued(job); return job;
    }
    private void begin(AiTurnJob job, int index) {
        recorder.begin(roomId, observation(job.getActorId(), 1), job.getId(), index,
                List.of(new AiChatMessageDto("system", "complete system"), new AiChatMessageDto("user", "{\"privateWord\":\"私有词\"}")),
                AiModelSelection.defaults("test-model"), 45000);
    }
    private ChatCompletionsResponse response(String content) {
        return ChatCompletionsResponse.newBuilder().setContent(content).setModelKey("actual-model")
                .setPromptTokens(100).setCompletionTokens(20).setReasoningTokens(12).setUsageAuthoritative(true)
                .setDebugInfo(ChatDebugInfo.newBuilder().setReasoningKind(ChatDebugInfo.ReasoningKind.TEXT).setReasoningText("provider thought").setCacheReadTokens(40)).build();
    }

    @Test void retainsEachAttemptAndLateReplaysWithoutDoubleCountingOrChangingTheGame() {
        var job = job("ai", 1); begin(job, 0);
        recorder.response(job.getId() + ":0", response("not valid JSON"));
        recorder.evaluated(job.getId(), 0, Map.of("flags", List.of("INVALID_JSON_ENVELOPE"), "rpcMs", 12L), "PARSE");
        begin(job, 1);
        recorder.evaluated(job.getId(), 1, Map.of("flags", List.of("AI_BUDGET_BUSY")), "RPC");
        begin(job, 1);
        var finalResponse = response("{\"action\":{\"type\":\"SPEAK\",\"content\":\"final\"}}");
        recorder.response(job.getId() + ":1", finalResponse); recorder.parsed(job.getId(), 1, Map.of("action", Map.of("content", "final")));
        job.setStatus("DISCARDED"); job.getDiagnostics().put("terminalReason", "TURN_OBSOLETE"); jobs.saveAndFlush(job); recorder.finished(job);
        recorder.response(job.getId() + ":1", finalResponse); recorder.response(job.getId() + ":1", finalResponse);
        var row = records.findById(job.getId()).orElseThrow();
        assertEquals(2, row.attemptCount); assertEquals(2, row.responseCount);
        assertEquals(200L, row.promptTokens); assertEquals(40L, row.completionTokens); assertEquals(80L, row.cacheReadTokens);
        assertEquals(24L, row.reasoningTokens); assertNull(row.cacheWriteTokens); assertTrue(row.usageComplete);
        assertEquals("DISCARDED", jobs.findById(job.getId()).orElseThrow().getStatus());
        var detail = service.detail("undercover", roomId, "viewer", job.getId());
        var attempts = RuleSupport.map(detail.data().get("attempts"));
        assertEquals("not valid JSON", RuleSupport.map(attempts.get("0")).get("rawOutput"));
        assertEquals("provider thought", RuleSupport.map(attempts.get("1")).get("reasoningText"));
        assertEquals("complete system", RuleSupport.maps(RuleSupport.map(attempts.get("1")).get("messages")).getFirst().get("content"));
        assertEquals(200L, service.summary("undercover", roomId, "viewer", instance).usage().promptTokens());
        assertEquals(1, service.instances("undercover", roomId, "viewer", 0, 20).getTotal());
    }

    @Test void zeroUsageIsPreservedWhileUnknownAndIncompleteFieldsStayUnknown() {
        var job = job("ai", 1); begin(job, 0);
        recorder.response(job.getId() + ":0", response("").toBuilder().setPromptTokens(0).setCompletionTokens(0).setReasoningTokens(0)
                .setDebugInfo(ChatDebugInfo.newBuilder().setReasoningKind(ChatDebugInfo.ReasoningKind.NOT_PROVIDED).setCacheReadTokens(0)).build());
        var row = records.findById(job.getId()).orElseThrow(); assertEquals(0L, row.promptTokens); assertEquals(0L, row.cacheReadTokens);
        assertNull(row.cacheWriteTokens); assertTrue(row.usageAuthoritative);
        begin(job, 1); recorder.evaluated(job.getId(), 1, Map.of("flags", List.of("RPC_TIMEOUT")), "RPC");
        row = records.findById(job.getId()).orElseThrow(); assertEquals(0L, row.promptTokens); assertFalse(row.usageComplete); assertNull(row.cacheReadTokens);
        assertNull(row.usageAuthoritative);
    }

    @Test void omittedLegacyZeroIsUnknownUntilBudgetStatusProvidesAuthoritativeProof() {
        var job = job("ai", 1); begin(job, 0);
        var legacy = ChatCompletionsResponse.newBuilder().setContent("historical response").build();
        recorder.response(job.getId() + ":0", legacy);
        var row = records.findById(job.getId()).orElseThrow();
        assertNull(row.promptTokens); assertNull(row.completionTokens); assertNull(row.usageAuthoritative); assertFalse(row.usageComplete);
        assertEquals(1, row.responseCount);
        assertEquals("historical response", RuleSupport.map(RuleSupport.map(row.payload.get("attempts")).get("0")).get("rawOutput"));
        recorder.response(job.getId() + ":0", legacy, true);
        recorder.response(job.getId() + ":0", legacy);
        row = records.findById(job.getId()).orElseThrow();
        assertEquals(0L, row.promptTokens); assertEquals(0L, row.completionTokens); assertTrue(row.usageAuthoritative);
        assertTrue(row.usageComplete); assertNull(row.cacheReadTokens); assertEquals(1, row.responseCount);
        var knownCache = job("other-ai", 1); begin(knownCache, 0);
        recorder.response(knownCache.getId() + ":0", legacy.toBuilder().setPromptTokens(20).setCacheTokens(5).build());
        var known = records.findById(knownCache.getId()).orElseThrow();
        assertEquals(20L, known.promptTokens); assertEquals(5L, known.cacheReadTokens); assertNull(known.completionTokens);
    }

    @Test void readsPersistedRecordsThroughANewServiceAndSeparatesRoundsAndInstances() {
        var a = job("ai-a", 1); var b = job("ai-b", 2); begin(a, 0); recorder.response(a.getId() + ":0", response("first"));
        var restored = new LocalAiDebugService(new LocalAiDebugRecorder(records, new AdminAuthPolicy("local", "password"), manager), access, rooms, jobs, records, traces);
        assertEquals(2, restored.summary("undercover", roomId, "viewer", instance).rounds().size());
        assertEquals(List.of(b.getId()), restored.turns("undercover", roomId, "viewer", instance, 2, null, "ai-b", null, 0, 50).getItems().stream().map(LocalAiDebugView.Turn::id).toList());
        String original = instance; instance = UUID.randomUUID().toString(); var c = job("host", 1);
        assertEquals(2, restored.instances("undercover", roomId, "viewer", 0, 20).getTotal());
        assertEquals(2, restored.summary("undercover", roomId, "viewer", original).usage().turns());
        assertEquals(1, restored.summary("undercover", roomId, "viewer", instance).usage().turns());
        assertNotEquals(a.getInstanceId(), c.getInstanceId());
    }

    @Test void concurrentRolesKeepTheirPrivateInputsSeparate() throws Exception {
        var a = job("ai-a", 1); var b = job("ai-b", 1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            pool.invokeAll(List.<Callable<Void>>of(() -> { begin(a, 0); recorder.response(a.getId() + ":0", response("alpha")); return null; },
                    () -> { begin(b, 0); recorder.response(b.getId() + ":0", response("beta")); return null; })).forEach(f -> assertDoesNotThrow(() -> { f.get(); }));
        }
        assertEquals("alpha", RuleSupport.map(RuleSupport.map(records.findById(a.getId()).orElseThrow().payload.get("attempts")).get("0")).get("rawOutput"));
        assertEquals("beta", RuleSupport.map(RuleSupport.map(records.findById(b.getId()).orElseThrow().payload.get("attempts")).get("0")).get("rawOutput"));
    }

    @Test void environmentMembershipAndRecordOwnershipBoundariesAreEnforced() {
        for (String env : List.of("test", "production")) {
            var disabled = new LocalAiDebugRecorder(records, new AdminAuthPolicy(env, "totp"), manager);
            var restricted = new LocalAiDebugService(disabled, access, rooms, jobs, records, traces);
            assertFalse(restricted.available(roomId, "viewer"));
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> restricted.summary("undercover", roomId, "viewer", instance)).getStatus());
            var job = new AiTurnJob(); disabled.queued(job); assertFalse(disabled.enabled());
        }
        var job = job("ai", 1);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "not a participant")).when(access).requireParticipant(roomId, "outsider");
        assertFalse(service.available(roomId, "outsider"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> service.detail("undercover", roomId, "outsider", job.getId())).getStatus());
        String other = UUID.randomUUID().toString(); Room room = new Room(); room.setGameId("undercover"); when(rooms.findById(other)).thenReturn(Optional.of(room));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.detail("undercover", other, "viewer", job.getId())).getStatus());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.detail("werewolf", roomId, "viewer", job.getId())).getStatus());
    }

    @Test void historicalJobsExposeOnlySavedSnapshotsAndExplicitlyMissingOriginalMessages() {
        var job = new AiTurnJob(); job.setId(UUID.randomUUID().toString()); job.setRoomId(roomId); job.setInstanceId(instance);
        job.setActorId("historical-ai"); job.setKind("SPEAK"); job.setTurnKey(job.getId()); job.setStatus("SUCCEEDED");
        job.setObservation(RuleSupport.map(json.convertValue(observation("historical-ai", 1), Map.class)));
        job.getDiagnostics().put("generation", Map.of("promptTokens", 123, "completionTokens", 45, "calls", 1, "responsesObserved", 1, "usageComplete", true));
        jobs.saveAndFlush(job);
        var detail = service.detail("undercover", roomId, "viewer", job.getId());
        assertTrue(detail.turn().legacy()); assertEquals(123L, detail.turn().promptTokens());
        assertTrue(detail.data().containsKey("observationSnapshot")); assertFalse(detail.data().containsKey("attempts"));
        assertFalse(records.existsById(job.getId()));
    }

    @Test void collectionFailureIsVisibleAndDoesNotEscapeIntoGameplay() {
        var failing = mock(LocalAiDebugTurnRepository.class); when(failing.lock(anyString())).thenThrow(new IllegalStateException("private provider content"));
        var debug = new LocalAiDebugRecorder(failing, new AdminAuthPolicy("local", "password"), manager);
        assertDoesNotThrow(() -> debug.begin(roomId, observation("ai", 1), "failed-job", 0, List.of(), AiModelSelection.defaults("model"), 1000));
        assertEquals("CAPTURE_FAILED", debug.captureWarning());
    }
}
