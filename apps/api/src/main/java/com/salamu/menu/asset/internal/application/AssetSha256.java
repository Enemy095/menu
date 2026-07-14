package com.salamu.menu.asset.internal.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

public record AssetSha256(String value) {

    private static final Pattern LOWERCASE_SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public AssetSha256 {
        Objects.requireNonNull(value, "value");
        if (!LOWERCASE_SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException("value must be lowercase hexadecimal SHA-256");
        }
    }

    public static AssetSha256 calculate(byte[] content) {
        Objects.requireNonNull(content, "content");
        try {
            return new AssetSha256(
                    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public String base64() {
        return Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(value));
    }
}
