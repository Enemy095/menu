package com.salamu.menu.notification.internal.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.worker")
public record WorkerProperties(boolean enabled) {
}
