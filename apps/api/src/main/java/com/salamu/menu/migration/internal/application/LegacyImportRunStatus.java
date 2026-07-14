package com.salamu.menu.migration.internal.application;

import java.util.Locale;

public enum LegacyImportRunStatus {
    STAGING,
    APPLYING,
    APPLIED,
    FAILED,
    CLEANED;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LegacyImportRunStatus fromDatabase(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
