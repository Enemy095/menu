package com.salamu.menu;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

class ObservabilityConfigurationTests {

    @Test
    void rejectsSensitiveAndUnboundedMetricTagKeys() {
        var registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new ObservabilityConfiguration().denySensitiveOrUnboundedMetricTags());

        for (var tagKey : new String[] {
                "customer.phone",
                "delivery_address",
                "order-id",
                "tenant",
                "tenant_slug",
                "user",
                "http.request.header.host"
        }) {
            Counter.builder("orders.created." + tagKey.hashCode())
                    .tag(tagKey, "sensitive-or-unbounded-value")
                    .register(registry)
                    .increment();
        }

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void acceptsBoundedMetricTags() {
        var registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new ObservabilityConfiguration().denySensitiveOrUnboundedMetricTags());

        Counter.builder("orders.created")
                .tag("outcome", "accepted")
                .tag("orderType", "delivery")
                .register(registry)
                .increment();

        assertThat(registry.find("orders.created")
                .tag("outcome", "accepted")
                .tag("orderType", "delivery")
                .counter())
                .isNotNull();
    }
}
