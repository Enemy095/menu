package com.salamu.menu.notification.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned canonical SHA-256 for sanitized destination semantic state. */
public record NotificationDestinationSemanticHash(int schemaVersion, String value) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public NotificationDestinationSemanticHash {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported notification destination hash schema");
        }
        Objects.requireNonNull(value, "value");
        if (!LOWERCASE_SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("Notification destination hash must be a lowercase SHA-256 value");
        }
    }
}
