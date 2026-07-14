package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

class SecurityPropertiesTests {

    @Test
    void acceptsEmptyOrExactOriginAllowlistAndPositiveLimits() {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var validator = validatorFactory.getValidator();

            assertThat(validator.validate(properties(Set.of()))).isEmpty();
            assertThat(validator.validate(properties(Set.of(
                    "https://restaurant.example",
                    "http://localhost:3000"))))
                    .isEmpty();
        }
    }

    @Test
    void rejectsWildcardCredentialedOrNonOriginCorsValues() {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var validator = validatorFactory.getValidator();

            for (var origin : Set.of(
                    "*",
                    "https://*.example.test",
                    "https://user@example.test",
                    "https://example.test/path",
                    "https://example.test?query=true",
                    "https://example.test#fragment",
                    "ftp://example.test")) {
                assertThat(validator.validate(properties(Set.of(origin))))
                        .as("origin %s is rejected", origin)
                        .isNotEmpty();
            }
        }
    }

    @Test
    void rejectsInvalidLimitAndCacheRelationships() {
        var invalid = new SecurityProperties(
                new SecurityProperties.Cors(Set.of()),
                new SecurityProperties.RateLimit(
                        new SecurityProperties.Limit(5, Duration.ZERO, 4),
                        new SecurityProperties.Limit(120, Duration.ofMinutes(1), 240),
                        new SecurityProperties.Cache(0, 1, Duration.ofMinutes(-1))));

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var violations = validatorFactory.getValidator().validate(invalid);

            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains(
                            "rateLimit.client.periodPositive",
                            "rateLimit.client.burstCapacityValid",
                            "rateLimit.cache.maximumClientBuckets",
                            "rateLimit.cache.expiryPositive");
        }
    }

    private static SecurityProperties properties(Set<String> origins) {
        return new SecurityProperties(
                new SecurityProperties.Cors(origins),
                new SecurityProperties.RateLimit(
                        new SecurityProperties.Limit(5, Duration.ofMinutes(1), 10),
                        new SecurityProperties.Limit(120, Duration.ofMinutes(1), 240),
                        new SecurityProperties.Cache(50_000, 5_000, Duration.ofMinutes(10))));
    }
}
