package com.aisocialgame.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class AdminEmergencyDataSourceGuardTest {
    private static final String APPROVED = "jdbc:mysql://localmysql.testhut.top:23306/aienie_emergency_20261004_social";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(AdminEmergencyDataSourceGuard.class)
            .withPropertyValues("runtime.acceptance.admin-emergency-codes=true", "spring.datasource.url=" + APPROVED,
                    "spring.datasource.username=acceptance-test", "spring.datasource.password=synthetic-test-only",
                    "spring.datasource.hikari.connection-test-query=SELECT 1");

    @Test
    void actualBootDatasourceIsCheckedWithoutOpeningAConnection() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var source = context.getBean(HikariDataSource.class);
            assertEquals(APPROVED, source.getJdbcUrl());
            assertFalse(source.isRunning());
        });
    }

    @Test
    void anotherHikariJdbcUrlFailsBeforeConnection() {
        runner.withPropertyValues("spring.datasource.hikari.jdbc-url=jdbc:mysql://localmysql.testhut.top:23306/aisocialgame")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void customConnectionDetailsCannotRedirectTheBoundDatasource() {
        runner.withBean(JdbcConnectionDetails.class, () -> new JdbcConnectionDetails() {
            public String getJdbcUrl() { return "jdbc:mysql://localmysql.testhut.top:23306/aisocialgame"; }
            public String getUsername() { return "synthetic"; }
            public String getPassword() { return "synthetic"; }
            public String getDriverClassName() { return "com.mysql.cj.jdbc.Driver"; }
        }).run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void alternateDriverDatasourcePropertiesFailBeforeConnection() {
        runner.withPropertyValues("spring.datasource.hikari.data-source-properties.databaseName=aisocialgame")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void defaultModeKeepsNormalDatasourceConfiguration() {
        runner.withPropertyValues("runtime.acceptance.admin-emergency-codes=false",
                        "spring.datasource.url=jdbc:h2:mem:normal-datasource")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.getBean(HikariDataSource.class).isRunning());
                });
    }
}
