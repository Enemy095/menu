package com.salamu.menu.ordering.internal.web;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import com.salamu.menu.ordering.internal.application.PublicOrderApplicationService;
import com.salamu.menu.ordering.internal.application.StrictOrderRequestParser;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicOrderController {

    private final StrictOrderRequestParser parser;
    private final PublicOrderApplicationService orders;

    public PublicOrderController(StrictOrderRequestParser parser, PublicOrderApplicationService orders) {
        this.parser = parser;
        this.orders = orders;
    }

    @PostMapping(path = "/api/v1/public/orders", consumes = "application/json", produces = "application/json")
    public ResponseEntity<CreateOrderResponse> create(
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @RequestBody JsonNode body) {
        var result = orders.create(idempotencyKey, parser.parse(body));
        var stored = result.order();
        var headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        headers.set("Idempotency-Replayed", Boolean.toString(result.replayed()));
        return new ResponseEntity<>(new CreateOrderResponse(
                stored.orderId(),
                Long.toString(stored.orderNumber()),
                stored.status(),
                stored.serverTotalMinor(),
                stored.currency(),
                stored.serverReceivedAt(),
                stored.needsConfirmation()), headers, HttpStatus.CREATED);
    }

    public record CreateOrderResponse(
            UUID orderId,
            String orderNumber,
            String status,
            long serverTotalMinor,
            String currency,
            Instant serverReceivedAt,
            boolean needsConfirmation) {
    }
}
