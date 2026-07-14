package com.salamu.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiErrorHandlerTests {

    private static final String TRACE_ID = "f7ab469535a145e08e9c094f86181f87";
    private static final String SENSITIVE_VALUE =
            "phone=+79991112233 address=Secret-street comment=Secret-comment";
    private static final Set<String> CONTRACT_ERROR_CODES = Set.of(
            "TENANT_NOT_FOUND",
            "VALIDATION_ERROR",
            "MENU_CHANGED",
            "ITEM_UNAVAILABLE",
            "TOTAL_MISMATCH",
            "IDEMPOTENCY_CONFLICT",
            "RATE_LIMITED",
            "INTERNAL_ERROR");
    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\\\"code\\\":\\\"([A-Z_]+)\\\"");

    @Test
    void returnsAndLogsTheSameTraceIdWithoutPii() throws Exception {
        var traceIdAccessor = mock(TraceIdAccessor.class);
        when(traceIdAccessor.currentTraceId(any(HttpServletRequest.class))).thenReturn(TRACE_ID);
        var logger = (Logger) LoggerFactory.getLogger(ApiErrorHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            var mockMvc = MockMvcBuilders
                    .standaloneSetup(new FailingController())
                    .setControllerAdvice(new ApiErrorHandler(traceIdAccessor))
                    .addFilters(new CorrelationIdFilter())
                    .build();

            var response = mockMvc.perform(get("/__test/observability/failure"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().string(ApiErrorHandler.TRACE_ID_HEADER, TRACE_ID))
                    .andReturn()
                    .getResponse();

            assertThat(response.getContentAsString())
                    .contains("\"code\":\"INTERNAL_ERROR\"")
                    .contains("\"traceId\":\"" + TRACE_ID + "\"")
                    .doesNotContain("79991112233", "Secret-street", "Secret-comment");

            var notFoundResponse = mockMvc.perform(get("/__test/observability/not-found"))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string(ApiErrorHandler.TRACE_ID_HEADER, TRACE_ID))
                    .andReturn()
                    .getResponse();
            assertThat(notFoundResponse.getContentAsString())
                    .contains("\"code\":\"TENANT_NOT_FOUND\"")
                    .contains("\"traceId\":\"" + TRACE_ID + "\"")
                    .doesNotContain("79991112233", "Secret-street", "Secret-comment");

            var validationResponse = mockMvc.perform(get("/__test/observability/validation"))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(ApiErrorHandler.TRACE_ID_HEADER, TRACE_ID))
                    .andReturn()
                    .getResponse();
            assertThat(validationResponse.getContentAsString())
                    .contains("\"code\":\"VALIDATION_ERROR\"")
                    .contains("\"traceId\":\"" + TRACE_ID + "\"")
                    .doesNotContain("79991112233", "Secret-street", "Secret-comment");

            assertContractCode(mockMvc.perform(get("/__test/observability/bad-request"))
                    .andExpect(status().isBadRequest())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(), "VALIDATION_ERROR");
            assertContractCode(mockMvc.perform(get("/__test/observability/rate-limited"))
                    .andExpect(status().isTooManyRequests())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(), "RATE_LIMITED");
            assertContractCode(mockMvc.perform(get("/__test/observability/not-implemented"))
                    .andExpect(status().isNotImplemented())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(), "INTERNAL_ERROR");

            assertThat(CONTRACT_ERROR_CODES).contains(extractErrorCode(notFoundResponse.getContentAsString()));
            assertThat(CONTRACT_ERROR_CODES).contains(extractErrorCode(validationResponse.getContentAsString()));

            assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            var loggedTraceId = event.getKeyValuePairs().stream()
                    .filter(pair -> pair.key.equals("traceId"))
                    .map(pair -> Objects.toString(pair.value))
                    .findFirst();
            assertThat(loggedTraceId).contains(TRACE_ID);
            assertThat(event.getFormattedMessage())
                    .doesNotContain("79991112233", "Secret-street", "Secret-comment");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void assertContractCode(String responseBody, String expectedCode) {
        var code = extractErrorCode(responseBody);
        assertThat(code).isEqualTo(expectedCode);
        assertThat(CONTRACT_ERROR_CODES).contains(code);
    }

    private static String extractErrorCode(String responseBody) {
        var matcher = ERROR_CODE_PATTERN.matcher(responseBody);
        assertThat(matcher.find()).as("error response contains code").isTrue();
        return matcher.group(1);
    }

    @RestController
    static class FailingController {

        @GetMapping("/__test/observability/failure")
        String fail() {
            throw new IllegalStateException(SENSITIVE_VALUE);
        }

        @GetMapping("/__test/observability/not-found")
        String notFound() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, SENSITIVE_VALUE);
        }

        @GetMapping("/__test/observability/bad-request")
        String badRequest() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, SENSITIVE_VALUE);
        }

        @GetMapping("/__test/observability/rate-limited")
        String rateLimited() {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, SENSITIVE_VALUE);
        }

        @GetMapping("/__test/observability/not-implemented")
        String notImplemented() {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, SENSITIVE_VALUE);
        }

        @GetMapping("/__test/observability/validation")
        String validationFailure() {
            throw new ConstraintViolationException(SENSITIVE_VALUE, Set.of());
        }
    }
}
