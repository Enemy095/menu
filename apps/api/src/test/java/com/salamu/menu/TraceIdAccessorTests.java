package com.salamu.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

class TraceIdAccessorTests {

    @Test
    void prefersCurrentTracingSpan() {
        @SuppressWarnings("unchecked")
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        var tracer = mock(Tracer.class);
        var span = mock(Span.class);
        var context = mock(TraceContext.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("5d8505cb35a642e28b02dd6737003be1");

        var accessor = new TraceIdAccessor(provider);

        assertThat(accessor.currentTraceId(new MockHttpServletRequest()))
                .isEqualTo("5d8505cb35a642e28b02dd6737003be1");
    }

    @Test
    void fallsBackToRequestCorrelationIdWhenNoSpanExists() {
        @SuppressWarnings("unchecked")
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        var request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE, "request-correlation");

        assertThat(new TraceIdAccessor(provider).currentTraceId(request))
                .isEqualTo("request-correlation");
    }
}
