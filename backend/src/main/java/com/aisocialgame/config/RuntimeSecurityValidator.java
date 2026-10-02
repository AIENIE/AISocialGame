package com.aisocialgame.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aisocialgame.adminauth.AdminAuthCrypto;
import com.aisocialgame.adminauth.AdminAuthPolicy;

@Component
@Profile("!test")
public class RuntimeSecurityValidator {
    private static final String DEFAULT_DB_PASSWORD = "aisocialgame" + "_pwd";
    private static final Pattern BCRYPT = Pattern.compile(
            "^\\$2[aby]\\$([0-3][0-9])\\$[./A-Za-z0-9]{53}$"
    );

    private final AppProperties appProperties;
    private final String datasourceUrl;
    private final String datasourcePassword;
    private final String redisHost;
    private final int redisPort;
    private final boolean redisSslEnabled;
    private final String redisUsername;
    private final String redisPassword;
    private final String qdrantHost;
    private final int qdrantPort;
    private final boolean qdrantEnabled;
    private final String qdrantApiKey;
    private final String userGrpcNegotiationType;
    private final String billingGrpcNegotiationType;
    private final String aiGrpcNegotiationType;
    private final boolean allowWeakRuntimeDefaults;
    private final boolean allowPlaintextGrpc;
    private final AdminAuthPolicy adminAuthPolicy;
    private final AdminAuthCrypto adminAuthCrypto;

    public RuntimeSecurityValidator(AppProperties appProperties,
                                    @Value("${spring.datasource.url:}") String datasourceUrl,
                                    @Value("${spring.datasource.password:}") String datasourcePassword,
                                    @Value("${spring.data.redis.host:}") String redisHost,
                                    @Value("${spring.data.redis.port:0}") int redisPort,
                                    @Value("${spring.data.redis.ssl.enabled:false}") boolean redisSslEnabled,
                                    @Value("${spring.data.redis.username:}") String redisUsername,
                                    @Value("${spring.data.redis.password:}") String redisPassword,
                                    @Value("${qdrant.host:}") String qdrantHost,
                                    @Value("${qdrant.http-port:0}") int qdrantPort,
                                    @Value("${qdrant.enabled:false}") boolean qdrantEnabled,
                                    @Value("${qdrant.api-key:}") String qdrantApiKey,
                                    @Value("${grpc.client.user.negotiationType:}") String userGrpcNegotiationType,
                                    @Value("${grpc.client.billing.negotiationType:}") String billingGrpcNegotiationType,
                                    @Value("${grpc.client.ai.negotiationType:}") String aiGrpcNegotiationType,
                                    @Value("${app.security.allow-weak-runtime-defaults:false}") boolean allowWeakRuntimeDefaults,
                                    @Value("${app.security.allow-plaintext-grpc:false}") boolean allowPlaintextGrpc,
                                    AdminAuthPolicy adminAuthPolicy,
                                    AdminAuthCrypto adminAuthCrypto) {
        this.appProperties = appProperties;
        this.datasourceUrl = datasourceUrl;
        this.datasourcePassword = datasourcePassword;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisSslEnabled = redisSslEnabled;
        this.redisUsername = redisUsername;
        this.redisPassword = redisPassword;
        this.qdrantHost = qdrantHost;
        this.qdrantPort = qdrantPort;
        this.qdrantEnabled = qdrantEnabled;
        this.qdrantApiKey = qdrantApiKey;
        this.userGrpcNegotiationType = userGrpcNegotiationType;
        this.billingGrpcNegotiationType = billingGrpcNegotiationType;
        this.aiGrpcNegotiationType = aiGrpcNegotiationType;
        this.allowWeakRuntimeDefaults = allowWeakRuntimeDefaults;
        this.allowPlaintextGrpc = allowPlaintextGrpc;
        this.adminAuthPolicy = adminAuthPolicy;
        this.adminAuthCrypto = adminAuthCrypto;
    }

    @PostConstruct
    public void validate() {
        List<String> violations = new ArrayList<>();
        validateSecrets(violations);
        validateDatasource(violations);
        validateGrpc(violations);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Unsafe runtime configuration: " + String.join("; ", violations));
        }
    }

    private void validateSecrets(List<String> violations) {
        String passwordHash = appProperties.getAdmin() != null ? appProperties.getAdmin().getPasswordHash() : "";
        Matcher bcrypt = BCRYPT.matcher(passwordHash == null ? "" : passwordHash);
        if (!bcrypt.matches() || Integer.parseInt(bcrypt.group(1)) < 10) {
            violations.add("APP_ADMIN_PASSWORD_HASH must be a BCrypt hash with cost >= 10");
        }
        if (adminAuthPolicy.totpRequired() && !adminAuthCrypto.hasUsableActiveKey()) {
            violations.add("ADMIN_TOTP_ENCRYPTION_KEYS must include ADMIN_TOTP_ACTIVE_KEY_VERSION");
        }
        if (!StringUtils.hasText(datasourcePassword)) {
            violations.add("SPRING_DATASOURCE_PASSWORD is required");
        }
        if (!AdminAuthPolicy.ENV_LOCAL.equals(adminAuthPolicy.environment())
                && !appProperties.getAdmin().isCookieSecure()) {
            violations.add("APP_ADMIN_COOKIE_SECURE must be true outside ENV=local");
        }
        if (!allowWeakRuntimeDefaults && DEFAULT_DB_PASSWORD.equals(datasourcePassword)) {
            violations.add("SPRING_DATASOURCE_PASSWORD must not use the default value");
        }
    }

    private void validateDatasource(List<String> violations) {
        String url = datasourceUrl == null ? "" : datasourceUrl.toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(url)) {
            violations.add("SPRING_DATASOURCE_URL is required");
            return;
        }
        validateDataTransport(adminAuthPolicy.environment(), datasourceUrl, redisHost, redisPort,
                redisSslEnabled, redisUsername, redisPassword, qdrantHost, qdrantPort,
                qdrantEnabled, qdrantApiKey, violations);
    }

    private void validateGrpc(List<String> violations) {
        validateGrpcNegotiation("user", userGrpcNegotiationType, violations);
        validateGrpcNegotiation("billing", billingGrpcNegotiationType, violations);
        validateGrpcNegotiation("ai", aiGrpcNegotiationType, violations);
    }

    static void validateDataTransport(String runtimeEnv,
                                      String datasourceUrl,
                                      String redisHost,
                                      int redisPort,
                                      boolean redisSslEnabled,
                                      String redisUsername,
                                      String redisPassword,
                                      String qdrantHost,
                                      int qdrantPort,
                                      boolean qdrantEnabled,
                                      String qdrantApiKey,
                                      List<String> violations) {
        if (AdminAuthPolicy.ENV_LOCAL.equals(runtimeEnv)) {
            return;
        }
        if (AdminAuthPolicy.ENV_TEST.equals(runtimeEnv)) {
            if (!hasMysqlEndpoint(datasourceUrl, "mysql.testhut.top", 13306)
                    || !hasOnlyQueryValue(datasourceUrl, "sslMode", "REQUIRED")
                    || !hasOnlyQueryValue(datasourceUrl, "allowPublicKeyRetrieval", "false")) {
                violations.add("test MySQL must use mysql.testhut.top:13306, sslMode=REQUIRED, and allowPublicKeyRetrieval=false");
            }
            if (!"redis.testhut.top".equalsIgnoreCase(redisHost)
                    || redisPort != 16379 || redisSslEnabled
                    || StringUtils.hasText(redisUsername) || StringUtils.hasText(redisPassword)) {
                violations.add("test Redis must use unauthenticated plaintext redis.testhut.top:16379 exactly as rebaselined");
            }
            if (qdrantEnabled && (!("http://qdrant.testhut.top").equalsIgnoreCase(qdrantHost)
                    || qdrantPort != 16333 || StringUtils.hasText(qdrantApiKey))) {
                violations.add("test Qdrant must use unauthenticated http://qdrant.testhut.top:16333 exactly as rebaselined");
            }
            return;
        }
        if (!AdminAuthPolicy.ENV_PRODUCTION.equals(runtimeEnv)) {
            violations.add("ENV must be exactly local, test or production");
            return;
        }
        if (!hasMysqlEndpoint(datasourceUrl, null, -1)
                || !hasOnlyQueryValue(datasourceUrl, "sslMode", "VERIFY_IDENTITY")
                || !hasOnlyQueryValue(datasourceUrl, "allowPublicKeyRetrieval", "false")) {
            violations.add("production MySQL must use sslMode=VERIFY_IDENTITY and disable allowPublicKeyRetrieval");
        }
        if (!redisSslEnabled || !StringUtils.hasText(redisPassword)) {
            violations.add("production Redis must use TLS and authentication");
        }
        if (qdrantEnabled && (qdrantHost == null
                || !qdrantHost.toLowerCase(Locale.ROOT).startsWith("https://")
                || !StringUtils.hasText(qdrantApiKey))) {
            violations.add("production Qdrant must use HTTPS and authentication");
        }
    }

    private static boolean hasMysqlEndpoint(String jdbcUrl, String expectedHost, int expectedPort) {
        if (jdbcUrl == null || !jdbcUrl.regionMatches(true, 0, "jdbc:mysql://", 0, 13)) {
            return false;
        }
        try {
            URI uri = URI.create(jdbcUrl.substring(5));
            if (!"mysql".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getPath() == null || uri.getPath().length() <= 1) {
                return false;
            }
            return expectedHost == null
                    || (expectedHost.equalsIgnoreCase(uri.getHost()) && expectedPort == uri.getPort());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean hasOnlyQueryValue(String url, String expectedKey, String expectedValue) {
        if (url == null) {
            return false;
        }
        int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryStart == url.length() - 1) {
            return false;
        }
        boolean found = false;
        for (String parameter : url.substring(queryStart + 1).split("&", -1)) {
            int equals = parameter.indexOf('=');
            if (equals <= 0 || !parameter.substring(0, equals).equalsIgnoreCase(expectedKey)) {
                continue;
            }
            if (!parameter.substring(equals + 1).equalsIgnoreCase(expectedValue)) {
                return false;
            }
            found = true;
        }
        return found;
    }

    private void validateGrpcNegotiation(String name, String negotiationType, List<String> violations) {
        if (!allowPlaintextGrpc && "PLAINTEXT".equalsIgnoreCase(negotiationType)) {
            violations.add("grpc.client." + name + ".negotiationType must not be PLAINTEXT");
        }
    }
}
