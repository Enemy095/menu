package com.salamu.menu.notification.internal.infrastructure.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salamu.menu.notification.internal.application.OpaqueEventPayload;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TelegramOrderMessageFactoryTests {

    private final TelegramOrderMessageFactory factory =
            new TelegramOrderMessageFactory(new ObjectMapper(), site());

    static com.salamu.menu.site.api.SiteConfigurationQuery site() {
        var query = org.mockito.Mockito.mock(com.salamu.menu.site.api.SiteConfigurationQuery.class);
        var configuration = org.mockito.Mockito.mock(com.salamu.menu.site.api.SiteConfigurationSnapshot.class);
        org.mockito.Mockito.when(query.current()).thenReturn(configuration);
        org.mockito.Mockito.when(configuration.timezone()).thenReturn("Europe/Moscow");
        return query;
    }

    @Test
    void rendersCompleteEscapedOrderMessage() {
        String message = factory.create(new OpaqueEventPayload("""
                {
                  "orderNumber": "1042",
                  "orderType": "delivery",
                  "customerName": "Иван <Петров>",
                  "customerPhone": "+7 900 000-00-00",
                  "customerAddress": "ул. Центральная, 10 & 2",
                  "comment": "Позвонить <за 5 минут>",
                  "guestCount": 3,
                  "serverTotalMinor": 109000,
                  "currency": "RUB",
                  "serverReceivedAt": "2026-07-17T18:42:00.320350Z",
                  "items": [
                    {
                      "name": "Цезарь с курицей",
                      "quantity": 2,
                      "lineTotalMinor": 50000,
                      "modifiers": [
                        {"optionName": "Острый соус", "quantity": 1}
                      ]
                    },
                    {
                      "name": "Пепперони",
                      "quantity": 1,
                      "lineTotalMinor": 59000,
                      "modifiers": []
                    }
                  ]
                }
                """));

        assertThat(message)
                .contains("🍽 <b>НОВЫЙ ЗАКАЗ №1042</b>")
                .contains("<b>Получение:</b> <b>Доставка</b>\n\n<b>Клиент:</b>")
                .contains("<b>Получен:</b> 17.07.2026 21:42:00")
                .doesNotContain(".320350", "T18:42")
                .contains("Иван &lt;Петров&gt;")
                .contains("ул. Центральная, 10 &amp; 2")
                .contains("• Цезарь с курицей × 2 — 500,00 ₽")
                .contains("<i>Острый соус</i>")
                .contains("<b>Приборов:</b> 3")
                .contains("Позвонить &lt;за 5 минут&gt;")
                .contains("<b>ИТОГО: 1 090,00 ₽</b>");
    }

    @Test
    void rejectsMalformedOrIncompletePayload() {
        assertThatThrownBy(() -> factory.create(new OpaqueEventPayload("{\"orderNumber\":\"1\"}")))
                .isInstanceOf(TelegramOrderMessageFactory.InvalidOrderEventException.class)
                .hasMessage("Telegram order payload is invalid");
    }
}
