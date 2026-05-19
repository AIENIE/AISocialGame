package com.aisocialgame;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.aisocialgame.service.AdminAuthService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AdminAuthServiceTest {

    @Test
    void loginAndValidateToken() {
        AppProperties properties = new AppProperties();
        properties.getAdmin().setUsername("admin");
        properties.getAdmin().setPassword("test-admin-password");
        properties.getAdmin().setTokenTtlHours(1);
        AdminAuthService adminAuthService = new AdminAuthService(properties);

        String token = adminAuthService.login("admin", "test-admin-password");
        Assertions.assertNotNull(token);
        Assertions.assertEquals("admin", adminAuthService.requireAdmin(token));
    }

    @Test
    void wrongPasswordShouldThrow() {
        AppProperties properties = new AppProperties();
        properties.getAdmin().setUsername("admin");
        properties.getAdmin().setPassword("test-admin-password");
        AdminAuthService adminAuthService = new AdminAuthService(properties);

        Assertions.assertThrows(ApiException.class, () -> adminAuthService.login("admin", "bad"));
    }
}
