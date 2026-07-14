package com.salamu.menu.tenancy.internal.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ConfiguredTrustedProxyPolicyTests {

    @Test
    void appliesIpv4PrefixBoundariesWithoutTrustingAdjacentAddress() throws Exception {
        var exact = policy(true, "192.0.2.10/32");
        assertThat(exact.isTrusted(InetAddress.getByName("192.0.2.10"))).isTrue();
        assertThat(exact.isTrusted(InetAddress.getByName("192.0.2.11"))).isFalse();

        var network = policy(true, "10.0.0.0/8");
        assertThat(network.isTrusted(InetAddress.getByName("10.255.255.255"))).isTrue();
        assertThat(network.isTrusted(InetAddress.getByName("11.0.0.1"))).isFalse();

        var allIpv4 = policy(true, "0.0.0.0/0");
        assertThat(allIpv4.isTrusted(InetAddress.getByName("203.0.113.7"))).isTrue();
        assertThat(allIpv4.isTrusted(InetAddress.getByName("2001:db8::1"))).isFalse();
    }

    @Test
    void appliesIpv6PrefixBoundaries() throws Exception {
        var exact = policy(true, "2001:db8::1/128");
        assertThat(exact.isTrusted(InetAddress.getByName("2001:db8::1"))).isTrue();
        assertThat(exact.isTrusted(InetAddress.getByName("2001:db8::2"))).isFalse();

        var allIpv6 = policy(true, "::/0");
        assertThat(allIpv6.isTrusted(InetAddress.getByName("2001:db8::2"))).isTrue();
        assertThat(allIpv6.isTrusted(InetAddress.getByName("192.0.2.10"))).isFalse();
    }

    @Test
    void disabledForwardingTrustsNoAddress() throws Exception {
        var policy = policy(false, "127.0.0.1/32");
        assertThat(policy.isTrusted(InetAddress.getByName("127.0.0.1"))).isFalse();
    }

    @Test
    void rejectsShorthandLeadingZeroAndDnsCidrValues() {
        assertThatThrownBy(() -> policy(true, "127.1/8")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(true, "010.0.0.1/32")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(true, "proxy.example.test/32"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(true, "2001:db8::gg/128"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ConfiguredTrustedProxyPolicy policy(boolean enabled, String... cidrs) {
        return new ConfiguredTrustedProxyPolicy(
                new TrustedForwardingProperties(enabled, Set.of(cidrs)));
    }
}
