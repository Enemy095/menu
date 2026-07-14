package com.salamu.menu.asset.internal.application;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.salamu.menu.asset.api.AssetKind;

public final class AssetContentPolicy {

    private static final long MEBIBYTE = 1024L * 1024L;
    private static final Map<AssetKind, Policy> POLICIES = Map.of(
            AssetKind.LOGO, new Policy(5 * MEBIBYTE, Set.of("image/png", "image/jpeg", "image/webp")),
            AssetKind.FAVICON,
                    new Policy(1 * MEBIBYTE, Set.of("image/png", "image/x-icon", "image/vnd.microsoft.icon")),
            AssetKind.HERO, new Policy(10 * MEBIBYTE, Set.of("image/png", "image/jpeg", "image/webp")),
            AssetKind.OPEN_GRAPH, new Policy(10 * MEBIBYTE, Set.of("image/png", "image/jpeg", "image/webp")),
            AssetKind.CATEGORY, new Policy(10 * MEBIBYTE, Set.of("image/png", "image/jpeg", "image/webp")),
            AssetKind.MENU_ITEM, new Policy(10 * MEBIBYTE, Set.of("image/png", "image/jpeg", "image/webp")));

    public ValidatedAssetContent validate(AssetUploadContent upload) {
        Objects.requireNonNull(upload, "upload");
        byte[] content = upload.content();
        Policy policy = POLICIES.get(upload.kind());
        if (content.length == 0) {
            throw invalid("Asset content must not be empty");
        }
        if (content.length > policy.maxSizeBytes()) {
            throw invalid("Asset exceeds the maximum size for its kind");
        }
        if (!policy.contentTypes().contains(upload.contentType())) {
            throw invalid("Asset content type is not allowed for its kind");
        }

        SupportedAssetMediaType mediaType = SupportedAssetMediaType.require(upload.contentType());
        if (!mediaType.acceptsExtension(upload.extension())) {
            throw invalid("Asset extension does not match its content type");
        }
        if (!mediaType.matches(content)) {
            throw invalid("Asset bytes do not match its content type");
        }

        return new ValidatedAssetContent(
                upload.kind(),
                mediaType.contentType(),
                mediaType.canonicalExtension(),
                content,
                AssetSha256.calculate(content));
    }

    public static long maximumSizeBytes(AssetKind kind) {
        Objects.requireNonNull(kind, "kind");
        return POLICIES.get(kind).maxSizeBytes();
    }

    public static boolean allowsContentType(AssetKind kind, String contentType) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(contentType, "contentType");
        return POLICIES.get(kind).contentTypes().contains(contentType);
    }

    private static InvalidAssetContentException invalid(String message) {
        return new InvalidAssetContentException(message);
    }

    private record Policy(long maxSizeBytes, Set<String> contentTypes) {
    }
}
