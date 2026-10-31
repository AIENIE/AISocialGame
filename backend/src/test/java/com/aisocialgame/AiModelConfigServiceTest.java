package com.aisocialgame;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.dto.admin.*;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiModelCatalogClient;
import com.aisocialgame.integration.grpc.dto.*;
import com.aisocialgame.model.AiModelCatalog;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.ai.config.*;
import com.aisocialgame.service.ai.config.AiModelSelection.ThinkingMode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:modelconfig;DB_CLOSE_DELAY=-1;MODE=MySQL", "app.ai.budget-enabled=true"})
@ActiveProfiles("test")
class AiModelConfigServiceTest {
    @Autowired AiModelConfigService service;
    @Autowired AiModelCatalogRepository catalogs;
    @Autowired AiFunctionModelConfigRepository configs;
    @Autowired AppProperties properties;
    @Autowired com.aisocialgame.service.v2.AiTurnCoordinator coordinator;
    @Autowired AiTurnJobRepository jobs;
    @MockitoBean AiModelCatalogClient remote;

    @BeforeEach void setup() {
        configs.deleteAll(); catalogs.deleteAll(); catalogs.saveAndFlush(new AiModelCatalog());
        properties.getAi().setDefaultModel("12");
        when(remote.listCallerModels()).thenReturn(List.of(model(true, List.of("LOW", "HIGH"))));
    }
    private AiModelOptionDto model(boolean declared, List<String> efforts) {
        return new AiModelOptionDto(12, "测试模型", "mock", 0, 0, "MODEL_TYPE_TEXT", false, "test-model", true,
                new AiThinkingCapabilities(declared, declared, declared,
                        efforts.stream().map(e -> new AiThinkingCapabilities.Option(e, e)).toList(), null, null));
    }
    private AdminAiModelConfigResponse.FunctionView row(AdminAiModelConfigResponse response) { return response.functions().getFirst(); }
    private AdminAiModelConfigRequest request(AdminAiModelConfigResponse response, ThinkingMode mode, String effort) {
        var row = row(response);
        return new AdminAiModelConfigRequest(row.modelKey(), mode, effort, response.catalogVersion(), row.version());
    }
    @Test void readsNeverSyncAndFirstSyncImportsStableKeyForAllFourFunctions() {
        var environmentSelection = service.resolve("undercover", "ai");
        assertEquals("12", environmentSelection.modelKey());
        assertNull(service.getConfiguration().syncedAt()); verifyNoInteractions(remote);
        var result = service.sync();
        assertEquals("AI_MODEL_CONFIG_INVALID", assertThrows(ApiException.class, () -> service.requireUsable(environmentSelection)).getCode());
        assertEquals("12", environmentSelection.modelKey(), "validation must not rewrite a prepared request");
        assertEquals(4, result.functions().size()); assertEquals(1, result.catalogVersion());
        assertTrue(result.functions().stream().allMatch(f -> f.modelKey().equals("test-model") && f.thinkingMode() == ThinkingMode.DEFAULT && f.thinkingEffort() == null));
        assertEquals(Set.of(AiFunctionCode.UNDERCOVER, AiFunctionCode.WEREWOLF),
                result.functions().stream().filter(AdminAiModelConfigResponse.FunctionView::inferenceEnabled)
                        .map(AdminAiModelConfigResponse.FunctionView::functionCode).collect(java.util.stream.Collectors.toSet()));
        assertNull(result.models().getFirst().thinking().defaultEnabled()); assertNull(result.models().getFirst().thinking().defaultEffort());
        properties.getAi().setDefaultModel("another-environment-model");
        assertEquals("test-model", service.resolve("undercover", "ai").modelKey());
        assertEquals(AiFunctionCode.TURTLE_SOUP_HOST, service.resolve("turtle_soup", com.aisocialgame.engine.v2.RuleSupport.HOST).functionCode());
        assertEquals(AiFunctionCode.TURTLE_SOUP_PLAYER, service.resolve("turtle_soup", "ai").functionCode());
        assertEquals(AiFunctionCode.WEREWOLF, service.resolve("WEREWOLF", "ai").functionCode());
        assertEquals(AiModelSelection.defaults("another-environment-model"), service.resolve("unregistered-plugin", "ai"));
    }
    @Test void failedSyncPreservesSnapshotAndEmptySuccessInvalidatesWithoutDeletingBindings() {
        var previous = service.sync();
        when(remote.listCallerModels()).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, service::sync);
        assertEquals(previous, service.getConfiguration());
        doReturn(List.of()).when(remote).listCallerModels();
        var empty = service.sync();
        assertTrue(empty.models().isEmpty()); assertTrue(empty.catalogVersion() > previous.catalogVersion());
        assertTrue(empty.functions().stream().allMatch(f -> f.status().equals("INVALID") && f.modelKey().equals("test-model")));
        var failure = assertThrows(ApiException.class, () -> service.requireUsable(service.resolve("undercover", "ai")));
        assertEquals("AI_MODEL_CONFIG_INVALID", failure.getCode());
    }
    @Test void revokedEffortIsRetainedAndUnknownCapabilitiesOnlyAllowDefaults() {
        var initial = service.sync();
        var saved = service.save(AiFunctionCode.UNDERCOVER, request(initial, ThinkingMode.ENABLED, "HIGH"));
        assertEquals(1, saved.version());
        var snapshot = service.resolve("undercover", "ai");
        when(remote.listCallerModels()).thenReturn(List.of(model(true, List.of("LOW"))));
        var reduced = service.sync();
        assertEquals("HIGH", row(reduced).thinkingEffort()); assertEquals("INVALID", row(reduced).status());
        assertThrows(ApiException.class, () -> service.requireUsable(snapshot));
        when(remote.listCallerModels()).thenReturn(List.of(model(false, List.of())));
        var unknown = service.sync();
        assertThrows(ApiException.class, () -> service.save(AiFunctionCode.UNDERCOVER, request(unknown, ThinkingMode.ENABLED, null)));
        assertThrows(ApiException.class, () -> service.save(AiFunctionCode.UNDERCOVER, request(unknown, ThinkingMode.DISABLED, null)));
        assertEquals("VALID", service.save(AiFunctionCode.UNDERCOVER, request(unknown, ThinkingMode.DEFAULT, null)).status());
    }
    @Test void staleCatalogAndConfigurationVersionsCannotOverwriteAndModesDoNotRetainEffort() {
        var initial = service.sync();
        assertThrows(ApiException.class, () -> service.save(AiFunctionCode.UNDERCOVER, request(initial, ThinkingMode.DEFAULT, "LOW")));
        assertThrows(ApiException.class, () -> service.save(AiFunctionCode.UNDERCOVER, request(initial, ThinkingMode.DISABLED, "LOW")));
        service.save(AiFunctionCode.UNDERCOVER, request(initial, ThinkingMode.ENABLED, "LOW"));
        assertEquals("AI_MODEL_CONFIG_CONFLICT", assertThrows(ApiException.class,
                () -> service.save(AiFunctionCode.UNDERCOVER, request(initial, ThinkingMode.DISABLED, null))).getCode());
        var fresh = service.getConfiguration(); service.sync();
        assertEquals("AI_MODEL_CONFIG_CONFLICT", assertThrows(ApiException.class,
                () -> service.save(AiFunctionCode.UNDERCOVER, request(fresh, ThinkingMode.DISABLED, null))).getCode());
        assertEquals("LOW", row(service.getConfiguration()).thinkingEffort());
    }
    @Test void concurrentSavesWithTheSameVersionHaveExactlyOneWinner() throws Exception {
        var initial = service.sync(); var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Future<String>> results = new ArrayList<>();
            for (String effort : List.of("LOW", "HIGH")) results.add(workers.submit(() -> {
                start.await();
                try { service.save(AiFunctionCode.UNDERCOVER, request(initial, ThinkingMode.ENABLED, effort)); return "SAVED"; }
                catch (ApiException failure) { return failure.getCode(); }
            }));
            start.countDown(); var statuses = new ArrayList<String>();
            for (var result : results) statuses.add(result.get(10, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(statuses, "SAVED"));
            assertEquals(1, Collections.frequency(statuses, "AI_MODEL_CONFIG_CONFLICT"));
        }
    }
    @Test void unmappedEnvironmentDefaultIsNeverReplacedByTheFirstAvailableModel() {
        properties.getAi().setDefaultModel("missing-model");
        var initial = service.sync();
        assertEquals("missing-model", row(initial).modelKey()); assertEquals("INVALID", row(initial).status());
        when(remote.listCallerModels()).thenReturn(List.of(model(true, List.of("LOW")), model(true, List.of("HIGH"))));
        assertEquals("AI_MODEL_SYNC_FAILED", assertThrows(ApiException.class, service::sync).getCode());
        assertEquals(initial, service.getConfiguration());
    }

    @Test void claimingAndReclaimingAQueuedJobPersistsTheOriginalSelectionAcrossAnAdminSave() {
        var initial = service.sync();
        var job = new com.aisocialgame.model.AiTurnJob();
        job.setId(UUID.randomUUID().toString()); job.setRoomId("model-config-room"); job.setInstanceId("instance");
        job.setActorId("actor"); job.setKind("SPEAK"); job.setTurnKey("turn"); job.setObservation(Map.of("gameId","undercover"));
        jobs.saveAndFlush(job);
        var first = coordinator.claim(job.getId()); assertNotNull(first);
        var persisted = jobs.findById(job.getId()).orElseThrow();
        var snapshot = persisted.getDiagnostics().get("modelSelection"); assertNotNull(snapshot);
        assertEquals(ThinkingMode.DEFAULT,AiModelSelection.fromMap(com.aisocialgame.engine.v2.RuleSupport.map(snapshot)).thinkingMode());
        service.save(AiFunctionCode.UNDERCOVER,request(initial,ThinkingMode.ENABLED,"HIGH"));
        persisted.setStatus("QUEUED"); jobs.saveAndFlush(persisted);
        assertNotNull(coordinator.claim(job.getId()));
        assertEquals(snapshot,jobs.findById(job.getId()).orElseThrow().getDiagnostics().get("modelSelection"));
        assertEquals(ThinkingMode.ENABLED,service.resolve("undercover","actor").thinkingMode());
    }
}
