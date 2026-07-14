package com.salamu.menu.migration.internal.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LegacyImportTenantProvenance(
        UUID runId,
        LegacyImportRunFingerprint runFingerprint,
        LegacyImportTenantPlan plan,
        UUID tenantIdSnapshot,
        UUID liveTenantId,
        LegacyImportTenantState state,
        Instant createdAt,
        Instant updatedAt) {

    public LegacyImportTenantProvenance {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (liveTenantId != null && !liveTenantId.equals(tenantIdSnapshot)) {
            throw new IllegalArgumentException("Live tenant reference must match its immutable snapshot");
        }
        var cleanupWithoutSkeleton = tenantIdSnapshot == null
                && (state.stage() == LegacyImportRunStage.CLEANUP_OBJECTS_PENDING
                        || state.status() == LegacyImportTenantStatus.CLEANED);
        if (tenantIdSnapshot == null && !state.equals(LegacyImportTenantState.initial()) && !cleanupWithoutSkeleton) {
            throw new IllegalArgumentException(
                    "Only an unmaterialized or early-cleanup tenant plan may omit tenantIdSnapshot");
        }
    }
}
