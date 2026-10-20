package com.aisocialgame.controller.admin;

import com.aisocialgame.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    private final AdminAuthService auth;

    public AdminAuthController(AdminAuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/policy")
    public ResponseEntity<?> policy() {
        return noStore(auth.policy());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return loginResponse(auth.login(body.username(), body.password(), source(request)));
    }

    @PostMapping("/recovery/challenge")
    public ResponseEntity<?> recoveryChallenge(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return loginResponse(auth.recoveryLogin(body.username(), body.password(), source(request)));
    }

    @PostMapping("/enrollment/start")
    public ResponseEntity<?> enrollmentStart(@Valid @RequestBody ChallengeRequest body,
                                             HttpServletRequest request) {
        return noStore(auth.startEnrollment(body.challengeId(), source(request)));
    }

    @PostMapping("/enrollment/confirm")
    public ResponseEntity<?> enrollmentConfirm(@Valid @RequestBody CodeRequest body,
                                               HttpServletRequest request) {
        return loginResponse(auth.confirmEnrollment(body.challengeId(), body.code(), source(request)));
    }

    @PostMapping("/totp/verify")
    public ResponseEntity<?> verifyTotp(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        return loginResponse(auth.verifyTotp(body.challengeId(), body.code(), source(request)));
    }

    @PostMapping("/recovery/verify")
    public ResponseEntity<?> verifyRecovery(@Valid @RequestBody RecoveryRequest body, HttpServletRequest request) {
        return loginResponse(auth.verifyRecovery(body.challengeId(), body.recoveryCode(), source(request)));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        return noStore(auth.me(auth.currentPrincipal(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        auth.logout(auth.currentPrincipal(request), source(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .body(Map.of("success", true));
    }

    @PostMapping("/rebind/start")
    public ResponseEntity<?> rebindStart(HttpServletRequest request) {
        return noStore(auth.startRebind(auth.currentPrincipal(request), source(request)));
    }

    @PostMapping("/rebind/confirm")
    public ResponseEntity<?> rebindConfirm(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        return loginResponse(auth.confirmRebind(auth.currentPrincipal(request), body.challengeId(), body.code(),
                source(request)));
    }

    @PostMapping({"/recovery-codes/get", "/recovery-codes/regenerate"})
    public ResponseEntity<?> regenerate(@Valid @RequestBody TotpRequest body, HttpServletRequest request) {
        return noStore(auth.getRecoveryCodes(auth.currentPrincipal(request), body.code(), source(request)));
    }

    @PostMapping("/operation/verify")
    public ResponseEntity<?> verifyOperation(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        return noStore(auth.verifyOperation(auth.currentPrincipal(request), body.challengeId(), body.code(),
                source(request)));
    }

    private ResponseEntity<?> loginResponse(AdminAuthService.LoginResult result) {
        if (!"AUTHENTICATED".equals(result.state())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(result);
        }
        Duration maxAge = Duration.between(java.time.Instant.now(), result.expiresAt());
        ResponseCookie cookie = ResponseCookie.from(AdminAuthService.COOKIE_NAME, result.sessionToken())
                .httpOnly(true).secure(auth.secureCookie()).sameSite("Strict").path("/")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build();
        AuthenticatedResponse response = new AuthenticatedResponse(result.state(), result.username(),
                result.displayName(), result.sessionScope(), result.expiresAt(), result.recoveryCodes());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie.toString()).body(response);
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(AdminAuthService.COOKIE_NAME, "")
                .httpOnly(true).secure(auth.secureCookie()).sameSite("Strict").path("/").maxAge(Duration.ZERO).build();
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private String source(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record ChallengeRequest(@NotBlank String challengeId) {
    }

    public record CodeRequest(@NotBlank String challengeId, @NotBlank String code) {
    }

    public record RecoveryRequest(@NotBlank String challengeId, @NotBlank String recoveryCode) {
    }

    public record TotpRequest(String code) {
    }

    public record AuthenticatedResponse(String state, String username, String displayName, String sessionScope,
                                        java.time.Instant expiresAt, java.util.List<String> recoveryCodes) {
    }
}
