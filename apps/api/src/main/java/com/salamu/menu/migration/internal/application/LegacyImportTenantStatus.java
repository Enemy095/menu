package com.salamu.menu.migration.internal.application;

import java.util.Locale;

public enum LegacyImportTenantStatus {
    SKELETON,
    STAGED,
    APPLIED,
    CLEANED;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LegacyImportTenantStatus fromDatabase(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
