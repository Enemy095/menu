package com.salamu.menu.identity.internal.security;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

public final class RequestAuthorityFilter implements Filter {

    static final int MAX_HOST_HEADER_LENGTH = 255;
    private static final String INVALID_AUTHORITY_CODE = "VALIDATION_ERROR";
    private static final String INVALID_AUTHORITY_MESSAGE = "Invalid request authority";

    private final Set<String> allowedHosts;
    private final SecurityErrorWriter errorWriter;

    public RequestAuthorityFilter(SecurityProperties properties, SecurityErrorWriter errorWriter) {
        allowedHosts = properties.requestAuthority().allowedHosts().stream()
                .map(host -> RequestAuthorityParser.normalizeHostLiteral(host)
                        .orElseThrow(() -> new IllegalArgumentException("Invalid configured request authority")))
                .collect(Collectors.toUnmodifiableSet());
        this.errorWriter = errorWriter;
    }

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain filterChain) throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)
                || request.getDispatcherType() != DispatcherType.REQUEST) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        var hostHeaders = Collections.list(request.getHeaders(HttpHeaders.HOST));
        var allowed = hostHeaders.size() == 1
                && hostHeaders.getFirst() != null
                && hostHeaders.getFirst().length() <= MAX_HOST_HEADER_LENGTH
                && RequestAuthorityParser.normalizeRequestAuthority(hostHeaders.getFirst())
                        .filter(allowedHosts::contains)
                        .isPresent();
        if (!allowed) {
            errorWriter.write(
                    request,
                    response,
                    HttpStatus.BAD_REQUEST,
                    INVALID_AUTHORITY_CODE,
                    INVALID_AUTHORITY_MESSAGE,
                    null);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
