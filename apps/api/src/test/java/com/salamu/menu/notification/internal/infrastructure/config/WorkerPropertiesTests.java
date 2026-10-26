package com.salamu.menu.notification.internal.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class WorkerPropertiesTests {

    @Test
    void rejectsProviderTimeoutAtOrBeyondLeaseAndUnboundedConcurrency() {
        assertThatThrownBy(() -> properties(4, Duration.ofSeconds(30), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than lease-duration");
        assertThatThrownBy(() -> properties(33, Duration.ofSeconds(30), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("concurrency");
    }

    private static WorkerProperties properties(int concurrency, Duration lease, Duration providerTimeout) {
        return new WorkerProperties(
                true,
                "test-worker",
                Duration.ofSeconds(1),
                4,
                concurrency,
                2,
                lease,
                providerTimeout,
                4,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                0.2,
                Duration.ofSeconds(5));
    }
}
