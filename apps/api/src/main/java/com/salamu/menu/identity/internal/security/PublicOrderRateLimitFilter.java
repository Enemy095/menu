package com.salamu.menu.identity.internal.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.salamu.menu.TraceIdAccessor;
import com.salamu.menu.tenancy.api.TenantRequestAccess;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

public class PublicOrderRateLimitFilter implements Filter {

    static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final Pattern SAFE_DIAGNOSTIC_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final String TENANT_NOT_FOUND_MESSAGE = "Заведение не найдено";
    private static final String RATE_LIMITED_MESSAGE = "Слишком много запросов";

    private final TenantRequestAccess tenantRequestAccess;
    private final TrustedClientIpResolver clientIpResolver;
    private final TenantRateLimiter rateLimiter;
    private final TraceIdAccessor traceIdAccessor;

    public PublicOrderRateLimitFilter(
            TenantRequestAccess tenantRequestAccess,
            TrustedClientIpResolver clientIpResolver,
            TenantRateLimiter rateLimiter,
            TraceIdAccessor traceIdAccessor) {
        this.tenantRequestAccess = tenantRequestAccess;
        this.clientIpResolver = clientIpResolver;
        this.rateLimiter = rateLimiter;
        this.traceIdAccessor = traceIdAccessor;
    }

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain filterChain) throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)
                || request.getDispatcherType() != DispatcherType.REQUEST
                || !SecurityRequestMatchers.PUBLIC_ORDER.matches(request)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        var tenantContext = tenantRequestAccess.context(request);
        if (tenantContext.isEmpty()) {
            writeError(
                    request,
                    response,
                    HttpStatus.NOT_FOUND,
                    "TENANT_NOT_FOUND",
                    TENANT_NOT_FOUND_MESSAGE,
                    null);
            return;
        }

        var trustedClientIp = clientIpResolver.resolve(request);
        var decision = rateLimiter.tryAcquire(tenantContext.get(), trustedClientIp);
        if (!decision.allowed()) {
            writeError(
                    request,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED",
                    RATE_LIMITED_MESSAGE,
                    decision.retryAfterSeconds());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            Long retryAfterSeconds) throws IOException {
        var traceId = safeTraceId(traceIdAccessor.currentTraceId(request));
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(TRACE_ID_HEADER, traceId);
        if (retryAfterSeconds != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfterSeconds)));
        }
        response.getWriter().write("{\"code\":\""
                + code
                + "\",\"message\":\""
                + message
                + "\",\"details\":[],\"traceId\":\""
                + traceId
                + "\"}");
    }

    private static String safeTraceId(String candidate) {
        if (candidate != null && SAFE_DIAGNOSTIC_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
