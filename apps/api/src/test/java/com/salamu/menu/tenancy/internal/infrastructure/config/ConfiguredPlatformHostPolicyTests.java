package com.salamu.menu.tenancy.internal.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.salamu.menu.tenancy.internal.application.PlatformHostPolicy;

import org.junit.jupiter.api.Test;

class ConfiguredPlatformHostPolicyTests {

    @Test
    void classifiesNormalizedPlatformLocalAndTenantHosts() {
        var policy = new ConfiguredPlatformHostPolicy(new PlatformHostsProperties(
                "Platform.Example.",
                Set.of("menu.platform.example"),
                Set.of("LOCALHOST", "127.0.0.1", "::1")));

        assertThat(policy.classify("platform.example")).isEqualTo(PlatformHostPolicy.HostKind.PLATFORM);
        assertThat(policy.classify("menu.platform.example"))
                .isEqualTo(PlatformHostPolicy.HostKind.PLATFORM);
        assertThat(policy.classify("localhost")).isEqualTo(PlatformHostPolicy.HostKind.LOCAL);
        assertThat(policy.classify("0:0:0:0:0:0:0:1"))
                .isEqualTo(PlatformHostPolicy.HostKind.LOCAL);
        assertThat(policy.classify("custom.example"))
                .isEqualTo(PlatformHostPolicy.HostKind.TENANT_DOMAIN);
    }

    @Test
    void rejectsOverlappingPlatformAndLocalConfiguration() {
        assertThatThrownBy(() -> new ConfiguredPlatformHostPolicy(new PlatformHostsProperties(
                "platform.example",
                Set.of("localhost"),
                Set.of("localhost"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
