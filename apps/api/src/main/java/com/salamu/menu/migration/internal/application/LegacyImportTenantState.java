package com.salamu.menu.migration.internal.application;

import java.util.Objects;

public record LegacyImportTenantState(LegacyImportTenantStatus status, LegacyImportRunStage stage) {

    public LegacyImportTenantState {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stage, "stage");
        if (!compatible(status, stage)) {
            throw new IllegalArgumentException("Incompatible legacy import tenant status and stage");
        }
    }

    public static LegacyImportTenantState initial() {
        return new LegacyImportTenantState(LegacyImportTenantStatus.SKELETON, LegacyImportRunStage.PLANNED);
    }

    public void requireValidTransitionTo(LegacyImportTenantState next) {
        Objects.requireNonNull(next, "next");
        var valid = switch (status) {
            case SKELETON -> validSkeletonTransition(next);
            case STAGED -> validStagedTransition(next);
            case APPLIED -> validAppliedTransition(next);
            case CLEANED -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Legacy import tenant stages cannot be skipped or reversed");
        }
    }

    private static boolean compatible(LegacyImportTenantStatus status, LegacyImportRunStage stage) {
        return switch (status) {
            case SKELETON -> switch (stage) {
                case PLANNED, SKELETONS_SUSPENDED, ASSETS_PENDING, CLEANUP_OBJECTS_PENDING -> true;
                default -> false;
            };
            case STAGED -> switch (stage) {
                case ASSETS_STAGED, FINAL_TX_PENDING, CLEANUP_OBJECTS_PENDING, FAILED -> true;
                default -> false;
            };
            case APPLIED -> switch (stage) {
                case DB_PUBLISHED, SMOKE_VERIFIED, CUTOVER_APPROVED, COMPLETED -> true;
                default -> false;
            };
            case CLEANED -> stage == LegacyImportRunStage.COMPLETED;
        };
    }

    private boolean validSkeletonTransition(LegacyImportTenantState next) {
        return switch (stage) {
            case PLANNED -> is(next, LegacyImportTenantStatus.SKELETON, LegacyImportRunStage.SKELETONS_SUSPENDED)
                    || isCleanupPending(next, LegacyImportTenantStatus.SKELETON);
            case SKELETONS_SUSPENDED ->
                is(next, LegacyImportTenantStatus.SKELETON, LegacyImportRunStage.ASSETS_PENDING)
                        || isCleanupPending(next, LegacyImportTenantStatus.SKELETON);
            case ASSETS_PENDING ->
                is(next, LegacyImportTenantStatus.STAGED, LegacyImportRunStage.ASSETS_STAGED)
                        || isCleanupPending(next, LegacyImportTenantStatus.SKELETON);
            case CLEANUP_OBJECTS_PENDING ->
                is(next, LegacyImportTenantStatus.CLEANED, LegacyImportRunStage.COMPLETED);
            default -> false;
        };
    }

    private boolean validStagedTransition(LegacyImportTenantState next) {
        return switch (stage) {
            case ASSETS_STAGED ->
                is(next, LegacyImportTenantStatus.STAGED, LegacyImportRunStage.FINAL_TX_PENDING)
                        || is(next, LegacyImportTenantStatus.STAGED, LegacyImportRunStage.FAILED)
                        || isCleanupPending(next, LegacyImportTenantStatus.STAGED);
            case FINAL_TX_PENDING ->
                is(next, LegacyImportTenantStatus.APPLIED, LegacyImportRunStage.DB_PUBLISHED)
                        || is(next, LegacyImportTenantStatus.STAGED, LegacyImportRunStage.FAILED)
                        || isCleanupPending(next, LegacyImportTenantStatus.STAGED);
            case FAILED -> isCleanupPending(next, LegacyImportTenantStatus.STAGED);
            case CLEANUP_OBJECTS_PENDING ->
                is(next, LegacyImportTenantStatus.CLEANED, LegacyImportRunStage.COMPLETED);
            default -> false;
        };
    }

    private boolean validAppliedTransition(LegacyImportTenantState next) {
        return switch (stage) {
            case DB_PUBLISHED ->
                is(next, LegacyImportTenantStatus.APPLIED, LegacyImportRunStage.SMOKE_VERIFIED);
            case SMOKE_VERIFIED ->
                is(next, LegacyImportTenantStatus.APPLIED, LegacyImportRunStage.CUTOVER_APPROVED);
            case CUTOVER_APPROVED ->
                is(next, LegacyImportTenantStatus.APPLIED, LegacyImportRunStage.COMPLETED);
            default -> false;
        };
    }

    private static boolean isCleanupPending(
            LegacyImportTenantState state,
            LegacyImportTenantStatus status) {
        return is(state, status, LegacyImportRunStage.CLEANUP_OBJECTS_PENDING);
    }

    private static boolean is(
            LegacyImportTenantState state,
            LegacyImportTenantStatus status,
            LegacyImportRunStage stage) {
        return state.status == status && state.stage == stage;
    }
}
