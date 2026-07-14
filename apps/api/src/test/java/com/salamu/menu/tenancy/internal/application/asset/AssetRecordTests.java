package com.salamu.menu.tenancy.internal.application.asset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

class AssetRecordTests {

    private static final TenantId TENANT_A = tenant("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final TenantId TENANT_B = tenant("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final AssetSha256 SHA256 = new AssetSha256("0".repeat(64));

    @Test
    void rejectsObjectKeyPointingAtAnotherTenant() {
        assertThatThrownBy(() -> record(
                        TENANT_A,
                        AssetKind.LOGO,
                        "tenants/" + TENANT_B.value() + "/logo/" + SHA256.value() + ".png",
                        "image/png",
                        SHA256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsObjectKeyWithDifferentKindHashOrContentTypeExtension() {
        assertThatThrownBy(() -> record(
                        TENANT_A,
                        AssetKind.LOGO,
                        "tenants/" + TENANT_A.value() + "/hero/" + SHA256.value() + ".png",
                        "image/png",
                        SHA256))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(
                        TENANT_A,
                        AssetKind.LOGO,
                        "tenants/" + TENANT_A.value() + "/logo/" + "1".repeat(64) + ".png",
                        "image/png",
                        SHA256))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(
                        TENANT_A,
                        AssetKind.LOGO,
                        "tenants/" + TENANT_A.value() + "/logo/" + SHA256.value() + ".webp",
                        "image/png",
                        SHA256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AssetRecord record(
            TenantId tenantId,
            AssetKind kind,
            String objectKey,
            String contentType,
            AssetSha256 sha256) {
        return new AssetRecord(
                new TenantAssetId(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")),
                tenantId,
                kind,
                new AssetObjectKey(objectKey),
                contentType,
                1,
                sha256,
                AssetStatus.ACTIVE);
    }

    private static TenantId tenant(String value) {
        return new TenantId(UUID.fromString(value));
    }
}
