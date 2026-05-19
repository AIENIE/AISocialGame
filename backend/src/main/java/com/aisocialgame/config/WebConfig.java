package com.aisocialgame.config;

import com.aisocialgame.web.CurrentAdminArgumentResolver;
import com.aisocialgame.web.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig {
    private final AppProperties appProperties;
    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final CurrentAdminArgumentResolver currentAdminArgumentResolver;

    public WebConfig(AppProperties appProperties,
                     CurrentUserArgumentResolver currentUserArgumentResolver,
                     CurrentAdminArgumentResolver currentAdminArgumentResolver) {
        this.appProperties = appProperties;
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.currentAdminArgumentResolver = currentAdminArgumentResolver;
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins(appProperties.getCors().getAllowedOrigins().toArray(String[]::new))
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("Content-Type", "Authorization", "X-Auth-Token", "X-Admin-Token")
                        .allowCredentials(true);
            }

            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(currentUserArgumentResolver);
                resolvers.add(currentAdminArgumentResolver);
            }
        };
    }
}
