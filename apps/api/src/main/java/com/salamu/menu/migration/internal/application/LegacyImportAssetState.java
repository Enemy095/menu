package com.salamu.menu.migration.internal.application;

import java.util.Objects;

public record LegacyImportAssetState(LegacyImportAssetStatus status, LegacyImportRunStage stage) {

    public LegacyImportAssetState {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stage, "stage");
        if (!compatible(status, stage)) {
            throw new IllegalArgumentException("Incompatible legacy import asset status and stage");
        }
    }

    public static LegacyImportAssetState initial() {
        return new LegacyImportAssetState(LegacyImportAssetStatus.PLANNED, LegacyImportRunStage.ASSETS_PENDING);
    }

    public void requireValidTransitionTo(LegacyImportAssetState next) {
        Objects.requireNonNull(next, "next");
        var valid = switch (status) {
            case PLANNED -> switch (stage) {
                case ASSETS_PENDING -> ((next.status == LegacyImportAssetStatus.CREATED
                                        || next.status == LegacyImportAssetStatus.REUSED)
                                && next.stage == LegacyImportRunStage.ASSETS_STAGED)
                        || (next.status == LegacyImportAssetStatus.PLANNED
                                && next.stage == LegacyImportRunStage.CLEANUP_OBJECTS_PENDING);
                case CLEANUP_OBJECTS_PENDING -> next.status == LegacyImportAssetStatus.CLEANED
                        && next.stage == LegacyImportRunStage.COMPLETED;
                default -> false;
            };
            case CREATED, REUSED -> switch (stage) {
                case ASSETS_STAGED -> next.status == status
                        && (next.stage == LegacyImportRunStage.CLEANUP_OBJECTS_PENDING
                                || next.stage == LegacyImportRunStage.FAILED);
                case FAILED -> next.status == status
                        && next.stage == LegacyImportRunStage.CLEANUP_OBJECTS_PENDING;
                case CLEANUP_OBJECTS_PENDING -> next.status == LegacyImportAssetStatus.CLEANED
                        && next.stage == LegacyImportRunStage.COMPLETED;
                default -> false;
            };
            case CLEANED -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Legacy import asset stages cannot be skipped or reversed");
        }
    }

    private static boolean compatible(LegacyImportAssetStatus status, LegacyImportRunStage stage) {
        return switch (status) {
            case PLANNED -> stage == LegacyImportRunStage.ASSETS_PENDING
                    || stage == LegacyImportRunStage.CLEANUP_OBJECTS_PENDING;
            case CREATED, REUSED -> stage == LegacyImportRunStage.ASSETS_STAGED
                    || stage == LegacyImportRunStage.CLEANUP_OBJECTS_PENDING
                    || stage == LegacyImportRunStage.FAILED;
            case CLEANED -> stage == LegacyImportRunStage.COMPLETED;
        };
    }
}
