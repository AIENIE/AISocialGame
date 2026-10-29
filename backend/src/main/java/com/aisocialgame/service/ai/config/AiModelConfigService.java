package com.aisocialgame.service.ai.config;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.dto.admin.AdminAiModelConfigRequest;
import com.aisocialgame.dto.admin.AdminAiModelConfigResponse;
import com.aisocialgame.dto.admin.AdminAiModelConfigResponse.FunctionView;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.AiModelCatalogClient;
import com.aisocialgame.integration.grpc.dto.AiModelOptionDto;
import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.ai.config.AiModelSelection.ThinkingMode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class AiModelConfigService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AiModelCatalogRepository catalogs;
    private final AiFunctionModelConfigRepository configs;
    private final AiModelCatalogClient client;
    private final AppProperties properties;
    private final boolean budgetEnabled;
    private final int maxOutput;

    public AiModelConfigService(AiModelCatalogRepository catalogs, AiFunctionModelConfigRepository configs,
            AiModelCatalogClient client, AppProperties properties, @Value("${app.ai.budget-enabled:false}") boolean budgetEnabled,
            @Value("${app.ai.budget-max-output-tokens:8192}") int maxOutput) {
        this.catalogs = catalogs; this.configs = configs; this.client = client; this.properties = properties;
        this.budgetEnabled = budgetEnabled; this.maxOutput = maxOutput;
    }

    public AdminAiModelConfigResponse getConfiguration() {
        return view(catalogs.findById(1L).orElseGet(AiModelCatalog::new));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminAiModelConfigResponse sync() {
        var models = client.listCallerModels();
        var keys = new HashSet<String>();
        for (var model : models) {
            if (model.modelKey() == null || model.modelKey().isBlank() || model.modelKey().length() > 128 || !keys.add(model.modelKey()))
                throw error(HttpStatus.BAD_GATEWAY, "AI_MODEL_SYNC_FAILED", "AI 服务返回了无效的模型目录");
        }
        var catalog = lockCatalog();
        boolean first = catalog.syncedAt == null;
        try { catalog.modelsJson = JSON.writeValueAsString(models); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
        catalog.syncedAt = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        catalogs.saveAndFlush(catalog);
        if (first) {
            String previous = environmentModel();
            String modelKey = models.stream().filter(m -> previous.equals(m.modelKey()) || previous.equals(Long.toString(m.id())))
                    .map(AiModelOptionDto::modelKey).findFirst().orElse(previous.isBlank() ? null : previous);
            for (var code : AiFunctionCode.values()) {
                if (!configs.existsById(code.name())) {
                    var config = new AiFunctionModelConfig(); config.functionCode = code.name(); config.modelKey = modelKey;
                    configs.save(config);
                }
            }
            configs.flush();
        }
        return view(catalog);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FunctionView save(AiFunctionCode code, AdminAiModelConfigRequest request) {
        var catalog = lockCatalog();
        if (catalog.syncedAt == null) throw error(HttpStatus.CONFLICT, "AI_MODEL_SYNC_REQUIRED", "请先同步模型目录");
        var config = configs.findById(code.name()).orElseThrow(() -> error(HttpStatus.CONFLICT, "AI_MODEL_CONFIG_CONFLICT", "配置已变化，请重新读取"));
        if (!Objects.equals(catalog.version, request.catalogVersion()) || !Objects.equals(config.version, request.version()))
            throw error(HttpStatus.CONFLICT, "AI_MODEL_CONFIG_CONFLICT", "模型目录或配置已变化，请重新读取后保存");
        var selection = new AiModelSelection(code, request.modelKey(), request.thinkingMode(), request.thinkingEffort(), request.version());
        String problem = problem(selection, models(catalog));
        if (problem != null) throw error(HttpStatus.BAD_REQUEST, "AI_MODEL_CONFIG_INVALID", problem);
        config.modelKey = selection.modelKey(); config.thinkingMode = selection.thinkingMode(); config.thinkingEffort = selection.thinkingEffort();
        config.updatedAt = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); configs.saveAndFlush(config);
        return functionView(code, catalog, config);
    }

    public AiModelSelection resolve(String gameId, String actorId) {
        var code = AiFunctionCode.forGame(gameId, actorId);
        if (code == null) return AiModelSelection.defaults(environmentModel());
        var catalog = catalogs.findById(1L).orElseGet(AiModelCatalog::new);
        var config = catalog.syncedAt == null ? null : configs.findById(code.name()).orElse(null);
        if (config != null) return selection(code, config);
        return catalog.syncedAt == null ? AiModelSelection.environmentDefault(code, environmentModel())
                : new AiModelSelection(code, "", ThinkingMode.DEFAULT, null, 0);
    }

    /** Checked again immediately before dispatch, since an administrator may have synced while a budget waited. */
    public void requireUsable(AiModelSelection selection) {
        var catalog = catalogs.findById(1L).orElseGet(AiModelCatalog::new);
        if (catalog.syncedAt == null && selection.thinkingMode() == ThinkingMode.DEFAULT && selection.thinkingEffort() == null) return;
        var available = models(catalog);
        var checked = selection;
        // A turn started before the first sync may contain the legacy numeric ID. Validate the same model
        // without changing the original request bytes; persisted bindings always use stable keys.
        if (selection.configVersion() == AiModelSelection.ENVIRONMENT_VERSION && available.stream().noneMatch(m -> selection.modelKey().equals(m.modelKey()))) {
            var legacyModel = available.stream().filter(m -> selection.modelKey().equals(Long.toString(m.id()))).findFirst();
            if (legacyModel.isPresent()) checked = new AiModelSelection(selection.functionCode(), legacyModel.get().modelKey(), selection.thinkingMode(), selection.thinkingEffort(), selection.configVersion());
        }
        String problem = problem(checked, available);
        if (problem != null) throw error(HttpStatus.SERVICE_UNAVAILABLE, "AI_MODEL_CONFIG_INVALID", "模型配置待调整：" + problem);
    }

    private AiModelCatalog lockCatalog() {
        return catalogs.lockSingleton().orElseThrow(() -> error(HttpStatus.SERVICE_UNAVAILABLE, "AI_MODEL_CONFIG_NOT_INITIALIZED", "模型配置数据尚未初始化，请先执行数据库增量"));
    }
    private AdminAiModelConfigResponse view(AiModelCatalog catalog) {
        var byCode = new HashMap<String, AiFunctionModelConfig>();
        configs.findAll().forEach(config -> byCode.put(config.functionCode, config));
        return new AdminAiModelConfigResponse(catalog.version == null ? 0 : catalog.version, catalog.syncedAt, models(catalog),
                Arrays.stream(AiFunctionCode.values()).map(code -> functionView(code, catalog, byCode.get(code.name()))).toList());
    }
    private FunctionView functionView(AiFunctionCode code, AiModelCatalog catalog, AiFunctionModelConfig config) {
        var selected = config == null ? new AiModelSelection(code, catalog.syncedAt == null ? environmentModel() : "", ThinkingMode.DEFAULT, null, 0) : selection(code, config);
        String problem = catalog.syncedAt == null ? null : problem(selected, models(catalog));
        boolean enabled = (code == AiFunctionCode.UNDERCOVER || code == AiFunctionCode.WEREWOLF) && budgetEnabled && maxOutput > 0;
        return new FunctionView(code, code.label, selected.modelKey(), selected.thinkingMode(), selected.thinkingEffort(), selected.configVersion(),
                catalog.syncedAt == null ? "NOT_SYNCED" : problem == null ? "VALID" : "INVALID",
                catalog.syncedAt == null ? "尚未同步，沿用环境默认模型" : problem == null ? "配置有效" : problem,
                enabled, enabled ? "流式调用与实耗结算已启用" : "当前调用配置未启用预算凭据");
    }
    private AiModelSelection selection(AiFunctionCode code, AiFunctionModelConfig config) {
        return new AiModelSelection(code, config.modelKey, config.thinkingMode, config.thinkingEffort, config.version == null ? 0 : config.version);
    }
    private List<AiModelOptionDto> models(AiModelCatalog catalog) {
        try { return JSON.readValue(catalog.modelsJson, new TypeReference<List<AiModelOptionDto>>() { }); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Stored model catalog is unreadable", failure); }
    }
    private String environmentModel() { return properties.getAi().getDefaultModel() == null ? "" : properties.getAi().getDefaultModel().strip(); }
    private static String problem(AiModelSelection selected, List<AiModelOptionDto> models) {
        if (selected.modelKey().isBlank()) return "尚未配置模型";
        var model = models.stream().filter(m -> selected.modelKey().equals(m.modelKey())).findFirst().orElse(null);
        if (model == null) return "模型已下架或当前调用方不可见";
        if (selected.thinkingMode() != ThinkingMode.ENABLED && selected.thinkingEffort() != null) return "仅开启思考时可以配置强度";
        if (selected.thinkingMode() == ThinkingMode.DEFAULT) return null;
        var thinking = model.thinking();
        if (thinking == null || !thinking.declared()) return "思考能力未知，只能跟随服务默认";
        if (selected.thinkingMode() == ThinkingMode.DISABLED) return thinking.supportsDisable() ? null : "该模型不支持关闭思考";
        if (!thinking.supportsEnable()) return "该模型不支持开启思考";
        if (selected.thinkingEffort() != null && thinking.options().stream().noneMatch(o -> selected.thinkingEffort().equals(o.effort())))
            return "原思考强度已不受支持，请重新选择";
        return null;
    }
    private static ApiException error(HttpStatus status, String code, String message) { return new ApiException(status, message, code, Map.of()); }
}
