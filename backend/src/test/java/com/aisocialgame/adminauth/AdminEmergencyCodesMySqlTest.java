package com.aisocialgame.adminauth;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
class AdminEmergencyCodesMySqlTest extends EmergencyCodesContract {
    @Override DataSource database() {
        String url=System.getenv("AIENIE_SOCIAL_EMERGENCY_TEST_URL");
        Assumptions.assumeTrue(url!=null);
        if(!url.matches("jdbc:mysql://[^/]+/aienie_emergency_20261004_social(?:\\?.*)?")) throw new IllegalArgumentException("Only isolated emergency schema is allowed");
        return new DriverManagerDataSource(url,System.getenv("AIENIE_SOCIAL_EMERGENCY_TEST_USERNAME"),System.getenv("AIENIE_SOCIAL_EMERGENCY_TEST_PASSWORD"));
    }
}
