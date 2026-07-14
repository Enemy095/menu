package com.salamu.menu.notification.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryPolicyTests {

    @Test
    void doublesFromFailedAttemptAndStopsAtConfiguredCap() {
        var policy = new RetryPolicy(Duration.ofSeconds(5), Duration.ofSeconds(30), 0, () -> 0);

        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.delayAfter(100)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void appliesBoundedSymmetricJitterAndClampsAnInvalidSource() {
        assertThat(new RetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(1), 0.2, () -> -1)
                .delayAfter(1)).isEqualTo(Duration.ofSeconds(8));
        assertThat(new RetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(1), 0.2, () -> 1)
                .delayAfter(1)).isEqualTo(Duration.ofSeconds(12));
        assertThat(new RetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(1), 0.2, () -> 50)
                .delayAfter(1)).isEqualTo(Duration.ofSeconds(12));
        assertThat(new RetryPolicy(Duration.ofSeconds(30), Duration.ofSeconds(30), 0.2, () -> 1)
                .delayAfter(8)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsInvalidBounds() {
        assertThatThrownBy(() -> new RetryPolicy(Duration.ZERO, Duration.ofSeconds(1), 0, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1), 0, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 0.6, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(2), 0, () -> 0)
                .delayAfter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
