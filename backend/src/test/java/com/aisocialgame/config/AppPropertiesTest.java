package com.aisocialgame.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppPropertiesTest {

    @Test
    void defaultsShouldUseLocalhutUserServiceBaseUrl() {
        AppProperties properties = new AppProperties();

        assertEquals("https://userservice.localhut.com", properties.getSso().getUserServiceBaseUrl());
        assertEquals("https://aisocialgame.localhut.com/sso/callback", properties.getSso().getCallbackUrl());
    }
}
