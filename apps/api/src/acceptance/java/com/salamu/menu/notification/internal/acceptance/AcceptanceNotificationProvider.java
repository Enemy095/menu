package com.salamu.menu.notification.internal.acceptance;

import java.util.concurrent.atomic.AtomicInteger;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.internal.application.NotificationProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local acceptance provider with no network, file or subprocess capability.
 *
 * <p>The class is compiled only into the separate acceptance jar. It deliberately returns
 * a bounded deterministic sequence so the disposable full-stack harness can observe one
 * retry followed by success without contacting a real notification destination.</p>
 */
@Component
@Profile("acceptance")
@ConditionalOnProperty(
        prefix = "app.acceptance.notification",
        name = "enabled",
        havingValue = "true")
public final class AcceptanceNotificationProvider implements NotificationProvider {

    private static final String TRANSIENT_ERROR = "ACCEPTANCE_TRANSIENT_FAILURE";

    private final AtomicInteger failuresRemaining;

    public AcceptanceNotificationProvider(
            @Value("${app.acceptance.notification.failures-before-success:1}")
            int failuresBeforeSuccess) {
        if (failuresBeforeSuccess < 0 || failuresBeforeSuccess > 10) {
            throw new IllegalArgumentException(
                    "app.acceptance.notification.failures-before-success must be between 0 and 10");
        }
        this.failuresRemaining = new AtomicInteger(failuresBeforeSuccess);
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.TELEGRAM;
    }

    @Override
    public ProviderResult send(ProviderRequest request) {
        if (!"order.created".equals(request.eventType()) || request.schemaVersion() != 1) {
            return ProviderResult.permanentFailure("ACCEPTANCE_UNSUPPORTED_EVENT");
        }
        int before = failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1));
        return before > 0
                ? ProviderResult.transientFailure(TRANSIENT_ERROR)
                : ProviderResult.succeeded(null);
    }
}
