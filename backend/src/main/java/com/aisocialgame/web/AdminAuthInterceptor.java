package com.aisocialgame.web;

import com.aisocialgame.adminauth.AdminAuthPolicy;
import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {
    public static final String OPERATION_PROOF_HEADER = "X-Admin-Operation-Proof";
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/admin/auth/policy",
            "/api/admin/auth/login",
            "/api/admin/auth/enrollment/start",
            "/api/admin/auth/enrollment/confirm",
            "/api/admin/auth/totp/verify",
            "/api/admin/auth/recovery/verify",
            "/api/admin/auth/recovery/challenge"
    );

    private final AdminAuthService auth;
    private final AdminAuthPolicy policy;
    private final AppProperties properties;

    public AdminAuthInterceptor(AdminAuthService auth, AdminAuthPolicy policy, AppProperties properties) {
        this.auth = auth;
        this.policy = policy;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/admin/")) {
            return true;
        }
        if (path.startsWith("/api/admin/auth/")) response.setHeader("Cache-Control", "no-store");
        if (isUnsafe(request.getMethod())) {
            requireTrustedOrigin(request);
        }
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        AdminAuthService.AdminPrincipal principal = auth.authenticate(request);
        request.setAttribute(AdminAuthService.PRINCIPAL_ATTRIBUTE, principal);
        if (AdminAuthService.RECOVERY_SCOPE.equals(principal.scope()) && !isRecoveryPath(path)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "恢复会话仅允许重绑动态验证器");
        }
        if (policy.totpRequired() && isHighRisk(request.getMethod(), path)) {
            response.setHeader("Cache-Control", "no-store");
            requireOperationProof(request, principal);
        }
        return true;
    }

    private void requireOperationProof(HttpServletRequest request, AdminAuthService.AdminPrincipal principal) {
        String action = request.getMethod() + ":" + request.getRequestURI();
        Object boundTarget = request.getAttribute(AdminProofTargetFilter.TARGET_ATTRIBUTE);
        if (!(boundTarget instanceof String target) || target.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "管理员操作请求未完成安全绑定", "ADMIN_OPERATION_BINDING_UNAVAILABLE", Map.of());
        }
        String proof = request.getHeader(OPERATION_PROOF_HEADER);
        if (proof != null && auth.consumeOperationProof(principal, proof, action, target)) {
            return;
        }
        AdminAuthService.OperationChallenge challenge = auth.createOperationChallenge(principal, action, target,
                request.getRemoteAddr());
        throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "需要管理员动态验证码二次确认",
                "ADMIN_OPERATION_PROOF_REQUIRED", Map.of(
                "challengeId", challenge.challengeId(),
                "expiresAt", challenge.expiresAt().toString(),
                "proofTtlSeconds", 60));
    }

    private void requireTrustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || !properties.getCors().getAllowedOrigins().contains(origin)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "管理员请求来源不受信任", "ADMIN_ORIGIN_REJECTED", Map.of());
        }
    }

    private boolean isHighRisk(String method, String path) {
        return isUnsafe(method) && !path.startsWith("/api/admin/auth/");
    }

    private boolean isUnsafe(String method) {
        return !HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method)
                && !HttpMethod.OPTIONS.matches(method);
    }

    private boolean isRecoveryPath(String path) {
        return path.equals("/api/admin/auth/me") || path.equals("/api/admin/auth/logout")
                || path.startsWith("/api/admin/auth/rebind/");
    }
}
