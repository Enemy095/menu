package com.salamu.menu.notification.internal.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public final class RetryPolicy {

    @FunctionalInterface
    public interface JitterSource {
        double symmetricUnit();
    }

    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final double jitterRatio;
    private final JitterSource jitterSource;

    public RetryPolicy(
            Duration initialBackoff,
            Duration maxBackoff,
            double jitterRatio,
            JitterSource jitterSource) {
        this.initialBackoff = requirePositive(initialBackoff, "initialBackoff");
        this.maxBackoff = requirePositive(maxBackoff, "maxBackoff");
        if (initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("Initial backoff cannot exceed maximum backoff");
        }
        if (jitterRatio < 0 || jitterRatio > 0.5 || !Double.isFinite(jitterRatio)) {
            throw new IllegalArgumentException("Jitter ratio must be between zero and 0.5");
        }
        this.jitterRatio = jitterRatio;
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource");
    }

    public static RetryPolicy production(Duration initialBackoff, Duration maxBackoff, double jitterRatio) {
        return new RetryPolicy(
                initialBackoff,
                maxBackoff,
                jitterRatio,
                () -> ThreadLocalRandom.current().nextDouble(-1.0, 1.0));
    }

    /** Delay after the supplied failed attempt, before the next attempt. */
    public Duration delayAfter(int failedAttempt) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("Failed attempt must be positive");
        }
        long capMillis = maxBackoff.toMillis();
        long baseMillis = initialBackoff.toMillis();
        int doublings = Math.min(failedAttempt - 1, 62);
        for (int index = 0; index < doublings && baseMillis < capMillis; index++) {
            baseMillis = baseMillis > capMillis / 2 ? capMillis : baseMillis * 2;
        }
        baseMillis = Math.min(baseMillis, capMillis);

        double unit = Math.max(-1.0, Math.min(1.0, jitterSource.symmetricUnit()));
        BigDecimal multiplier = BigDecimal.ONE.add(BigDecimal.valueOf(jitterRatio * unit));
        long jittered = BigDecimal.valueOf(baseMillis)
                .multiply(multiplier)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        return Duration.ofMillis(Math.max(1, Math.min(capMillis, jittered)));
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
