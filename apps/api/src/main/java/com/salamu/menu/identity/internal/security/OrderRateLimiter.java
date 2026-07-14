package com.salamu.menu.identity.internal.security;

import java.time.Duration;
import java.util.Objects;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OrderRateLimiter {

    private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

    private final SecurityProperties.RateLimit properties;
    private final Cache<String, Bucket> clientBuckets;
    private final Bucket applicationBucket;
    private final TimeMeter timeMeter;

    @Autowired
    public OrderRateLimiter(SecurityProperties properties) {
        this(properties, Ticker.systemTicker(), TimeMeter.SYSTEM_NANOTIME);
    }

    OrderRateLimiter(SecurityProperties properties, Ticker ticker, TimeMeter timeMeter) {
        this.properties = Objects.requireNonNull(properties, "properties").rateLimit();
        this.timeMeter = Objects.requireNonNull(timeMeter, "timeMeter");
        var cacheProperties = this.properties.cache();
        clientBuckets = Caffeine.newBuilder()
                .ticker(Objects.requireNonNull(ticker, "ticker"))
                .maximumSize(cacheProperties.maximumClientBuckets())
                .expireAfterAccess(cacheProperties.expireAfterAccess())
                .build();
        applicationBucket = newBucket(this.properties.application());
    }

    public Decision tryAcquire(String trustedClientIp) {
        Objects.requireNonNull(trustedClientIp, "trustedClientIp");

        var clientProbe = clientBuckets
                .get(trustedClientIp, ignored -> newBucket(properties.client()))
                .tryConsumeAndReturnRemaining(1);
        if (!clientProbe.isConsumed()) {
            return Decision.denied(retryAfterSeconds(clientProbe));
        }

        var applicationProbe = applicationBucket.tryConsumeAndReturnRemaining(1);
        if (!applicationProbe.isConsumed()) {
            return Decision.denied(retryAfterSeconds(applicationProbe));
        }

        return Decision.granted();
    }

    long clientBucketCount() {
        clientBuckets.cleanUp();
        return clientBuckets.estimatedSize();
    }

    void cleanUp() {
        clientBuckets.cleanUp();
    }

    private Bucket newBucket(SecurityProperties.Limit limit) {
        return Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(bandwidth -> bandwidth
                        .capacity(limit.burstCapacity())
                        .refillGreedy(limit.tokensPerPeriod(), limit.period()))
                .build();
    }

    private static long retryAfterSeconds(ConsumptionProbe probe) {
        var nanos = probe.getNanosToWaitForRefill();
        if (nanos <= 0) {
            return 1;
        }
        return Math.max(1, Math.ceilDiv(nanos, NANOS_PER_SECOND));
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {

        static Decision granted() {
            return new Decision(true, 0);
        }

        static Decision denied(long retryAfterSeconds) {
            return new Decision(false, Math.max(1, retryAfterSeconds));
        }
    }
}
