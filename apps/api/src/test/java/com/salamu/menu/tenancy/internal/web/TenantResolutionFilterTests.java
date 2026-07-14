package com.salamu.menu.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import com.salamu.menu.tenancy.api.TenantContext;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantResolution;
import com.salamu.menu.tenancy.internal.application.TenantNotFoundException;
import com.salamu.menu.tenancy.internal.application.TenantResolver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

class TenantResolutionFilterTests {

    private static final TenantContext STAMBUL = context(
            "10000000-0000-4000-8000-000000000001", "stambul");
    private static final TenantContext ADAMAS = context(
            "20000000-0000-4000-8000-000000000002", "adamas");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void makesImmutableResolutionAvailableOnlyInsideTheRequestChain() throws Exception {
        var resolver = mock(TenantResolver.class);
        when(resolver.resolve("example.test", Optional.of("stambul")))
                .thenReturn(resolution(STAMBUL, "example.test"));
        var filter = filter(resolver, mock(HandlerExceptionResolver.class));
        var request = tenantRequest("stambul");
        var response = new MockHttpServletResponse();
        var requestAccess = new TenantRequestAttributeAccess();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(requestAccess.context(request)).contains(STAMBUL);
            assertThat(MDC.get(TenantResolutionFilter.TENANT_ID_MDC_KEY))
                    .isEqualTo(STAMBUL.tenantId().value().toString());
            assertThat(MDC.get(TenantResolutionFilter.TENANT_SLUG_MDC_KEY)).isEqualTo("stambul");
        });

        assertThat(requestAccess.context(request)).isEmpty();
        assertThat(MDC.get(TenantResolutionFilter.TENANT_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(TenantResolutionFilter.TENANT_SLUG_MDC_KEY)).isNull();
    }

    @Test
    void ignoresQueryHeaderAndBodyTenantOverrides() throws Exception {
        var resolver = mock(TenantResolver.class);
        when(resolver.resolve("example.test", Optional.of("stambul")))
                .thenReturn(resolution(STAMBUL, "example.test"));
        var filter = filter(resolver, mock(HandlerExceptionResolver.class));
        var request = tenantRequest("stambul");
        request.addParameter("tenantId", ADAMAS.tenantId().value().toString());
        request.addHeader("X-Tenant-Id", ADAMAS.tenantId().value().toString());
        request.setContentType("application/json");
        request.setContent("{\"tenantId\":\"20000000-0000-4000-8000-000000000002\"}".getBytes());

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
        });

        verify(resolver).resolve("example.test", Optional.of("stambul"));
    }

    @Test
    void restoresPreviousThreadMetadataAndRequestAttributeWhenChainFails() {
        var resolver = mock(TenantResolver.class);
        when(resolver.resolve("example.test", Optional.of("adamas")))
                .thenReturn(resolution(ADAMAS, "example.test"));
        var filter = filter(resolver, mock(HandlerExceptionResolver.class));
        var request = tenantRequest("adamas");
        var previous = resolution(STAMBUL, "previous.example");
        request.setAttribute(TenantRequestAttributeAccess.RESOLUTION_ATTRIBUTE, previous);
        MDC.put(TenantResolutionFilter.TENANT_ID_MDC_KEY, "previous-tenant");
        MDC.put(TenantResolutionFilter.TENANT_SLUG_MDC_KEY, "previous-slug");
        FilterChain failingChain = (ignoredRequest, ignoredResponse) -> {
            throw new ServletException("downstream failure");
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failingChain))
                .isInstanceOf(ServletException.class)
                .hasMessage("downstream failure");

        assertThat(request.getAttribute(TenantRequestAttributeAccess.RESOLUTION_ATTRIBUTE)).isSameAs(previous);
        assertThat(MDC.get(TenantResolutionFilter.TENANT_ID_MDC_KEY)).isEqualTo("previous-tenant");
        assertThat(MDC.get(TenantResolutionFilter.TENANT_SLUG_MDC_KEY)).isEqualTo("previous-slug");
    }

    @Test
    void delegatesTenantNotFoundToCommonExceptionEnvelopeResolver() throws Exception {
        var resolver = mock(TenantResolver.class);
        when(resolver.resolve(anyString(), any())).thenThrow(new TenantNotFoundException());
        var exceptionResolver = mock(HandlerExceptionResolver.class);
        when(exceptionResolver.resolveException(any(), any(), any(), any()))
                .thenReturn(new ModelAndView());
        var filter = filter(resolver, exceptionResolver);
        var request = tenantRequest("missing");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("chain must not run");
        });

        verify(exceptionResolver).resolveException(
                org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers.eq(response),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isA(TenantNotFoundException.class));
        assertThat(request.getAttribute(TenantRequestAttributeAccess.RESOLUTION_ATTRIBUTE)).isNull();
        assertThat(MDC.get(TenantResolutionFilter.TENANT_ID_MDC_KEY)).isNull();
    }

    private static TenantResolutionFilter filter(
            TenantResolver resolver,
            HandlerExceptionResolver exceptionResolver) {
        return new TenantResolutionFilter(
                new EffectiveHostResolver(address -> false),
                new TenantPathSlugExtractor(),
                resolver,
                exceptionResolver);
    }

    private static MockHttpServletRequest tenantRequest(String slug) {
        var request = new MockHttpServletRequest("GET", "/api/v1/public/" + slug + "/menu");
        request.addHeader("Host", "example.test");
        request.setRemoteAddr("203.0.113.10");
        return request;
    }

    private static TenantResolution resolution(TenantContext context, String host) {
        return new TenantResolution(
                context,
                host,
                Optional.of(context.slug()),
                TenantResolution.Source.PLATFORM_PATH);
    }

    private static TenantContext context(String id, String slug) {
        return new TenantContext(new TenantId(UUID.fromString(id)), slug);
    }
}
