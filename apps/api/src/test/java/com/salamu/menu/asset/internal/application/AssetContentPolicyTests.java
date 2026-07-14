package com.salamu.menu.asset.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.salamu.menu.asset.api.AssetKind;

class AssetContentPolicyTests {

    private final AssetContentPolicy policy = new AssetContentPolicy();

    @Test
    void validatesBytesAndBuildsCanonicalContentAddressedObject() {
        byte[] png = png();

        ValidatedAssetContent validated = policy.validate(
                new AssetUploadContent(AssetKind.CATEGORY, "image/png", "png", png));
        AssetObject object = validated.toObject();

        assertThat(validated.sha256()).isEqualTo(AssetSha256.calculate(png));
        assertThat(object.key().value()).isEqualTo(
                "assets/menu_category_image/" + validated.sha256().value() + ".png");
        assertThat(object.contentType()).isEqualTo("image/png");
        assertThat(object.content()).isEqualTo(png);
    }

    @Test
    void canonicalizesJpegExtension() {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};

        ValidatedAssetContent validated = policy.validate(
                new AssetUploadContent(AssetKind.HERO, "image/jpeg", "jpeg", jpeg));

        assertThat(validated.extension()).isEqualTo("jpg");
        assertThat(validated.toObject().key().value()).endsWith(".jpg");
    }

    @Test
    void rejectsEmptyOversizedMismatchedAndDisallowedContentBeforeStorage() {
        assertThatThrownBy(() -> policy.validate(
                        new AssetUploadContent(AssetKind.LOGO, "image/png", "png", new byte[0])))
                .isInstanceOf(InvalidAssetContentException.class);
        assertThatThrownBy(() -> policy.validate(
                        new AssetUploadContent(AssetKind.LOGO, "image/png", "jpg", png())))
                .isInstanceOf(InvalidAssetContentException.class);
        assertThatThrownBy(() -> policy.validate(
                        new AssetUploadContent(AssetKind.LOGO, "image/png", "png", new byte[] {1, 2, 3})))
                .isInstanceOf(InvalidAssetContentException.class);
        assertThatThrownBy(() -> policy.validate(
                        new AssetUploadContent(
                                AssetKind.FAVICON,
                                "image/png",
                                "png",
                                oversizedPng(1024 * 1024 + 1))))
                .isInstanceOf(InvalidAssetContentException.class);
        assertThatThrownBy(() -> policy.validate(
                        new AssetUploadContent(
                                AssetKind.FAVICON,
                                "image/webp",
                                "webp",
                                new byte[] {0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50})))
                .isInstanceOf(InvalidAssetContentException.class);
    }

    @Test
    void contentIsDefensivelyCopiedAtBothBoundaries() {
        byte[] original = png();
        AssetUploadContent upload = new AssetUploadContent(AssetKind.LOGO, "image/png", "png", original);
        original[0] = 0;

        ValidatedAssetContent validated = policy.validate(upload);
        byte[] exposed = validated.content();
        exposed[0] = 0;

        assertThat(validated.content()[0]).isEqualTo((byte) 0x89);
    }

    private static byte[] png() {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    }

    private static byte[] oversizedPng(int size) {
        byte[] content = new byte[size];
        byte[] signature = png();
        System.arraycopy(signature, 0, content, 0, signature.length);
        return content;
    }
}
