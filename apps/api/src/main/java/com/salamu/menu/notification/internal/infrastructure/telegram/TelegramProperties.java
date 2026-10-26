package com.salamu.menu.notification.internal.infrastructure.telegram;

import java.net.URI;

import com.salamu.menu.notification.internal.application.OpaqueSecretReference;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.notification.telegram")
public record TelegramProperties(
        boolean enabled,
        URI apiBaseUrl,
        String botToken,
        String chatId,
        Integer messageThreadId,
        String destinationSecretReference) {

    public TelegramProperties {
        if (enabled) {
            if (apiBaseUrl == null
                    || !apiBaseUrl.isAbsolute()
                    || apiBaseUrl.getHost() == null
                    || apiBaseUrl.getUserInfo() != null
                    || apiBaseUrl.getQuery() != null
                    || apiBaseUrl.getFragment() != null) {
                throw new IllegalArgumentException("app.notification.telegram.api-base-url must be an absolute URL");
            }
            boolean loopback = "localhost".equalsIgnoreCase(apiBaseUrl.getHost())
                    || "127.0.0.1".equals(apiBaseUrl.getHost())
                    || "::1".equals(apiBaseUrl.getHost());
            if (!"https".equalsIgnoreCase(apiBaseUrl.getScheme())
                    && !(loopback && "http".equalsIgnoreCase(apiBaseUrl.getScheme()))) {
                throw new IllegalArgumentException(
                        "app.notification.telegram.api-base-url must use HTTPS outside loopback tests");
            }
            if (botToken == null
                    || botToken.isBlank()
                    || botToken.length() > 256
                    || botToken.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("app.notification.telegram.bot-token is invalid");
            }
            if (chatId == null || !chatId.matches("-?[1-9][0-9]{0,19}")) {
                throw new IllegalArgumentException("app.notification.telegram.chat-id is invalid");
            }
            if (messageThreadId != null && messageThreadId < 1) {
                throw new IllegalArgumentException("app.notification.telegram.message-thread-id must be positive");
            }
            if (!new OpaqueSecretReference(destinationSecretReference).valid()) {
                throw new IllegalArgumentException(
                        "app.notification.telegram.destination-secret-reference is invalid");
            }
        }
    }

    @Override
    public String toString() {
        return "TelegramProperties[enabled=" + enabled
                + ", apiBaseUrl=" + apiBaseUrl
                + ", botToken=redacted, chatId=redacted, messageThreadId=redacted"
                + ", destinationSecretReference=redacted]";
    }
}
