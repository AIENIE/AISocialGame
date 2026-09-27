package com.aisocialgame;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.dto.AuthResponse;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.integration.grpc.client.BillingGrpcClient;
import com.aisocialgame.integration.grpc.client.UserGrpcClient;
import com.aisocialgame.integration.grpc.dto.BalanceSnapshot;
import com.aisocialgame.integration.grpc.dto.ExternalUserProfile;
import com.aisocialgame.model.User;
import com.aisocialgame.service.AuthService;
import com.aisocialgame.service.BalanceService;
import com.aisocialgame.service.ProjectCreditService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;

@SpringBootTest(classes = AiSocialGameApplication.class)
@ActiveProfiles("test")
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private AppProperties appProperties;

    @MockitoBean
    private UserGrpcClient userGrpcClient;

    @MockitoBean
    private BalanceService balanceService;

    @MockitoBean
    private BillingGrpcClient billingGrpcClient;

    @MockitoBean
    private ProjectCreditService projectCreditService;

    @BeforeEach
    void resetSsoPaths() {
        appProperties.getSso().setLoginPath("/sso/login");
        appProperties.getSso().setRegisterPath("/register");
        appProperties.getSso().setUserServiceBaseUrl("https://userservice.test.local");
    }

    @Test
    void ssoCallbackAndAuthenticateShouldSucceed() {
        ExternalUserProfile profile = new ExternalUserProfile(
                2001L,
                "tester01",
                "tester01@example.com",
                "https://avatar.example/u1.png",
                true,
                null,
                Instant.now()
        );
        Mockito.when(userGrpcClient.validateSession(Mockito.eq(2001L), Mockito.anyString()))
                .thenReturn(profile);
        Mockito.when(balanceService.getUserBalance(Mockito.any(User.class)))
                .thenReturn(BalanceSnapshot.empty());

        AuthResponse response = authService.ssoCallback(2001L, "测试用户", "session-1", "access-1");
        Assertions.assertNotNull(response.getToken());
        Assertions.assertEquals(2001L, response.getUser().getExternalUserId());

        User authenticated = authService.authenticate(response.getToken());
        Assertions.assertNotNull(authenticated);
        Assertions.assertEquals("tester01", authenticated.getUsername());
    }

    @Test
    void ssoCallbackShouldFailWhenSessionInvalid() {
        Mockito.when(userGrpcClient.validateSession(Mockito.eq(3001L), Mockito.anyString()))
                .thenReturn(null);

        ApiException ex = Assertions.assertThrows(ApiException.class,
                () -> authService.ssoCallback(3001L, "tester", "expired-session", "token"));
        Assertions.assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void buildSsoLoginRedirectUrlShouldContainRedirectAndState() {
        String redirectUrl = authService.buildSsoLoginRedirectUrl("state_token_123456");
        Assertions.assertTrue(redirectUrl.startsWith("https://userservice.test.local/sso/login"));
        Assertions.assertTrue(redirectUrl.contains("redirect="));
        Assertions.assertTrue(redirectUrl.contains("state=state_token_123456"));
    }

    @Test
    void buildSsoRegisterRedirectUrlShouldContainRedirectAndState() {
        String redirectUrl = authService.buildSsoRegisterRedirectUrl("state_token_abcdef");
        Assertions.assertTrue(redirectUrl.startsWith("https://userservice.test.local/register"));
        Assertions.assertTrue(redirectUrl.contains("redirect="));
        Assertions.assertTrue(redirectUrl.contains("state=state_token_abcdef"));
    }

    @Test
    void buildSsoRedirectUrlShouldFailWhenStateInvalid() {
        ApiException ex = Assertions.assertThrows(ApiException.class,
                () -> authService.buildSsoLoginRedirectUrl("bad"));
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void buildSsoRedirectUrlShouldUseConfiguredPaths() {
        appProperties.getSso().setLoginPath("custom-login");
        appProperties.getSso().setRegisterPath("/custom/register");

        String loginRedirectUrl = authService.buildSsoLoginRedirectUrl("state_token_custom_1");
        String registerRedirectUrl = authService.buildSsoRegisterRedirectUrl("state_token_custom_2");

        Assertions.assertTrue(loginRedirectUrl.startsWith("https://userservice.test.local/custom-login"));
        Assertions.assertTrue(registerRedirectUrl.startsWith("https://userservice.test.local/custom/register"));
    }

    @Test
    void independentSessionsAndLogoutStayBoundToOriginalSso() {
        var profile = new ExternalUserProfile(2002L, "sessions", "sessions@example.invalid", "", true, null, Instant.now());
        Mockito.when(userGrpcClient.validateSession(Mockito.eq(2002L), Mockito.anyString())).thenReturn(profile);
        Mockito.when(balanceService.getUserBalance(Mockito.any())).thenReturn(BalanceSnapshot.empty());
        String first = authService.ssoCallback(2002L, "sessions", "device-first", "access").getToken();
        String second = authService.ssoCallback(2002L, "sessions", "device-second", "access").getToken();
        Assertions.assertEquals("device-first", authService.authenticate(first).getSessionId());
        Assertions.assertEquals("device-second", authService.authenticate(second).getSessionId());
        authService.logout(first);
        authService.logout(first);
        Assertions.assertNull(authService.authenticate(first));
        Assertions.assertNotNull(authService.authenticate(second));
        Assertions.assertNull(authService.authenticate(java.util.UUID.randomUUID().toString()));
    }

    @Test
    void revocationDuringUpstreamValidationRejectsAuthentication() {
        var profile = new ExternalUserProfile(2003L, "racing", "racing@example.invalid", "", true, null, Instant.now());
        Mockito.when(userGrpcClient.validateSession(Mockito.eq(2003L), Mockito.anyString())).thenReturn(profile);
        Mockito.when(balanceService.getUserBalance(Mockito.any())).thenReturn(BalanceSnapshot.empty());
        String token = authService.ssoCallback(2003L, "racing", "race-session", "access").getToken();
        Mockito.when(userGrpcClient.validateSession(2003L, "race-session")).thenAnswer(ignored -> { authService.logout(token); return profile; });
        Assertions.assertNull(authService.authenticate(token));
    }

    @Test
    void untrustedLoopbackCertificateIsRejectedBeforeSendingSsoCode(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var keyStoreFile = directory.resolve("server.p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var keytool = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", keyStoreFile.toString(),
                "-storepass", "test-only-password", "-keypass", "test-only-password", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        Assertions.assertTrue(keytool.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        Assertions.assertEquals(0, keytool.exitValue());
        var keyStore = java.security.KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(keyStoreFile)) { keyStore.load(input, "test-only-password".toCharArray()); }
        var keys = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, "test-only-password".toCharArray());
        var tls = javax.net.ssl.SSLContext.getInstance("TLS"); tls.init(keys.getKeyManagers(), null, null);
        var server = com.sun.net.httpserver.HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls));
        var received = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/sso/token", exchange -> { received.incrementAndGet(); exchange.sendResponseHeaders(401, -1); exchange.close(); });
        server.start();
        try {
            appProperties.getSso().setUserServiceBaseUrl("https://localhost:" + server.getAddress().getPort());
            ApiException error = Assertions.assertThrows(ApiException.class, () -> authService.ssoCallback("sensitive-code", "http://localhost/callback"));
            Assertions.assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
            Assertions.assertEquals(0, received.get(), "SSO authorization code must never reach an untrusted TLS peer");
        } finally { server.stop(0); }
    }
}
