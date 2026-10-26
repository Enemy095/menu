package com.salamu.menu.notification.internal.infrastructure.telegram;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.telegram-calendar")
public record TelegramCalendarProperties(boolean enabled, Set<Long> ownerUserIds) {
    public TelegramCalendarProperties {
        ownerUserIds = ownerUserIds == null ? Set.of() : Set.copyOf(ownerUserIds);
        if (enabled && (ownerUserIds.isEmpty() || ownerUserIds.stream().anyMatch(id -> id <= 0))) {
            throw new IllegalArgumentException("Telegram calendar requires positive owner user IDs");
        }
    }
    @Override public String toString() { return "TelegramCalendarProperties[enabled=" + enabled + ", ownerUserIds=redacted]"; }
}
