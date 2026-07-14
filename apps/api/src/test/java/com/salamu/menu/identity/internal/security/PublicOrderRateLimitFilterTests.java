package com.salamu.menu.identity.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.salamu.menu.TraceIdAccessor;
import com.salamu.menu.tenancy.api.TenantContext;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantRequestAccess;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicOrderRateLimitFilterTests {

    private static final String TRACE_ID = "f7ab469535a145e08e9c094f86181f87";

    @Test
    void missingTrustedTenantContextFailsClosedWithContract404() throws Exception {
        var tenantAccess = mock(TenantRequestAccess.class);
        var clientIpResolver = mock(TrustedClientIpResolver.class);
        var rateLimiter = mock(TenantRateLimiter.class);
        var traceIdAccessor = mock(TraceIdAccessor.class);
        var request = orderRequest("missing");
        var response = new MockHttpServletResponse();
        when(tenantAccess.context(request)).thenReturn(Optional.empty());
        when(traceIdAccessor.currentTraceId(request)).thenReturn(TRACE_ID);

        new PublicOrderRateLimitFilter(tenantAccess, clientIpResolver, rateLimiter, traceIdAccessor)
                .doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                    throw new AssertionError("missing tenant request must not reach controller");
                });

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getHeader(PublicOrderRateLimitFilter.TRACE_ID_HEADER)).isEqualTo(TRACE_ID);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"TENANT_NOT_FOUND\"")
                .contains("\"traceId\":\"" + TRACE_ID + "\"");
        verifyNoInteractions(clientIpResolver, rateLimiter);
    }

    @Test
    void rejectedOrderReturns429RetryAfterAndNoInputData() throws Exception {
        var tenantAccess = mock(TenantRequestAccess.class);
        var clientIpResolver = mock(TrustedClientIpResolver.class);
        var rateLimiter = mock(TenantRateLimiter.class);
        var traceIdAccessor = mock(TraceIdAccessor.class);
        var request = orderRequest("stambul");
        request.addHeader(TrustedClientIpResolver.FORWARDED_FOR_HEADER, "phone=+79991112233");
        var response = new MockHttpServletResponse();
        var tenant = tenant("stambul");
        when(tenantAccess.context(request)).thenReturn(Optional.of(tenant));
        when(clientIpResolver.resolve(request)).thenReturn("198.51.100.42");
        when(rateLimiter.tryAcquire(tenant, "198.51.100.42"))
                .thenReturn(new TenantRateLimiter.Decision(false, 37));
        when(traceIdAccessor.currentTraceId(request)).thenReturn(TRACE_ID);

        new PublicOrderRateLimitFilter(tenantAccess, clientIpResolver, rateLimiter, traceIdAccessor)
                .doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                    throw new AssertionError("rate-limited request must not reach controller");
                });

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("37");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"RATE_LIMITED\"")
                .contains("\"details\":[]")
                .contains("\"traceId\":\"" + TRACE_ID + "\"")
                .doesNotContain("79991112233", "198.51.100.42", "stambul");
    }

    @Test
    void allowedOrderContinuesAndNonOrderRouteBypassesLimiter() throws Exception {
        var tenantAccess = mock(TenantRequestAccess.class);
        var clientIpResolver = mock(TrustedClientIpResolver.class);
        var rateLimiter = mock(TenantRateLimiter.class);
        var traceIdAccessor = mock(TraceIdAccessor.class);
        var tenant = tenant("adamas");
        var orderRequest = orderRequest("adamas");
        when(tenantAccess.context(orderRequest)).thenReturn(Optional.of(tenant));
        when(clientIpResolver.resolve(orderRequest)).thenReturn("203.0.113.10");
        when(rateLimiter.tryAcquire(tenant, "203.0.113.10"))
                .thenReturn(new TenantRateLimiter.Decision(true, 0));
        var continued = new AtomicBoolean();
        var filter = new PublicOrderRateLimitFilter(
                tenantAccess,
                clientIpResolver,
                rateLimiter,
                traceIdAccessor);

        filter.doFilter(orderRequest, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> continued.set(true));
        assertThat(continued).isTrue();

        var readRequest = request("GET", "/api/v1/public/adamas/menu");
        filter.doFilter(readRequest, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> continued.set(true));
    }

    private static MockHttpServletRequest orderRequest(String slug) {
        return request("POST", "/api/v1/public/" + slug + "/orders");
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }

    private static TenantContext tenant(String slug) {
        return new TenantContext(
                new TenantId(UUID.nameUUIDFromBytes(slug.getBytes())),
                slug);
    }
}
