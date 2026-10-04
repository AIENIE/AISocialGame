package com.aisocialgame.web;

import com.aisocialgame.adminauth.AdminAuthPolicy;
import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuthInterceptorTest {
    private static final String ORIGIN = "https://localsocialgame.testhut.top";

    @Test
    void totpModeRequiresOneUseProofForEveryAdminWrite() {
        AdminAuthService auth = mock(AdminAuthService.class);
        AdminAuthInterceptor interceptor = interceptor(auth, new AdminAuthPolicy("local", "totp"));
        HttpServletRequest request = request("POST", "/api/admin/billing/adjust");
        AdminAuthService.AdminPrincipal principal = principal("totp");
        when(auth.authenticate(request)).thenReturn(principal);
        when(auth.createOperationChallenge(principal, "POST:/api/admin/billing/adjust",
                "/api/admin/billing/adjust#bound", "127.0.0.1"))
                .thenReturn(new AdminAuthService.OperationChallenge("challenge", Instant.now().plusSeconds(120)));

        ApiException error = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));

        assertEquals(HttpStatus.PRECONDITION_REQUIRED, error.getStatus());
        assertEquals("ADMIN_OPERATION_PROOF_REQUIRED", error.getCode());
    }

    @Test
    void validBoundProofAllowsTheOriginalWrite() {
        AdminAuthService auth = mock(AdminAuthService.class);
        AdminAuthInterceptor interceptor = interceptor(auth, new AdminAuthPolicy("local", "totp"));
        HttpServletRequest request = request("DELETE", "/api/admin/safety/controls/7");
        when(request.getHeader(AdminAuthInterceptor.OPERATION_PROOF_HEADER)).thenReturn("proof");
        AdminAuthService.AdminPrincipal principal = principal("totp");
        when(auth.authenticate(request)).thenReturn(principal);
        when(auth.consumeOperationProof(principal, "proof", "DELETE:/api/admin/safety/controls/7",
                "/api/admin/safety/controls/7#bound")).thenReturn(true);

        assertTrue(interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));
        verify(request).setAttribute(AdminAuthService.PRINCIPAL_ATTRIBUTE, principal);
    }

    @Test
    void localPasswordModeBypassesStepUpButStillRequiresTrustedOrigin() {
        AdminAuthService auth = mock(AdminAuthService.class);
        AdminAuthInterceptor interceptor = interceptor(auth, new AdminAuthPolicy("local", "password"));
        HttpServletRequest request = request("POST", "/api/admin/billing/migrate-all");
        when(auth.authenticate(request)).thenReturn(principal("password"));

        assertTrue(interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void untrustedOriginIsRejectedBeforeAuthentication() {
        AdminAuthService auth = mock(AdminAuthService.class);
        AdminAuthInterceptor interceptor = interceptor(auth, new AdminAuthPolicy("local", "totp"));
        HttpServletRequest request = request("POST", "/api/admin/auth/login");
        when(request.getHeader("Origin")).thenReturn("https://attacker.invalid");

        ApiException error = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void recoverySessionCannotFetchCodesAndAuthenticationErrorsAreNeverCached() {
        var auth=mock(AdminAuthService.class);
        var interceptor=interceptor(auth,new AdminAuthPolicy("local","totp"));
        var request=request("POST","/api/admin/auth/recovery-codes/get");
        var principal=new AdminAuthService.AdminPrincipal("admin","recovery","RECOVERY_REBIND_ONLY",AdminAuthService.AUTHORITY,"totp",Instant.now(),null,Instant.now().plusSeconds(600));
        when(auth.authenticate(request)).thenReturn(principal);
        var response=mock(HttpServletResponse.class);
        assertThrows(ApiException.class, () -> interceptor.preHandle(request,response,new Object()));
        verify(response).setHeader("Cache-Control","no-store");
    }

    private AdminAuthInterceptor interceptor(AdminAuthService auth, AdminAuthPolicy policy) {
        AppProperties properties = new AppProperties();
        properties.getCors().setAllowedOrigins(List.of(ORIGIN));
        return new AdminAuthInterceptor(auth, policy, properties);
    }

    private HttpServletRequest request(String method, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getHeader("Origin")).thenReturn(ORIGIN);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getAttribute(AdminProofTargetFilter.TARGET_ATTRIBUTE)).thenReturn(path + "#bound");
        return request;
    }

    private AdminAuthService.AdminPrincipal principal(String mode) {
        return new AdminAuthService.AdminPrincipal("admin", "session", "FULL", AdminAuthService.AUTHORITY,
                mode, Instant.now(), "totp".equals(mode) ? Instant.now() : null, Instant.now().plusSeconds(600));
    }
}
