package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedClientIpResolverTests {

    @Test
    void untrustedDirectPeerIgnoresAllForwardingHeaders() {
        var resolver = resolver(false, Set.of("10.0.0.0/8"));
        var request = request("198.51.100.42", "203.0.113.10, 10.0.0.2");
        request.addHeader("Forwarded", "for=192.0.2.10");
        request.addHeader("X-Forwarded-Host", "attacker.example");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void trustedIngressWalksRightToLeftAndReturnsFirstUntrustedHop() {
        var resolver = resolver(true, Set.of("10.0.0.0/8", "2001:db8::/32"));

        assertThat(resolver.resolve(request(
                "10.0.0.1",
                "192.0.2.200, 198.51.100.42, 10.0.0.2")))
                .isEqualTo("198.51.100.42");
        assertThat(resolver.resolve(request(
                "2001:db8::1",
                "2001:db9::5, 2001:db8::2")))
                .isEqualTo("2001:db9:0:0:0:0:0:5");
    }

    @Test
    void malformedMultipleOversizedTooManyOrAllTrustedHeaderFallsBackToDirectPeer() {
        var resolver = resolver(true, Set.of("10.0.0.0/8"));

        assertThat(resolver.resolve(request("10.0.0.1", "phone=+79991112233"))).isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", "10.0.0.2"))).isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", "198.51.100.1,"))).isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", "1".repeat(TrustedClientIpResolver.MAX_FORWARDED_HEADER_LENGTH + 1))))
                .isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request(
                "10.0.0.1",
                java.util.stream.IntStream.range(0, TrustedClientIpResolver.MAX_FORWARDED_HOPS + 1)
                        .mapToObj(index -> "198.51.100.1")
                        .collect(java.util.stream.Collectors.joining(",")))))
                .isEqualTo("10.0.0.1");

        var multiple = request("10.0.0.1", "198.51.100.42");
        multiple.addHeader(TrustedClientIpResolver.FORWARDED_FOR_HEADER, "203.0.113.10");
        assertThat(resolver.resolve(multiple)).isEqualTo("10.0.0.1");
    }

    @Test
    void invalidDirectPeerUsesOneFailClosedNonInputKey() {
        var resolver = resolver(true, Set.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("not-an-ip", "198.51.100.42"))).isEqualTo("invalid-remote");
    }

    @Test
    void literalParserCanonicalizesAddressesAndNeverAcceptsHostnamesOrAmbiguousIpv4() {
        assertThat(TrustedClientIpResolver.parseIpLiteral("010.0.0.1")).isEmpty();
        assertThat(TrustedClientIpResolver.parseIpLiteral("10.00.0.1")).isEmpty();
        assertThat(TrustedClientIpResolver.parseIpLiteral("10.0.0.01")).isEmpty();
        assertThat(TrustedClientIpResolver.parseIpLiteral("localhost")).isEmpty();
        assertThat(TrustedClientIpResolver.parseIpLiteral("example.com")).isEmpty();
        assertThat(TrustedClientIpResolver.parseIpLiteral("10.0.0.1")).isPresent();
        assertThat(TrustedClientIpResolver.parseIpLiteral("2001:db8::1")).isPresent();
    }

    private static TrustedClientIpResolver resolver(boolean enabled, Set<String> cidrs) {
        var properties = SecurityTestProperties.properties(
                Set.of(),
                Set.of("localhost"),
                enabled,
                cidrs,
                new SecurityProperties.Limit(5, Duration.ofMinutes(1), 10),
                new SecurityProperties.Limit(120, Duration.ofMinutes(1), 240),
                100,
                Duration.ofMinutes(10));
        return new TrustedClientIpResolver(new TrustedProxyPolicy(properties));
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader(TrustedClientIpResolver.FORWARDED_FOR_HEADER, forwardedFor);
        return request;
    }
}
