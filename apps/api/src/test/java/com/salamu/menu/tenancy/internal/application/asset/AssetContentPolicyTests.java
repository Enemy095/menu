package com.salamu.menu.tenancy.internal.application.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.InvalidTenantAssetException;
import com.salamu.menu.tenancy.api.TenantAssetImportRequest;
import com.salamu.menu.tenancy.api.TenantId;

class AssetContentPolicyTests {

    private final AssetContentPolicy contentPolicy = new AssetContentPolicy();
    private final AssetKeyPolicy keyPolicy = new AssetKeyPolicy();

    @Test
    void calculatesChecksumServerSideAndBuildsTheFixedTenantScopedKey() {
        TenantId tenantId = new TenantId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        byte[] png = pngContent();

        ValidatedAssetContent validated = contentPolicy.validate(
                new TenantAssetImportRequest(AssetKind.LOGO, "image/png", "png", png));

        assertThat(validated.sha256().value())
                .isEqualTo("275f1bcbbb585c71e3b2184304eccfa0e37de92022ca3b6f4e9c10df32318d85");
        assertThat(keyPolicy.create(tenantId, validated).value())
                .isEqualTo("tenants/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/logo/"
                        + "275f1bcbbb585c71e3b2184304eccfa0e37de92022ca3b6f4e9c10df32318d85.png");
    }

    @Test
    void canonicalizesAllowedJpegExtensionWithoutChangingTheContentHash() {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};

        ValidatedAssetContent jpg = contentPolicy.validate(
                new TenantAssetImportRequest(AssetKind.MENU_ITEM_IMAGE, "image/jpeg", "jpg", jpeg));
        ValidatedAssetContent jpegAlias = contentPolicy.validate(
                new TenantAssetImportRequest(AssetKind.MENU_ITEM_IMAGE, "image/jpeg", "jpeg", jpeg));

        assertThat(jpegAlias.extension()).isEqualTo("jpg");
        assertThat(jpegAlias.sha256()).isEqualTo(jpg.sha256());
    }

    @Test
    void rejectsUnknownTypeMismatchedExtensionAndSpoofedBytes() {
        assertThatThrownBy(() -> contentPolicy.validate(
                        new TenantAssetImportRequest(AssetKind.LOGO, "text/html", "html", pngContent())))
                .isInstanceOf(InvalidTenantAssetException.class);
        assertThatThrownBy(() -> contentPolicy.validate(
                        new TenantAssetImportRequest(AssetKind.LOGO, "image/png", "jpg", pngContent())))
                .isInstanceOf(InvalidTenantAssetException.class);
        assertThatThrownBy(() -> contentPolicy.validate(
                        new TenantAssetImportRequest(AssetKind.LOGO, "image/png", "png", new byte[] {1, 2, 3})))
                .isInstanceOf(InvalidTenantAssetException.class);
    }

    @Test
    void rejectsEmptyOversizedDottedAndUppercaseInputs() {
        assertThatThrownBy(() -> contentPolicy.validate(
                        new TenantAssetImportRequest(AssetKind.LOGO, "image/png", "png", new byte[0])))
                .isInstanceOf(InvalidTenantAssetException.class);

        byte[] oversizedFavicon = new byte[1024 * 1024 + 1];
        System.arraycopy(pngContent(), 0, oversizedFavicon, 0, pngContent().length);
        assertThatThrownBy(() -> contentPolicy.validate(
                        new TenantAssetImportRequest(AssetKind.FAVICON, "image/png", "png", oversizedFavicon)))
                .isInstanceOf(InvalidTenantAssetException.class);

        assertThatThrownBy(() -> contentPolicy.validate(
                        new TenantAssetImportRequest(AssetKind.LOGO, "image/png", ".png", pngContent())))
                .isInstanceOf(InvalidTenantAssetException.class);
        assertThatThrownBy(() -> contentPolicy.validate(
                        new TenantAssetImportRequest(AssetKind.LOGO, "image/png", "PNG", pngContent())))
                .isInstanceOf(InvalidTenantAssetException.class);
    }

    @Test
    void rejectsMalformedOrCallerStyledChecksum() {
        assertThatThrownBy(() -> new AssetSha256("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssetSha256("0".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void importRequestDefensivelyCopiesMutableContent() {
        byte[] original = pngContent();
        TenantAssetImportRequest request =
                new TenantAssetImportRequest(AssetKind.LOGO, "image/png", "png", original);
        original[0] = 0;
        byte[] returned = request.content();
        returned[1] = 0;

        assertThat(Byte.toUnsignedInt(request.content()[0])).isEqualTo(0x89);
        assertThat(Byte.toUnsignedInt(request.content()[1])).isEqualTo(0x50);
    }

    private static byte[] pngContent() {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01};
    }
}
