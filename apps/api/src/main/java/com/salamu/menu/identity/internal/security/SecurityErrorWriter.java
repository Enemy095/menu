package com.salamu.menu.identity.internal.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

import com.salamu.menu.TraceIdAccessor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class SecurityErrorWriter {

    static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final Pattern SAFE_DIAGNOSTIC_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final ObjectMapper objectMapper;
    private final TraceIdAccessor traceIdAccessor;

    public SecurityErrorWriter(ObjectMapper objectMapper, TraceIdAccessor traceIdAccessor) {
        this.objectMapper = objectMapper;
        this.traceIdAccessor = traceIdAccessor;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            Long retryAfterSeconds) throws IOException {
        var traceId = safeTraceId(request);
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(TRACE_ID_HEADER, traceId);
        if (retryAfterSeconds != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfterSeconds)));
        }
        objectMapper.writeValue(
                response.getOutputStream(),
                new ErrorEnvelope(code, message, List.of(), traceId));
    }

    private String safeTraceId(HttpServletRequest request) {
        try {
            var candidate = traceIdAccessor.currentTraceId(request);
            if (candidate != null && SAFE_DIAGNOSTIC_ID.matcher(candidate).matches()) {
                return candidate;
            }
        } catch (RuntimeException ignored) {
            // A diagnostic failure must not make the security boundary permissive.
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record ErrorEnvelope(
            String code,
            String message,
            List<Object> details,
            String traceId) {
    }
}
