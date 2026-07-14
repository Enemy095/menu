package com.salamu.menu.migration.internal.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record LegacyImportRun(
        UUID runId,
        LegacyImportRunDefinition definition,
        LegacyImportRunState state,
        String failureCode,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant appliedAt,
        Instant completedAt) {

    private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,119}");

    public LegacyImportRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(startedAt, "startedAt");
        if (state.status() == LegacyImportRunStatus.FAILED) {
            if (failureCode == null || !FAILURE_CODE.matcher(failureCode).matches()) {
                throw new IllegalArgumentException("A failed run requires a redacted failure code");
            }
        } else if (failureCode != null) {
            throw new IllegalArgumentException("Only a failed run may have a failure code");
        }
    }
}
