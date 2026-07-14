package com.salamu.menu;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter implements Filter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".correlationId";
    static final String MDC_KEY = "correlationId";

    private static final Pattern COMPACT_CORRELATION_ID = Pattern.compile("^[0-9A-Fa-f]{32}$");
    private static final Pattern UUID_CORRELATION_ID = Pattern.compile(
            "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$");

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain filterChain) throws ServletException, IOException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        var correlationId = resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
        var previousCorrelationId = MDC.get(MDC_KEY);

        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            restoreMdc(previousCorrelationId);
        }
    }

    static String resolveCorrelationId(String candidate) {
        if (candidate != null
                && (COMPACT_CORRELATION_ID.matcher(candidate).matches()
                        || UUID_CORRELATION_ID.matcher(candidate).matches())) {
            return candidate.replace("-", "").toLowerCase(Locale.ROOT);
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void restoreMdc(String previousCorrelationId) {
        if (previousCorrelationId == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, previousCorrelationId);
        }
    }
}
