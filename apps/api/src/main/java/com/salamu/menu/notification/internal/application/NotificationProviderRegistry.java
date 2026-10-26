package com.salamu.menu.notification.internal.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.salamu.menu.notification.api.NotificationChannel;

import org.springframework.stereotype.Component;

@Component
public class NotificationProviderRegistry {

    private final Map<NotificationChannel, NotificationProvider> providers;

    public NotificationProviderRegistry(List<NotificationProvider> configuredProviders) {
        var indexed = new EnumMap<NotificationChannel, NotificationProvider>(NotificationChannel.class);
        for (NotificationProvider provider : configuredProviders) {
            if (indexed.putIfAbsent(provider.channel(), provider) != null) {
                throw new IllegalStateException("More than one provider is configured for a notification channel");
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public Optional<NotificationProvider> providerFor(NotificationChannel channel) {
        return Optional.ofNullable(providers.get(channel));
    }
}
