package com.salamu.menu.tenancy.internal.application.asset;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.InvalidTenantAssetException;
import com.salamu.menu.tenancy.api.TenantAssetImportRequest;

@Component
final class AssetContentPolicy {

    private static final long MEBIBYTE = 1024L * 1024L;

    private static final Map<AssetKind, Policy> POLICIES = Map.of(
            AssetKind.LOGO, new Policy(5 * MEBIBYTE, Set.of("image/png", "image/jpeg", "image/webp")),
            AssetKind.FAVICON, new Policy(1 * MEBIBYTE, Set.of("image/png", "image/x-icon", "image/vnd.microsoft.icon")),
            AssetKind.HERO, new Policy(10 * MEBIBYTE, Set.of("image/png", "image/jpeg", "image/webp")),
            AssetKind.OPEN_GRAPH, new Policy(10 * MEBIBYTE, Set.of("image/png", "image/jpeg", "image/webp")),
            AssetKind.MENU_CATEGORY_IMAGE,
                    new Policy(10 * MEBIBYTE, Set.of("image/png", "image/jpeg", "image/webp")),
            AssetKind.MENU_ITEM_IMAGE,
                    new Policy(10 * MEBIBYTE, Set.of("image/png", "image/jpeg", "image/webp")));

    ValidatedAssetContent validate(TenantAssetImportRequest request) {
        Objects.requireNonNull(request, "request");
        byte[] content = request.content();
        Policy policy = POLICIES.get(request.kind());
        if (content.length == 0) {
            throw invalid("Asset content must not be empty");
        }
        if (content.length > policy.maxSizeBytes()) {
            throw invalid("Asset exceeds the maximum size for " + request.kind().storageName());
        }
        if (!policy.contentTypes().contains(request.contentType())) {
            throw invalid("Content type is not allowed for " + request.kind().storageName());
        }

        String canonicalExtension = canonicalExtension(request.contentType(), request.extension());
        if (!matchesSignature(request.contentType(), content)) {
            throw invalid("Asset bytes do not match the declared content type");
        }

        return new ValidatedAssetContent(
                request.kind(),
                request.contentType(),
                canonicalExtension,
                content,
                AssetSha256.calculate(content));
    }

    private static String canonicalExtension(String contentType, String extension) {
        return switch (contentType) {
            case "image/png" -> requireExtension(extension, Set.of("png"), "png");
            case "image/jpeg" -> requireExtension(extension, Set.of("jpg", "jpeg"), "jpg");
            case "image/webp" -> requireExtension(extension, Set.of("webp"), "webp");
            case "image/x-icon", "image/vnd.microsoft.icon" -> requireExtension(extension, Set.of("ico"), "ico");
            default -> throw invalid("Unsupported content type");
        };
    }

    private static String requireExtension(String extension, Set<String> allowed, String canonical) {
        if (!allowed.contains(extension)) {
            throw invalid("File extension does not match the declared content type");
        }
        return canonical;
    }

    private static boolean matchesSignature(String contentType, byte[] content) {
        return switch (contentType) {
            case "image/png" -> startsWith(content, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "image/jpeg" -> startsWith(content, 0xff, 0xd8, 0xff);
            case "image/webp" -> startsWith(content, 0x52, 0x49, 0x46, 0x46)
                    && bytesAt(content, 8, 0x57, 0x45, 0x42, 0x50);
            case "image/x-icon", "image/vnd.microsoft.icon" -> startsWith(content, 0x00, 0x00, 0x01, 0x00);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] content, int... expected) {
        return bytesAt(content, 0, expected);
    }

    private static boolean bytesAt(byte[] content, int offset, int... expected) {
        if (content.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (Byte.toUnsignedInt(content[offset + index]) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static InvalidTenantAssetException invalid(String message) {
        return new InvalidTenantAssetException(message);
    }

    private record Policy(long maxSizeBytes, Set<String> contentTypes) {
    }
}
