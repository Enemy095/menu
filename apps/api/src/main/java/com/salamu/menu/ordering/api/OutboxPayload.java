package com.salamu.menu.ordering.api;

import java.util.Arrays;
import java.util.Objects;

/** Opaque outbox JSON. Its diagnostic representation intentionally never exposes payload data. */
public final class OutboxPayload {

    private static final int MAX_BYTES = 1_048_576;

    private final byte[] jsonUtf8;

    public OutboxPayload(byte[] jsonUtf8) {
        Objects.requireNonNull(jsonUtf8, "jsonUtf8");
        if (jsonUtf8.length == 0 || jsonUtf8.length > MAX_BYTES) {
            throw new IllegalArgumentException("outbox payload size is outside accepted bounds");
        }
        this.jsonUtf8 = jsonUtf8.clone();
    }

    public byte[] jsonUtf8() {
        return jsonUtf8.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutboxPayload payload && Arrays.equals(jsonUtf8, payload.jsonUtf8);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(jsonUtf8);
    }

    @Override
    public String toString() {
        return "OutboxPayload[redacted,length=" + jsonUtf8.length + "]";
    }
}
