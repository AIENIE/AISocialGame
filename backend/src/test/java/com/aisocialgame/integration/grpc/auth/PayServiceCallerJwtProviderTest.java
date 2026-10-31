package com.aisocialgame.integration.grpc.auth;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.config.PayServiceJwtConfigurationValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayServiceCallerJwtProviderTest {
    static final String SECRET = "aisocialgame-payservice-provider-secret-32-bytes";

    @Test
    void issuesCanonicalHs256ClaimsAndNeverReusesAToken() {
        AppProperties properties = validProperties();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T00:00:00Z"));
        AtomicInteger sequence = new AtomicInteger();
        PayServiceCallerJwtProvider provider = new PayServiceCallerJwtProvider(
                properties, clock, () -> "pay-jti-" + sequence.incrementAndGet());

        String first = provider.currentToken();
        String second = provider.currentToken();
        assertNotEquals(first, second);
        assertEquals(2, sequence.get());
        Jws<Claims> parsed = parse(first, clock.instant(), SECRET);
        assertEquals(SignatureAlgorithm.HS256.getValue(), parsed.getHeader().getAlgorithm());
        assertEquals("aisocialgame", parsed.getBody().getIssuer());
        assertEquals("aisocialgame", parsed.getBody().getSubject());
        assertEquals("aisocialgame", parsed.getBody().get("service", String.class));
        assertEquals("aienie-payservice-grpc", parsed.getBody().getAudience());
        assertEquals("SERVICE", parsed.getBody().get("role", String.class));
        assertEquals("pay-jti-1", parsed.getBody().getId());
        assertEquals(PayServiceJwtConfigurationValidator.REQUIRED_SCOPES,
                parsed.getBody().get("scopes", List.class));
        assertEquals(parsed.getBody().getIssuedAt(), parsed.getBody().getNotBefore());
        assertEquals(300L, Duration.between(parsed.getBody().getIssuedAt().toInstant(),
                parsed.getBody().getExpiration().toInstant()).toSeconds());

        assertEquals("pay-jti-2", parse(second, clock.instant(), SECRET).getBody().getId());
    }

    @Test
    void rotatesImmediatelyWhenSecretChangesAndIssuesUniqueConcurrentTokens() throws Exception {
        AppProperties properties = validProperties();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T01:00:00Z"));
        AtomicInteger sequence = new AtomicInteger();
        PayServiceCallerJwtProvider provider = new PayServiceCallerJwtProvider(
                properties, clock, () -> "rotation-jti-" + sequence.incrementAndGet());
        String first = provider.currentToken();
        String rotatedSecret = "aisocialgame-payservice-rotated-secret-32-bytes";
        properties.getExternal().getPayserviceJwt().setSecret(rotatedSecret);
        String rotated = provider.currentToken();
        assertNotEquals(first, rotated);
        parse(rotated, clock.instant(), rotatedSecret);

        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                futures.add(executor.submit(() -> {
                    release.await();
                    return provider.currentToken();
                }));
            }
            release.countDown();
            Set<String> values = new HashSet<>();
            for (Future<String> future : futures) {
                values.add(future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(24, values.size());
            assertEquals(26, sequence.get());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsExpandedTtlPlaceholderAndSecretReuseWithoutDisclosure() {
        AppProperties expanded = validProperties();
        expanded.getExternal().getPayserviceJwt().setScopes(
                PayServiceJwtConfigurationValidator.REQUIRED_SCOPES_VALUE + ",billing.admin");
        assertRejected(expanded, SECRET);

        AppProperties ttl = validProperties();
        ttl.getExternal().getPayserviceJwt().setTtlSeconds(301L);
        assertRejected(ttl, SECRET);

        AppProperties placeholder = validProperties();
        String privateValue = "REPLACE_WITH_PRIVATE_PAY_SECRET_VALUE";
        placeholder.getExternal().getPayserviceJwt().setSecret(privateValue);
        assertRejected(placeholder, privateValue);

        AppProperties structurallyWeak = validProperties();
        String repeatedValue = "abcd".repeat(8);
        structurallyWeak.getExternal().getPayserviceJwt().setSecret(repeatedValue);
        assertRejected(structurallyWeak, repeatedValue);

        AppProperties boundaryWhitespace = validProperties();
        String whitespaceValue = "\u2003aisocialgame-payservice-boundary-secret-32-bytes";
        boundaryWhitespace.getExternal().getPayserviceJwt().setSecret(whitespaceValue);
        assertRejected(boundaryWhitespace, whitespaceValue);

        AppProperties internalWhitespace = validProperties();
        String internalWhitespaceValue = "aisocialgame-payservice internal-secret-32-bytes";
        internalWhitespace.getExternal().getPayserviceJwt().setSecret(internalWhitespaceValue);
        assertRejected(internalWhitespace, internalWhitespaceValue);

    }

    private void assertRejected(AppProperties properties, String privateValue) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PayServiceCallerJwtProvider(properties).currentToken());
        assertFalse(error.getMessage().contains(privateValue));
    }

    static AppProperties validProperties() {
        AppProperties properties = new AppProperties();
        properties.getExternal().getUserserviceJwt()
                .setSecret("aisocialgame-userservice-provider-secret-32-bytes");
        properties.getExternal().setAiserviceHmacSecret(
                "aisocialgame-ai-hmac-provider-secret-32-bytes");
        properties.getExternal().getPayserviceJwt().setSecret(SECRET);
        return properties;
    }

    static Jws<Claims> parse(String token, Instant verificationInstant, String secret) {
        return Jwts.parserBuilder()
                .requireIssuer("aisocialgame")
                .requireSubject("aisocialgame")
                .requireAudience("aienie-payservice-grpc")
                .setClock(() -> Date.from(verificationInstant))
                .setSigningKey(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseClaimsJws(token);
    }

    static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
