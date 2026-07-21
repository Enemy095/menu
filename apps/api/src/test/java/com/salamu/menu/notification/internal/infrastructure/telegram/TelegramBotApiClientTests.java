package com.salamu.menu.notification.internal.infrastructure.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TelegramBotApiClientTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsExpectedJsonAndMapsMessageId() throws IOException {
        var capturedBody = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot123456:test-token/sendMessage", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"ok\":true,\"result\":{\"message_id\":321}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        var properties = new TelegramProperties(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "123456:test-token",
                "-100123",
                7,
                "secret://application/telegram/orders");
        var client = new TelegramBotApiClient(properties, new ObjectMapper(), HttpClient.newHttpClient());

        TelegramBotApi.SendResult result = client.sendHtmlMessage("<b>Заказ</b>", Duration.ofSeconds(3));

        assertThat(result.outcome()).isEqualTo(TelegramBotApi.SendResult.Outcome.SUCCEEDED);
        assertThat(result.messageId()).isEqualTo("321");
        assertThat(capturedBody.get())
                .contains("\"chat_id\":\"-100123\"")
                .contains("\"parse_mode\":\"HTML\"")
                .contains("\"message_thread_id\":7")
                .contains("\"text\":\"<b>Заказ</b>\"");
    }

    @Test
    void mapsRateLimitToTransientFailure() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot123456:test-token/sendMessage", exchange -> {
            byte[] response = "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        var properties = new TelegramProperties(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "123456:test-token",
                "123",
                null,
                "secret://application/telegram/orders");
        var client = new TelegramBotApiClient(properties, new ObjectMapper(), HttpClient.newHttpClient());

        TelegramBotApi.SendResult result = client.sendHtmlMessage("test", Duration.ofSeconds(3));

        assertThat(result.outcome()).isEqualTo(TelegramBotApi.SendResult.Outcome.TRANSIENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("TELEGRAM_RATE_LIMITED");
    }
}
