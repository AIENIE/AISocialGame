package com.aisocialgame.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@Profile("!test")
public class RuntimeSecurityValidator {
    private static final String DEFAULT_ADMIN_PASSWORD = "admin" + "123";
    private static final String DEFAULT_DB_PASSWORD = "aisocialgame" + "_pwd";

    private final AppProperties appProperties;
    private final String datasourceUrl;
    private final String datasourcePassword;
    private final String userGrpcNegotiationType;
    private final String billingGrpcNegotiationType;
    private final String aiGrpcNegotiationType;
    private final boolean allowWeakRuntimeDefaults;
    private final boolean allowPlaintextGrpc;

    public RuntimeSecurityValidator(AppProperties appProperties,
                                    @Value("${spring.datasource.url:}") String datasourceUrl,
                                    @Value("${spring.datasource.password:}") String datasourcePassword,
                                    @Value("${grpc.client.user.negotiationType:}") String userGrpcNegotiationType,
                                    @Value("${grpc.client.billing.negotiationType:}") String billingGrpcNegotiationType,
                                    @Value("${grpc.client.ai.negotiationType:}") String aiGrpcNegotiationType,
                                    @Value("${app.security.allow-weak-runtime-defaults:false}") boolean allowWeakRuntimeDefaults,
                                    @Value("${app.security.allow-plaintext-grpc:false}") boolean allowPlaintextGrpc) {
        this.appProperties = appProperties;
        this.datasourceUrl = datasourceUrl;
        this.datasourcePassword = datasourcePassword;
        this.userGrpcNegotiationType = userGrpcNegotiationType;
        this.billingGrpcNegotiationType = billingGrpcNegotiationType;
        this.aiGrpcNegotiationType = aiGrpcNegotiationType;
        this.allowWeakRuntimeDefaults = allowWeakRuntimeDefaults;
        this.allowPlaintextGrpc = allowPlaintextGrpc;
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
        String adminPassword = appProperties.getAdmin() != null ? appProperties.getAdmin().getPassword() : "";
        if (!StringUtils.hasText(adminPassword)) {
            violations.add("APP_ADMIN_PASSWORD is required");
        }
        if (!allowWeakRuntimeDefaults && DEFAULT_ADMIN_PASSWORD.equals(adminPassword)) {
            violations.add("APP_ADMIN_PASSWORD must not use the default value");
        }
        if (!StringUtils.hasText(datasourcePassword)) {
            violations.add("SPRING_DATASOURCE_PASSWORD is required");
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
        if (!allowWeakRuntimeDefaults && (url.contains("usessl=false") || url.contains("allowpublickeyretrieval=true"))) {
            violations.add("SPRING_DATASOURCE_URL must not disable SSL or allow public key retrieval");
        }
    }

    private void validateGrpc(List<String> violations) {
        validateGrpcNegotiation("user", userGrpcNegotiationType, violations);
        validateGrpcNegotiation("billing", billingGrpcNegotiationType, violations);
        validateGrpcNegotiation("ai", aiGrpcNegotiationType, violations);
    }

    private void validateGrpcNegotiation(String name, String negotiationType, List<String> violations) {
        if (!allowPlaintextGrpc && "PLAINTEXT".equalsIgnoreCase(negotiationType)) {
            violations.add("grpc.client." + name + ".negotiationType must not be PLAINTEXT");
        }
    }
}
