package com.aisocialgame.controller.admin;

import com.aisocialgame.dto.admin.AdminIntegrationStatusResponse;
import com.aisocialgame.service.AdminOpsService;
import com.aisocialgame.web.CurrentAdmin;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/integration")
public class AdminIntegrationController {
    private final AdminOpsService adminOpsService;

    public AdminIntegrationController(AdminOpsService adminOpsService) {
        this.adminOpsService = adminOpsService;
    }

    @GetMapping("/services")
    public ResponseEntity<AdminIntegrationStatusResponse> services(@CurrentAdmin String operator) {
        return ResponseEntity.ok(adminOpsService.integrationStatus());
    }
}
