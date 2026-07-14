package com.salamu.menu.tenancy.internal.infrastructure.config;

import java.util.Set;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.platform.trusted-forwarding")
public record TrustedForwardingProperties(
        boolean enabled,
        Set<@NotBlank String> trustedProxyCidrs) {

    public TrustedForwardingProperties {
        trustedProxyCidrs = trustedProxyCidrs == null ? Set.of() : Set.copyOf(trustedProxyCidrs);
    }

    @AssertTrue(message = "trusted proxy CIDRs are required when forwarded headers are enabled")
    public boolean isConfigurationSafe() {
        return !enabled || !trustedProxyCidrs.isEmpty();
    }
}
