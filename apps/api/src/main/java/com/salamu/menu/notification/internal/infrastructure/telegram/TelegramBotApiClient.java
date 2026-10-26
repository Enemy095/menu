package com.salamu.menu.notification.internal.infrastructure.telegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.notification.telegram",
        name = "enabled",
        havingValue = "true")
final class TelegramBotApiClient implements TelegramBotApi {

    private final TelegramProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    TelegramBotApiClient(TelegramProperties properties, ObjectMapper objectMapper) {
        this(
                properties,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    TelegramBotApiClient(
            TelegramProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public SendResult sendHtmlMessage(String text, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint())
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(text)))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());
            return mapResponse(response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return SendResult.transientFailure("TELEGRAM_CALL_INTERRUPTED");
        } catch (IOException exception) {
            return SendResult.transientFailure("TELEGRAM_NETWORK_FAILURE");
        } catch (RuntimeException exception) {
            return SendResult.permanentFailure("TELEGRAM_REQUEST_INVALID");
        }
    }

    private URI endpoint() {
        String base = properties.apiBaseUrl().toString();
        if (!base.endsWith("/")) {
            base += "/";
        }
        return URI.create(base + "bot" + properties.botToken() + "/sendMessage");
    }

    private String requestBody(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", properties.chatId());
        body.put("text", text);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (properties.messageThreadId() != null) {
            body.put("message_thread_id", properties.messageThreadId());
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Telegram request cannot be encoded", exception);
        }
    }

    private SendResult mapResponse(int status, String body) {
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return SendResult.transientFailure(status == 429
                    ? "TELEGRAM_RATE_LIMITED"
                    : "TELEGRAM_REMOTE_UNAVAILABLE");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException exception) {
            return status >= 400
                    ? SendResult.permanentFailure("TELEGRAM_REQUEST_REJECTED")
                    : SendResult.transientFailure("TELEGRAM_RESPONSE_INVALID");
        }
        if (status >= 200 && status < 300 && root.path("ok").asBoolean(false)) {
            JsonNode messageId = root.path("result").path("message_id");
            if (!messageId.canConvertToLong()) {
                return SendResult.transientFailure("TELEGRAM_RESPONSE_INVALID");
            }
            return SendResult.succeeded(messageId.asText());
        }
        int apiError = root.path("error_code").asInt(status);
        return switch (apiError) {
            case 401 -> SendResult.permanentFailure("TELEGRAM_TOKEN_INVALID");
            case 403 -> SendResult.permanentFailure("TELEGRAM_CHAT_FORBIDDEN");
            case 429 -> SendResult.transientFailure("TELEGRAM_RATE_LIMITED");
            default -> apiError >= 500
                    ? SendResult.transientFailure("TELEGRAM_REMOTE_UNAVAILABLE")
                    : SendResult.permanentFailure("TELEGRAM_REQUEST_REJECTED");
        };
    }
}
