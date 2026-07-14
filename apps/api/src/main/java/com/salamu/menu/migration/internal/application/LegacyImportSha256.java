package com.salamu.menu.migration.internal.application;

import java.util.Objects;
import java.util.regex.Pattern;

public record LegacyImportSha256(String value) {

    private static final Pattern LOWERCASE_SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public LegacyImportSha256 {
        Objects.requireNonNull(value, "value");
        if (!LOWERCASE_SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException("SHA-256 must contain exactly 64 lowercase hexadecimal characters");
        }
    }
}
