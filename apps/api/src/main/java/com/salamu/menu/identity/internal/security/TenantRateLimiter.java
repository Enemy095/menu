package com.salamu.menu.identity.internal.security;

import java.time.Duration;
import java.util.Objects;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;

import com.salamu.menu.tenancy.api.TenantContext;
import com.salamu.menu.tenancy.api.TenantId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TenantRateLimiter {

    private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

    private final SecurityProperties.RateLimit properties;
    private final Cache<ClientBucketKey, Bucket> clientBuckets;
    private final Cache<TenantId, Bucket> tenantBuckets;
    private final TimeMeter timeMeter;

    @Autowired
    public TenantRateLimiter(SecurityProperties properties) {
        this(properties, Ticker.systemTicker(), TimeMeter.SYSTEM_NANOTIME);
    }

    TenantRateLimiter(SecurityProperties properties, Ticker ticker, TimeMeter timeMeter) {
        this.properties = Objects.requireNonNull(properties, "properties").rateLimit();
        this.timeMeter = Objects.requireNonNull(timeMeter, "timeMeter");
        var cacheProperties = this.properties.cache();
        clientBuckets = Caffeine.newBuilder()
                .ticker(Objects.requireNonNull(ticker, "ticker"))
                .maximumSize(cacheProperties.maximumClientBuckets())
                .expireAfterAccess(cacheProperties.expireAfterAccess())
                .build();
        tenantBuckets = Caffeine.newBuilder()
                .ticker(ticker)
                .maximumSize(cacheProperties.maximumTenantBuckets())
                .expireAfterAccess(cacheProperties.expireAfterAccess())
                .build();
    }

    public Decision tryAcquire(TenantContext tenantContext, String trustedClientIp) {
        Objects.requireNonNull(tenantContext, "tenantContext");
        Objects.requireNonNull(trustedClientIp, "trustedClientIp");

        var clientKey = new ClientBucketKey(tenantContext.tenantId(), trustedClientIp);
        var clientProbe = bucket(clientBuckets, clientKey, properties.client()).tryConsumeAndReturnRemaining(1);
        if (!clientProbe.isConsumed()) {
            return Decision.denied(retryAfterSeconds(clientProbe));
        }

        var tenantProbe = bucket(tenantBuckets, tenantContext.tenantId(), properties.tenant())
                .tryConsumeAndReturnRemaining(1);
        if (!tenantProbe.isConsumed()) {
            return Decision.denied(retryAfterSeconds(tenantProbe));
        }

        return Decision.granted();
    }

    long clientBucketCount() {
        clientBuckets.cleanUp();
        return clientBuckets.estimatedSize();
    }

    long tenantBucketCount() {
        tenantBuckets.cleanUp();
        return tenantBuckets.estimatedSize();
    }

    void cleanUp() {
        clientBuckets.cleanUp();
        tenantBuckets.cleanUp();
    }

    private <K> Bucket bucket(Cache<K, Bucket> cache, K key, SecurityProperties.Limit limit) {
        return cache.get(key, ignored -> Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(bandwidth -> bandwidth
                        .capacity(limit.burstCapacity())
                        .refillGreedy(limit.tokensPerPeriod(), limit.period()))
                .build());
    }

    private static long retryAfterSeconds(ConsumptionProbe probe) {
        var nanos = probe.getNanosToWaitForRefill();
        if (nanos <= 0) {
            return 1;
        }
        return Math.max(1, Math.ceilDiv(nanos, NANOS_PER_SECOND));
    }

    private record ClientBucketKey(TenantId tenantId, String trustedClientIp) {
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
