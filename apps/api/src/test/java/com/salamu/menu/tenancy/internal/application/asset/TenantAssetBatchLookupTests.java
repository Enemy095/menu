package com.salamu.menu.tenancy.internal.application.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.TenantAsset;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantId;

class TenantAssetBatchLookupTests {

    private static final TenantId TENANT_ID =
            new TenantId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    private final AssetObjectStorage objectStorage = mock(AssetObjectStorage.class);
    private final AssetPersistence persistence = mock(AssetPersistence.class);
    private final AssetPublicUrlResolver publicUrlResolver = mock(AssetPublicUrlResolver.class);
    private final TenantAssetApplicationService service = new TenantAssetApplicationService(
            new AssetContentPolicy(),
            new AssetKeyPolicy(),
            objectStorage,
            persistence,
            publicUrlResolver);

    @Test
    void emptyInputReturnsImmutableEmptyMapWithoutPersistenceAccess() {
        var result = service.getActiveAssets(TENANT_ID, List.of());

        assertThat(result).isEmpty();
        assertThatThrownBy(() -> result.put(
                        new TenantAssetId(UUID.randomUUID()),
                        mock(TenantAsset.class)))
                .isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(persistence, publicUrlResolver, objectStorage);
    }

    @Test
    void resolvesSeveralAssetsThroughOneBatchPersistenceCallAndCollapsesDuplicates() {
        AssetRecord logo = activeRecord(
                "11111111-1111-1111-1111-111111111111",
                AssetKind.LOGO,
                "1".repeat(64),
                "png");
        AssetRecord item = activeRecord(
                "22222222-2222-2222-2222-222222222222",
                AssetKind.MENU_ITEM_IMAGE,
                "2".repeat(64),
                "webp");
        when(persistence.findAllActiveByIds(any(), any())).thenReturn(List.of(logo, item));
        when(publicUrlResolver.resolve(any()))
                .thenAnswer(invocation -> URI.create("https://cdn.example.test/" + invocation
                        .getArgument(0, AssetObjectKey.class)
                        .value()));

        var result = service.getActiveAssets(TENANT_ID, List.of(logo.id(), item.id(), logo.id()));

        assertThat(result).containsOnlyKeys(logo.id(), item.id());
        assertThat(result.get(logo.id()).publicUrl().getHost()).isEqualTo("cdn.example.test");
        assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(persistence).requireActiveTenant(TENANT_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<TenantAssetId>> ids = ArgumentCaptor.forClass(Set.class);
        verify(persistence).findAllActiveByIds(org.mockito.ArgumentMatchers.eq(TENANT_ID), ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(logo.id(), item.id());
        verify(persistence, never()).findActiveById(any(), any());
        verifyNoInteractions(objectStorage);
    }

    private static AssetRecord activeRecord(
            String id,
            AssetKind kind,
            String sha256,
            String extension) {
        AssetObjectKey objectKey = new AssetObjectKey("tenants/%s/%s/%s.%s".formatted(
                TENANT_ID.value(),
                kind.storageName(),
                sha256,
                extension));
        return new AssetRecord(
                new TenantAssetId(UUID.fromString(id)),
                TENANT_ID,
                kind,
                objectKey,
                "image/" + extension,
                100,
                new AssetSha256(sha256),
                AssetStatus.ACTIVE);
    }
}
