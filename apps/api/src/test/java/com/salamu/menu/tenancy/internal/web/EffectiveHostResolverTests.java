package com.salamu.menu.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;

import com.salamu.menu.tenancy.api.TrustedProxyPolicy;
import com.salamu.menu.tenancy.internal.domain.HostNormalizer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class EffectiveHostResolverTests {

    @Test
    void ignoresSpoofedForwardedHostFromDirectClient() {
        var resolver = new EffectiveHostResolver(address -> false);
        var request = request("Direct.Example.Test:8080", "203.0.113.10");
        request.addHeader("Forwarded", "for=198.51.100.1;host=evil.example.test");
        request.addHeader("X-Forwarded-Host", "also-evil.example.test");

        assertThat(resolver.resolve(request)).isEqualTo("direct.example.test");
    }

    @Test
    void usesSingleForwardedHostFromTrustedIngress() throws Exception {
        var trustedAddress = InetAddress.getByName("10.0.0.10");
        TrustedProxyPolicy policy = trustedAddress::equals;
        var resolver = new EffectiveHostResolver(policy);
        var request = request("internal:8080", "10.0.0.10");
        request.addHeader("Forwarded", "for=203.0.113.2;proto=https;host=Tenant.Example.Test.:443");

        assertThat(resolver.resolve(request)).isEqualTo("tenant.example.test");
    }

    @Test
    void rejectsAmbiguousForwardedChainsFromTrustedIngress() {
        var resolver = new EffectiveHostResolver(address -> true);
        var forwarded = request("internal:8080", "10.0.0.10");
        forwarded.addHeader(
                "Forwarded",
                "for=203.0.113.2;host=attacker.example, for=10.0.0.9;host=tenant.example.test");
        var legacy = request("internal:8080", "10.0.0.10");
        legacy.addHeader("X-Forwarded-Host", "attacker.example, tenant.example.test");

        assertThatThrownBy(() -> resolver.resolve(forwarded))
                .isInstanceOf(HostNormalizer.InvalidHostException.class);
        assertThatThrownBy(() -> resolver.resolve(legacy))
                .isInstanceOf(HostNormalizer.InvalidHostException.class);
    }

    @Test
    void rejectsRepeatedForwardedHeaderLinesFromTrustedIngress() {
        var resolver = new EffectiveHostResolver(address -> true);
        var forwarded = request("internal:8080", "10.0.0.10");
        forwarded.addHeader("Forwarded", "for=203.0.113.2;host=attacker.example");
        forwarded.addHeader("Forwarded", "for=10.0.0.9;host=tenant.example.test");
        var legacy = request("internal:8080", "10.0.0.10");
        legacy.addHeader("X-Forwarded-Host", "attacker.example");
        legacy.addHeader("X-Forwarded-Host", "tenant.example.test");

        assertThatThrownBy(() -> resolver.resolve(forwarded))
                .isInstanceOf(HostNormalizer.InvalidHostException.class);
        assertThatThrownBy(() -> resolver.resolve(legacy))
                .isInstanceOf(HostNormalizer.InvalidHostException.class);
    }

    @Test
    void rejectsRepeatedHostLinesAndDoesNotTrustShorthandRemoteAddress() {
        var resolver = new EffectiveHostResolver(address -> address != null);
        var repeatedHost = request("tenant.example.test", "203.0.113.10");
        repeatedHost.addHeader("Host", "evil.example.test");
        var shorthandRemote = request("direct.example.test", "127.1");
        shorthandRemote.addHeader("X-Forwarded-Host", "evil.example.test");

        assertThatThrownBy(() -> resolver.resolve(repeatedHost))
                .isInstanceOf(HostNormalizer.InvalidHostException.class);
        assertThat(resolver.resolve(shorthandRemote)).isEqualTo("direct.example.test");
    }

    @Test
    void rejectsRepeatedHostParametersInsideOneForwardedEntry() {
        var resolver = new EffectiveHostResolver(address -> true);
        var request = request("internal:8080", "10.0.0.10");
        request.addHeader(
                "Forwarded",
                "for=203.0.113.2;host=attacker.example;by=10.0.0.9;host=tenant.example.test");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(HostNormalizer.InvalidHostException.class);
    }

    private static MockHttpServletRequest request(String host, String remoteAddress) {
        var request = new MockHttpServletRequest();
        request.addHeader("Host", host);
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
