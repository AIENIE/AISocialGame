package com.aienie.configpair;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class HikariEnvironmentBindingTest {
    @Test
    void ambiguousLegacyEnvironmentNameStartsPoolBeforeConfigurationFinishes() {
        try (var source = dataSource()) {
            var environment = environment("SPRING_DATASOURCE_HIKARI_CONNECTION_TEST_QUERY");
            assertThrows(org.springframework.boot.context.properties.bind.BindException.class,
                    () -> Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(source)));
            assertTrue(source.isRunning());
        }
    }

    @Test
    void canonicalFlatPropertyNameBindsBeforeFirstConnection() throws Exception {
        try (var source = dataSource()) {
            var environment = environment("SPRING_DATASOURCE_HIKARI_CONNECTIONTESTQUERY");
            Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(source));
            assertEquals("SELECT 1", source.getConnectionTestQuery());
            assertFalse(source.isRunning());
            try (var connection = source.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT 1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    private static StandardEnvironment environment(String key) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace("systemEnvironment",
                new SystemEnvironmentPropertySource("systemEnvironment", Map.of(key, "SELECT 1")));
        return environment;
    }

    private static HikariDataSource dataSource() {
        var source = new HikariDataSource();
        source.setJdbcUrl("jdbc:h2:mem:hikari-binding-" + java.util.UUID.randomUUID());
        source.setUsername("sa");
        source.setPassword("");
        source.setMaximumPoolSize(1);
        return source;
    }
}
