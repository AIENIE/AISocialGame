package com.aisocialgame.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Local LAN service names must follow the same system DNS path as the Windows launcher. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalRedisConfiguration {
    @Bean
    ClientResourcesBuilderCustomizer localRedisSystemResolver() {
        return builder -> builder.addressResolverGroup(DefaultAddressResolverGroup.INSTANCE);
    }
}
