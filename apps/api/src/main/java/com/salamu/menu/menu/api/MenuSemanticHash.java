package com.salamu.menu.menu.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned canonical SHA-256 of one complete published menu snapshot. */
public record MenuSemanticHash(int schemaVersion, String value) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public MenuSemanticHash {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported menu semantic hash schema");
        }
        Objects.requireNonNull(value, "value");
        if (!LOWERCASE_SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("Menu semantic hash must be lowercase SHA-256");
        }
    }
}
