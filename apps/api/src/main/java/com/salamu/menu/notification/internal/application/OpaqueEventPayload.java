package com.salamu.menu.notification.internal.application;

import java.util.Objects;

/** Raw event JSON is accessible only at the provider boundary and is always redacted in text. */
public final class OpaqueEventPayload {

    private final String json;

    public OpaqueEventPayload(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Event payload must be present");
        }
        this.json = json;
    }

    public String providerValue() {
        return json;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OpaqueEventPayload payload && json.equals(payload.json);
    }

    @Override
    public int hashCode() {
        return Objects.hash(json);
    }

    @Override
    public String toString() {
        return "OpaqueEventPayload[redacted]";
    }
}
