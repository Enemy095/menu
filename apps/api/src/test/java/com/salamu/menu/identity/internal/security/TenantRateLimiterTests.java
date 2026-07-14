package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.bucket4j.TimeMeter;

import com.salamu.menu.tenancy.api.TenantContext;
import com.salamu.menu.tenancy.api.TenantId;

import org.junit.jupiter.api.Test;

class TenantRateLimiterTests {

    @Test
    void clientLimitIsTenantScopedAndRefillsDeterministically() {
        var clock = new ManualClock();
        var limiter = new TenantRateLimiter(properties(1, 2, 100, 100, 100, 100, Duration.ofMinutes(5)), clock, clock);
        var tenantA = tenant(1);
        var tenantB = tenant(2);

        assertThat(limiter.tryAcquire(tenantA, "198.51.100.10").allowed()).isTrue();
        assertThat(limiter.tryAcquire(tenantA, "198.51.100.10").allowed()).isTrue();
        var rejected = limiter.tryAcquire(tenantA, "198.51.100.10");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 60L);

        assertThat(limiter.tryAcquire(tenantB, "198.51.100.10").allowed()).isTrue();

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire(tenantA, "198.51.100.10").allowed()).isTrue();
    }

    @Test
    void tenantAggregateLimitCannotBeBypassedByChangingClientIp() {
        var clock = new ManualClock();
        var limiter = new TenantRateLimiter(properties(100, 100, 1, 2, 100, 100, Duration.ofMinutes(5)), clock, clock);
        var tenant = tenant(3);

        assertThat(limiter.tryAcquire(tenant, "198.51.100.1").allowed()).isTrue();
        assertThat(limiter.tryAcquire(tenant, "198.51.100.2").allowed()).isTrue();
        assertThat(limiter.tryAcquire(tenant, "198.51.100.3").allowed()).isFalse();
    }

    @Test
    void bucketCachesAreBoundedAndExpireAfterInactivity() {
        var clock = new ManualClock();
        var limiter = new TenantRateLimiter(properties(100, 100, 100, 100, 2, 2, Duration.ofMinutes(1)), clock, clock);

        for (var index = 1; index <= 8; index++) {
            assertThat(limiter.tryAcquire(tenant(index), "198.51.100." + index).allowed()).isTrue();
        }
        limiter.cleanUp();

        assertThat(limiter.clientBucketCount()).isLessThanOrEqualTo(2);
        assertThat(limiter.tenantBucketCount()).isLessThanOrEqualTo(2);

        clock.advance(Duration.ofMinutes(2));
        limiter.cleanUp();
        assertThat(limiter.clientBucketCount()).isZero();
        assertThat(limiter.tenantBucketCount()).isZero();
    }

    private static SecurityProperties properties(
            long clientTokens,
            long clientBurst,
            long tenantTokens,
            long tenantBurst,
            long maximumClientBuckets,
            long maximumTenantBuckets,
            Duration expiry) {
        return new SecurityProperties(
                new SecurityProperties.Cors(Set.of()),
                new SecurityProperties.RateLimit(
                        new SecurityProperties.Limit(clientTokens, Duration.ofMinutes(1), clientBurst),
                        new SecurityProperties.Limit(tenantTokens, Duration.ofMinutes(1), tenantBurst),
                        new SecurityProperties.Cache(maximumClientBuckets, maximumTenantBuckets, expiry)));
    }

    private static TenantContext tenant(int seed) {
        return new TenantContext(
                new TenantId(new UUID(0, seed)),
                "tenant-" + seed);
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
