package com.aisocialgame.controller.admin;

import com.aisocialgame.dto.admin.*;
import com.aisocialgame.service.ai.config.*;
import com.aisocialgame.web.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/ai/model-config")
public class AdminAiModelConfigController {
    private final AiModelConfigService service;
    public AdminAiModelConfigController(AiModelConfigService service) { this.service = service; }
    @GetMapping public AdminAiModelConfigResponse get(@CurrentAdmin String operator) { return service.getConfiguration(); }
    @PostMapping("/sync") public AdminAiModelConfigResponse sync(@CurrentAdmin String operator) { return service.sync(); }
    @PutMapping("/functions/{functionCode}") public AdminAiModelConfigResponse.FunctionView save(@CurrentAdmin String operator,
            @PathVariable String functionCode, @Valid @RequestBody AdminAiModelConfigRequest request) {
        AiFunctionCode code;
        try { code = AiFunctionCode.valueOf(functionCode); }
        catch (IllegalArgumentException failure) {
            throw new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "未知 AI 功能", "AI_MODEL_CONFIG_INVALID", java.util.Map.of());
        }
        return service.save(code, request);
    }
}
