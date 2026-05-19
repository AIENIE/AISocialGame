package com.aisocialgame.controller.admin;

import com.aisocialgame.dto.admin.AdminDashboardSummaryResponse;
import com.aisocialgame.service.AdminOpsService;
import com.aisocialgame.web.CurrentAdmin;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {
    private final AdminOpsService adminOpsService;

    public AdminDashboardController(AdminOpsService adminOpsService) {
        this.adminOpsService = adminOpsService;
    }

    @GetMapping("/summary")
    public ResponseEntity<AdminDashboardSummaryResponse> summary(@CurrentAdmin String operator) {
        return ResponseEntity.ok(adminOpsService.dashboardSummary());
    }
}
