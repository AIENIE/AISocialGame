package com.aisocialgame.service.ai.v2;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.client.AiModelCatalogClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.service.ai.config.AiFunctionCode;
import com.aisocialgame.service.ai.config.AiModelConfigService;
import com.aisocialgame.service.ai.config.AiModelSelection;
import com.aisocialgame.service.credit.BoundedAiClient;
import com.fasterxml.jackson.databind.JsonNode;
import fireflychat.ai.v1.BudgetPriceQuote;
import fireflychat.ai.v1.ChatCompletionsRequest;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.aisocialgame.service.ai.v2.ConversationValidation.JSON;
import static com.aisocialgame.service.ai.v2.UndercoverSpeechComparison.CALL_LIMIT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "undercover.speech.mode", matches = "quote|compare")
@SpringBootTest(classes = AiSocialGameApplication.class, properties = {
        "spring.config.import=${AIENIE_APPLICATION_FILE}", "spring.jpa.hibernate.ddl-auto=validate",
        "app.game.scheduler-enabled=false", "app.ai.validation-call-limit=0"
})
@ActiveProfiles("local")
class UndercoverSpeechComparisonRealTest {
    @TestBean(name = "taskScheduler", methodName = "inactiveScheduler", enforceOverride = true)
    ThreadPoolTaskScheduler scheduler;
    @MockitoSpyBean AiGrpcClient client;
    @Autowired AiTurnGenerator generator;
    @Autowired AiModelConfigService models;
    @Autowired AiModelCatalogClient catalog;
    @Autowired AppProperties properties;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean BoundedAiClient bounded;
    @Value("${app.ai.budget-max-output-tokens:8192}") int outputLimit;
    @Value("${spring.grpc.client.channel.ai.target}") String aiTarget;
    @Value("${spring.grpc.client.channel.ai.ssl.enabled}") boolean tls;

    static ThreadPoolTaskScheduler inactiveScheduler() {
        var scheduler = mock(ThreadPoolTaskScheduler.class);
        when(scheduler.getClock()).thenReturn(Clock.systemUTC());
        return scheduler;
    }

    @Test void quoteOrCollectFrozenSpeechPairs() throws Exception {
        assertEquals("local", System.getenv("ENV"));
        assertEquals("static://localaiservice.testhut.top:22011", aiTarget);
        assertTrue(tls);
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql://localmysql.testhut.top:23306/aisocialgame?"));
        }
        Path directory = Path.of(System.getProperty("undercover.speech.evidence")).toRealPath();
        assertFalse(directory.startsWith(Path.of("..").toRealPath()), "Use an external evidence directory");
        Path quoteFile = directory.resolve("quote.json");
        var trials = UndercoverSpeechComparison.trials();
        assertEquals(CALL_LIMIT, trials.size());
        var selection = comparisonModel();
        long userId = properties.getAi().getSystemUserId();
        assertTrue(userId > 0);
        if ("quote".equals(System.getProperty("undercover.speech.mode"))) {
            assertFalse(Files.exists(quoteFile), "Use a fresh quote directory");
            ConversationValidation.atomic(directory.resolve("history.json"), readHistory());
            ConversationValidation.atomic(directory.resolve("model-readback.json"), modelReadback(selection));
            var profile = profile(trials, selection, userId);
            long perRequest = ((Number) profile.get("maxCreditPoints")).longValue();
            ConversationValidation.atomic(quoteFile, Map.of("quotedAt", Instant.now().toString(), "profile", profile,
                    "comparisonCallLimit", CALL_LIMIT, "comparisonQuotedPoints", Math.multiplyExact(perRequest, CALL_LIMIT),
                    "mixedGameCallLimit", 48, "mixedGameQuotedPoints", Math.multiplyExact(perRequest, 48)));
        } else {
            collect(directory, quoteFile, trials, selection, userId);
        }
    }

    private AiModelSelection comparisonModel() {
        String model = System.getProperty("undercover.speech.model");
        String effort = System.getProperty("undercover.speech.effort");
        if (model == null && effort == null) return models.resolve("undercover", "p1");
        assertTrue(model != null && !model.isBlank() && effort != null && !effort.isBlank(),
                "The comparison requires both a model key and thinking effort");
        return new AiModelSelection(AiFunctionCode.UNDERCOVER, model, AiModelSelection.ThinkingMode.ENABLED,
                effort, AiModelSelection.ENVIRONMENT_VERSION);
    }

    private Map<String, Object> modelReadback(AiModelSelection selection) {
        var result = new LinkedHashMap<String, Object>(selection.toMap());
        try {
            var live = catalog.listCallerModels().stream().filter(model -> model.modelKey().equals(selection.modelKey())).findFirst();
            result.put("catalogStatus", live.isPresent() ? "VISIBLE" : "MODEL_NOT_VISIBLE");
            live.ifPresent(model -> result.put("catalogThinking", model.thinking()));
        } catch (ApiException failure) {
            result.put("catalogStatus", Status.fromThrowable(failure).getCode().name());
        }
        return result;
    }

    private Map<String, Object> profile(List<UndercoverSpeechComparison.Trial> trials, AiModelSelection selection,
                                        long userId) throws Exception {
        var build = AiBuildIdentity.current();
        assertEquals(MilestoneClosureScenarios.sourceFingerprint(), build.get("sourceFingerprint"), "Build current sources before quoting");
        var profile = new LinkedHashMap<>(client.preflight(properties.getProjectKey(), userId, selection, outputLimit));
        profile.putAll(build);
        profile.put("inputSha256", UndercoverSpeechComparison.inputSha256(trials));
        profile.put("baselineCommit", UndercoverSpeechComparison.BASELINE_COMMIT);
        profile.put("systemUserId", userId);
        return profile;
    }

    private void collect(Path directory, Path quoteFile, List<UndercoverSpeechComparison.Trial> trials,
                         AiModelSelection selection, long userId) throws Exception {
        Path approvalFile = Path.of(System.getProperty("undercover.speech.approval")).toRealPath();
        assertFalse(approvalFile.startsWith(Path.of("..").toRealPath()), "Keep approval outside the checkout");
        JsonNode approval = JSON.readTree(approvalFile.toFile());
        assertEquals(ConversationValidation.sha(quoteFile), approval.path("quoteSha256").asText());
        assertFalse(approval.path("approvalReference").asText().isBlank());
        assertEquals(CALL_LIMIT, approval.path("comparisonCallLimit").asInt());
        Instant expiresAt = Instant.parse(approval.path("expiresAt").asText());
        assertTrue(Instant.now().isBefore(expiresAt));
        long stopPoints = approval.path("comparisonStopPoints").asLong();
        assertTrue(stopPoints > 0);
        JsonNode quote = JSON.readTree(quoteFile.toFile());
        var currentProfile = JSON.readTree(JSON.writeValueAsBytes(profile(trials, selection, userId)));
        assertEquals(quote.path("profile"), currentProfile, "The approved quote or frozen input changed");
        requireApprovedPriceBeforeSend(currentProfile);

        Path result = directory.resolve("comparison.json");
        Path journal = directory.resolve("requests.jsonl");
        assertFalse(Files.exists(result), "A previous run already has evidence");
        try (var ledger = new AiRealismComparisonLedger(journal, CALL_LIMIT)) {
            assertEquals(0, ledger.consumed(), "Review the original reservations before further paid work");
            var rows = new ArrayList<Map<String, Object>>();
            var active = new AtomicReference<UndercoverSpeechComparison.Attempt>();
            UndercoverSpeechComparison.intercept(client, active::get, invocation -> (AiChatResult) invocation.callRealMethod());
            var document = new LinkedHashMap<String, Object>();
            document.put("quoteSha256", approval.path("quoteSha256").asText());
            document.put("results", rows);
            long spent = 0;
            try {
                for (var trial : trials) {
                    assertTrue(Instant.now().isBefore(expiresAt), "Comparison approval expired");
                    assertTrue(spent < stopPoints, "Comparison spending stop reached");
                    var attempt = new UndercoverSpeechComparison.Attempt(trial, ledger, result);
                    active.set(attempt);
                    var row = attempt.generate(generator, selection);
                    rows.add(row);
                    assertNotNull(attempt.requestId, "The first request did not reach the guarded entry point");
                    // Query only the just-issued identity. This can reconcile usage but cannot send inference.
                    var receipt = bounded.inspect(properties.getProjectKey(), userId, attempt.requestId);
                    var charge = new LinkedHashMap<>(receipt);
                    charge.keySet().retainAll(List.of("requestId", "budgetId", "state", "remoteState", "queryState",
                            "billedPoints", "billedCost", "usageAuthoritative", "timing"));
                    row.put("settlement", charge);
                    assertEquals("SETTLED", receipt.get("state"), "Unresolved request, stop the batch");
                    spent = Math.addExact(spent, ((Number) receipt.get("billedPoints")).longValue());
                    assertNotNull(attempt.response, "Original reply needs review before more requests");
                    assertEquals(selection.modelKey(), attempt.response.modelKey(), "The model changed during comparison");
                    document.put("status", "RUNNING");
                    document.put("spentPoints", spent);
                    ConversationValidation.atomic(result, document);
                }
                document.put("status", "AWAITING_REVIEW");
            } finally {
                if (!"AWAITING_REVIEW".equals(document.get("status"))) document.put("status", "STOPPED");
                document.put("reservedAttempts", ledger.consumed());
                document.put("spentPoints", spent);
                ConversationValidation.atomic(result, document);
            }
        }
    }

    private void requireApprovedPriceBeforeSend(JsonNode profile) {
        doAnswer(invocation -> {
            ChatCompletionsRequest request = invocation.getArgument(0);
            Runnable admission = invocation.getArgument(2);
            return bounded.chat(request, invocation.<Integer>getArgument(1), outputLimit, () -> {
                var encoded = jdbc.queryForObject("""
                        select billing_quote_base64 from ai_credit_reservations
                        where project_key = ? and user_id = ? and request_id = ?
                        """, String.class, request.getProjectKey(), request.getUserId(), request.getRequestId());
                BudgetPriceQuote actual;
                try {
                    actual = BudgetPriceQuote.parseFrom(Base64.getDecoder().decode(encoded));
                } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
                    throw new AssertionError("Prepared quote cannot be verified", failure);
                }
                assertEquals(profile.path("currency").asText(), actual.getCurrency());
                assertEquals(profile.path("priceVersionId").asLong(), actual.getPriceVersionId());
                assertTrue(actual.getMaxCreditPoints() <= profile.path("maxCreditPoints").asLong());
                admission.run();
            });
        }).when(bounded).chat(any(ChatCompletionsRequest.class), anyInt(), any(Runnable.class));
    }

    private List<Map<String, Object>> readHistory() {
        var archives = jdbc.queryForList("""
                select id, finished_at, total_rounds, player_count from game_archives
                where game_id = 'undercover' and source = 'NORMAL' and finished_at is not null
                order by finished_at desc, id desc limit 5
                """);
        for (var archive : archives) {
            archive.put("tasks", jdbc.queryForList("""
                    select kind, status,
                        json_unquote(json_extract(diagnostics, '$.terminalReason')) as reason,
                        json_unquote(json_extract(diagnostics, '$.promptVersion')) as prompt_version,
                        json_extract(diagnostics, '$.queueMs') as queue_ms,
                        json_extract(diagnostics, '$.generation.latencyMs') as generation_ms
                    from ai_turn_jobs where instance_id = ? order by created_at, id limit 100
                    """, archive.get("id")));
            archive.put("publicEvents", jdbc.queryForList("""
                    select round_number, event_type, json_unquote(json_extract(data, '$.content')) as content,
                        json_unquote(json_extract(data, '$.reason')) as reason
                    from game_events where archive_id = ? and visibility = 'PUBLIC'
                        and event_type in ('SPEAK', 'SKIP', 'TIMEOUT') order by seq limit 80
                    """, archive.get("id")));
        }
        return archives;
    }
}
