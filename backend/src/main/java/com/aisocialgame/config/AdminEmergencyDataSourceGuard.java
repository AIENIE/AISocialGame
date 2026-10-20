package com.aisocialgame.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Verify the actual bound datasource after Boot's properties/connection-details processors, before SQL/JPA. */
@Component
@ConditionalOnProperty(name = RuntimeConfigurationStartupGuard.ACCEPTANCE_SELECTOR, havingValue = "true")
public final class AdminEmergencyDataSourceGuard implements BeanPostProcessor, Ordered {
    private final Environment environment;

    public AdminEmergencyDataSourceGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String name) {
        if (!(bean instanceof DataSource)) return bean;
        if (!(bean instanceof HikariDataSource source) || source.isRunning()
                || !environment.getRequiredProperty("spring.datasource.url").equals(source.getJdbcUrl())
                || source.getDataSource() != null || source.getDataSourceClassName() != null
                || source.getDataSourceJNDI() != null || !source.getDataSourceProperties().isEmpty()) {
            throw new IllegalStateException("Administrator emergency acceptance datasource differs from its approved target before connection");
        }
        LoggerFactory.getLogger(AdminEmergencyDataSourceGuard.class)
                .info("Verified administrator emergency acceptance datasource before its first connection");
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
