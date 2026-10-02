package com.aisocialgame;

import com.aisocialgame.config.PayServiceJwtStartupGuard;
import com.aisocialgame.config.AiServiceTransportStartupGuard;
import com.aisocialgame.config.RuntimeConfigurationStartupGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.ConfigurableEnvironment;

@SpringBootApplication
public class AiSocialGameApplication {
    public static void main(String[] args) {
        com.aienie.configpair.RuntimeConfiguration.initialize(args);
        SpringApplication application = new SpringApplication(AiSocialGameApplication.class);
        application.addInitializers(context -> {
            ConfigurableEnvironment environment = (ConfigurableEnvironment) context.getEnvironment();
            RuntimeConfigurationStartupGuard.validateBeforeServerCreation(environment, com.aienie.configpair.RuntimeConfiguration.getenv());
            PayServiceJwtStartupGuard.validateBeforeServerCreation(environment, com.aienie.configpair.RuntimeConfiguration.getenv());
            AiServiceTransportStartupGuard.validateBeforeServerCreation(environment, com.aienie.configpair.RuntimeConfiguration.getenv());
        });
        application.setEnvironment(com.aienie.configpair.RuntimeConfiguration.springEnvironment());
        application.setEnvironment(com.aienie.configpair.RuntimeConfiguration.springEnvironment());
        application.setEnvironment(com.aienie.configpair.RuntimeConfiguration.springEnvironment());
        application.run(args);
    }
}
