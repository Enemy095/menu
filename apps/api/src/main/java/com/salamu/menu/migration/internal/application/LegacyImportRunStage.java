package com.salamu.menu.migration.internal.application;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum LegacyImportRunStage {
    PLANNED,
    SKELETONS_SUSPENDED,
    ASSETS_PENDING,
    ASSETS_STAGED,
    FINAL_TX_PENDING,
    DB_PUBLISHED,
    SMOKE_VERIFIED,
    CUTOVER_APPROVED,
    CLEANUP_OBJECTS_PENDING,
    COMPLETED,
    CONFLICT,
    FAILED;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LegacyImportRunStage fromDatabase(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    boolean canTransitionTo(LegacyImportRunStage next) {
        if (next == FAILED || next == CONFLICT) {
            return this != COMPLETED && this != CUTOVER_APPROVED && this != CONFLICT;
        }
        if (next == CLEANUP_OBJECTS_PENDING) {
            return CLEANUP_SOURCES.contains(this);
        }
        return switch (this) {
            case PLANNED -> next == SKELETONS_SUSPENDED;
            case SKELETONS_SUSPENDED -> next == ASSETS_PENDING;
            case ASSETS_PENDING -> next == ASSETS_STAGED;
            case ASSETS_STAGED -> next == FINAL_TX_PENDING;
            case FINAL_TX_PENDING -> next == DB_PUBLISHED;
            case DB_PUBLISHED -> next == SMOKE_VERIFIED;
            case SMOKE_VERIFIED -> next == CUTOVER_APPROVED;
            case CUTOVER_APPROVED, CLEANUP_OBJECTS_PENDING -> next == COMPLETED;
            case FAILED -> next == CLEANUP_OBJECTS_PENDING;
            case COMPLETED, CONFLICT -> false;
        };
    }

    private static final Set<LegacyImportRunStage> CLEANUP_SOURCES = EnumSet.of(
            PLANNED,
            SKELETONS_SUSPENDED,
            ASSETS_PENDING,
            ASSETS_STAGED,
            FINAL_TX_PENDING,
            FAILED);
}
