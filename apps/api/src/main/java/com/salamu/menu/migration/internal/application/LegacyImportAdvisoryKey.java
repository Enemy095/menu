package com.salamu.menu.migration.internal.application;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Objects;

public record LegacyImportAdvisoryKey(long value) {

    public static LegacyImportAdvisoryKey from(LegacyImportRunFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        var firstEightBytes = HexFormat.of().parseHex(fingerprint.value(), 0, 16);
        return new LegacyImportAdvisoryKey(ByteBuffer.wrap(firstEightBytes).getLong());
    }
}
