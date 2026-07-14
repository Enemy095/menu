package com.salamu.menu.restaurant.internal.infrastructure.config;

import java.net.URI;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.external")
public record ExternalConfigurationProperties(
        @NotNull URI publicBaseUrl,
        @NotNull URI assetBaseUrl) {
}
