package com.salamu.menu.menu.internal.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.menu.api.MenuAssetReference;
import com.salamu.menu.menu.api.MenuCleanupCandidate;
import com.salamu.menu.menu.api.MenuProvisioningConflictException;
import com.salamu.menu.menu.api.MenuProvisioningConflictException.Code;
import com.salamu.menu.menu.api.MenuProvisioningDisposition;
import com.salamu.menu.menu.api.MenuProvisioningInspection;
import com.salamu.menu.menu.api.MenuProvisioningResult;
import com.salamu.menu.menu.api.MenuProvisioningService;
import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.menu.internal.application.DeterministicMenuIds.ExpectedMenuIds;
import com.salamu.menu.menu.internal.application.MenuProvisioningRepository.StoredMenu;
import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningAsset;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningConflictException;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningService;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantLifecycle;
import com.salamu.menu.tenancy.api.TenantProvisioningException;
import com.salamu.menu.tenancy.api.TenantProvisioningService;
import com.salamu.menu.tenancy.api.TenantProvisioningState;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic create-or-compare menu writer; it never publishes a tenant or performs network I/O. */
@Service
@org.springframework.context.annotation.Profile("legacy-import")
public class MenuProvisioningApplicationService implements MenuProvisioningService {

    private final MenuProvisioningRepository repository;
    private final TenantProvisioningService tenantProvisioningService;
    private final TenantAssetProvisioningService tenantAssetProvisioningService;
    private final Clock clock;

    public MenuProvisioningApplicationService(
            MenuProvisioningRepository repository,
            TenantProvisioningService tenantProvisioningService,
            TenantAssetProvisioningService tenantAssetProvisioningService) {
        this.repository = repository;
        this.tenantProvisioningService = tenantProvisioningService;
        this.tenantAssetProvisioningService = tenantAssetProvisioningService;
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<MenuProvisioningInspection> inspectMenuSnapshot(
            TenantId tenantId,
            UUID restaurantId,
            long version) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(restaurantId, "restaurantId");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        var tenant = requireInspectableTenant(tenantId);
        return repository.find(tenantId, restaurantId, version)
                .map(stored -> inspection(stored, tenant));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MenuProvisioningResult writeMenuSnapshot(
            PublishedMenuSnapshot snapshot,
            ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        var tenant = requireSuspendedTenant(snapshot.tenantId());
        requireTenantCurrency(tenant, snapshot.currency());
        validatePreparedAssets(snapshot);
        var expectedHash = MenuSnapshotHasher.hash(snapshot);
        var expectedIds = DeterministicMenuIds.forSnapshot(snapshot, runFingerprint);
        var existing = repository.findForUpdate(snapshot.tenantId(), snapshot.restaurantId(), snapshot.version());
        if (existing.isPresent()) {
            var inspection = inspection(existing.orElseThrow(), tenant);
            requireSemanticEquality(snapshot, expectedHash, inspection);
            requireRunOwnership(existing.orElseThrow(), expectedIds);
            return new MenuProvisioningResult(
                    inspection.menuVersionId(), expectedHash, MenuProvisioningDisposition.UNCHANGED);
        }
        if (!repository.findPublishedVersions(snapshot.tenantId(), snapshot.restaurantId()).isEmpty()) {
            throw new MenuProvisioningConflictException(Code.PUBLISHED_VERSION_CONFLICT);
        }

        try {
            repository.insert(snapshot, expectedIds, clock.instant());
        } catch (DataIntegrityViolationException exception) {
            throw new MenuProvisioningConflictException(Code.STORAGE_CONFLICT);
        }
        var prepared = repository.findForUpdate(snapshot.tenantId(), snapshot.restaurantId(), snapshot.version())
                .orElseThrow(() -> new MenuProvisioningConflictException(Code.PERSISTED_STATE_INVALID));
        var inspection = inspection(prepared, tenant);
        requireSemanticEquality(snapshot, expectedHash, inspection);
        requireRunOwnership(prepared, expectedIds);
        return new MenuProvisioningResult(
                inspection.menuVersionId(), expectedHash, MenuProvisioningDisposition.CREATED);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MenuProvisioningInspection verifyPreparedMenu(
            PublishedMenuSnapshot expected,
            ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        var tenant = requireSuspendedTenant(expected.tenantId());
        requireTenantCurrency(tenant, expected.currency());
        validatePreparedAssets(expected);
        var stored = repository.findForUpdate(expected.tenantId(), expected.restaurantId(), expected.version())
                .orElseThrow(() -> new MenuProvisioningConflictException(Code.PERSISTED_STATE_INVALID));
        var inspection = inspection(stored, tenant);
        requireSemanticEquality(expected, MenuSnapshotHasher.hash(expected), inspection);
        requireRunOwnership(stored, DeterministicMenuIds.forSnapshot(expected, runFingerprint));
        return inspection;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cleanupMenuSnapshot(
            MenuCleanupCandidate candidate,
            ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        var tenant = requireSuspendedTenant(candidate.tenantId());
        var stored = repository.findForUpdate(candidate.tenantId(), candidate.restaurantId(), candidate.version());
        if (stored.isEmpty()) {
            if (!repository.findPublishedVersions(candidate.tenantId(), candidate.restaurantId()).isEmpty()) {
                throw new MenuProvisioningConflictException(Code.CLEANUP_FORBIDDEN);
            }
            return;
        }

        var locked = stored.orElseThrow();
        var expectedAssets = expectedAssets(locked);
        var actualAssets = validateAndInspectPreparedAssets(candidate.tenantId(), expectedAssets);
        var inspection = inspection(locked, tenant, actualAssets);
        if (!inspection.semanticHash().equals(candidate.expectedSemanticHash())) {
            throw new MenuProvisioningConflictException(Code.SNAPSHOT_CONFLICT);
        }
        var expectedIds = DeterministicMenuIds.forSnapshot(inspection.snapshot(), runFingerprint);
        requireRunOwnership(locked, expectedIds);
        try {
            int deleted = repository.delete(
                    candidate.tenantId(), candidate.restaurantId(), candidate.version(), expectedIds.menuVersionId());
            if (deleted != 1) {
                throw new MenuProvisioningConflictException(Code.CLEANUP_FORBIDDEN);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new MenuProvisioningConflictException(Code.CLEANUP_FORBIDDEN);
        }
    }

    private TenantProvisioningState requireInspectableTenant(TenantId tenantId) {
        try {
            var state = tenantProvisioningService.inspectProvisioningState(tenantId)
                    .orElseThrow(() -> new MenuProvisioningConflictException(Code.TENANT_UNAVAILABLE));
            if (state.lifecycle() != TenantLifecycle.ACTIVE && state.lifecycle() != TenantLifecycle.SUSPENDED) {
                throw new MenuProvisioningConflictException(Code.TENANT_LIFECYCLE_CONFLICT);
            }
            return state;
        } catch (TenantProvisioningException exception) {
            throw new MenuProvisioningConflictException(Code.TENANT_LIFECYCLE_CONFLICT);
        }
    }

    private TenantProvisioningState requireSuspendedTenant(TenantId tenantId) {
        var state = requireInspectableTenant(tenantId);
        if (state.lifecycle() != TenantLifecycle.SUSPENDED) {
            throw new MenuProvisioningConflictException(Code.TENANT_LIFECYCLE_CONFLICT);
        }
        return state;
    }

    private static void requireTenantCurrency(TenantProvisioningState tenant, String currency) {
        if (!tenant.identity().currency().equals(currency)) {
            throw new MenuProvisioningConflictException(Code.SNAPSHOT_CONFLICT);
        }
    }

    private MenuProvisioningInspection inspection(StoredMenu stored, TenantProvisioningState tenant) {
        var actualAssets = inspectPreparedAssets(
                tenant.tenantId(), expectedAssets(stored), tenant.lifecycle());
        return inspection(stored, tenant, actualAssets);
    }

    private static MenuProvisioningInspection inspection(
            StoredMenu stored,
            TenantProvisioningState tenant,
            Map<TenantAssetId, TenantAssetProvisioningAsset> actualAssets) {
        try {
            var header = stored.header();
            if (!header.tenantId().equals(tenant.tenantId())
                    || !"published".equals(header.status())
                    || header.publishedAt() == null) {
                throw new MenuProvisioningConflictException(Code.PERSISTED_STATE_INVALID);
            }
            List<PublishedMenuSnapshot.Category> categories = new ArrayList<>(stored.categories().size());
            for (var category : stored.categories()) {
                categories.add(new PublishedMenuSnapshot.Category(
                        category.categoryId(), category.name(), category.description(),
                        assetReference(category.imageAssetId(), actualAssets),
                        category.sortOrder(), category.available()));
            }
            List<PublishedMenuSnapshot.Item> items = new ArrayList<>(stored.items().size());
            for (var item : stored.items()) {
                items.add(new PublishedMenuSnapshot.Item(
                        item.menuItemId(), item.categoryId(), item.name(), item.description(), item.priceMinor(),
                        item.currency(), assetReference(item.imageAssetId(), actualAssets),
                        item.available(), item.sortOrder()));
            }
            var snapshot = new PublishedMenuSnapshot(
                    header.tenantId(), header.restaurantId(), header.version(), header.publishedAt(),
                    tenant.identity().currency(), categories, items);
            var hash = MenuSnapshotHasher.hash(snapshot);
            return new MenuProvisioningInspection(
                    header.menuVersionId(), snapshot, hash, categories.size(), items.size());
        } catch (MenuProvisioningConflictException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MenuProvisioningConflictException(Code.PERSISTED_STATE_INVALID);
        }
    }

    private static void requireSemanticEquality(
            PublishedMenuSnapshot expected,
            com.salamu.menu.menu.api.MenuSemanticHash expectedHash,
            MenuProvisioningInspection actual) {
        if (!expectedHash.equals(actual.semanticHash()) || !expected.equals(actual.snapshot())) {
            throw new MenuProvisioningConflictException(Code.SNAPSHOT_CONFLICT);
        }
    }

    private static void requireRunOwnership(StoredMenu stored, ExpectedMenuIds expectedIds) {
        if (!stored.header().menuVersionId().equals(expectedIds.menuVersionId())
                || stored.categories().size() != expectedIds.categoryIds().size()
                || stored.items().size() != expectedIds.itemIds().size()) {
            throw new MenuProvisioningConflictException(Code.FOREIGN_PROVENANCE);
        }
        for (var category : stored.categories()) {
            if (!category.internalId().equals(expectedIds.categoryIds().get(category.categoryId()))) {
                throw new MenuProvisioningConflictException(Code.FOREIGN_PROVENANCE);
            }
        }
        for (var item : stored.items()) {
            var expectedCategoryId = expectedIds.categoryIds().get(item.categoryId());
            if (!item.internalId().equals(expectedIds.itemIds().get(item.menuItemId()))
                    || !item.categoryInternalId().equals(expectedCategoryId)) {
                throw new MenuProvisioningConflictException(Code.FOREIGN_PROVENANCE);
            }
        }
    }

    private void validatePreparedAssets(PublishedMenuSnapshot snapshot) {
        var expectedAssets = expectedAssets(snapshot);
        var actualAssets = validateAndInspectPreparedAssets(snapshot.tenantId(), expectedAssets);
        snapshot.categories().forEach(category -> requireExpectedAssetHash(category.imageAsset(), actualAssets));
        snapshot.items().forEach(item -> requireExpectedAssetHash(item.imageAsset(), actualAssets));
    }

    private Map<TenantAssetId, TenantAssetProvisioningAsset> validateAndInspectPreparedAssets(
            TenantId tenantId,
            Map<TenantAssetId, AssetKind> expectedAssets) {
        try {
            tenantAssetProvisioningService.validatePreparedAssetReferences(
                    tenantId, expectedAssets, TenantLifecycle.SUSPENDED);
        } catch (TenantAssetProvisioningConflictException | IllegalArgumentException exception) {
            throw new MenuProvisioningConflictException(Code.STORAGE_CONFLICT);
        }
        return inspectPreparedAssets(tenantId, expectedAssets, TenantLifecycle.SUSPENDED);
    }

    private Map<TenantAssetId, TenantAssetProvisioningAsset> inspectPreparedAssets(
            TenantId tenantId,
            Map<TenantAssetId, AssetKind> expectedAssets,
            TenantLifecycle expectedLifecycle) {
        final Map<TenantAssetId, TenantAssetProvisioningAsset> actualAssets;
        try {
            actualAssets = tenantAssetProvisioningService.inspectPreparedAssetReferences(
                    tenantId, expectedAssets, expectedLifecycle);
        } catch (TenantAssetProvisioningConflictException | IllegalArgumentException exception) {
            throw new MenuProvisioningConflictException(Code.STORAGE_CONFLICT);
        }
        if (actualAssets == null || actualAssets.size() != expectedAssets.size()) {
            throw new MenuProvisioningConflictException(Code.STORAGE_CONFLICT);
        }
        for (var expected : expectedAssets.entrySet()) {
            var actual = actualAssets.get(expected.getKey());
            if (actual == null
                    || !expected.getKey().equals(actual.id())
                    || !tenantId.equals(actual.tenantId())
                    || expected.getValue() != actual.kind()) {
                throw new MenuProvisioningConflictException(Code.STORAGE_CONFLICT);
            }
        }
        return Map.copyOf(actualAssets);
    }

    private static Map<TenantAssetId, AssetKind> expectedAssets(PublishedMenuSnapshot snapshot) {
        Map<TenantAssetId, AssetKind> expectedAssets = new LinkedHashMap<>();
        snapshot.categories().forEach(category -> addExpectedAsset(
                expectedAssets, assetId(category.imageAsset()), AssetKind.MENU_CATEGORY_IMAGE));
        snapshot.items().forEach(item -> addExpectedAsset(
                expectedAssets, assetId(item.imageAsset()), AssetKind.MENU_ITEM_IMAGE));
        return Map.copyOf(expectedAssets);
    }

    private static Map<TenantAssetId, AssetKind> expectedAssets(StoredMenu stored) {
        Map<TenantAssetId, AssetKind> expectedAssets = new LinkedHashMap<>();
        stored.categories().forEach(category -> addExpectedAsset(
                expectedAssets, category.imageAssetId(), AssetKind.MENU_CATEGORY_IMAGE));
        stored.items().forEach(item -> addExpectedAsset(
                expectedAssets, item.imageAssetId(), AssetKind.MENU_ITEM_IMAGE));
        return Map.copyOf(expectedAssets);
    }

    private static void requireExpectedAssetHash(
            MenuAssetReference reference,
            Map<TenantAssetId, TenantAssetProvisioningAsset> actualAssets) {
        if (reference == null) {
            return;
        }
        var actual = actualAssets.get(reference.assetId());
        if (actual == null || !reference.sha256().equals(actual.sha256())) {
            throw new MenuProvisioningConflictException(Code.SNAPSHOT_CONFLICT);
        }
    }

    private static MenuAssetReference assetReference(
            TenantAssetId assetId,
            Map<TenantAssetId, TenantAssetProvisioningAsset> actualAssets) {
        if (assetId == null) {
            return null;
        }
        var actual = actualAssets.get(assetId);
        if (actual == null) {
            throw new MenuProvisioningConflictException(Code.STORAGE_CONFLICT);
        }
        return new MenuAssetReference(assetId, actual.sha256());
    }

    private static TenantAssetId assetId(MenuAssetReference reference) {
        return reference == null ? null : reference.assetId();
    }

    private static void addExpectedAsset(
            Map<TenantAssetId, AssetKind> expectedAssets,
            TenantAssetId assetId,
            AssetKind expectedKind) {
        if (assetId == null) {
            return;
        }
        var previous = expectedAssets.putIfAbsent(assetId, expectedKind);
        if (previous != null && previous != expectedKind) {
            throw new MenuProvisioningConflictException(Code.SNAPSHOT_CONFLICT);
        }
    }
}
