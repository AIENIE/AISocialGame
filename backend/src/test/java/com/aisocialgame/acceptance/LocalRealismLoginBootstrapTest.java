package com.aisocialgame.acceptance;

import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.dto.AuthResponse;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.service.AuthService;
import com.aisocialgame.config.PayServiceJwtStartupGuard;
import com.aisocialgame.integration.grpc.auth.BillingGrpcAuthClientInterceptor;
import fireflychat.billing.v1.BillingBalanceServiceGrpc;
import fireflychat.billing.v1.GetPublicBalanceRequest;
import com.aisocialgame.service.token.RedisTokenStore;
import com.aisocialgame.service.token.TokenStore;
import fireflychat.user.v1.LoginUserRequest;
import fireflychat.user.v1.LoginUserResponse;
import fireflychat.user.v1.UserAuthServiceGrpc;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Run only through scripts/windows/Prepare-GameRealismV2Accounts.ps1 after reviewing its private inputs.
 * Uses the real local MySQL/Redis and the normal LoginUser -> AuthService session/onboarding path.
 * It creates no HTTP listener, changes no authentication route, and makes no model calls.
 * Input properties: account.{1,2,3}.username/password/userId. Output contains secrets and stays private.
 */
@EnabledIfEnvironmentVariable(named = "AI_REALISM_LOGIN_BOOTSTRAP", matches = "1")
@SpringBootTest(classes = AiSocialGameApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"app.game.scheduler-enabled=false", "app.demo-seed.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("local")
@Import(LocalRealismLoginBootstrapTest.LoginConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalRealismLoginBootstrapTest {
    @Autowired private LoginGateway login;
    @Autowired private AuthService auth;
    @Autowired private TokenStore tokens;
    @Autowired private org.springframework.core.env.ConfigurableEnvironment environment;

    @Test
    void logInThreeExistingNormalUsersAndWritePrivateApplicationSessions() throws Exception {
        assertEquals("local", System.getenv("ENV"), "Bootstrap requires the canonical local adapter");
        assertEquals("windows-local", System.getenv("AIENIE_RUNTIME_PLANE"));
        assertInstanceOf(RedisTokenStore.class, tokens, "Sessions must survive this test process");
        // Test-classpath runtime markers must not relax the real service transport/auth checks.
        PayServiceJwtStartupGuard.validateBeforeServerCreation(environment, System.getenv());

        Path input = Path.of(requiredEnvironment("AI_REALISM_ACCOUNTS_FILE")).toRealPath();
        Path output = Path.of(requiredEnvironment("AI_REALISM_TOKENS_FILE")).toAbsolutePath().normalize();
        assertEquals(input.getParent(), output.getParent().toRealPath(), "Token output must stay beside the private account input");
        assertFalse(Files.exists(output), "Use a new private output file for every explicit bootstrap attempt");
        Properties accounts = new Properties();
        try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) { accounts.load(reader); }
        List<Account> selected = readAccounts(accounts);

        // Create an empty file, then restrict its ACL before any credential is written.
        Files.createFile(output);
        AclFileAttributeView acl = Files.getFileAttributeView(output, AclFileAttributeView.class);
        assertNotNull(acl, "Private token output requires Windows ACL support");
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(output)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));

        Properties result = new Properties();
        result.setProperty("status", "INCOMPLETE");
        result.setProperty("startedAt", Instant.now().toString());
        result.setProperty("completedAccounts", "0");
        writePrivate(output, result);
        try {
            // Read-only authenticated preflight keeps the raw gRPC status/cause available for diagnosis.
            login.billing.withDeadlineAfter(15, TimeUnit.SECONDS).getPublicBalance(
                    GetPublicBalanceRequest.newBuilder().setUserId(selected.getFirst().userId()).build());
            System.out.println("PayService authenticated balance preflight passed.");
        } catch (io.grpc.StatusRuntimeException error) {
            if (error.getStatus().getCode() != io.grpc.Status.Code.NOT_FOUND) {
                throw safeFailure(1, "BILLING_READ_PREFLIGHT", error, output, result);
            }
            System.out.println("PayService authenticated preflight passed; account initialization is still required.");
        }
        List<String> issued = new ArrayList<>();
        for (int index = 1; index <= selected.size(); index++) {
            Account account = selected.get(index - 1);
            LoginUserResponse session;
            try {
                // LoginUser is the canonical machine-readable ordinary-user login endpoint.
                session = login.stub.withDeadlineAfter(15, TimeUnit.SECONDS).loginUser(LoginUserRequest.newBuilder()
                        .setRequestId(UUID.randomUUID().toString()).setUsername(account.username())
                        .setPassword(account.password()).setUserAgent("AISocialGame local realism acceptance bootstrap")
                        .setKeepDays(1).build());
            } catch (RuntimeException error) {
                throw safeFailure(index, "LOGIN_USER", error, output, result);
            }
            assertTrue(session.getSuccess(), "Account " + index + " LoginUser was rejected; credentials and remote details are withheld");
            assertTrue(session.getErrorMessage().isBlank(), "LoginUser returned an unexpected business error");
            assertTrue(session.hasUser(), "LoginUser did not return a user");
            assertEquals(account.userId(), session.getUser().getUserId(), "LoginUser returned the wrong existing account");
            assertTrue(session.getUser().getActive(), "The existing ordinary user must be active");
            assertFalse(session.getSessionId().isBlank(), "LoginUser did not return a session");
            assertFalse(session.getAccessToken().isBlank(), "LoginUser did not return an access token");
            assertTrue(session.getExpiresInSeconds() > 0, "LoginUser returned an expired session");

            AuthResponse application;
            try {
                // Includes ValidateSession, billing EnsureUserInitialized, project wallet and local user upsert.
                application = auth.ssoCallback(session.getUser().getUserId(), session.getUser().getUsername(),
                        session.getSessionId(), session.getAccessToken());
                assertNotNull(application.getUser());
                assertTrue(application.getUser().getExternalUserId() == account.userId(), "Application identity differs from the real login");
                assertFalse(application.getToken().isBlank(), "Application token was not issued");
                assertEquals(application.getUser().getId(), tokens.getSession(application.getToken()).userId(), "Redis session was not persisted");
                assertNotNull(auth.authenticate(application.getToken()), "Normal application authentication did not accept the session");
            } catch (RuntimeException error) {
                throw safeFailure(index, "APPLICATION_AUTHENTICATION_ONBOARDING", error, output, result);
            }
            String prefix = "account." + index + ".";
            result.setProperty(prefix + "externalUserId", Long.toString(account.userId()));
            result.setProperty(prefix + "localUserId", application.getUser().getId());
            result.setProperty(prefix + "token", application.getToken());
            result.setProperty("completedAccounts", Integer.toString(index));
            issued.add(application.getToken());
            writePrivate(output, result);
        }
        assertEquals(3, new HashSet<>(issued).size(), "Application sessions must be distinct");
        result.setProperty("APP_AI_SYSTEM_USER_ID", Long.toString(selected.getFirst().userId()));
        result.setProperty("E2E_AUTH_TOKENS", String.join(",", issued));
        result.setProperty("completedAt", Instant.now().toString());
        result.setProperty("status", "COMPLETE");
        writePrivate(output, result);
        System.out.println("Local realism login bootstrap completed for 3 ordinary users; externalUserIds="
                + selected.stream().map(Account::userId).toList() + ". Application tokens are in the private output file.");
    }

    private static List<Account> readAccounts(Properties properties) {
        List<Account> result = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            String prefix = "account." + index + ".";
            String username = requiredProperty(properties, prefix + "username");
            String password = requiredProperty(properties, prefix + "password");
            long userId;
            try { userId = Long.parseLong(requiredProperty(properties, prefix + "userId")); }
            catch (NumberFormatException ignored) { throw new AssertionError("Account " + index + " userId must be a positive integer"); }
            assertTrue(userId > 0, "Existing external user IDs must be positive");
            result.add(new Account(username, password, userId));
        }
        assertEquals(3, result.stream().map(Account::userId).distinct().count(), "Three different real accounts are required");
        return result;
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        assertTrue(value != null && !value.isBlank(), "Missing private account property " + key);
        return value;
    }

    private static String requiredEnvironment(String key) {
        String value = System.getenv(key);
        assertTrue(value != null && !value.isBlank(), "Missing environment setting " + key);
        return value;
    }

    private static AssertionError safeFailure(int index, String operation, RuntimeException error, Path output, Properties result) {
        // Only fixed enums and application class/method names leave this boundary. Never attach the raw cause.
        List<String> types = new ArrayList<>();
        List<String> frames = new ArrayList<>();
        ApiException api = null;
        String category = "UNCLASSIFIED";
        for (Throwable cause = error; cause != null && types.size() < 6; cause = cause.getCause()) {
            types.add(safeSymbol(cause.getClass().getSimpleName()));
            if (api == null && cause instanceof ApiException found) api = found;
            if ("UNCLASSIFIED".equals(category)) category = safeErrorCategory(cause.getMessage());
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (frames.size() >= 3) break;
                if (frame.getClassName().startsWith("com.aisocialgame.")
                        && !frame.getClassName().startsWith("com.aisocialgame.acceptance.")) {
                    String symbol = safeSymbol(frame.getClassName()) + "." + safeSymbol(frame.getMethodName());
                    if (!frames.contains(symbol)) frames.add(symbol);
                }
            }
        }
        String httpStatus = api == null || api.getStatus() == null ? "NONE" : api.getStatus().value() + "_" + api.getStatus().name();
        String apiCode = api == null ? "NONE" : safeApiCode(api.getCode());
        String grpcCode = io.grpc.Status.fromThrowable(error).getCode().name();
        String prefix = "diagnostic.account." + index + ".";
        result.setProperty(prefix + "operation", operation);
        result.setProperty(prefix + "at", Instant.now().toString());
        result.setProperty(prefix + "httpStatus", httpStatus);
        result.setProperty(prefix + "apiCode", apiCode);
        result.setProperty(prefix + "grpcCode", grpcCode);
        result.setProperty(prefix + "category", category);
        result.setProperty(prefix + "applicationFrames", String.join(",", frames));
        result.setProperty(prefix + "causeTypes", String.join(",", types));
        String persisted = "SAVED";
        try { writePrivate(output, result); }
        catch (Exception ignored) { persisted = "WRITE_FAILED"; }
        return new AssertionError("Account " + index + " " + operation + " failed (http=" + httpStatus
                + ", apiCode=" + apiCode + ", grpc=" + grpcCode + ", category=" + category
                + ", causes=" + types + ", appFrames=" + frames + ", privateDiagnostic=" + persisted + ")");
    }

    private static String safeSymbol(String value) {
        return value != null && value.length() <= 200 && value.matches("[A-Za-z0-9_.$<>]+") ? value : "UNKNOWN";
    }

    private static String safeApiCode(String value) {
        if (value == null || value.isBlank()) return "NONE";
        // A regex alone is insufficient: a secret could itself look like an uppercase error code.
        return Set.of("UNAUTHENTICATED", "UNAUTHORIZED", "FORBIDDEN", "PERMISSION_DENIED", "INVALID_ARGUMENT",
                "FAILED_PRECONDITION", "NOT_FOUND", "CONFLICT", "RESOURCE_EXHAUSTED", "RATE_LIMITED",
                "UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL", "USER_NOT_FOUND", "INVALID_SESSION",
                "SESSION_EXPIRED", "BILLING_INIT_FAILED").contains(value) ? value : "UNCLASSIFIED";
    }

    private static String safeErrorCategory(String value) {
        if (value == null || value.isBlank()) return "UNCLASSIFIED";
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (io.grpc.Status.Code code : io.grpc.Status.Code.values()) {
            String prefix = code.name().toLowerCase(Locale.ROOT) + ": ";
            if (normalized.startsWith(prefix)) { normalized = normalized.substring(prefix.length()); break; }
        }
        // Exact service-owned messages only; never print a matched substring or unrecognized remote text.
        return switch (normalized) {
            case "missing bearer authorization", "missing bearer token" -> "MISSING_BEARER";
            case "invalid bearer authorization", "invalid token" -> "INVALID_BEARER";
            case "invalid or unauthorized bearer token" -> "UNAUTHORIZED_BEARER";
            case "missing required scope" -> "MISSING_REQUIRED_SCOPE";
            case "grpc method has no authorization policy" -> "MISSING_RPC_POLICY";
            case "io exception" -> "TRANSPORT_IO";
            case "application error processing rpc" -> "REMOTE_APPLICATION_ERROR";
            case "sso 会话无效或已过期" -> "INVALID_SSO_SESSION";
            case "sso 回调参数不完整" -> "INCOMPLETE_SSO_SESSION";
            case "计费账户初始化失败" -> "BILLING_INITIALIZATION_FAILED";
            case "用户服务调用失败" -> "USER_SERVICE_CALL_FAILED";
            case "积分服务调用失败" -> "BILLING_SERVICE_CALL_FAILED";
            default -> "UNCLASSIFIED";
        };
    }

    private static void writePrivate(Path output, Properties value) throws Exception {
        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            value.store(writer, "PRIVATE: local acceptance application tokens; do not attach to evidence or commit");
        }
    }

    private record Account(String username, String password, long userId) {
        @Override public String toString() { return "Account[credentials withheld]"; }
    }

    static class LoginGateway {
        private final UserAuthServiceGrpc.UserAuthServiceBlockingStub stub;
        private final BillingBalanceServiceGrpc.BillingBalanceServiceBlockingStub billing;

        LoginGateway(GrpcChannelFactory channels,
                     BillingBalanceServiceGrpc.BillingBalanceServiceBlockingStub billing) {
            this.stub = UserAuthServiceGrpc.newBlockingStub(channels.createChannel("user"));
            this.billing = billing;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LoginConfiguration {
        @Bean LoginGateway localAcceptanceLoginGateway(
                GrpcChannelFactory channels,
                BillingBalanceServiceGrpc.BillingBalanceServiceBlockingStub billing) {
            return new LoginGateway(channels, billing);
        }
    }
}
