package com.aisocialgame.controller.admin;

import com.aisocialgame.dto.AiChatResponse;
import com.aisocialgame.dto.AiModelView;
import com.aisocialgame.dto.PagedResponse;
import com.aisocialgame.dto.admin.AdminAiDecisionTraceView;
import com.aisocialgame.dto.admin.AdminAiPersonaMemoryView;
import com.aisocialgame.dto.admin.AdminAiTestChatRequest;
import com.aisocialgame.service.AdminAuthService;
import com.aisocialgame.service.AdminOpsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/ai")
public class AdminAiController {
    private final AdminAuthService adminAuthService;
    private final AdminOpsService adminOpsService;

    public AdminAiController(AdminAuthService adminAuthService, AdminOpsService adminOpsService) {
        this.adminAuthService = adminAuthService;
        this.adminOpsService = adminOpsService;
    }

    @GetMapping("/models")
    public ResponseEntity<List<AiModelView>> models(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        adminAuthService.requireAdmin(token);
        return ResponseEntity.ok(adminOpsService.listModels());
    }

    @PostMapping("/test-chat")
    public ResponseEntity<AiChatResponse> testChat(@Valid @RequestBody AdminAiTestChatRequest request,
                                                   @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        adminAuthService.requireAdmin(token);
        return ResponseEntity.ok(adminOpsService.testChat(
                request.getUserId(),
                request.getSessionId(),
                request.getModel(),
                request.getMessages()
        ));
    }

    @GetMapping("/decision-traces")
    public ResponseEntity<PagedResponse<AdminAiDecisionTraceView>> decisionTraces(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) String roomId,
            @RequestParam(required = false) String gameId,
            @RequestParam(required = false) String personaId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Boolean fallback,
            @RequestParam(required = false) String qualityFlag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        adminAuthService.requireAdmin(token);
        return ResponseEntity.ok(adminOpsService.decisionTraces(roomId, gameId, personaId, action, fallback, qualityFlag, page, size));
    }

    @GetMapping("/persona-memories")
    public ResponseEntity<List<AdminAiPersonaMemoryView>> personaMemories(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) String personaId) {
        adminAuthService.requireAdmin(token);
        return ResponseEntity.ok(adminOpsService.personaMemories(personaId));
    }

    @PostMapping("/persona-memories/{id}/reset")
    public ResponseEntity<Void> resetPersonaMemory(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                                   @PathVariable Long id) {
        adminAuthService.requireAdmin(token);
        adminOpsService.resetPersonaMemory(id);
        return ResponseEntity.noContent().build();
    }
}
