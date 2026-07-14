package com.salamu.menu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class ProductionObservabilityConfigurationTests {

    @Test
    void productionUsesStructuredLoggingAndExplicitOtlpEndpoints() throws Exception {
        var properties = new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application-production.yml"))
                .getFirst();

        assertThat(properties.getProperty("logging.structured.format.console")).isEqualTo("logstash");
        assertThat(properties.getProperty("management.server.port"))
                .isEqualTo("${MANAGEMENT_SERVER_PORT:8081}");
        assertThat(properties.getProperty("management.server.address"))
                .isEqualTo("${MANAGEMENT_SERVER_ADDRESS:127.0.0.1}");
        assertThat(properties.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("${OTEL_TRACES_ENDPOINT}");
        assertThat(properties.getProperty("management.otlp.metrics.export.url"))
                .isEqualTo("${OTEL_METRICS_ENDPOINT}");
    }
}
