package com.salamu.menu;

import java.util.Locale;
import java.util.Set;

import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    private static final Set<String> FORBIDDEN_METRIC_TAG_KEY_FRAGMENTS = Set.of(
            "phone",
            "address",
            "comment",
            "userid",
            "orderid",
            "host",
            "tenantslug",
            "tenantid");
    private static final Set<String> FORBIDDEN_METRIC_TAG_KEYS = Set.of("tenant", "user");

    @Bean
    MeterFilter denySensitiveOrUnboundedMetricTags() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(io.micrometer.core.instrument.Meter.Id id) {
                var hasForbiddenTag = id.getTags().stream()
                        .map(tag -> normalizeTagKey(tag.getKey()))
                        .anyMatch(ObservabilityConfiguration::isForbiddenMetricTagKey);
                return hasForbiddenTag ? MeterFilterReply.DENY : MeterFilterReply.NEUTRAL;
            }
        };
    }

    private static String normalizeTagKey(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean isForbiddenMetricTagKey(String normalizedKey) {
        return FORBIDDEN_METRIC_TAG_KEYS.contains(normalizedKey)
                || FORBIDDEN_METRIC_TAG_KEY_FRAGMENTS.stream().anyMatch(normalizedKey::contains);
    }
}
