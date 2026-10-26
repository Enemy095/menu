package com.salamu.menu.ordering.internal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.salamu.menu.ApiErrorHandler;
import com.salamu.menu.TraceIdAccessor;
import com.salamu.menu.ordering.internal.application.PublicOrderApplicationService;
import com.salamu.menu.ordering.internal.application.StoredOrder;
import com.salamu.menu.ordering.internal.application.StrictOrderRequestParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicOrderControllerTests {

    private static final String KEY = "10000000-0000-4000-8000-000000000010";
    private MockMvc mvc;
    private PublicOrderApplicationService service;

    @BeforeEach
    void setUp() {
        service = mock(PublicOrderApplicationService.class);
        var traceIds = mock(TraceIdAccessor.class);
        when(traceIds.currentTraceId(any(HttpServletRequest.class))).thenReturn("trace-1");
        mvc = MockMvcBuilders.standaloneSetup(
                        new PublicOrderController(new StrictOrderRequestParser(), service))
                .setControllerAdvice(new ApiErrorHandler(traceIds))
                .build();
    }

    @Test
    void createsThroughTheExactFrozenRouteWithNoStoreAndReplayHeaders() throws Exception {
        var stored = new StoredOrder(
                UUID.fromString("20000000-0000-4000-8000-000000000020"),
                42,
                "a".repeat(64),
                "new",
                125_000,
                "RUB",
                Instant.parse("2026-07-14T12:00:00Z"),
                false);
        when(service.create(anyString(), any())).thenReturn(
                new PublicOrderApplicationService.CreationResult(stored, false));

        mvc.perform(post("/api/v1/public/orders")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.orderNumber").value("42"))
                .andExpect(jsonPath("$.status").value("new"))
                .andExpect(jsonPath("$.serverTotalMinor").value(125_000))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.needsConfirmation").value(false));
    }

    @Test
    void rejectsMissingKeyAndUnknownJsonFieldsWithContractErrorsAndNoStore() throws Exception {
        mvc.perform(post("/api/v1/public/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/public/orders")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("\"clientMenuVersion\":7", "\"clientMenuVersion\":7,\"extra\":true")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].reason").value("INVALID_FIELDS"));
    }

    @Test void closedBusinessDateReturnsSafeConflict() throws Exception {
        when(service.create(any(), any())).thenThrow(com.salamu.menu.ordering.api.OrderApiException.restaurantClosed());
        mvc.perform(post("/api/v1/public/orders").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("RESTAURANT_CLOSED"))
                .andExpect(jsonPath("$.message").value("Сегодня выходной. К сожалению, оформить заказ нельзя."));
    }

    private static String validRequest() {
        return """
                {
                  "orderType":"pickup",
                  "customer":{"name":"Customer","phone":"+79990000000"},
                  "fulfillment":{},
                  "items":[{"menuItemId":"pepperoni","quantity":2,"modifiers":[]}],
                  "clientMenuVersion":7
                }
                """;
    }
}
