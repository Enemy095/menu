package com.salamu.menu.notification.internal.infrastructure.telegram;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.internal.application.NotificationProvider;
import com.salamu.menu.notification.internal.infrastructure.telegram.TelegramBotApi.SendResult;
import com.salamu.menu.notification.internal.infrastructure.telegram.TelegramOrderMessageFactory.InvalidOrderEventException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.notification.telegram",
        name = "enabled",
        havingValue = "true")
public final class TelegramNotificationProvider implements NotificationProvider {

    private final TelegramProperties properties;
    private final TelegramOrderMessageFactory messageFactory;
    private final TelegramBotApi botApi;

    TelegramNotificationProvider(
            TelegramProperties properties,
            TelegramOrderMessageFactory messageFactory,
            TelegramBotApi botApi) {
        this.properties = properties;
        this.messageFactory = messageFactory;
        this.botApi = botApi;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.TELEGRAM;
    }

    @Override
    public ProviderResult send(ProviderRequest request) {
        if (!"order.created".equals(request.eventType()) || request.schemaVersion() != 1) {
            return ProviderResult.permanentFailure("TELEGRAM_EVENT_UNSUPPORTED");
        }
        if (!properties.destinationSecretReference()
                .equals(request.secretReference().persistenceValue())) {
            return ProviderResult.permanentFailure("TELEGRAM_DESTINATION_UNKNOWN");
        }

        final String message;
        try {
            message = messageFactory.create(request.payload());
        } catch (InvalidOrderEventException exception) {
            return ProviderResult.permanentFailure("TELEGRAM_PAYLOAD_INVALID");
        }

        SendResult result = botApi.sendHtmlMessage(message, request.timeout());
        return switch (result.outcome()) {
            case SUCCEEDED -> ProviderResult.succeeded(result.messageId());
            case TRANSIENT_FAILURE -> ProviderResult.transientFailure(result.errorCode());
            case PERMANENT_FAILURE -> ProviderResult.permanentFailure(result.errorCode());
        };
    }
}
