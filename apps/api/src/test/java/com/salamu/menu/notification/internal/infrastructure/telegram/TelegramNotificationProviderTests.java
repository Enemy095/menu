package com.salamu.menu.notification.internal.infrastructure.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;

import com.salamu.menu.notification.internal.application.NotificationProvider.ProviderRequest;
import com.salamu.menu.notification.internal.application.NotificationProvider.ProviderResult;
import com.salamu.menu.notification.internal.application.OpaqueEventPayload;
import com.salamu.menu.notification.internal.application.OpaqueSecretReference;
import com.salamu.menu.notification.internal.infrastructure.telegram.TelegramBotApi.SendResult;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TelegramNotificationProviderTests {

    private final TelegramProperties properties = new TelegramProperties(
            true,
            URI.create("https://api.telegram.org"),
            "123456:secret-token",
            "123456789",
            null,
            "secret://application/telegram/orders");

    @Test
    void mapsSuccessfulTelegramDelivery() {
        var botApi = new FakeBotApi(SendResult.succeeded("789"));
        var provider = provider(botApi);

        ProviderResult result = provider.send(request(
                validPayload(),
                "secret://application/telegram/orders"));

        assertThat(result.outcome()).isEqualTo(ProviderResult.Outcome.SUCCEEDED);
        assertThat(result.providerMessageId()).isEqualTo("789");
        assertThat(botApi.text).contains("НОВЫЙ ЗАКАЗ №1042");
        assertThat(botApi.timeout).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rejectsUnknownDestinationWithoutCallingTelegram() {
        var botApi = new FakeBotApi(SendResult.succeeded("789"));
        ProviderResult result = provider(botApi).send(request(
                validPayload(),
                "secret://application/telegram/other"));

        assertThat(result.outcome()).isEqualTo(ProviderResult.Outcome.PERMANENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("TELEGRAM_DESTINATION_UNKNOWN");
        assertThat(botApi.text).isNull();
    }

    @Test
    void rejectsInvalidPayloadWithoutCallingTelegram() {
        var botApi = new FakeBotApi(SendResult.succeeded("789"));
        ProviderResult result = provider(botApi).send(request(
                "{\"orderNumber\":\"1042\"}",
                "secret://application/telegram/orders"));

        assertThat(result.outcome()).isEqualTo(ProviderResult.Outcome.PERMANENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("TELEGRAM_PAYLOAD_INVALID");
        assertThat(botApi.text).isNull();
    }

    private TelegramNotificationProvider provider(FakeBotApi botApi) {
        return new TelegramNotificationProvider(
                properties,
                new TelegramOrderMessageFactory(new ObjectMapper()),
                botApi);
    }

    private static ProviderRequest request(String payload, String secretReference) {
        return new ProviderRequest(
                "order.created",
                1,
                new OpaqueEventPayload(payload),
                new OpaqueSecretReference(secretReference),
                "a".repeat(64),
                Duration.ofSeconds(5));
    }

    private static String validPayload() {
        return """
                {
                  "orderNumber": "1042",
                  "orderType": "pickup",
                  "customerName": "Иван",
                  "customerPhone": "+7 900 000-00-00",
                  "guestCount": 1,
                  "serverTotalMinor": 25000,
                  "currency": "RUB",
                  "serverReceivedAt": "2026-07-17T18:42:00Z",
                  "items": [
                    {
                      "name": "Цезарь с курицей",
                      "quantity": 1,
                      "lineTotalMinor": 25000,
                      "modifiers": []
                    }
                  ]
                }
                """;
    }

    private static final class FakeBotApi implements TelegramBotApi {
        private final SendResult result;
        private String text;
        private Duration timeout;

        private FakeBotApi(SendResult result) {
            this.result = result;
        }

        @Override
        public SendResult sendHtmlMessage(String text, Duration timeout) {
            this.text = text;
            this.timeout = timeout;
            return result;
        }
    }
}
