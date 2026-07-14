package com.salamu.menu.migration.internal.application;

import java.util.Locale;

public enum LegacyImportAssetStatus {
    PLANNED,
    CREATED,
    REUSED,
    CLEANED;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LegacyImportAssetStatus fromDatabase(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
