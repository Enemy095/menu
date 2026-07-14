package com.salamu.menu.tenancy.internal.infrastructure.config;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.platform.hosts")
public record PlatformHostsProperties(
        @NotBlank String baseDomain,
        @NotEmpty Set<@NotBlank String> allowedHosts) {

    public PlatformHostsProperties {
        allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
    }
}
