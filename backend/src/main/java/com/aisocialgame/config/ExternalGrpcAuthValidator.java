package com.aisocialgame.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class ExternalGrpcAuthValidator {

    private final AppProperties appProperties;

    public ExternalGrpcAuthValidator(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void validate() {
        AppProperties.External external = appProperties.getExternal();
        if (external == null) {
            throw new IllegalStateException("External gRPC authentication configuration is required");
        }

        if (StringUtils.hasLength(external.getUserserviceInternalGrpcToken())) {
            throw new IllegalStateException(
                    "Legacy APP_EXTERNAL_USERSERVICE_INTERNAL_GRPC_TOKEN must be absent or empty");
        }
        if (!external.isGrpcAuthRequired()) {
            return;
        }

        try {
            UserServiceJwtConfigurationValidator.validate(external.getUserserviceJwt());
            PayServiceJwtConfigurationValidator.validate(external);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid external caller JWT configuration", exception);
        }

        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(external.getAiserviceHmacCaller())) {
            missing.add("APP_EXTERNAL_AISERVICE_HMAC_CALLER");
        }
        if (!StringUtils.hasText(external.getAiserviceHmacSecret())) {
            missing.add("GRPC_SHARED_SECRET");
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required gRPC auth configuration: " + String.join(", ", missing));
        }
    }
}
