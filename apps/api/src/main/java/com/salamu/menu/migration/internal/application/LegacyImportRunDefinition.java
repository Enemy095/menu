package com.salamu.menu.migration.internal.application;

import java.util.Objects;
import java.util.regex.Pattern;

public record LegacyImportRunDefinition(
        String importerKey,
        String manifestId,
        int schemaVersion,
        String environment,
        LegacyImportRunFingerprint runFingerprint,
        LegacyImportSha256 manifestSha256,
        LegacyImportSha256 fixtureSha256,
        LegacyImportSha256 completeSourceSetSha256,
        LegacyImportSha256 semanticPlanSha256) {

    private static final Pattern IMPORTER_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,119}");
    private static final Pattern MANIFEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}");
    private static final Pattern ENVIRONMENT = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    public LegacyImportRunDefinition {
        importerKey = requirePattern(importerKey, IMPORTER_KEY, "importerKey");
        manifestId = requirePattern(manifestId, MANIFEST_ID, "manifestId");
        environment = requirePattern(environment, ENVIRONMENT, "environment");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        Objects.requireNonNull(fixtureSha256, "fixtureSha256");
        Objects.requireNonNull(completeSourceSetSha256, "completeSourceSetSha256");
        Objects.requireNonNull(semanticPlanSha256, "semanticPlanSha256");
    }

    private static String requirePattern(String value, Pattern pattern, String field) {
        Objects.requireNonNull(value, field);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " has an invalid format");
        }
        return value;
    }
}
