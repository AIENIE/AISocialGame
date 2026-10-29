package com.aisocialgame.config;

import io.lettuce.core.resource.DefaultClientResources;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class LocalRedisConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalRedisConfiguration.class);

    @Test void localProfileUsesSystemResolutionForTheConfiguredDomain() {
        runner.withPropertyValues("spring.profiles.active=local").run(context -> {
            var builder = DefaultClientResources.builder();
            context.getBean(ClientResourcesBuilderCustomizer.class).customize(builder);
            var resources = builder.build();
            try { assertThat(resources.addressResolverGroup()).isSameAs(DefaultAddressResolverGroup.INSTANCE); }
            finally { resources.shutdown().get(); }
        });
    }

    @Test void nonLocalProfilesKeepTheExistingRedisClientConfiguration() {
        for (String profile : new String[]{"test", "stag", "prod"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile).run(context ->
                    assertThat(context).doesNotHaveBean(ClientResourcesBuilderCustomizer.class));
        }
    }
}
