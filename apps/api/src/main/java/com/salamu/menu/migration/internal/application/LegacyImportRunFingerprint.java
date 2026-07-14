package com.salamu.menu.migration.internal.application;

public record LegacyImportRunFingerprint(String value) {

    public LegacyImportRunFingerprint {
        value = new LegacyImportSha256(value).value();
    }
}
