package com.salamu.menu.tenancy.internal.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.salamu.menu.tenancy.internal.application.TenantNotFoundException;
import com.salamu.menu.tenancy.internal.application.TenantResolver;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
final class TenantResolutionFilter extends OncePerRequestFilter {

    static final String TENANT_ID_MDC_KEY = "tenantId";
    static final String TENANT_SLUG_MDC_KEY = "tenantSlug";

    private final EffectiveHostResolver effectiveHostResolver;
    private final TenantPathSlugExtractor pathSlugExtractor;
    private final TenantResolver tenantResolver;
    private final HandlerExceptionResolver exceptionResolver;

    TenantResolutionFilter(
            EffectiveHostResolver effectiveHostResolver,
            TenantPathSlugExtractor pathSlugExtractor,
            TenantResolver tenantResolver,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.effectiveHostResolver = effectiveHostResolver;
        this.pathSlugExtractor = pathSlugExtractor;
        this.tenantResolver = tenantResolver;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !pathSlugExtractor.pathTenant(request).tenantBound();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var pathTenant = pathSlugExtractor.pathTenant(request);
        var previousAttribute = request.getAttribute(TenantRequestAttributeAccess.RESOLUTION_ATTRIBUTE);
        var previousTenantId = MDC.get(TENANT_ID_MDC_KEY);
        var previousTenantSlug = MDC.get(TENANT_SLUG_MDC_KEY);

        try {
            var effectiveHost = effectiveHostResolver.resolve(request);
            var resolution = tenantResolver.resolve(effectiveHost, pathTenant.slug());
            request.setAttribute(TenantRequestAttributeAccess.RESOLUTION_ATTRIBUTE, resolution);
            MDC.put(TENANT_ID_MDC_KEY, resolution.context().tenantId().value().toString());
            MDC.put(TENANT_SLUG_MDC_KEY, resolution.context().slug());
            filterChain.doFilter(request, response);
        } catch (TenantNotFoundException exception) {
            resolveNotFound(request, response, exception);
        } catch (com.salamu.menu.tenancy.internal.domain.HostNormalizer.InvalidHostException exception) {
            resolveNotFound(request, response, new TenantNotFoundException());
        } finally {
            restoreAttribute(request, previousAttribute);
            restoreMdc(TENANT_ID_MDC_KEY, previousTenantId);
            restoreMdc(TENANT_SLUG_MDC_KEY, previousTenantSlug);
        }
    }

    private void resolveNotFound(
            HttpServletRequest request,
            HttpServletResponse response,
            Exception exception) throws ServletException {
        var resolved = exception instanceof TenantNotFoundException tenantNotFound
                ? tenantNotFound
                : new TenantNotFoundException();
        var modelAndView = exceptionResolver.resolveException(request, response, null, resolved);
        if (modelAndView == null && !response.isCommitted()) {
            throw new ServletException(resolved);
        }
    }

    private static void restoreAttribute(HttpServletRequest request, Object previousValue) {
        if (previousValue == null) {
            request.removeAttribute(TenantRequestAttributeAccess.RESOLUTION_ATTRIBUTE);
        } else {
            request.setAttribute(TenantRequestAttributeAccess.RESOLUTION_ATTRIBUTE, previousValue);
        }
    }

    private static void restoreMdc(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }
}
