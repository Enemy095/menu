package com.salamu.menu.migration.internal.application;

import java.util.Objects;
import java.util.Set;

public record LegacyImportRunState(LegacyImportRunStatus status, LegacyImportRunStage stage) {

    public LegacyImportRunState {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stage, "stage");
        if (!compatible(status, stage)) {
            throw new IllegalArgumentException("Incompatible legacy import run status and stage");
        }
    }

    public static LegacyImportRunState initial() {
        return new LegacyImportRunState(LegacyImportRunStatus.STAGING, LegacyImportRunStage.PLANNED);
    }

    public void requireValidTransitionTo(LegacyImportRunState next) {
        Objects.requireNonNull(next, "next");
        if (!stage.canTransitionTo(next.stage)) {
            throw new IllegalArgumentException("Legacy import stages cannot be skipped or reversed");
        }
    }

    private static boolean compatible(LegacyImportRunStatus status, LegacyImportRunStage stage) {
        return switch (status) {
            case STAGING -> Set.of(
                    LegacyImportRunStage.PLANNED,
                    LegacyImportRunStage.SKELETONS_SUSPENDED,
                    LegacyImportRunStage.ASSETS_PENDING,
                    LegacyImportRunStage.ASSETS_STAGED,
                    LegacyImportRunStage.CLEANUP_OBJECTS_PENDING).contains(stage);
            case APPLYING -> stage == LegacyImportRunStage.FINAL_TX_PENDING;
            case APPLIED -> Set.of(
                    LegacyImportRunStage.DB_PUBLISHED,
                    LegacyImportRunStage.SMOKE_VERIFIED,
                    LegacyImportRunStage.CUTOVER_APPROVED,
                    LegacyImportRunStage.COMPLETED).contains(stage);
            case FAILED -> Set.of(
                    LegacyImportRunStage.FAILED,
                    LegacyImportRunStage.CONFLICT,
                    LegacyImportRunStage.CLEANUP_OBJECTS_PENDING).contains(stage);
            case CLEANED -> stage == LegacyImportRunStage.COMPLETED;
        };
    }
}
