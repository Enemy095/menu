package com.salamu.menu.notification.internal.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.salamu.menu.notification.api.NotificationDestinationCleanupCandidate;
import com.salamu.menu.notification.api.NotificationDestinationSemanticHash;
import com.salamu.menu.notification.api.NotificationDestinationSnapshot;
import com.salamu.menu.notification.api.NotificationDestinationSpec;
import com.salamu.menu.notification.api.NotificationProvisioningException;
import com.salamu.menu.notification.api.NotificationProvisioningException.Code;
import com.salamu.menu.notification.api.NotificationProvisioningService;
import com.salamu.menu.notification.api.SecretReferenceReadiness;
import com.salamu.menu.notification.internal.application.NotificationProvisioningRepository.StoredDestination;
import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantLifecycle;
import com.salamu.menu.tenancy.api.TenantProvisioningService;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.context.annotation.Profile("legacy-import")
public class NotificationProvisioningApplicationService implements NotificationProvisioningService {

    private final NotificationProvisioningRepository repository;
    private final TenantProvisioningService tenantProvisioning;
    private final Clock clock;

    public NotificationProvisioningApplicationService(
            NotificationProvisioningRepository repository,
            TenantProvisioningService tenantProvisioning) {
        this.repository = repository;
        this.tenantProvisioning = tenantProvisioning;
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<NotificationDestinationSnapshot> inspectDestinations(TenantId tenantId) {
        requireInspectableTenant(tenantId);
        return repository.findAll(tenantId).stream().map(this::snapshot).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public NotificationDestinationSnapshot writeDestination(
            NotificationDestinationSpec spec,
            ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        requireTenantIdentity(spec.tenantId(), spec.tenantSlug(), false);

        var existing = repository.findByNaturalKeyForUpdate(
                spec.tenantId(), spec.channel(), spec.displayName());
        if (existing.isPresent()) {
            return requireExpected(existing.orElseThrow(), spec);
        }
        try {
            repository.insert(deterministicId(spec), spec, now());
        } catch (DataIntegrityViolationException exception) {
            throw new NotificationProvisioningException(Code.DESTINATION_CONFLICT);
        }
        var inserted = repository.findByNaturalKeyForUpdate(
                        spec.tenantId(), spec.channel(), spec.displayName())
                .orElseThrow(() -> new NotificationProvisioningException(Code.PERSISTED_STATE_INVALID));
        return requireExpected(inserted, spec);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public NotificationDestinationSemanticHash verifyPreparedDestination(
            NotificationDestinationSpec expected,
            ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        requireTenantIdentity(expected.tenantId(), expected.tenantSlug(), false);
        var stored = repository.findByNaturalKeyForUpdate(
                        expected.tenantId(), expected.channel(), expected.displayName())
                .orElseThrow(() -> new NotificationProvisioningException(Code.DESTINATION_CONFLICT));
        return requireExpected(stored, expected).semanticHash();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cleanupDestination(
            UUID runId,
            ProvisioningRunFingerprint runFingerprint,
            NotificationDestinationCleanupCandidate candidate) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        Objects.requireNonNull(candidate, "candidate");
        requireTenantIdentity(candidate.tenantId(), candidate.tenantSlug(), true);

        var stored = repository.findByNaturalKeyForUpdate(
                candidate.tenantId(), candidate.channel(), candidate.displayName());
        var byId = repository.findByIdForUpdate(candidate.tenantId(), candidate.destinationId());
        if (stored.isEmpty() && byId.isEmpty()) {
            return;
        }
        if (stored.isEmpty()
                || byId.isEmpty()
                || !stored.orElseThrow().destinationId().equals(byId.orElseThrow().destinationId())) {
            throw new NotificationProvisioningException(Code.CLEANUP_CONFLICT);
        }
        var destination = stored.orElseThrow();
        if (!destination.destinationId().equals(candidate.destinationId())
                || !NotificationDestinationHasher.hash(destination).equals(candidate.expectedSemanticHash())
                || repository.hasDeliveries(candidate.tenantId(), candidate.destinationId())) {
            throw new NotificationProvisioningException(Code.CLEANUP_CONFLICT);
        }
        try {
            if (!repository.deleteExact(
                    candidate.tenantId(),
                    candidate.destinationId(),
                    candidate.channel(),
                    candidate.displayName())) {
                throw new NotificationProvisioningException(Code.CLEANUP_CONFLICT);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new NotificationProvisioningException(Code.CLEANUP_CONFLICT);
        }
    }

    private NotificationDestinationSnapshot requireExpected(
            StoredDestination stored,
            NotificationDestinationSpec expected) {
        var expectedHash = NotificationDestinationHasher.hash(expected);
        var actualHash = NotificationDestinationHasher.hash(stored);
        if (!actualHash.equals(expectedHash)) {
            throw new NotificationProvisioningException(Code.DESTINATION_CONFLICT);
        }
        return snapshot(stored);
    }

    private NotificationDestinationSnapshot snapshot(StoredDestination stored) {
        try {
            return new NotificationDestinationSnapshot(
                    stored.destinationId(),
                    stored.tenantId(),
                    stored.channel(),
                    stored.displayName(),
                    stored.status(),
                    stored.secretReference().fingerprint(),
                    SecretReferenceReadiness.READY,
                    NotificationDestinationHasher.hash(stored));
        } catch (IllegalArgumentException exception) {
            throw new NotificationProvisioningException(Code.PERSISTED_STATE_INVALID);
        }
    }

    private void requireInspectableTenant(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        var tenant = tenantProvisioning.inspectProvisioningState(tenantId)
                .orElseThrow(() -> new NotificationProvisioningException(Code.TENANT_STATE_CONFLICT));
        if (tenant.lifecycle() != TenantLifecycle.ACTIVE && tenant.lifecycle() != TenantLifecycle.SUSPENDED) {
            throw new NotificationProvisioningException(Code.TENANT_STATE_CONFLICT);
        }
    }

    private void requireTenantIdentity(TenantId tenantId, String tenantSlug, boolean requireSuspended) {
        var tenant = tenantProvisioning.inspectProvisioningState(tenantId)
                .orElseThrow(() -> new NotificationProvisioningException(Code.TENANT_STATE_CONFLICT));
        if (!tenant.identity().slug().equals(tenantSlug)
                || (requireSuspended && tenant.lifecycle() != TenantLifecycle.SUSPENDED)
                || (!requireSuspended
                        && tenant.lifecycle() != TenantLifecycle.ACTIVE
                        && tenant.lifecycle() != TenantLifecycle.SUSPENDED)) {
            throw new NotificationProvisioningException(Code.TENANT_STATE_CONFLICT);
        }
    }

    private static UUID deterministicId(NotificationDestinationSpec spec) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("notification-destination:v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(spec.tenantId().value().toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(spec.channel().databaseValue().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(spec.displayName().getBytes(StandardCharsets.UTF_8));
            var bytes = digest.digest();
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x80);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            return new UUID(ByteBuffer.wrap(bytes, 0, 8).getLong(), ByteBuffer.wrap(bytes, 8, 8).getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
