package com.salamu.menu;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ApiErrorHandler {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorHandler.class);
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    private static final String INTERNAL_ERROR_MESSAGE = "Не удалось обработать запрос";
    private static final String VALIDATION_ERROR_CODE = "VALIDATION_ERROR";
    private static final String VALIDATION_ERROR_MESSAGE = "Ошибка валидации запроса";
    private static final String TENANT_NOT_FOUND_CODE = "TENANT_NOT_FOUND";
    private static final String RATE_LIMITED_CODE = "RATE_LIMITED";
    private static final String GENERIC_HTTP_ERROR_MESSAGE = "Ошибка HTTP-запроса";

    private final TraceIdAccessor traceIdAccessor;

    public ApiErrorHandler(TraceIdAccessor traceIdAccessor) {
        this.traceIdAccessor = traceIdAccessor;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request) {
        var status = exception.getStatusCode();
        var resolvedStatus = HttpStatus.resolve(status.value());
        var message = resolvedStatus == null ? GENERIC_HTTP_ERROR_MESSAGE : resolvedStatus.getReasonPhrase();
        return createResponse(status, contractErrorCode(status), message, request);
    }

    @ExceptionHandler({
            BindException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> handleValidation(HttpServletRequest request) {
        return createResponse(
                HttpStatus.BAD_REQUEST,
                VALIDATION_ERROR_CODE,
                VALIDATION_ERROR_MESSAGE,
                request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        var traceId = traceIdAccessor.currentTraceId(request);

        LOGGER.atError()
                .addKeyValue("traceId", traceId)
                .addKeyValue("errorType", exception.getClass().getName())
                .log("Unhandled API request failure");

        var headers = new HttpHeaders();
        headers.set(TRACE_ID_HEADER, traceId);
        var body = new ApiErrorResponse(INTERNAL_ERROR_CODE, INTERNAL_ERROR_MESSAGE, List.of(), traceId);
        return new ResponseEntity<>(body, headers, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ApiErrorResponse> createResponse(
            HttpStatusCode status,
            String code,
            String message,
            HttpServletRequest request) {
        var traceId = traceIdAccessor.currentTraceId(request);
        var headers = new HttpHeaders();
        headers.set(TRACE_ID_HEADER, traceId);
        var body = new ApiErrorResponse(code, message, List.of(), traceId);
        return new ResponseEntity<>(body, headers, status);
    }

    private static String contractErrorCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> VALIDATION_ERROR_CODE;
            case 404 -> TENANT_NOT_FOUND_CODE;
            case 429 -> RATE_LIMITED_CODE;
            default -> INTERNAL_ERROR_CODE;
        };
    }

    public record ApiErrorResponse(
            String code,
            String message,
            List<ApiErrorDetail> details,
            String traceId) {
    }

    public record ApiErrorDetail(String field, String reason) {
    }
}
