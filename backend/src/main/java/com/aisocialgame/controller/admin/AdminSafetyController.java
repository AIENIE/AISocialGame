package com.aisocialgame.controller.admin;

import com.aisocialgame.dto.PagedResponse;
import com.aisocialgame.dto.admin.AdminSafetyCloseRequest;
import com.aisocialgame.dto.admin.AdminSafetyControlRequest;
import com.aisocialgame.dto.admin.AdminSafetyControlView;
import com.aisocialgame.dto.admin.AdminSafetyEventView;
import com.aisocialgame.dto.admin.AdminSafetySummaryResponse;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.service.safety.AiSafetyService;
import com.aisocialgame.web.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/safety")
public class AdminSafetyController {
    private final AiSafetyService aiSafetyService;

    public AdminSafetyController(AiSafetyService aiSafetyService) {
        this.aiSafetyService = aiSafetyService;
    }

    @GetMapping("/summary")
    public ResponseEntity<AdminSafetySummaryResponse> summary(@CurrentAdmin String operator) {
        var summary = aiSafetyService.summary();
        return ResponseEntity.ok(new AdminSafetySummaryResponse(
                summary.openHighRiskEvents(),
                summary.blockedLast24h(),
                summary.costAnomaliesLast24h(),
                summary.activeControls()
        ));
    }

    @GetMapping("/events")
    public ResponseEntity<PagedResponse<AdminSafetyEventView>> events(
            @CurrentAdmin String operator,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String roomId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String personaId,
            @RequestParam(required = false) String modelKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = aiSafetyService.searchEvents(status, severity, source, roomId, userId, personaId, modelKey, page, size);
        return ResponseEntity.ok(new PagedResponse<>(
                result.getContent().stream().map(AdminSafetyEventView::new).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        ));
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<AdminSafetyEventView> event(@CurrentAdmin String operator, @PathVariable long id) {
        return ResponseEntity.ok(aiSafetyService.findEvent(id)
                .map(AdminSafetyEventView::new)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "安全事件不存在")));
    }

    @PostMapping("/events/{id}/ack")
    public ResponseEntity<AdminSafetyEventView> ack(@CurrentAdmin String operator, @PathVariable long id) {
        return ResponseEntity.ok(new AdminSafetyEventView(aiSafetyService.acknowledge(id, operator)));
    }

    @PostMapping("/events/{id}/close")
    public ResponseEntity<AdminSafetyEventView> close(@CurrentAdmin String operator,
                                                      @PathVariable long id,
                                                      @RequestBody(required = false) AdminSafetyCloseRequest request) {
        return ResponseEntity.ok(new AdminSafetyEventView(aiSafetyService.close(id, operator, request == null ? "" : request.getReason())));
    }

    @GetMapping("/controls")
    public ResponseEntity<java.util.List<AdminSafetyControlView>> controls(@CurrentAdmin String operator) {
        return ResponseEntity.ok(aiSafetyService.activeControls().stream().map(AdminSafetyControlView::new).toList());
    }

    @PostMapping("/controls")
    public ResponseEntity<AdminSafetyControlView> createControl(@CurrentAdmin String operator,
                                                                @Valid @RequestBody AdminSafetyControlRequest request) {
        return ResponseEntity.ok(new AdminSafetyControlView(aiSafetyService.createControl(
                request.getScope(),
                request.getTargetKey(),
                request.getAction(),
                request.getReason(),
                request.getExpiresAt(),
                operator
        )));
    }

    @DeleteMapping("/controls/{id}")
    public ResponseEntity<Void> disableControl(@CurrentAdmin String operator, @PathVariable long id) {
        aiSafetyService.disableControl(id);
        return ResponseEntity.noContent().build();
    }
}
