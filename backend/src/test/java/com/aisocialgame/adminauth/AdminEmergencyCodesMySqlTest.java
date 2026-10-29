package com.aisocialgame.adminauth;

import com.aisocialgame.migration.ClosureMySqlSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="AIENIE_CLOSURE_MYSQL", matches="1")
class AdminEmergencyCodesMySqlTest extends EmergencyCodesContract {
    @Override DataSource database() {
        try (var connection=ClosureMySqlSupport.connect(null); var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + ClosureMySqlSupport.database("admin_auth"));
        } catch (java.sql.SQLException error) { throw new IllegalStateException(error); }
        return new DriverManagerDataSource(ClosureMySqlSupport.url("admin_auth"),"root",ClosureMySqlSupport.password());
    }
}
