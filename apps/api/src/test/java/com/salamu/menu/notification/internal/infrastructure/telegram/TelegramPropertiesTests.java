package com.salamu.menu.notification.internal.infrastructure.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;

import org.junit.jupiter.api.Test;

class TelegramPropertiesTests {

    @Test
    void allowsEmptySecretsWhileDisabled() {
        var properties = new TelegramProperties(false, null, "", "", null, "");

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.toString()).doesNotContain("token", "123456");
    }

    @Test
    void validatesAndRedactsEnabledConfiguration() {
        var properties = new TelegramProperties(
                true,
                URI.create("https://api.telegram.org"),
                "123456:secret-token",
                "-1001234567890",
                42,
                "secret://application/telegram/orders");

        assertThat(properties.toString())
                .contains("botToken=redacted", "chatId=redacted")
                .doesNotContain("secret-token", "-1001234567890");
    }

    @Test
    void rejectsMissingTokenAndNonHttpsRemoteEndpoint() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TelegramProperties(
                true,
                URI.create("https://api.telegram.org"),
                "",
                "123456",
                null,
                "secret://application/telegram/orders"));

        assertThatIllegalArgumentException().isThrownBy(() -> new TelegramProperties(
                true,
                URI.create("http://example.test"),
                "123456:secret-token",
                "123456",
                null,
                "secret://application/telegram/orders"));
    }
}
