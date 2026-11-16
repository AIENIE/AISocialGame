package com.aisocialgame.service.ai.v2;

import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import com.aisocialgame.service.ai.config.AiModelSelection;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

final class UndercoverSpeechComparison {
    static final String BASELINE = "undercover-v1.2";
    static final String CANDIDATE = "undercover-v1.3";
    static final String BASELINE_COMMIT = "7276aecb6fb76e793bc33d18f89bc74ad8cdb566";
    static final int CALL_LIMIT = 16;
    private static final Set<String> PERSONAS = Set.of("ai1", "ai2");

    record Trial(String caseId, String personaId, String criterion, VisibleObservation observation,
                 GameAiAdapter adapter, String promptVersion) {
        String sampleId() {
            return caseId + ":" + personaId;
        }
    }

    static List<Trial> trials() throws Exception {
        var fixtures = new MilestoneClosureScenarios();
        var rules = fixtures.rules.getFirst();
        var baseline = mock(GameAiAdapter.class, delegatesTo(rules));
        var candidate = mock(GameAiAdapter.class, delegatesTo(rules));
        // Preserve the two phase instructions used by the recorded comparison.
        doReturn("根据局势选择合法动作；描述时通常先概括，自行把握细节，不重复旧描述。")
                .when(baseline).instruction(argThat(o -> "DESCRIPTION".equals(o.phase())));
        doReturn("最高票并列：选择合法动作；描述时补充新线索，最多60字，无法补充可SKIP。")
                .when(baseline).instruction(argThat(o -> "TIE_DEFENSE".equals(o.phase())));
        doReturn("根据局势选择合法动作。描述时给出一个相关特征，已有描述时换一个尚未提过的用途、场景或细节。"
                + "白板从已听到的线索推测，再试探一个新方向。首位白板线索有限，先给出一条留有余地、便于后续比较的描述。")
                .when(candidate).instruction(argThat(o -> "DESCRIPTION".equals(o.phase())));
        doReturn("最高票并列。选择合法动作，补充描述时给出一个尚未提过的相关特征，最多60字。没有合适的新线索时可选择SKIP。")
                .when(candidate).instruction(argThat(o -> "TIE_DEFENSE".equals(o.phase())));

        var trials = new ArrayList<Trial>();
        for (var sample : fixtures.singles()) {
            if (!PERSONAS.contains(sample.personaId())) continue;
            String scenario = (String) sample.metadata().get("scenarioId");
            String criterion = switch (scenario) {
                case "undercover:blank_first" -> "给出一条可以供后续比较的试探性描述，依据与本轮已有记录相符。";
                case "undercover:blank_last" -> "顺着已有描述试探一个新的相关特征，保持猜测的分寸。";
                default -> null;
            };
            if (criterion != null) addPair(trials, scenario, sample.personaId(), criterion,
                    sample.observation(), baseline, candidate);
        }
        for (var sample : new AiConversationScenarios().load()) {
            if (!PERSONAS.contains(sample.personaId())) continue;
            String criterion = switch (sample.id()) {
                case "undercover:next_description" -> "结合前一轮描述，提供一个相关且此前未提到的特征。";
                case "undercover:runoff_description" -> "给出一条新的相关特征，或在缺少可靠补充时合法跳过。";
                default -> null;
            };
            if (criterion != null) addPair(trials, sample.id(), sample.personaId(), criterion,
                    freezeEventIds(sample.id(), sample.observation()), baseline, candidate);
        }
        return List.copyOf(trials);
    }

    private static VisibleObservation freezeEventIds(String caseId, VisibleObservation observation) {
        var events = new ArrayList<Map<String, Object>>();
        for (var event : observation.events()) {
            var copy = new LinkedHashMap<>(event);
            copy.put("eventId", UUID.nameUUIDFromBytes((caseId + ":" + events.size())
                    .getBytes(StandardCharsets.UTF_8)).toString());
            events.add(copy);
        }
        return new VisibleObservation(observation.gameId(), observation.instanceId(), observation.phase(),
                observation.round(), observation.actorId(), observation.turnKind(), observation.self(),
                observation.players(), observation.rules(), List.copyOf(events), observation.privateFacts(),
                observation.legalActions(), observation.persona(), observation.memory(), observation.knowledge());
    }

    static String inputSha256(List<Trial> trials) throws Exception {
        var input = trials.stream().map(trial -> Map.of("sample", trial.sampleId(), "version", trial.promptVersion(),
                "criterion", trial.criterion(), "prompt", AiPromptProjection.project(trial.adapter(), trial.observation()))).toList();
        byte[] bytes = ConversationValidation.JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(input);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void addPair(List<Trial> trials, String caseId, String personaId, String criterion,
                                VisibleObservation observation, GameAiAdapter baseline, GameAiAdapter candidate) {
        trials.add(new Trial(caseId, personaId, criterion, observation, baseline, BASELINE));
        trials.add(new Trial(caseId, personaId, criterion, observation, candidate, CANDIDATE));
    }

    static void intercept(AiGrpcClient client, Supplier<Attempt> active, Answer<AiChatResult> transport) {
        doAnswer(invocation -> {
            Attempt attempt = active.get();
            attempt.reserve(invocation.getArgument(5));
            attempt.response = transport.answer(invocation);
            return attempt.response;
        }).when(client).undercoverChatCompletions(anyString(), anyLong(), anyString(),
                any(AiModelSelection.class), anyList(), anyString(), anyInt());
    }

    static final class Attempt {
        final Trial trial;
        final String jobId = "undercover-eval-" + UUID.randomUUID();
        private final AiRealismComparisonLedger ledger;
        private final Path evidence;
        String requestId;
        AiChatResult response;

        Attempt(Trial trial, AiRealismComparisonLedger ledger, Path evidence) {
            this.trial = trial;
            this.ledger = ledger;
            this.evidence = evidence;
        }

        private void reserve(String actualRequestId) {
            if (requestId != null || !actualRequestId.equals(jobId + ":0")) {
                throw new AssertionError("Only one attempt per version and sample is permitted");
            }
            try {
                ledger.reserve(evidence.toString(), trial.sampleId(), trial.promptVersion(), actualRequestId);
            } catch (IOException failure) {
                throw new AssertionError("Comparison reservation failed before dispatch", failure);
            }
            requestId = actualRequestId;
        }

        Map<String, Object> generate(AiTurnGenerator generator, AiModelSelection selection) {
            var row = new LinkedHashMap<String, Object>();
            row.put("sampleId", trial.sampleId());
            row.put("promptVersion", trial.promptVersion());
            row.put("criterion", trial.criterion());
            row.put("observation", AiPromptProjection.project(trial.adapter(), trial.observation()));
            Map<String, Object> diagnostics;
            try {
                var decision = generator.generate(trial.adapter(), trial.observation(), jobId,
                        AiTurnGenerator.UNDERCOVER_DECISION_MILLIS, () -> requestId == null,
                        Map.of("modelSelection", selection.toMap()));
                row.put("action", decision.action());
                row.put("speech", decision.speech());
                row.put("presentation", decision.presentation());
                row.put("evidenceEventIds", decision.evidenceEventIds());
                diagnostics = decision.diagnostics();
            } catch (AiTurnGenerator.GenerationFailure failure) {
                row.put("failure", failure.getMessage());
                diagnostics = failure.diagnostics();
            }
            var measurements = AiJobDiagnostics.generation(diagnostics);
            measurements.put("promptVersion", trial.promptVersion());
            row.put("diagnostics", measurements);
            row.put("requestId", requestId);
            if (response != null) row.put("rawResponse", response.content());
            return row;
        }
    }
}
