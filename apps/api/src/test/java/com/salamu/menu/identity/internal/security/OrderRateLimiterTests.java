package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;

import org.junit.jupiter.api.Test;

class OrderRateLimiterTests {

    @Test
    void clientLimitUsesOnlyCanonicalIpAndRefillsDeterministically() {
        var clock = new ManualClock();
        var limiter = new OrderRateLimiter(properties(1, Duration.ofMinutes(1), 2, 100, Duration.ofMinutes(1), 100, 100), clock, clock);

        assertThat(limiter.tryAcquire("198.51.100.10").allowed()).isTrue();
        assertThat(limiter.tryAcquire("198.51.100.10").allowed()).isTrue();
        var rejected = limiter.tryAcquire("198.51.100.10");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(60);

        assertThat(limiter.tryAcquire("198.51.100.11").allowed()).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("198.51.100.10").allowed()).isTrue();
    }

    @Test
    void applicationLimitCannotBeBypassedByChangingClientIp() {
        var clock = new ManualClock();
        var limiter = new OrderRateLimiter(properties(100, Duration.ofMinutes(1), 100, 1, Duration.ofMinutes(1), 2, 100), clock, clock);

        assertThat(limiter.tryAcquire("198.51.100.1").allowed()).isTrue();
        assertThat(limiter.tryAcquire("198.51.100.2").allowed()).isTrue();
        assertThat(limiter.tryAcquire("198.51.100.3").allowed()).isFalse();
    }

    @Test
    void clientCacheIsBoundedAndExpiresWhileApplicationHasOneDirectBucket() {
        var clock = new ManualClock();
        var limiter = new OrderRateLimiter(properties(100, Duration.ofMinutes(1), 100, 100, Duration.ofMinutes(1), 100, 2), clock, clock);

        for (var index = 1; index <= 8; index++) {
            assertThat(limiter.tryAcquire("198.51.100." + index).allowed()).isTrue();
        }
        limiter.cleanUp();
        assertThat(limiter.clientBucketCount()).isLessThanOrEqualTo(2);

        clock.advance(Duration.ofMinutes(2));
        limiter.cleanUp();
        assertThat(limiter.clientBucketCount()).isZero();

        assertThat(java.util.Arrays.stream(OrderRateLimiter.class.getDeclaredFields())
                .filter(field -> Cache.class.isAssignableFrom(field.getType())))
                .hasSize(1);
        assertThat(java.util.Arrays.stream(OrderRateLimiter.class.getDeclaredFields())
                .filter(field -> Bucket.class.isAssignableFrom(field.getType())))
                .hasSize(1);
    }

    @Test
    void retryAfterRoundsUpWithoutSleeping() {
        var clock = new ManualClock();
        var limiter = new OrderRateLimiter(properties(1, Duration.ofMillis(1500), 1, 100, Duration.ofMinutes(1), 100, 10), clock, clock);

        assertThat(limiter.tryAcquire("198.51.100.10").allowed()).isTrue();
        assertThat(limiter.tryAcquire("198.51.100.10").retryAfterSeconds()).isEqualTo(2);
        clock.advance(Duration.ofMillis(500));
        assertThat(limiter.tryAcquire("198.51.100.10").retryAfterSeconds()).isEqualTo(1);
    }

    private static SecurityProperties properties(
            long clientTokens,
            Duration clientPeriod,
            long clientBurst,
            long applicationTokens,
            Duration applicationPeriod,
            long applicationBurst,
            long maximumClientBuckets) {
        return SecurityTestProperties.properties(
                Set.of(),
                Set.of("localhost"),
                false,
                Set.of(),
                new SecurityProperties.Limit(clientTokens, clientPeriod, clientBurst),
                new SecurityProperties.Limit(applicationTokens, applicationPeriod, applicationBurst),
                maximumClientBuckets,
                Duration.ofMinutes(1));
    }

    private static final class ManualClock implements Ticker, TimeMeter {

        private final AtomicLong nanoseconds = new AtomicLong();

        @Override
        public long read() {
            return nanoseconds.get();
        }

        @Override
        public long currentTimeNanos() {
            return nanoseconds.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void advance(Duration duration) {
            nanoseconds.addAndGet(duration.toNanos());
        }
    }
}
