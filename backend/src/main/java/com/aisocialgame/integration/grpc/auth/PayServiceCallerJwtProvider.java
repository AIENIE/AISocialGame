package com.aisocialgame.integration.grpc.auth;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.config.PayServiceJwtConfigurationValidator;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Issues and rotates short-lived caller JWTs for protected PayService gRPC calls. */
@Component
public class PayServiceCallerJwtProvider {
    private final AppProperties appProperties;
    private final Clock clock;
    private final Supplier<String> jtiSupplier;

    @Autowired
    public PayServiceCallerJwtProvider(AppProperties appProperties) {
        this(appProperties, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    PayServiceCallerJwtProvider(AppProperties appProperties,
                                Clock clock,
                                Supplier<String> jtiSupplier) {
        this.appProperties = Objects.requireNonNull(appProperties);
        this.clock = Objects.requireNonNull(clock);
        this.jtiSupplier = Objects.requireNonNull(jtiSupplier);
    }

    public String currentToken() {
        return sign(PayServiceJwtConfigurationValidator.REQUIRED_SCOPES);
    }

    String currentToken(String scope) {
        if (!PayServiceJwtConfigurationValidator.REQUIRED_SCOPES.contains(scope)) {
            throw new IllegalArgumentException("PayService caller JWT scope is not configured");
        }
        return sign(List.of(scope));
    }

    private String sign(List<String> scopes) {
        Configuration configuration = snapshotAndValidate();
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plusSeconds(PayServiceJwtConfigurationValidator.REQUIRED_TTL_SECONDS);
        return Jwts.builder()
                .setIssuer(configuration.issuer)
                .setSubject(configuration.callerId)
                .setAudience(configuration.audience)
                .setIssuedAt(Date.from(now))
                .setNotBefore(Date.from(now))
                .setExpiration(Date.from(expiresAt))
                .setId(requireJti(jtiSupplier.get()))
                .claim("role", configuration.role)
                .claim("service", configuration.service)
                .claim("scopes", scopes)
                .signWith(
                        Keys.hmacShaKeyFor(configuration.secret.getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256
                )
                .compact();
    }

    private Configuration snapshotAndValidate() {
        AppProperties.External external = appProperties.getExternal();
        PayServiceJwtConfigurationValidator.validate(external);
        AppProperties.PayServiceJwt source = external.getPayserviceJwt();
        return new Configuration(
                source.getCallerId(), source.getIssuer(), source.getService(), source.getSecret(),
                source.getAudience(), source.getRole(), source.getTtlSeconds(), source.getScopes());
    }

    private String requireJti(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalStateException("Unable to issue PayService caller JWT with a valid jti");
        }
        return normalized;
    }

    private record Configuration(String callerId,
                                 String issuer,
                                 String service,
                                 String secret,
                                 String audience,
                                 String role,
                                 long ttlSeconds,
                                 String scopes) {
        @Override
        public String toString() {
            return "Configuration[callerId=" + callerId + ", issuer=" + issuer
                    + ", service=" + service + ", secret=<redacted>, audience=" + audience
                    + ", role=" + role + ", ttlSeconds=" + ttlSeconds + ", scopes=" + scopes + "]";
        }
    }
}
