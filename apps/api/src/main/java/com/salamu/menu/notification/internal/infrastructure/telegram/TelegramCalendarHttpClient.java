package com.salamu.menu.notification.internal.infrastructure.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.telegram-calendar", name = "enabled", havingValue = "true")
final class TelegramCalendarHttpClient implements TelegramCalendarGateway {
    private final TelegramProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    TelegramCalendarHttpClient(TelegramProperties properties, ObjectMapper mapper) {
        if (properties.botToken() == null || properties.botToken().isBlank()) throw new IllegalArgumentException("Calendar bot token is required");
        var uri = properties.apiBaseUrl();
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || ("http".equalsIgnoreCase(uri.getScheme())
                && java.util.Set.of("127.0.0.1", "localhost", "::1").contains(uri.getHost()))))
            throw new IllegalArgumentException("Calendar bot API requires HTTPS outside loopback tests");
        this.properties = properties; this.mapper = mapper;
    }
    private JsonNode call(String method, Map<String,Object> body) {
        try {
            var base = properties.apiBaseUrl().toString().replaceAll("/$", "");
            var request = HttpRequest.newBuilder(URI.create(base + "/bot" + properties.botToken() + "/" + method))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var result = mapper.readTree(response.body());
            if (response.statusCode() == 200 && result.path("ok").asBoolean(false)) return result.path("result");
            if (method.equals("editMessageText") && response.statusCode() == 400
                    && result.path("description").asText("").contains("message is not modified")) return result;
            throw new IllegalStateException("TELEGRAM_CALENDAR_REMOTE_FAILURE");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("TELEGRAM_CALENDAR_INTERRUPTED");
        } catch (Exception exception) {
            // Never attach the HTTP exception: its URL contains the bot token.
            throw new IllegalStateException("TELEGRAM_CALENDAR_UNAVAILABLE");
        }
    }
    public JsonNode updates(long offset) {
        return call("getUpdates", Map.of("offset", offset, "timeout", 0, "limit", 25, "allowed_updates", java.util.List.of("message", "callback_query")));
    }
    public void send(long chatId, String text, Map<String,Object> keyboard) {
        call("sendMessage", Map.of("chat_id", chatId, "text", text, "reply_markup", keyboard));
    }
    public void edit(long chatId, long messageId, String text, Map<String,Object> keyboard) {
        call("editMessageText", Map.of("chat_id", chatId, "message_id", messageId, "text", text, "reply_markup", keyboard));
    }
    public void answer(String callbackId) {
        try { call("answerCallbackQuery", Map.of("callback_query_id", callbackId)); } catch (RuntimeException ignored) { /* Expired callback acknowledgements must not block subsequent updates. */ }
    }
}
