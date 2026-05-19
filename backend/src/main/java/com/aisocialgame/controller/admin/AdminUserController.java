package com.aisocialgame.controller.admin;

import com.aisocialgame.dto.admin.AdminBanRequest;
import com.aisocialgame.dto.admin.AdminUnbanRequest;
import com.aisocialgame.dto.admin.AdminUserView;
import com.aisocialgame.service.AdminOpsService;
import com.aisocialgame.web.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final AdminOpsService adminOpsService;

    public AdminUserController(AdminOpsService adminOpsService) {
        this.adminOpsService = adminOpsService;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<AdminUserView> getUser(@PathVariable long userId,
                                                 @CurrentAdmin String operator) {
        return ResponseEntity.ok(adminOpsService.getUser(userId));
    }

    @PostMapping("/{userId}/ban")
    public ResponseEntity<AdminUserView> ban(@PathVariable long userId,
                                             @Valid @RequestBody AdminBanRequest request,
                                             @CurrentAdmin String operator) {
        return ResponseEntity.ok(adminOpsService.banUser(userId, request.getReason(), request.isPermanent(), request.getExpiresAt()));
    }

    @PostMapping("/{userId}/unban")
    public ResponseEntity<AdminUserView> unban(@PathVariable long userId,
                                               @Valid @RequestBody AdminUnbanRequest request,
                                               @CurrentAdmin String operator) {
        return ResponseEntity.ok(adminOpsService.unbanUser(userId, request.getReason()));
    }
}
