package com.salamu.menu.tenancy.internal.application.asset;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public record AssetSha256(String value) {

    private static final Pattern LOWER_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public AssetSha256 {
        Objects.requireNonNull(value, "value");
        if (!LOWER_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("SHA-256 must contain exactly 64 lowercase hexadecimal characters");
        }
    }

    static AssetSha256 calculate(byte[] content) {
        Objects.requireNonNull(content, "content");
        try {
            return new AssetSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public String base64() {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(value));
    }
}
