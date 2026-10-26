package com.salamu.menu.identity.internal.security;

import java.time.Duration;
import java.util.Set;

final class SecurityTestProperties {

    private SecurityTestProperties() {
    }

    static SecurityProperties defaults() {
        return properties(
                Set.of("https://frontend.example.test"),
                Set.of("localhost", "127.0.0.1", "[::1]", "allowed.example.test", "alias.example.test"),
                false,
                Set.of(),
                new SecurityProperties.Limit(5, Duration.ofMinutes(1), 10),
                new SecurityProperties.Limit(120, Duration.ofMinutes(1), 240),
                50_000,
                Duration.ofMinutes(10));
    }

    static SecurityProperties properties(
            Set<String> origins,
            Set<String> hosts,
            boolean trustedProxiesEnabled,
            Set<String> trustedProxyCidrs,
            SecurityProperties.Limit clientLimit,
            SecurityProperties.Limit applicationLimit,
            long maximumClientBuckets,
            Duration expiry) {
        return new SecurityProperties(
                new SecurityProperties.Cors(origins),
                new SecurityProperties.RequestAuthority(hosts),
                new SecurityProperties.TrustedProxies(trustedProxiesEnabled, trustedProxyCidrs),
                new SecurityProperties.RateLimit(
                        clientLimit,
                        applicationLimit,
                        new SecurityProperties.Cache(maximumClientBuckets, expiry)));
    }
}
