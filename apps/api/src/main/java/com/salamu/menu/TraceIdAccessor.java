package com.salamu.menu;

import jakarta.servlet.http.HttpServletRequest;

import io.micrometer.tracing.Tracer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class TraceIdAccessor {

    private final ObjectProvider<Tracer> tracerProvider;

    public TraceIdAccessor(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    public String currentTraceId(HttpServletRequest request) {
        var tracer = tracerProvider.getIfAvailable();
        var currentSpan = tracer == null ? null : tracer.currentSpan();
        if (currentSpan != null) {
            var traceId = currentSpan.context().traceId();
            if (traceId != null && !traceId.isBlank()) {
                return traceId;
            }
        }

        var correlationId = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        if (correlationId instanceof String value && !value.isBlank()) {
            return value;
        }
        return CorrelationIdFilter.resolveCorrelationId(null);
    }
}
