package com.aisocialgame.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSecurityValidatorDataTransportTest {

    private static final String STAGING_MYSQL =
            "jdbc:mysql://base.testhut.top:13306/aisocialgame"
                    + "?sslMode=REQUIRED&allowPublicKeyRetrieval=false";

    @Test
    void stagingAcceptsOnlyRebaselinedCanonicalDataEndpoints() {
        assertThat(validate("test", STAGING_MYSQL, "base.testhut.top", 16379, false, "",
                "http://base.testhut.top", 16333, true)).isEmpty();

        assertThat(validate("test", STAGING_MYSQL.replace("base.testhut.top", "192.168.1.3"),
                "base.testhut.top", 16379, false, "", "http://base.testhut.top", 16333, true))
                .anyMatch(value -> value.contains("test MySQL"));
        assertThat(validate("test", STAGING_MYSQL, "base.testhut.top", 26379, false, "",
                "http://base.testhut.top", 16333, true))
                .anyMatch(value -> value.contains("test Redis"));
        assertThat(validate("test", STAGING_MYSQL, "base.testhut.top", 16379, false, "",
                "http://192.168.1.3", 16333, true))
                .anyMatch(value -> value.contains("test Qdrant"));
        assertThat(validate("test", STAGING_MYSQL + "&allowPublicKeyRetrieval=true",
                "base.testhut.top", 16379, false, "", "http://base.testhut.top", 16333, true))
                .anyMatch(value -> value.contains("test MySQL"));
        for (String insecureMode : List.of("DISABLED", "PREFERRED")) {
            assertThat(validate("test", STAGING_MYSQL.replace("sslMode=REQUIRED", "sslMode=" + insecureMode),
                    "base.testhut.top", 16379, false, "", "http://base.testhut.top", 16333, true))
                    .anyMatch(value -> value.contains("test MySQL"));
        }
        assertThat(validate("test", STAGING_MYSQL + "&sslMode=DISABLED",
                "base.testhut.top", 16379, false, "", "http://base.testhut.top", 16333, true))
                .anyMatch(value -> value.contains("test MySQL"));
        assertThat(validate("test", STAGING_MYSQL, "base.testhut.top", 16379, false, "fake-secret",
                "http://base.testhut.top", 16333, true))
                .anyMatch(value -> value.contains("test Redis"));
        assertThat(validate("test", STAGING_MYSQL, "base.testhut.top", 16379, false, "",
                "http://base.testhut.top", 16333, true, "fake-key"))
                .anyMatch(value -> value.contains("test Qdrant"));
    }

    @Test
    void productionStillRequiresVerifiedTls() {
        assertThat(validate("production",
                "jdbc:mysql://db.example/aisocialgame?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false",
                "redis.example", 6379, true, "secret", "https://qdrant.example", 6333, true)).isEmpty();
        assertThat(validate("production", STAGING_MYSQL, "base.testhut.top", 16379, false, "",
                "http://base.testhut.top", 16333, true))
                .anySatisfy(value -> assertThat(value).contains("production"));
    }

    private List<String> validate(String runtimeEnv,
                                  String datasourceUrl,
                                  String redisHost,
                                  int redisPort,
                                  boolean redisSsl,
                                  String redisPassword,
                                  String qdrantHost,
                                  int qdrantPort,
                                  boolean qdrantEnabled) {
        List<String> violations = new ArrayList<>();
        RuntimeSecurityValidator.validateDataTransport(runtimeEnv, datasourceUrl, redisHost, redisPort,
                redisSsl, "", redisPassword, qdrantHost, qdrantPort, qdrantEnabled,
                "production".equals(runtimeEnv) ? "secret" : "", violations);
        return violations;
    }

    private List<String> validate(String runtimeEnv,
                                  String datasourceUrl,
                                  String redisHost,
                                  int redisPort,
                                  boolean redisSsl,
                                  String redisPassword,
                                  String qdrantHost,
                                  int qdrantPort,
                                  boolean qdrantEnabled,
                                  String qdrantApiKey) {
        List<String> violations = new ArrayList<>();
        RuntimeSecurityValidator.validateDataTransport(runtimeEnv, datasourceUrl, redisHost, redisPort,
                redisSsl, "", redisPassword, qdrantHost, qdrantPort, qdrantEnabled,
                qdrantApiKey, violations);
        return violations;
    }
}
