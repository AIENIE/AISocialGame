package com.aisocialgame.controller;

import com.aisocialgame.dto.AuthResponse;
import com.aisocialgame.dto.AuthUserView;
import com.aisocialgame.dto.SsoCallbackRequest;
import com.aisocialgame.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/sso/login")
    public ResponseEntity<Void> redirectToSsoLogin(@RequestParam("state") String state) {
        String redirectUrl = authService.buildSsoLoginRedirectUrl(state);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .build();
    }

    @GetMapping("/sso/register")
    public ResponseEntity<Void> redirectToSsoRegister(@RequestParam("state") String state) {
        String redirectUrl = authService.buildSsoRegisterRedirectUrl(state);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .build();
    }

    @PostMapping("/sso-callback")
    public ResponseEntity<AuthResponse> ssoCallback(@Valid @RequestBody SsoCallbackRequest request) {
        AuthResponse response = authService.ssoCallback(request.getCode(), request.getRedirect());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<AuthUserView> me(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ResponseEntity.ok(authService.currentUserView(token));
    }
}
