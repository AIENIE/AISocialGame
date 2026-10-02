package com.aisocialgame.service.ai.v2;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.config.AppProperties;
import com.aisocialgame.config.PromptProperties;
import com.aisocialgame.engine.TurtleSoupGameEngine;
import com.aisocialgame.engine.v2.GameRuleSet;
import com.aisocialgame.engine.v2.turtlesoup.TurtleSoupCaseCatalog;
import com.aisocialgame.engine.v2.undercover.UndercoverWordCatalog;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.integration.grpc.dto.AiModelOptionDto;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Persona;
import com.aisocialgame.repository.PersonaRepository;
import com.aisocialgame.service.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.aisocialgame.engine.v2.RuleSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/**
 * Explicit opt-in, never a default CI model call. Each run requires a NEW absolute evidence directory
 * outside the checkout. A shared external journal counts all comparison reruns against 90 calls;
 * its cumulative count must also be subtracted from the independent live-game 300-call allowance.
 * 30 scenarios * (legacy <= 1 + v2 <= 2) <= 90; actual turtle legacy scripts cost zero.
 * Default mode uses one authenticated ListModels preflight with no chat budget. Explicit project-scoped
 * mode validates generation through the first normal scenario request, charged to the same ledger.
 * No names, warmups, evaluator models, or host semantic calls are made here.
 */
@EnabledIfEnvironmentVariable(named = "AI_REALISM_COMPARISON", matches = "1")
@SpringBootTest(classes = AiSocialGameApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:realismcomparison;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.grpc.client.channel.ai.target=${AI_GRPC_ADDR:static://127.0.0.1:19003}",
        "spring.grpc.client.channel.ai.ssl.enabled=true",
        "app.grpc.ai-trust-cert-collection=",
        "app.external.aiservice-hmac-caller=${APP_EXTERNAL_AISERVICE_HMAC_CALLER:}",
        "app.external.aiservice-hmac-secret=${GRPC_SHARED_SECRET:}",
        "app.ai.default-model=${APP_AI_DEFAULT_MODEL:}",
        "app.ai.system-user-id=${APP_AI_SYSTEM_USER_ID:1}",
        "app.ai.validation-call-limit=90",
        "app.ai.validation-run-id=realism-comparison-only",
        "app.game.scheduler-enabled=false"
})
@ActiveProfiles("test")
class AiRealismComparisonIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final int MAX_CALLS = 90;
    @MockitoSpyBean private AiGrpcClient client;
    @Autowired private AppProperties properties;
    @Autowired private Environment environment;
    @Autowired private PromptProperties prompts;
    @Autowired private AiBeliefService beliefs;
    @Autowired private AiQualityService quality;
    @Autowired private AiReflectionService reflections;
    @Autowired private AiDecisionTraceService traces;
    @Autowired private AiTurnGenerator generator;
    @Autowired private AiCallBudgetService budget;
    @Autowired private List<GameRuleSet> rules;
    @Autowired private UndercoverWordCatalog words;
    @Autowired private TurtleSoupCaseCatalog soups;
    @Autowired private TurtleSoupGameEngine legacySoup;
    private Path evidence;
    private int journaledAttempts;
    private int completedResponses;
    private int successfulGenerationResponses;
    private int transportFailures;
    private String activeScenario;
    private String activeVariant;
    private AiRealismComparisonLedger comparisonLedger;
    private int previousComparisonAttempts;
    private String preflightStatus = "NOT_RUN";
    private boolean projectScopedPreflight;
    private final Map<String, Object> scopedFirstRequest = new LinkedHashMap<>();
    private AiRealismGrpcDiagnostics grpcDiagnostics;

    @Test
    void collectThirtyScenarioComparisonsWithinNinetyCallCeiling() throws Exception {
        requireEnvironment("AI_GRPC_ADDR");
        requireEnvironment("APP_EXTERNAL_AISERVICE_HMAC_CALLER");
        requireEnvironment("GRPC_SHARED_SECRET");
        requireEnvironment("APP_AI_SYSTEM_USER_ID");
        assertTrue(System.getProperty("os.name", "").startsWith("Windows"), "This acceptance adapter is Windows-local only");
        assertEquals("local", requireEnvironment("ENV"), "Only the local gateway is authorized");
        assertEquals("static://localaiservice.testhut.top:22011", requireEnvironment("AI_GRPC_ADDR"));
        assertEquals("TLS", requireEnvironment("AI_GRPC_NEGOTIATION_TYPE"));
        assertEquals("static://localaiservice.testhut.top:22011", environment.getProperty("spring.grpc.client.channel.ai.target"));
        assertEquals("true", environment.getProperty("spring.grpc.client.channel.ai.ssl.enabled"));
        assertEquals("", environment.getProperty("app.grpc.ai-trust-cert-collection", ""));
        assertEquals(85L, properties.getAi().getSystemUserId(), "Use the explicitly authorized ordinary account");
        projectScopedPreflight = "1".equals(System.getenv("AI_REALISM_PROJECT_SCOPED_PREFLIGHT"));
        assertEquals(MAX_CALLS, budget.limit(), "The comparison may never use an unbounded model client");
        assertEquals(0, budget.consumed(), "This comparison ledger has already been used; do not silently rerun");
        AiRealismScenarioFixtures fixtures = new AiRealismScenarioFixtures(rules, words, soups);
        List<AiRealismScenarioFixtures.Scenario> scenarios = fixtures.load();
        assertEquals(30, scenarios.size());
        scenarios = AiRealismScenarioFixtures.select(scenarios, System.getenv("AI_REALISM_SCENARIOS"));
        evidence = createEvidenceDirectory(requireEnvironment("AI_REALISM_EVIDENCE_DIR"));
        String configuredLedger = System.getenv("AI_REALISM_BUDGET_FILE");
        Path budgetPath = externalFilePath(configuredLedger == null || configuredLedger.isBlank()
                ? evidence.getParent().resolve("comparison-call-budget.jsonl").toString() : configuredLedger);
        if (budgetPath.startsWith(evidence)) throw new IllegalArgumentException("The shared budget journal must outlive an individual evidence run directory");
        try (AiRealismComparisonLedger persistent = new AiRealismComparisonLedger(budgetPath, MAX_CALLS)) {
            comparisonLedger = persistent;
            previousComparisonAttempts = persistent.consumed();
            collectEvidence(fixtures, scenarios, budgetPath);
        }
    }

    private void collectEvidence(AiRealismScenarioFixtures fixtures, List<AiRealismScenarioFixtures.Scenario> scenarios,
                                 Path budgetPath) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("startedAt", Instant.now().toString()); manifest.put("scenarioCount", scenarios.size());
        manifest.put("selectedScenarioIds", scenarios.stream().map(AiRealismScenarioFixtures.Scenario::id).toList());
        manifest.put("promptVersion", AiTurnGenerator.PROMPT_VERSION);
        manifest.put("callCeilingAcrossComparisonReruns", MAX_CALLS); manifest.put("reservedForSixLiveGames", 210);
        manifest.put("comparisonBudgetFile", budgetPath.toString()); manifest.put("previousComparisonAttempts", previousComparisonAttempts);
        manifest.put("accounting", "Every external reservation, including interrupted requests, counts against the cumulative comparison ceiling. Reuse this SAME budget file for every rerun. Live-game calls have a separate ledger and must also be included in the total 300 allowance.");
        manifest.put("acceptance", "Collection only. Score naturalness, response specificity, persona continuity, and emotion/action consistency separately; cooperation and visible-information boundaries are independent evidence/gates. No automatic quality score is claimed.");
        manifest.put("requestedModel", text(properties.getAi().getDefaultModel()));
        manifest.put("modelSelection", properties.getAi().getDefaultModel() == null || properties.getAi().getDefaultModel().isEmpty() ? "GATEWAY_DEFAULT" : "CONFIGURED_MODEL");
        manifest.put("normalization", "Test fixtures normalize visible events, own private facts and legal actions. They are not live-room observations; legacy limitations are recorded per scenario.");
        manifest.put("preflightMode", projectScopedPreflight ? "PROJECT_SCOPED_FIRST_SCENARIO" : "MODEL_CATALOG");
        manifest.put("catalogStatus", projectScopedPreflight ? "UNAVAILABLE_FOR_PROJECT_SCOPED_CALLER" : "NOT_CHECKED_YET");
        manifest.put("scopedFirstRequestStatus", projectScopedPreflight ? "SCOPED_GENERATION_PENDING" : "NOT_APPLICABLE");
        manifest.put("preflight", projectScopedPreflight
                ? "ListModels has no project_key and the current gateway evaluates it against the default project. Do not request or widen that permission. Standard local transport readiness is an external precondition; this test sends no separate health/warmup call. The first normal aisocialgame ChatCompletions validates generation and consumes the same cumulative budget. Failure stops collection."
                : "One authenticated ListModels read with a 15-second deadline, before any chat reservation. It verifies catalogue access and at least one active text model, not upstream balance or generation readiness.");
        writeNew("manifest.json", manifest);
        writeNew("host-semantic-fixtures-not-executed.json", Map.of("executed", false, "modelCalls", 0,
                "fixtures", fixtures.turtleHostFixtures(), "note", "Reusable 120 question and 30 solution cases; excluded from this social-comparison call budget."));
        grpcDiagnostics = AiRealismGrpcDiagnostics.install(client);
        installJournal();
        List<Map<String, Object>> comparisons = new ArrayList<>();
        String outcome = "INTERRUPTED_OR_FAILED";
        try {
            if (projectScopedPreflight) {
                preflightStatus = "SCOPED_GENERATION_PENDING";
                writeNew("preflight.json", Map.of("status", preflightStatus, "mode", "PROJECT_SCOPED_FIRST_SCENARIO",
                        "catalogStatus", "UNAVAILABLE_FOR_PROJECT_SCOPED_CALLER", "metadataReadAttempts", 0,
                        "projectKey", "aisocialgame", "note", "No ListModels or warmup is sent. The first normal scenario request counts against the unchanged cumulative budget. Its result is recorded in calls.jsonl and summary.json."));
            } else preflightModels();
            for (var scenario : scenarios) {
                activeScenario = scenario.id();
                Map<String, Object> comparison = new LinkedHashMap<>();
                comparison.put("scenarioId", scenario.id()); comparison.put("gameId", scenario.gameId());
                comparison.put("baselineMatch", scenario.baselineMatch()); comparison.put("limitations", scenario.limitations());
                comparison.put("observation", scenario.observation()); comparison.put("goldExpectations", scenario.expectations());
                activeVariant = "legacy";
                int beforeLegacy = journaledAttempts;
                Object old = legacyDecision(fixtures, scenario);
                comparison.put("legacy", old); comparison.put("legacyCalls", journaledAttempts - beforeLegacy);
                append("comparisons.jsonl", Map.of("event", "LEGACY_COMPLETE", "scenarioId", scenario.id(), "result", comparison));
                assertTrue(journaledAttempts - beforeLegacy <= 1, "Legacy may make at most one call per scenario");
                assertEquals(0, transportFailures, "Stop immediately after a call, journal, budget or authentication failure; inspect external evidence");
                activeVariant = "v2";
                int beforeNew = journaledAttempts;
                AiTurnDecision next = generator.generate(scenario.adapter(), scenario.observation(), UUID.randomUUID().toString());
                comparison.put("v2", next); comparison.put("v2Calls", journaledAttempts - beforeNew);
                comparison.put("structuralChecks", structuralChecks(scenario, next));
                comparison.put("manualReview", manualReview());
                comparisons.add(comparison);
                append("comparisons.jsonl", Map.of("event", "SCENARIO_COMPLETE", "scenarioId", scenario.id(), "result", comparison));
                assertTrue(journaledAttempts - beforeNew >= 1 && journaledAttempts - beforeNew <= 2, "V2 must attempt one decision plus at most one repair");
                assertEquals(0, transportFailures, "Stop immediately after a call, journal, budget or authentication failure; inspect external evidence");
            }
            assertEquals(scenarios.size(), comparisons.size());
            assertTrue(journaledAttempts <= MAX_CALLS);
            assertEquals(journaledAttempts, budget.consumed(), "Persistent client budget and evidence journal must agree");
            assertEquals(previousComparisonAttempts + journaledAttempts, comparisonLedger.consumed());
            if (projectScopedPreflight) {
                assertTrue(successfulGenerationResponses > 0, "Project-scoped acceptance requires a real nonempty generation response");
            }
            outcome = "COLLECTED_REQUIRES_MANUAL_REVIEW";
        } finally {
            if (projectScopedPreflight) preflightStatus = completedResponses > 0 && successfulGenerationResponses > 0 && transportFailures == 0
                    ? "SCOPED_GENERATION_CONFIRMED" : "SCOPED_GENERATION_FAILED";
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("outcome", outcome); summary.put("completedScenarios", comparisons.size());
            summary.put("selectedScenarios", scenarios.size());
            summary.put("promptVersion", AiTurnGenerator.PROMPT_VERSION);
            summary.put("journaledAttempts", journaledAttempts); summary.put("clientBudgetConsumed", budget.consumed());
            summary.put("completedResponses", completedResponses); summary.put("callOrJournalFailures", transportFailures);
            summary.put("successfulGenerationResponses", successfulGenerationResponses);
            summary.put("preflightStatus", preflightStatus);
            summary.put("preflightMode", projectScopedPreflight ? "PROJECT_SCOPED_FIRST_SCENARIO" : "MODEL_CATALOG");
            summary.put("catalogStatus", projectScopedPreflight ? "UNAVAILABLE_FOR_PROJECT_SCOPED_CALLER" : preflightStatus);
            summary.put("scopedFirstRequest", projectScopedPreflight ? scopedFirstRequest : Map.of("status", "NOT_APPLICABLE"));
            summary.put("previousComparisonAttempts", previousComparisonAttempts); summary.put("cumulativeComparisonAttempts", comparisonLedger.consumed());
            summary.put("remainingFromOriginal300BeforeLiveGameCalls", 300 - comparisonLedger.consumed());
            summary.put("remainingWarning", "All live-game attempts and any comparisons outside this shared budget file must also be subtracted. Never replace the shared file to reset the allowance.");
            summary.put("finishedAt", Instant.now().toString());
            writeNew("summary.json", summary);
            writeNew("review.json", Map.of("status", "PENDING_MANUAL_REVIEW", "comparisons", comparisons));
        }
    }

    private void preflightModels() throws IOException {
        long began = System.nanoTime();
        int clientBefore = budget.consumed(), ledgerBefore = comparisonLedger.consumed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", "ListModels"); result.put("metadataReadAttempts", 1); result.put("deadlineSeconds", 15);
        result.put("note", "Catalogue authorization and active TEXT models only. The existing client DTO does not expose stable model keys, so a configured key and the default route are not independently resolved. No warmup or chat request is issued.");
        var timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "comparison-preflight-deadline"); thread.setDaemon(true); return thread;
        });
        var deadline = io.grpc.Context.current().withDeadlineAfter(15, TimeUnit.SECONDS, timer);
        try {
            grpcDiagnostics.reset();
            List<AiModelOptionDto> models = deadline.call(() -> client.listModels(properties.getAi().getSystemUserId()));
            long textModels = models.stream().filter(model -> "MODEL_TYPE_TEXT".equals(model.type())).count();
            result.put("activeModelCount", models.size()); result.put("activeTextModelCount", textModels);
            if (textModels == 0) throw new IllegalStateException("NO_ACTIVE_TEXT_MODEL");
            if (budget.consumed() != clientBefore || comparisonLedger.consumed() != ledgerBefore)
                throw new IllegalStateException("PREFLIGHT_UNEXPECTED_CHAT_RESERVATION");
            preflightStatus = "MODEL_CATALOG_READY";
        } catch (Exception error) {
            preflightStatus = "MODEL_CATALOG_PREFLIGHT_FAILED";
            result.put("errorType", error.getClass().getSimpleName());
            if (error instanceof ApiException api) result.put("httpStatus", api.getStatus().value());
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            // Never put gateway error descriptions or exception causes into Maven diagnostics.
            throw new IllegalStateException("MODEL_CATALOG_PREFLIGHT_FAILED");
        } finally {
            deadline.cancel(null); timer.shutdownNow();
            result.put("status", preflightStatus); result.put("latencyMs", (System.nanoTime() - began) / 1_000_000);
            result.put("clientBudgetDelta", budget.consumed() - clientBefore);
            result.put("comparisonLedgerDelta", comparisonLedger.consumed() - ledgerBefore);
            result.put("grpc", grpcDiagnostics.snapshot());
            writeNew("preflight.json", result);
        }
    }

    private Object legacyDecision(AiRealismScenarioFixtures fixtures, AiRealismScenarioFixtures.Scenario scenario) {
        GameState state = fixtures.legacyState(scenario);
        if ("SCRIPT_QUESTION".equals(scenario.baselineAction())) {
            Object oldCase = ReflectionTestUtils.invokeMethod(legacySoup, "caseById", scenario.caseId());
            String content = ReflectionTestUtils.invokeMethod(legacySoup, "nextAiQuestion", state, oldCase);
            return Map.of("source", "TurtleSoupGameEngine.nextAiQuestion", "action", content == null || content.isBlank() ? "PASS" : "ASK_QUESTION",
                    "content", content == null ? "" : content, "modelCalls", 0, "presentation", Map.of());
        }
        // Unique persona ID isolates each legacy case's existing reflection mechanism in the test DB.
        PersonaRepository personas = new PersonaRepository() {
            @Override public Persona findById(String id) {
                if (!scenario.id().equals(id)) return super.findById(id);
                Map<String, Object> persona = scenario.observation().persona();
                return new Persona(id, text(persona.get("name")), text(persona.get("trait")), "", text(persona.get("speechStyle")), text(persona.get("strategyStyle")), 2, "");
            }
        };
        AiDecisionService legacy = new AiDecisionService(client, properties, prompts, personas, beliefs, quality, reflections, traces);
        GamePlayerState actor = state.getPlayers().stream().filter(p -> scenario.observation().actorId().equals(p.getPlayerId())).findFirst().orElseThrow();
        return switch (scenario.baselineAction()) {
            case "VOTE" -> legacy.decideVote(state, actor);
            case "NIGHT_ACTION" -> legacy.decideNightAction(state, actor);
            default -> legacy.generateSpeech(state, actor);
        };
    }

    private Map<String, Object> structuralChecks(AiRealismScenarioFixtures.Scenario scenario, AiTurnDecision decision) {
        List<String> missing = strings(scenario.expectations().get("requiredEventIds")).stream().filter(id -> !decision.evidenceEventIds().contains(id)).toList();
        return Map.of("validationErrors", generator.validate(scenario.adapter(), scenario.observation(), decision),
                "missingExpectedEvidenceEventIds", missing, "fallback", decision.fallback(),
                "note", "These checks do not establish semantic correctness, absence of every possible leak, or naturalness.");
    }

    private static Map<String, Object> manualReview() {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("status", "PENDING"); review.put("scale", "Score each variant 1-5 with a quoted output example; do not infer naturalness from JSON validity.");
        review.put("rubricVersion", "social-realism-user-criteria-v1");
        for (String dimension : List.of("naturalness", "responseSpecificity", "personaContinuity", "emotionActionConsistency")) review.put(dimension, null);
        review.put("visibleInformation", Map.of("status", "PENDING", "evidence", List.of()));
        review.put("cooperationNotes", List.of());
        review.put("rationale", ""); return review;
    }

    private void installJournal() {
        doAnswer(invocation -> {
            if (evidence == null || activeScenario == null || activeVariant == null) throw new IllegalStateException("Unscoped model call rejected");
            int ordinal = journaledAttempts + 1;
            long began = System.nanoTime();
            boolean firstScopedRequest = projectScopedPreflight && scopedFirstRequest.isEmpty();
            if (firstScopedRequest) {
                scopedFirstRequest.put("scenarioId", activeScenario); scopedFirstRequest.put("variant", activeVariant);
                scopedFirstRequest.put("requestId", invocation.getArgument(5)); scopedFirstRequest.put("projectKey", invocation.getArgument(0));
                scopedFirstRequest.put("status", "AWAITING_RESPONSE");
            }
            grpcDiagnostics.reset();
            try {
                if (!"aisocialgame".equals(invocation.getArgument(0))) throw new IllegalStateException("NONCANONICAL_COMPARISON_PROJECT");
                // Reserve against the shared durable ceiling before either per-run journaling or dispatch.
                int cumulative = comparisonLedger.reserve(evidence.toString(), activeScenario, activeVariant, invocation.getArgument(5));
                journaledAttempts = ordinal;
                Map<String, Object> start = new LinkedHashMap<>();
                start.put("event", "ATTEMPT_RESERVED"); start.put("attempt", ordinal); start.put("comparisonAttempt", cumulative);
                start.put("scenarioId", activeScenario); start.put("variant", activeVariant);
                start.put("requestId", invocation.getArgument(5)); start.put("model", invocation.getArgument(3)); start.put("messages", invocation.getArgument(4));
                start.put("at", Instant.now().toString());
                append("calls.jsonl", start);
                AiChatResult response = (AiChatResult) invocation.callRealMethod();
                completedResponses++;
                if (response != null && !text(response.content()).isBlank()) successfulGenerationResponses++;
                append("calls.jsonl", Map.of("event", "RESPONSE", "attempt", ordinal, "model", text(response.modelKey()),
                        "promptTokens", response.promptTokens(), "completionTokens", response.completionTokens(),
                        "latencyMs", (System.nanoTime() - began) / 1_000_000, "content", text(response.content()), "grpc", grpcDiagnostics.snapshot()));
                if (firstScopedRequest) {
                    scopedFirstRequest.put("latencyMs", (System.nanoTime() - began) / 1_000_000);
                    scopedFirstRequest.put("modelKey", text(response.modelKey()));
                    scopedFirstRequest.put("nonemptyContent", !text(response.content()).isBlank());
                    if (text(response.content()).isBlank()) throw new IllegalStateException("EMPTY_FIRST_GENERATION_RESPONSE");
                    scopedFirstRequest.put("status", "SUCCEEDED"); scopedFirstRequest.put("grpc", grpcDiagnostics.snapshot());
                }
                return response;
            } catch (Throwable error) {
                transportFailures++;
                try {
                    append("calls.jsonl", Map.of("event", "ERROR", "attempt", ordinal, "errorType", error.getClass().getSimpleName(), "latencyMs", (System.nanoTime() - began) / 1_000_000, "grpc", grpcDiagnostics.snapshot()));
                    if (firstScopedRequest) {
                        scopedFirstRequest.put("latencyMs", (System.nanoTime() - began) / 1_000_000);
                        scopedFirstRequest.put("status", "FAILED"); scopedFirstRequest.put("errorType", error.getClass().getSimpleName());
                        scopedFirstRequest.put("grpc", grpcDiagnostics.snapshot());
                    }
                } catch (IOException journalError) { error.addSuppressed(journalError); }
                throw error;
            }
        }).when(client).chatCompletions(anyString(), anyLong(), nullable(String.class), nullable(String.class), anyList(), anyString(), anyInt());
    }

    static Path createEvidenceDirectory(String requested) throws IOException {
        return Files.createDirectory(externalFilePath(requested)); // Existing evidence always rejects reruns.
    }

    static Path externalFilePath(String requested) throws IOException {
        Path path = Path.of(requested).normalize();
        if (!path.isAbsolute() || path.getParent() == null) throw new IllegalArgumentException("Evidence path must be absolute");
        Path parent = path.getParent().toRealPath();
        Path resolved = parent.resolve(path.getFileName());
        if (Files.exists(resolved)) resolved = resolved.toRealPath();
        Path checkout = Path.of("").toAbsolutePath().normalize();
        while (checkout.getParent() != null && !Files.exists(checkout.resolve(".git"))) checkout = checkout.getParent();
        if (Files.exists(checkout.resolve(".git")) && resolved.startsWith(checkout.toRealPath())) throw new IllegalArgumentException("Evidence must be outside the checkout");
        return resolved;
    }

    private static String requireEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required comparison environment variable missing: " + key);
        return value;
    }
    private void writeNew(String name, Object value) throws IOException {
        Files.writeString(evidence.resolve(name), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private synchronized void append(String name, Object value) throws IOException {
        byte[] line = (JSON.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(evidence.resolve(name), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = ByteBuffer.wrap(line);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }
}
