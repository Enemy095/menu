package com.salamu.menu.asset.api;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

public record PublicAsset(AssetId id, AssetKind kind, URI publicUrl, String sha256) {

    private static final Pattern LOWERCASE_SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public PublicAsset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(publicUrl, "publicUrl");
        Objects.requireNonNull(sha256, "sha256");
        if (!publicUrl.isAbsolute()
                || !("https".equalsIgnoreCase(publicUrl.getScheme())
                        || "http".equalsIgnoreCase(publicUrl.getScheme()))
                || publicUrl.getHost() == null
                || publicUrl.getUserInfo() != null
                || publicUrl.getQuery() != null
                || publicUrl.getFragment() != null) {
            throw new IllegalArgumentException("publicUrl must be a safe absolute HTTP(S) URL");
        }
        if (!LOWERCASE_SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be lowercase hexadecimal SHA-256");
        }
    }
}
