package com.aisocialgame.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins(
                                "http://localhost:5173",
                                "http://localhost:4173",
                                "http://localhost:11030",
                                "http://127.0.0.1:5173",
                                "http://127.0.0.1:4173",
                                "http://127.0.0.1:11030",
                                "http://localhost",
                                "https://localhost",
                                "http://aisocialgame.seekerhut.com",
                                "http://aisocialgame.seekerhut.com:11030",
                                "https://aisocialgame.seekerhut.com",
                                "http://aisocialgame.aienie.com",
                                "http://aisocialgame.aienie.com:11030",
                                "https://aisocialgame.aienie.com")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
