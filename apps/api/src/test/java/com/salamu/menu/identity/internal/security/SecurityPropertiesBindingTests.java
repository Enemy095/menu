package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

class SecurityPropertiesBindingTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "app.security.cors.allowed-origins=${APP_SECURITY_CORS_ALLOWED_ORIGINS:}",
                    "app.security.request-authority.allowed-hosts=${APP_SECURITY_ALLOWED_HOSTS:}",
                    "app.security.trusted-proxies.enabled=false",
                    "app.security.trusted-proxies.cidrs=",
                    "app.security.rate-limit.client.tokens-per-period=5",
                    "app.security.rate-limit.client.period=1m",
                    "app.security.rate-limit.client.burst-capacity=10",
                    "app.security.rate-limit.application.tokens-per-period=120",
                    "app.security.rate-limit.application.period=1m",
                    "app.security.rate-limit.application.burst-capacity=240",
                    "app.security.rate-limit.cache.maximum-client-buckets=50000",
                    "app.security.rate-limit.cache.expire-after-access=10m");

    @Test
    void commaSeparatedEnvironmentValuesBindToExactImmutableSets() {
        contextRunner
                .withPropertyValues(
                        "APP_SECURITY_CORS_ALLOWED_ORIGINS=https://one.example.test,https://two.example.test",
                        "APP_SECURITY_ALLOWED_HOSTS=one.example.test,two.example.test")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    var properties = context.getBean(SecurityProperties.class);
                    assertThat(properties.cors().allowedOrigins())
                            .containsExactlyInAnyOrder("https://one.example.test", "https://two.example.test");
                    assertThat(properties.requestAuthority().allowedHosts())
                            .containsExactlyInAnyOrder("one.example.test", "two.example.test");
                });
    }

    @Test
    void obsoleteRateLimitPropertyFailsStrictBinding() {
        contextRunner
                .withPropertyValues("app.security.rate-limit.tenant.tokens-per-period=1")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    void productionProfileRejectsEmptyHostOrCorsAllowlist() {
        contextRunner
                .withUserConfiguration(ProductionSecurityPropertiesValidator.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    void productionProfileAcceptsExplicitAllowlists() {
        contextRunner
                .withUserConfiguration(ProductionSecurityPropertiesValidator.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
                .withPropertyValues(
                        "APP_SECURITY_CORS_ALLOWED_ORIGINS=https://frontend.example.test",
                        "APP_SECURITY_ALLOWED_HOSTS=menu.example.test")
                .run(context -> assertThat(context.getStartupFailure()).isNull());
    }

    @Test
    void ownedYamlUsesNeutralSecurityAndAssetDefaults() throws Exception {
        var base = resource("application.yml");
        var local = resource("application-local.yml");
        var production = resource("application-production.yml");
        var test = resource("application-test.yml");

        assertThat(base).contains("forward-headers-strategy: none", "request-authority:", "application:");
        assertThat(local).contains("menu-assets-local", "localhost,127.0.0.1,[::1]");
        assertThat(test).contains("menu-assets-test", "allowed.example.test", "alias.example.test");
        assertThat(production).contains("APP_SECURITY_ALLOWED_HOSTS", "APP_SECURITY_CORS_ALLOWED_ORIGINS");
        assertThat(base + local + production + test)
                .doesNotContain(
                        "platform:",
                        "trusted-forwarding",
                        "maximum-tenant-buckets",
                        "tenant-assets");
    }

    private static String resource(String name) throws Exception {
        return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class PropertiesConfiguration {
    }
}
