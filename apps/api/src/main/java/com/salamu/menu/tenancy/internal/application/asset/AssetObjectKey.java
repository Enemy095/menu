package com.salamu.menu.tenancy.internal.application.asset;

import java.util.Objects;
import java.util.regex.Pattern;

public record AssetObjectKey(String value) {

    private static final Pattern FORMAT = Pattern.compile(
            "^tenants/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/"
                    + "[a-z][a-z0-9_]+/[0-9a-f]{64}\\.[a-z0-9]+$");

    public AssetObjectKey {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Asset object key does not match the tenant-scoped immutable format");
        }
    }
}
