package com.salamu.menu.identity.internal.security;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;

public final class PublicOrderRateLimitFilter implements Filter {

    private static final String RATE_LIMITED_CODE = "RATE_LIMITED";
    private static final String RATE_LIMITED_MESSAGE = "Слишком много запросов, повторите позже";

    private final TrustedClientIpResolver clientIpResolver;
    private final OrderRateLimiter rateLimiter;
    private final SecurityErrorWriter errorWriter;

    public PublicOrderRateLimitFilter(
            TrustedClientIpResolver clientIpResolver,
            OrderRateLimiter rateLimiter,
            SecurityErrorWriter errorWriter) {
        this.clientIpResolver = clientIpResolver;
        this.rateLimiter = rateLimiter;
        this.errorWriter = errorWriter;
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

        var decision = rateLimiter.tryAcquire(clientIpResolver.resolve(request));
        if (!decision.allowed()) {
            errorWriter.write(
                    request,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    RATE_LIMITED_CODE,
                    RATE_LIMITED_MESSAGE,
                    decision.retryAfterSeconds());
            return;
        }

        filterChain.doFilter(request, response);
    }
}
