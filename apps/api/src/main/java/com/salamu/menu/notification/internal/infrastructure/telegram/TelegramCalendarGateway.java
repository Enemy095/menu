package com.salamu.menu.notification.internal.infrastructure.telegram;

import java.util.Map;
import tools.jackson.databind.JsonNode;

interface TelegramCalendarGateway {
    JsonNode updates(long offset);
    void send(long chatId, String text, Map<String, Object> keyboard);
    void edit(long chatId, long messageId, String text, Map<String, Object> keyboard);
    void answer(String callbackId);
}
