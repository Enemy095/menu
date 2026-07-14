package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SecurityPropertiesBindingTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "app.security.cors.allowed-origins=${APP_SECURITY_CORS_ALLOWED_ORIGINS:}",
                    "app.security.rate-limit.client.tokens-per-period=5",
                    "app.security.rate-limit.client.period=1m",
                    "app.security.rate-limit.client.burst-capacity=10",
                    "app.security.rate-limit.tenant.tokens-per-period=120",
                    "app.security.rate-limit.tenant.period=1m",
                    "app.security.rate-limit.tenant.burst-capacity=240",
                    "app.security.rate-limit.cache.maximum-client-buckets=50000",
                    "app.security.rate-limit.cache.maximum-tenant-buckets=5000",
                    "app.security.rate-limit.cache.expire-after-access=10m");

    @Test
    void emptyEnvironmentValueBindsToEmptySameOriginAllowlist() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBean(SecurityProperties.class).cors().allowedOrigins()).isEmpty();
        });
    }

    @Test
    void commaSeparatedEnvironmentValueBindsToExactOriginSet() {
        contextRunner
                .withPropertyValues(
                        "APP_SECURITY_CORS_ALLOWED_ORIGINS="
                                + "https://stambul.example.test,https://adamas.example.test")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBean(SecurityProperties.class).cors().allowedOrigins())
                            .containsExactlyInAnyOrder(
                                    "https://stambul.example.test",
                                    "https://adamas.example.test");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class PropertiesConfiguration {
    }
}
