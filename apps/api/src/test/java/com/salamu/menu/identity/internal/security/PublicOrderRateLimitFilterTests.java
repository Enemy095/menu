package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.salamu.menu.TraceIdAccessor;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicOrderRateLimitFilterTests {

    private static final String TRACE_ID = "f7ab469535a145e08e9c094f86181f87";

    @Test
    void rejectedExactOrderReturnsTyped429AndNeverInvokesChain() throws Exception {
        var clientIpResolver = mock(TrustedClientIpResolver.class);
        var rateLimiter = mock(OrderRateLimiter.class);
        var request = orderRequest();
        request.setRemoteAddr("198.51.100.42");
        request.addHeader(TrustedClientIpResolver.FORWARDED_FOR_HEADER, "phone=+79991112233,address=Secret");
        when(clientIpResolver.resolve(request)).thenReturn("198.51.100.42");
        when(rateLimiter.tryAcquire("198.51.100.42"))
                .thenReturn(new OrderRateLimiter.Decision(false, 37));
        var response = new MockHttpServletResponse();
        var continued = new AtomicInteger();

        filter(clientIpResolver, rateLimiter).doFilter(
                request,
                response,
                (ignoredRequest, ignoredResponse) -> continued.incrementAndGet());

        assertThat(continued).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("37");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader(SecurityErrorWriter.TRACE_ID_HEADER)).isEqualTo(TRACE_ID);
        var json = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertThat(json.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("code", "message", "details", "traceId");
        assertThat(json.get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(response.getContentAsString())
                .doesNotContain("79991112233", "Secret", "198.51.100.42");
    }

    @Test
    void allowedExactOrderContinuesOnceAndNonOrderRoutesBypassLimiter() throws Exception {
        var clientIpResolver = mock(TrustedClientIpResolver.class);
        var rateLimiter = mock(OrderRateLimiter.class);
        var order = orderRequest();
        when(clientIpResolver.resolve(order)).thenReturn("203.0.113.10");
        when(rateLimiter.tryAcquire("203.0.113.10"))
                .thenReturn(new OrderRateLimiter.Decision(true, 0));
        var continued = new AtomicInteger();
        var filter = filter(clientIpResolver, rateLimiter);

        filter.doFilter(order, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> continued.incrementAndGet());
        assertThat(continued).hasValue(1);

        var read = request("GET", "/api/v1/public/menu");
        filter.doFilter(read, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> continued.incrementAndGet());
        assertThat(continued).hasValue(2);
    }

    @Test
    void wrongMethodOldRouteAndSlugRouteNeverTouchRateComponents() throws Exception {
        var clientIpResolver = mock(TrustedClientIpResolver.class);
        var rateLimiter = mock(OrderRateLimiter.class);
        var filter = filter(clientIpResolver, rateLimiter);

        for (var request : Set.of(
                request("GET", "/api/v1/public/orders"),
                request("POST", "/api/public/orders"),
                request("POST", "/api/v1/public/example/orders"))) {
            filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {});
        }

        verifyNoInteractions(clientIpResolver, rateLimiter);
    }

    @Test
    void hostAliasesAndForgedHeadersCannotCreateDifferentClientBuckets() throws Exception {
        var properties = SecurityTestProperties.properties(
                Set.of(),
                Set.of("allowed.example.test", "alias.example.test"),
                false,
                Set.of(),
                new SecurityProperties.Limit(1, Duration.ofMinutes(1), 2),
                new SecurityProperties.Limit(100, Duration.ofMinutes(1), 100),
                100,
                Duration.ofMinutes(10));
        var resolver = new TrustedClientIpResolver(new TrustedProxyPolicy(properties));
        var limiter = new OrderRateLimiter(properties);
        var filter = filter(resolver, limiter);

        var first = orderRequest();
        first.setRemoteAddr("198.51.100.42");
        first.addHeader("Host", "allowed.example.test");
        first.addHeader("X-Forwarded-For", "203.0.113.10");
        var second = orderRequest();
        second.setRemoteAddr("198.51.100.42");
        second.addHeader("Host", "alias.example.test");
        second.addHeader("X-Forwarded-For", "192.0.2.10");
        var third = orderRequest();
        third.setRemoteAddr("198.51.100.42");
        third.addHeader("Host", "allowed.example.test");
        var continued = new AtomicInteger();

        filter.doFilter(first, new MockHttpServletResponse(), (request, response) -> continued.incrementAndGet());
        filter.doFilter(second, new MockHttpServletResponse(), (request, response) -> continued.incrementAndGet());
        var rejected = new MockHttpServletResponse();
        filter.doFilter(third, rejected, (request, response) -> continued.incrementAndGet());

        assertThat(continued).hasValue(2);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(limiter.clientBucketCount()).isEqualTo(1);
    }

    private static PublicOrderRateLimitFilter filter(
            TrustedClientIpResolver resolver,
            OrderRateLimiter rateLimiter) {
        var traceIdAccessor = mock(TraceIdAccessor.class);
        when(traceIdAccessor.currentTraceId(org.mockito.ArgumentMatchers.any())).thenReturn(TRACE_ID);
        return new PublicOrderRateLimitFilter(
                resolver,
                rateLimiter,
                new SecurityErrorWriter(new ObjectMapper(), traceIdAccessor));
    }

    private static MockHttpServletRequest orderRequest() {
        return request("POST", "/api/v1/public/orders");
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
