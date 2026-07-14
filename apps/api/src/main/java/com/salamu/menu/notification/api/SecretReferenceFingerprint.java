package com.salamu.menu.notification.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Non-reversible SHA-256 of an opaque secret-manager reference. */
public record SecretReferenceFingerprint(String value) {

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public SecretReferenceFingerprint {
        Objects.requireNonNull(value, "value");
        if (!LOWERCASE_SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("Secret reference fingerprint must be a lowercase SHA-256 value");
        }
    }
}
