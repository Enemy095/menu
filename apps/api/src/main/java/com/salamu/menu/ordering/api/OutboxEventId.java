package com.salamu.menu.ordering.api;

import java.util.Objects;
import java.util.UUID;

public record OutboxEventId(UUID value) {

    public OutboxEventId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return "OutboxEventId[redacted]";
    }
}
