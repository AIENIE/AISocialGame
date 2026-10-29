package com.aisocialgame.config;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSecurityValidatorDataTransportTest {
    private static final String STAGING_MYSQL = "jdbc:mysql://mysql.testhut.top:13306/aisocialgame?sslMode=REQUIRED&allowPublicKeyRetrieval=false";
    @Test void stagingAcceptsOnlyRebaselinedCanonicalDataEndpoints() {
        assertThat(validate("test", STAGING_MYSQL, "redis.testhut.top", 16379, false, "")).isEmpty();
        assertThat(validate("test", STAGING_MYSQL.replace("mysql.testhut.top", "192.168.1.3"), "redis.testhut.top", 16379, false, "")).anyMatch(value -> value.contains("test MySQL"));
        assertThat(validate("test", STAGING_MYSQL, "redis.testhut.top", 26379, false, "")).anyMatch(value -> value.contains("test Redis"));
        assertThat(validate("test", STAGING_MYSQL + "&allowPublicKeyRetrieval=true", "redis.testhut.top", 16379, false, "")).anyMatch(value -> value.contains("test MySQL"));
        for (String mode : List.of("DISABLED", "PREFERRED"))
            assertThat(validate("test", STAGING_MYSQL.replace("sslMode=REQUIRED", "sslMode=" + mode), "redis.testhut.top", 16379, false, "")).anyMatch(value -> value.contains("test MySQL"));
        assertThat(validate("test", STAGING_MYSQL + "&sslMode=DISABLED", "redis.testhut.top", 16379, false, "")).anyMatch(value -> value.contains("test MySQL"));
        assertThat(validate("test", STAGING_MYSQL, "redis.testhut.top", 16379, false, "fake-secret")).anyMatch(value -> value.contains("test Redis"));
    }
    @Test void productionStillRequiresVerifiedTls() {
        assertThat(validate("production", "jdbc:mysql://db.example/aisocialgame?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false", "redis.example", 6379, true, "secret")).isEmpty();
        assertThat(validate("production", STAGING_MYSQL, "redis.testhut.top", 16379, false, "")).anySatisfy(value -> assertThat(value).contains("production"));
    }
    private List<String> validate(String runtimeEnv, String datasourceUrl, String redisHost, int redisPort, boolean redisSsl, String redisPassword) {
        List<String> violations = new ArrayList<>();
        RuntimeSecurityValidator.validateDataTransport(runtimeEnv, datasourceUrl, redisHost, redisPort, redisSsl, "", redisPassword, violations);
        return violations;
    }
}
