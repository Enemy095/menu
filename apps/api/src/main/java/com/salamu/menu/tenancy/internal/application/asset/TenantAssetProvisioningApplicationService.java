package com.salamu.menu.tenancy.internal.application.asset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.salamu.menu.tenancy.api.AssetKind;
import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.TenantAssetCleanupCandidate;
import com.salamu.menu.tenancy.api.TenantAssetCleanupProof;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantAssetImportRequest;
import com.salamu.menu.tenancy.api.TenantAssetObjectCleanupDisposition;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningConflictException;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningAsset;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningDisposition;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningProvenance;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningResult;
import com.salamu.menu.tenancy.api.TenantAssetProvisioningService;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantLifecycle;

@Service
@Lazy
@org.springframework.context.annotation.Profile("legacy-import")
public class TenantAssetProvisioningApplicationService implements TenantAssetProvisioningService {

    private final AssetContentPolicy contentPolicy;
    private final AssetKeyPolicy keyPolicy;
    private final AssetObjectStorage objectStorage;
    private final AssetProvisioningPersistence persistence;

    public TenantAssetProvisioningApplicationService(
            AssetContentPolicy contentPolicy,
            AssetKeyPolicy keyPolicy,
            AssetObjectStorage objectStorage,
            AssetProvisioningPersistence persistence) {
        this.contentPolicy = contentPolicy;
        this.keyPolicy = keyPolicy;
        this.objectStorage = objectStorage;
        this.persistence = persistence;
    }

    @Override
    public TenantAssetProvisioningResult provision(
            TenantId tenantId,
            TenantAssetImportRequest request,
            ProvisioningRunFingerprint runFingerprint,
            TenantLifecycle expectedLifecycle) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        requireProvisionableLifecycle(expectedLifecycle);

        ValidatedAssetContent validated = contentPolicy.validate(request);
        AssetObjectKey objectKey = keyPolicy.create(tenantId, validated);
        AssetObject object = new AssetObject(
                objectKey,
                validated.contentType(),
                validated.content(),
                validated.sha256());
        AssetProvisioningDescriptor descriptor = descriptor(tenantId, validated.kind(), object);

        Optional<AssetRecord> registeredBeforeUpload =
                persistence.inspect(tenantId, validated.kind(), validated.sha256(), expectedLifecycle);
        ProvisionedAssetObjectDisposition objectDisposition;
        try {
            if (registeredBeforeUpload.isPresent()) {
                AssetRecord existing = registeredBeforeUpload.orElseThrow();
                requireProvisionableRecord(descriptor, existing);
                if (!objectStorage.inspectProvisionedImmutable(
                        descriptor.object(), runFingerprint.value())) {
                    throw conflict("Existing asset registration has no matching provisioned object");
                }
                objectDisposition = ProvisionedAssetObjectDisposition.RECOVERED;
            } else {
                objectDisposition = objectStorage.putProvisionedImmutable(object, runFingerprint.value());
            }
        } catch (AssetObjectProvenanceConflictException exception) {
            throw new TenantAssetProvisioningConflictException(
                    "Immutable asset belongs to another provisioning run", exception);
        }

        AssetRecord registered = registerUploaded(descriptor, expectedLifecycle);
        TenantAssetProvisioningProvenance provenance = provenance(descriptor, runFingerprint);
        return new TenantAssetProvisioningResult(
                toProvisioningAsset(registered),
                objectDisposition == ProvisionedAssetObjectDisposition.CREATED
                        ? TenantAssetProvisioningDisposition.CREATED
                        : TenantAssetProvisioningDisposition.RECOVERED,
                provenance);
    }

    @Override
    public Optional<TenantAssetProvisioningAsset> inspect(
            TenantId tenantId,
            AssetKind kind,
            String sha256,
            ProvisioningRunFingerprint runFingerprint,
            TenantLifecycle expectedLifecycle) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        requireProvisionableLifecycle(expectedLifecycle);
        AssetSha256 assetSha256 = new AssetSha256(sha256);
        Optional<AssetRecord> registered = persistence.inspect(tenantId, kind, assetSha256, expectedLifecycle);
        if (registered.isEmpty()) {
            return Optional.empty();
        }
        AssetRecord record = registered.orElseThrow();
        if (record.status() != AssetStatus.ACTIVE) {
            throw conflict("Provisioned asset registration is incomplete");
        }
        AssetProvisioningDescriptor descriptor = descriptor(record);
        try {
            if (!objectStorage.inspectProvisionedImmutable(descriptor.object(), runFingerprint.value())) {
                throw conflict("Provisioned asset object is missing");
            }
        } catch (AssetObjectProvenanceConflictException exception) {
            throw new TenantAssetProvisioningConflictException(
                    "Immutable asset belongs to another provisioning run", exception);
        }
        return Optional.of(toProvisioningAsset(record));
    }

    @Override
    @Transactional(
            readOnly = true,
            propagation = Propagation.REQUIRED,
            isolation = Isolation.REPEATABLE_READ)
    public Map<TenantAssetId, TenantAssetProvisioningAsset> inspectPreparedAssetReferences(
            TenantId tenantId,
            Map<TenantAssetId, AssetKind> expectedAssets,
            TenantLifecycle expectedLifecycle) {
        Objects.requireNonNull(tenantId, "tenantId");
        requireInspectableLifecycle(expectedLifecycle);
        Map<TenantAssetId, AssetKind> immutableExpected = Map.copyOf(
                Objects.requireNonNull(expectedAssets, "expectedAssets"));
        Map<TenantAssetId, AssetRecord> inspected = persistence.inspectPreparedAssetReferences(
                tenantId,
                immutableExpected,
                expectedLifecycle);
        Map<TenantAssetId, TenantAssetProvisioningAsset> result = new LinkedHashMap<>();
        inspected.forEach((assetId, record) -> result.put(assetId, toProvisioningAsset(record)));
        return Collections.unmodifiableMap(result);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void validatePreparedAssetReferences(
            TenantId tenantId,
            Map<TenantAssetId, AssetKind> expectedAssets,
            TenantLifecycle expectedLifecycle) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (expectedLifecycle != TenantLifecycle.SUSPENDED) {
            throw new IllegalArgumentException("Final asset reference validation requires a suspended tenant");
        }
        Map<TenantAssetId, AssetKind> immutableExpected = Map.copyOf(
                Objects.requireNonNull(expectedAssets, "expectedAssets"));
        persistence.validatePreparedAssetReferences(tenantId, immutableExpected, expectedLifecycle);
    }

    @Override
    public TenantAssetCleanupProof verifyCleanupProvenance(
            TenantId tenantId,
            TenantAssetProvisioningProvenance provenance) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(provenance, "provenance");
        requireSameTenant(tenantId, provenance);
        AssetProvisioningDescriptor descriptor = descriptor(provenance);
        Optional<AssetRecord> registered = persistence.inspect(
                tenantId,
                provenance.kind(),
                descriptor.object().sha256(),
                TenantLifecycle.SUSPENDED);
        registered.ifPresent(record -> requireProvisionableRecord(descriptor, record));
        boolean objectPresent;
        try {
            objectPresent = objectStorage.inspectProvisionedImmutable(
                    descriptor.object(), provenance.runFingerprint().value());
        } catch (AssetObjectProvenanceConflictException exception) {
            throw new TenantAssetProvisioningConflictException(
                    "Immutable asset belongs to another provisioning run", exception);
        }
        if (registered.isPresent() && !objectPresent) {
            throw conflict("Registered asset object is missing during cleanup preflight");
        }
        return new VerifiedTenantAssetCleanupProof(provenance, objectPresent);
    }

    @Override
    public TenantAssetCleanupCandidate removeRegistrationForCleanup(
            TenantId tenantId,
            TenantAssetCleanupProof proof) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(proof, "proof");
        if (!(proof instanceof VerifiedTenantAssetCleanupProof verified)) {
            throw conflict("Asset cleanup proof was not issued by tenancy");
        }
        TenantAssetProvisioningProvenance provenance = verified.provenance();
        requireSameTenant(tenantId, provenance);
        AssetProvisioningDescriptor descriptor = descriptor(provenance);
        boolean removed = persistence.removeUnreferencedForCleanup(
                descriptor,
                TenantLifecycle.SUSPENDED);
        return new VerifiedTenantAssetCleanupCandidate(provenance, removed);
    }

    @Override
    public TenantAssetObjectCleanupDisposition deleteObjectForCleanup(
            TenantId tenantId,
            TenantAssetCleanupCandidate candidate) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(candidate, "candidate");
        if (!(candidate instanceof VerifiedTenantAssetCleanupCandidate verified)) {
            throw conflict("Asset cleanup candidate was not issued by tenancy");
        }
        TenantAssetProvisioningProvenance provenance = verified.provenance();
        requireSameTenant(tenantId, provenance);
        AssetProvisioningDescriptor descriptor = descriptor(provenance);
        persistence.requireObjectDeletionAllowed(descriptor);
        try {
            return objectStorage.deleteProvisionedImmutable(
                            descriptor.object(), provenance.runFingerprint().value())
                    ? TenantAssetObjectCleanupDisposition.DELETED
                    : TenantAssetObjectCleanupDisposition.ALREADY_ABSENT;
        } catch (AssetObjectProvenanceConflictException exception) {
            throw new TenantAssetProvisioningConflictException(
                    "Immutable asset belongs to another provisioning run", exception);
        }
    }

    private AssetRecord registerUploaded(
            AssetProvisioningDescriptor descriptor,
            TenantLifecycle expectedLifecycle) {
        try {
            return persistence.registerUploaded(
                    descriptor.tenantId(),
                    descriptor.kind(),
                    descriptor.object().key(),
                    descriptor.object().contentType(),
                    descriptor.object().sizeBytes(),
                    descriptor.object().sha256(),
                    expectedLifecycle);
        } catch (AssetRegistrationConflictException conflict) {
            try {
                return persistence.registerUploaded(
                        descriptor.tenantId(),
                        descriptor.kind(),
                        descriptor.object().key(),
                        descriptor.object().contentType(),
                        descriptor.object().sizeBytes(),
                        descriptor.object().sha256(),
                        expectedLifecycle);
            } catch (AssetRegistrationConflictException repeatedConflict) {
                throw new TenantAssetProvisioningConflictException(
                        "Concurrent asset registration did not converge", repeatedConflict);
            }
        }
    }

    private static TenantAssetProvisioningAsset toProvisioningAsset(AssetRecord record) {
        return new TenantAssetProvisioningAsset(
                record.id(),
                record.tenantId(),
                record.kind(),
                record.contentType(),
                record.sizeBytes(),
                record.sha256().value());
    }

    private static AssetProvisioningDescriptor descriptor(
            TenantId tenantId,
            AssetKind kind,
            AssetObject object) {
        return new AssetProvisioningDescriptor(tenantId, kind, AssetObjectDescriptor.from(object));
    }

    private static AssetProvisioningDescriptor descriptor(AssetRecord record) {
        return new AssetProvisioningDescriptor(
                record.tenantId(),
                record.kind(),
                new AssetObjectDescriptor(
                        record.objectKey(),
                        record.contentType(),
                        record.sizeBytes(),
                        record.sha256()));
    }

    private static AssetProvisioningDescriptor descriptor(TenantAssetProvisioningProvenance provenance) {
        return new AssetProvisioningDescriptor(
                provenance.tenantId(),
                provenance.kind(),
                new AssetObjectDescriptor(
                        new AssetObjectKey(provenance.canonicalObjectKey()),
                        provenance.contentType(),
                        provenance.sizeBytes(),
                        new AssetSha256(provenance.sha256())));
    }

    private static TenantAssetProvisioningProvenance provenance(
            AssetProvisioningDescriptor descriptor,
            ProvisioningRunFingerprint runFingerprint) {
        return new TenantAssetProvisioningProvenance(
                descriptor.tenantId(),
                descriptor.kind(),
                descriptor.object().contentType(),
                descriptor.object().sizeBytes(),
                descriptor.object().sha256().value(),
                descriptor.object().key().value(),
                runFingerprint);
    }

    private static void requireProvisionableRecord(
            AssetProvisioningDescriptor expected,
            AssetRecord actual) {
        if (!actual.tenantId().equals(expected.tenantId())
                || actual.kind() != expected.kind()
                || !actual.objectKey().equals(expected.object().key())
                || !actual.contentType().equals(expected.object().contentType())
                || actual.sizeBytes() != expected.object().sizeBytes()
                || !actual.sha256().equals(expected.object().sha256())
                || actual.status() == AssetStatus.DELETED) {
            throw conflict("Asset registry metadata conflict");
        }
    }

    private static void requireProvisionableLifecycle(TenantLifecycle expectedLifecycle) {
        Objects.requireNonNull(expectedLifecycle, "expectedLifecycle");
        if (expectedLifecycle == TenantLifecycle.DELETED) {
            throw new IllegalArgumentException("Deleted lifecycle cannot be used for asset provisioning");
        }
    }

    private static void requireInspectableLifecycle(TenantLifecycle expectedLifecycle) {
        Objects.requireNonNull(expectedLifecycle, "expectedLifecycle");
        if (expectedLifecycle != TenantLifecycle.ACTIVE && expectedLifecycle != TenantLifecycle.SUSPENDED) {
            throw new IllegalArgumentException("Prepared asset inspection requires an active or suspended tenant");
        }
    }

    private static void requireSameTenant(
            TenantId tenantId,
            TenantAssetProvisioningProvenance provenance) {
        if (!tenantId.equals(provenance.tenantId())) {
            throw conflict("Asset provenance is outside the requested tenant scope");
        }
    }

    private static TenantAssetProvisioningConflictException conflict(String message) {
        return new TenantAssetProvisioningConflictException(message);
    }
}
