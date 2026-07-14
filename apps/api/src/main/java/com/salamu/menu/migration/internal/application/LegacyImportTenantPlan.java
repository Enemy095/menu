package com.salamu.menu.migration.internal.application;

import java.util.Objects;
import java.util.regex.Pattern;

public record LegacyImportTenantPlan(
        String tenantSlug,
        long menuVersion,
        LegacyImportSha256 tenantSourceSetSha256,
        LegacyImportSha256 assetSetSha256,
        LegacyImportSha256 expectedConfigSha256,
        LegacyImportSha256 expectedMenuSha256,
        LegacyImportSha256 expectedDestinationSha256,
        LegacyImportSha256 semanticFingerprint) {

    private static final Pattern TENANT_SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    public LegacyImportTenantPlan {
        Objects.requireNonNull(tenantSlug, "tenantSlug");
        if (tenantSlug.length() > 63 || !TENANT_SLUG.matcher(tenantSlug).matches()) {
            throw new IllegalArgumentException("tenantSlug has an invalid format");
        }
        if (menuVersion < 1) {
            throw new IllegalArgumentException("menuVersion must be positive");
        }
        Objects.requireNonNull(tenantSourceSetSha256, "tenantSourceSetSha256");
        Objects.requireNonNull(assetSetSha256, "assetSetSha256");
        Objects.requireNonNull(expectedConfigSha256, "expectedConfigSha256");
        Objects.requireNonNull(expectedMenuSha256, "expectedMenuSha256");
        Objects.requireNonNull(expectedDestinationSha256, "expectedDestinationSha256");
        Objects.requireNonNull(semanticFingerprint, "semanticFingerprint");
    }
}
