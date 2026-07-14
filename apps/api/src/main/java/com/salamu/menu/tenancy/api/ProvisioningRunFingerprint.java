package com.salamu.menu.tenancy.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Non-secret SHA-256 provenance token for an immutable provisioning plan. */
public record ProvisioningRunFingerprint(String value) {

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public ProvisioningRunFingerprint {
        Objects.requireNonNull(value, "value");
        if (!LOWERCASE_SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Provisioning run fingerprint must be a lowercase SHA-256 value");
        }
    }
}
