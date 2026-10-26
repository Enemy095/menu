package com.salamu.menu.asset.internal.application;

import java.util.Set;

enum SupportedAssetMediaType {
    PNG("image/png", "png", Set.of("png")),
    JPEG("image/jpeg", "jpg", Set.of("jpg", "jpeg")),
    WEBP("image/webp", "webp", Set.of("webp")),
    ICO("image/x-icon", "ico", Set.of("ico")),
    MICROSOFT_ICO("image/vnd.microsoft.icon", "ico", Set.of("ico"));

    private final String contentType;
    private final String canonicalExtension;
    private final Set<String> acceptedExtensions;

    SupportedAssetMediaType(String contentType, String canonicalExtension, Set<String> acceptedExtensions) {
        this.contentType = contentType;
        this.canonicalExtension = canonicalExtension;
        this.acceptedExtensions = acceptedExtensions;
    }

    String contentType() {
        return contentType;
    }

    String canonicalExtension() {
        return canonicalExtension;
    }

    boolean acceptsExtension(String extension) {
        return acceptedExtensions.contains(extension);
    }

    boolean matches(byte[] content) {
        return switch (this) {
            case PNG -> startsWith(content, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case JPEG -> startsWith(content, 0xff, 0xd8, 0xff);
            case WEBP -> startsWith(content, 0x52, 0x49, 0x46, 0x46)
                    && bytesAt(content, 8, 0x57, 0x45, 0x42, 0x50);
            case ICO, MICROSOFT_ICO -> startsWith(content, 0x00, 0x00, 0x01, 0x00);
        };
    }

    static SupportedAssetMediaType require(String contentType) {
        for (SupportedAssetMediaType candidate : values()) {
            if (candidate.contentType.equals(contentType)) {
                return candidate;
            }
        }
        throw new InvalidAssetContentException("Unsupported asset content type");
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
}
