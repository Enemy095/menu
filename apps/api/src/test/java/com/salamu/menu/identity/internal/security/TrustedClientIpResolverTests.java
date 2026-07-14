package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.Set;

import com.salamu.menu.tenancy.api.TrustedProxyPolicy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedClientIpResolverTests {

    @Test
    void directRequestIgnoresSpoofedForwardedHeader() {
        var resolver = new TrustedClientIpResolver(address -> false);
        var request = request("198.51.100.42", "203.0.113.10, 192.0.2.55");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void trustedIngressWalksRightToLeftAndReturnsFirstUntrustedHop() throws Exception {
        var trusted = Set.of(
                InetAddress.getByName("10.0.0.1"),
                InetAddress.getByName("10.0.0.2"));
        TrustedProxyPolicy policy = trusted::contains;
        var resolver = new TrustedClientIpResolver(policy);
        var request = request(
                "10.0.0.1",
                "192.0.2.200, 198.51.100.42, 10.0.0.2");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void invalidAmbiguousOrAllTrustedForwardingFallsBackToDirectPeer() throws Exception {
        var trusted = Set.of(
                InetAddress.getByName("10.0.0.1"),
                InetAddress.getByName("10.0.0.2"));
        var resolver = new TrustedClientIpResolver(trusted::contains);

        assertThat(resolver.resolve(request("10.0.0.1", "phone=+79991112233")))
                .isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", "10.0.0.2")))
                .isEqualTo("10.0.0.1");

        var multipleHeaders = request("10.0.0.1", "198.51.100.42");
        multipleHeaders.addHeader(TrustedClientIpResolver.FORWARDED_FOR_HEADER, "203.0.113.10");
        assertThat(resolver.resolve(multipleHeaders)).isEqualTo("10.0.0.1");
    }

    @Test
    void invalidDirectPeerUsesOneFailClosedKey() {
        var resolver = new TrustedClientIpResolver(address -> true);
        var request = request("not-an-ip", "198.51.100.42");

        assertThat(resolver.resolve(request)).isEqualTo("invalid-remote");
    }

    @Test
    void rejectsAmbiguousLeadingZeroIpv4Octets() {
        assertThat(TrustedClientIpResolver.parseIpLiteral("010.0.0.1")).isEmpty();
        assertThat(TrustedClientIpResolver.parseIpLiteral("10.00.0.1")).isEmpty();
        assertThat(TrustedClientIpResolver.parseIpLiteral("10.0.0.01")).isEmpty();
        assertThat(TrustedClientIpResolver.parseIpLiteral("10.0.0.1")).isPresent();
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader(TrustedClientIpResolver.FORWARDED_FOR_HEADER, forwardedFor);
        return request;
    }
}
