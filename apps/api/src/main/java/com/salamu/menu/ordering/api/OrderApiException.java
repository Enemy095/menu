package com.salamu.menu.ordering.api;

import java.util.List;

/** Safe, contract-level order failure. Values supplied by a customer are never retained. */
public final class OrderApiException extends RuntimeException {

    private final Code code;
    private final List<Detail> details;

    private OrderApiException(Code code, List<Detail> details) {
        super(code.name());
        this.code = code;
        this.details = List.copyOf(details);
    }

    public static OrderApiException validation(String field, String reason) {
        return new OrderApiException(Code.VALIDATION_ERROR, List.of(new Detail(field, reason)));
    }

    public static OrderApiException menuChanged() {
        return new OrderApiException(Code.MENU_CHANGED, List.of());
    }

    public static OrderApiException itemUnavailable(String field) {
        return new OrderApiException(Code.ITEM_UNAVAILABLE, List.of(new Detail(field, "UNAVAILABLE")));
    }

    public static OrderApiException modifierUnavailable(String field) {
        return new OrderApiException(Code.MODIFIER_UNAVAILABLE, List.of(new Detail(field, "UNAVAILABLE")));
    }

    public static OrderApiException idempotencyConflict() {
        return new OrderApiException(
                Code.IDEMPOTENCY_CONFLICT,
                List.of(new Detail("Idempotency-Key", "PAYLOAD_MISMATCH")));
    }

    public static OrderApiException dependencyUnavailable() {
        return new OrderApiException(Code.DEPENDENCY_UNAVAILABLE, List.of());
    }

    public Code code() {
        return code;
    }

    public List<Detail> details() {
        return details;
    }

    public enum Code {
        VALIDATION_ERROR,
        MENU_CHANGED,
        ITEM_UNAVAILABLE,
        MODIFIER_UNAVAILABLE,
        IDEMPOTENCY_CONFLICT,
        DEPENDENCY_UNAVAILABLE
    }

    public record Detail(String field, String reason) {

        public Detail {
            if (field == null || field.isBlank() || field.length() > 160
                    || reason == null || !reason.matches("^[A-Z][A-Z0-9_]{0,119}$")) {
                throw new IllegalArgumentException("order error detail is invalid");
            }
        }
    }
}
