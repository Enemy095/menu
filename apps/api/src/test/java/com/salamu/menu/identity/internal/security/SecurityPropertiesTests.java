package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

class SecurityPropertiesTests {

    @Test
    void acceptsExactOriginsHostsCidrsAndPositiveLimits() {
        var properties = SecurityTestProperties.properties(
                Set.of("https://restaurant.example", "http://localhost:3000"),
                Set.of("restaurant.example", "LOCALHOST", "127.0.0.1", "[::1]"),
                true,
                Set.of("10.0.0.0/8", "2001:db8::/32"),
                new SecurityProperties.Limit(5, Duration.ofMinutes(1), 10),
                new SecurityProperties.Limit(120, Duration.ofMinutes(1), 240),
                50_000,
                Duration.ofMinutes(10));

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertThat(validatorFactory.getValidator().validate(properties)).isEmpty();
        }
    }

    @Test
    void copiesAllConfiguredCollections() {
        var origins = new java.util.HashSet<>(Set.of("https://restaurant.example"));
        var hosts = new java.util.HashSet<>(Set.of("restaurant.example"));
        var cidrs = new java.util.HashSet<>(Set.of("10.0.0.0/8"));
        var properties = SecurityTestProperties.properties(
                origins,
                hosts,
                true,
                cidrs,
                new SecurityProperties.Limit(5, Duration.ofMinutes(1), 10),
                new SecurityProperties.Limit(120, Duration.ofMinutes(1), 240),
                50_000,
                Duration.ofMinutes(10));

        origins.clear();
        hosts.clear();
        cidrs.clear();

        assertThat(properties.cors().allowedOrigins()).containsExactly("https://restaurant.example");
        assertThat(properties.requestAuthority().allowedHosts()).containsExactly("restaurant.example");
        assertThat(properties.trustedProxies().cidrs()).containsExactly("10.0.0.0/8");
        assertThatThrownByMutation(properties);
    }

    @Test
    void rejectsWildcardCredentialedOrNonOriginCorsValues() {
        for (var origin : Set.of(
                "*",
                "https://*.example.test",
                "https://user@example.test",
                "https://example.test/path",
                "https://example.test?query=true",
                "https://example.test#fragment",
                "https://example.test:0",
                "https://example.test:65536",
                "ftp://example.test")) {
            assertThat(violations(SecurityTestProperties.properties(
                    Set.of(origin),
                    Set.of("example.test"),
                    false,
                    Set.of(),
                    validClientLimit(),
                    validApplicationLimit(),
                    100,
                    Duration.ofMinutes(1))))
                    .as("origin %s is rejected", origin)
                    .isNotEmpty();
        }
    }

    @Test
    void rejectsMalformedHostLiteralsAndCidrs() {
        for (var host : Set.of(
                "*",
                "https://example.test",
                "user@example.test",
                "example.test/path",
                "example.test?query=true",
                "example.test#fragment",
                "example.test:443",
                "010.0.0.1",
                "[::1]:8080",
                "bad host")) {
            assertThat(violations(SecurityTestProperties.properties(
                    Set.of(),
                    Set.of(host),
                    false,
                    Set.of(),
                    validClientLimit(),
                    validApplicationLimit(),
                    100,
                    Duration.ofMinutes(1))))
                    .as("host %s is rejected", host)
                    .isNotEmpty();
        }

        for (var cidr : Set.of("localhost/8", "10.0.0.0", "10.0.0.0/33", "2001:db8::/129", "010.0.0.0/8")) {
            assertThat(violations(SecurityTestProperties.properties(
                    Set.of(),
                    Set.of("example.test"),
                    true,
                    Set.of(cidr),
                    validClientLimit(),
                    validApplicationLimit(),
                    100,
                    Duration.ofMinutes(1))))
                    .as("CIDR %s is rejected", cidr)
                    .isNotEmpty();
        }
    }

    @Test
    void rejectsInvalidLimitAndCacheRelationships() {
        var invalid = SecurityTestProperties.properties(
                Set.of(),
                Set.of(),
                false,
                Set.of(),
                new SecurityProperties.Limit(5, Duration.ZERO, 4),
                new SecurityProperties.Limit(120, Duration.ofMinutes(-1), 119),
                0,
                Duration.ZERO);

        assertThat(violations(invalid))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "rateLimit.client.periodPositive",
                        "rateLimit.client.burstCapacityValid",
                        "rateLimit.application.periodPositive",
                        "rateLimit.application.burstCapacityValid",
                        "rateLimit.cache.maximumClientBuckets",
                        "rateLimit.cache.expiryPositive");
    }

    private static Set<jakarta.validation.ConstraintViolation<SecurityProperties>> violations(
            SecurityProperties properties) {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            return validatorFactory.getValidator().validate(properties);
        }
    }

    private static SecurityProperties.Limit validClientLimit() {
        return new SecurityProperties.Limit(5, Duration.ofMinutes(1), 10);
    }

    private static SecurityProperties.Limit validApplicationLimit() {
        return new SecurityProperties.Limit(120, Duration.ofMinutes(1), 240);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertThatThrownByMutation(SecurityProperties properties) {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> ((Set) properties.cors().allowedOrigins()).add("https://other.example")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> ((Set) properties.requestAuthority().allowedHosts()).add("other.example")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> ((Set) properties.trustedProxies().cidrs()).add("192.0.2.0/24")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
