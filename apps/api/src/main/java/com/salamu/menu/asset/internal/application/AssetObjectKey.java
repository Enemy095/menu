package com.salamu.menu.asset.internal.application;

import java.util.Objects;
import java.util.regex.Pattern;

import com.salamu.menu.asset.api.AssetKind;

public record AssetObjectKey(String value) {

    private static final Pattern FORMAT = Pattern.compile(
            "^assets/(logo|favicon|hero|open_graph|menu_category_image|menu_item_image)/"
                    + "[0-9a-f]{64}\\.(png|jpg|webp|ico)$");
    private static final Pattern EXTENSION = Pattern.compile("^(png|jpg|webp|ico)$");

    public AssetObjectKey {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Asset object key does not match the canonical immutable format");
        }
    }

    public static AssetObjectKey canonical(AssetKind kind, AssetSha256 sha256, String extension) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(extension, "extension");
        if (!EXTENSION.matcher(extension).matches()) {
            throw new IllegalArgumentException("extension is not canonical");
        }
        return new AssetObjectKey(
                "assets/" + AssetKindStorageName.of(kind) + "/" + sha256.value() + "." + extension);
    }

    public String extension() {
        return value.substring(value.lastIndexOf('.') + 1);
    }
}
