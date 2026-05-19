package com.aisocialgame.controller.admin;

import com.aisocialgame.dto.admin.AdminAuthResponse;
import com.aisocialgame.dto.admin.AdminLoginRequest;
import com.aisocialgame.service.AdminAuthService;
import com.aisocialgame.web.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public ResponseEntity<AdminAuthResponse> login(@Valid @RequestBody AdminLoginRequest request) {
        String token = adminAuthService.login(request.getUsername(), request.getPassword());
        return ResponseEntity.ok(new AdminAuthResponse(token, request.getUsername(), adminAuthService.getDisplayName()));
    }

    @GetMapping("/me")
    public ResponseEntity<AdminAuthResponse> me(@CurrentAdmin String username,
                                                @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        return ResponseEntity.ok(new AdminAuthResponse(token, username, adminAuthService.getDisplayName()));
    }
}
