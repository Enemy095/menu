package com.salamu.menu.restaurant.internal.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.restaurant.api.DefaultRestaurantCleanupCandidate;
import com.salamu.menu.restaurant.api.DefaultRestaurantSnapshot;
import com.salamu.menu.restaurant.api.DefaultRestaurantSpec;
import com.salamu.menu.restaurant.api.RestaurantProvisioningException;
import com.salamu.menu.restaurant.api.RestaurantProvisioningException.Code;
import com.salamu.menu.restaurant.api.RestaurantProvisioningService;
import com.salamu.menu.restaurant.api.RestaurantSemanticHash;
import com.salamu.menu.restaurant.internal.application.RestaurantProvisioningRepository.StoredRestaurant;
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
public class RestaurantProvisioningApplicationService implements RestaurantProvisioningService {

    private final RestaurantProvisioningRepository repository;
    private final TenantProvisioningService tenantProvisioning;
    private final Clock clock;

    public RestaurantProvisioningApplicationService(
            RestaurantProvisioningRepository repository,
            TenantProvisioningService tenantProvisioning) {
        this.repository = repository;
        this.tenantProvisioning = tenantProvisioning;
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<DefaultRestaurantSnapshot> inspectDefaultRestaurant(TenantId tenantId) {
        requireInspectableTenant(tenantId);
        return repository.findDefault(tenantId).map(this::snapshot);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DefaultRestaurantSnapshot writeDefaultRestaurant(
            DefaultRestaurantSpec spec,
            ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        requireTenantIdentity(spec.tenantId(), spec.tenantSlug(), false);

        var byNaturalKey = repository.findByNaturalKeyForUpdate(spec.tenantId(), spec.restaurantSlug());
        var currentDefault = repository.findDefaultForUpdate(spec.tenantId());
        if (byNaturalKey.isPresent() || currentDefault.isPresent()) {
            if (byNaturalKey.isEmpty()
                    || currentDefault.isEmpty()
                    || !byNaturalKey.orElseThrow().restaurantId().equals(currentDefault.orElseThrow().restaurantId())) {
                throw new RestaurantProvisioningException(Code.RESTAURANT_CONFLICT);
            }
            return requireExpected(currentDefault.orElseThrow(), spec);
        }

        var restaurantId = deterministicId(spec);
        try {
            repository.insertDefault(restaurantId, spec, now());
        } catch (DataIntegrityViolationException exception) {
            throw new RestaurantProvisioningException(Code.RESTAURANT_CONFLICT);
        }
        var inserted = repository.findDefaultForUpdate(spec.tenantId())
                .orElseThrow(() -> new RestaurantProvisioningException(Code.PERSISTED_STATE_INVALID));
        return requireExpected(inserted, spec);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RestaurantSemanticHash verifyPreparedDefaultRestaurant(
            DefaultRestaurantSpec expected,
            ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        requireTenantIdentity(expected.tenantId(), expected.tenantSlug(), false);
        var restaurant = repository.findDefaultForUpdate(expected.tenantId())
                .orElseThrow(() -> new RestaurantProvisioningException(Code.RESTAURANT_CONFLICT));
        return requireExpected(restaurant, expected).semanticHash();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cleanupDefaultRestaurant(
            UUID runId,
            ProvisioningRunFingerprint runFingerprint,
            DefaultRestaurantCleanupCandidate candidate) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        Objects.requireNonNull(candidate, "candidate");
        requireTenantIdentity(candidate.tenantId(), candidate.tenantSlug(), true);

        var currentDefault = repository.findDefaultForUpdate(candidate.tenantId());
        var byNaturalKey = repository.findByNaturalKeyForUpdate(candidate.tenantId(), candidate.restaurantSlug());
        var byId = repository.findByIdForUpdate(candidate.tenantId(), candidate.restaurantId());
        if (currentDefault.isEmpty() && byNaturalKey.isEmpty() && byId.isEmpty()) {
            return;
        }
        if (currentDefault.isEmpty()
                || byNaturalKey.isEmpty()
                || byId.isEmpty()
                || !currentDefault.orElseThrow().restaurantId().equals(candidate.restaurantId())
                || !byNaturalKey.orElseThrow().restaurantId().equals(candidate.restaurantId())
                || !byId.orElseThrow().restaurantId().equals(candidate.restaurantId())
                || !RestaurantProvisioningHasher.hash(currentDefault.orElseThrow())
                        .equals(candidate.expectedSemanticHash())) {
            throw new RestaurantProvisioningException(Code.CLEANUP_CONFLICT);
        }
        try {
            if (!repository.deleteExactDefault(
                    candidate.tenantId(), candidate.restaurantId(), candidate.restaurantSlug())) {
                throw new RestaurantProvisioningException(Code.CLEANUP_CONFLICT);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new RestaurantProvisioningException(Code.CLEANUP_CONFLICT);
        }
    }

    private DefaultRestaurantSnapshot requireExpected(StoredRestaurant stored, DefaultRestaurantSpec expected) {
        var expectedHash = RestaurantProvisioningHasher.hash(expected);
        var actualHash = RestaurantProvisioningHasher.hash(stored);
        if (!actualHash.equals(expectedHash)) {
            throw new RestaurantProvisioningException(Code.RESTAURANT_CONFLICT);
        }
        return snapshot(stored);
    }

    private DefaultRestaurantSnapshot snapshot(StoredRestaurant stored) {
        try {
            return new DefaultRestaurantSnapshot(
                    stored.restaurantId(),
                    stored.tenantId(),
                    stored.restaurantSlug(),
                    stored.displayName(),
                    stored.status(),
                    stored.defaultRestaurant(),
                    RestaurantProvisioningHasher.hash(stored));
        } catch (IllegalArgumentException exception) {
            throw new RestaurantProvisioningException(Code.PERSISTED_STATE_INVALID);
        }
    }

    private void requireInspectableTenant(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        var tenant = tenantProvisioning.inspectProvisioningState(tenantId)
                .orElseThrow(() -> new RestaurantProvisioningException(Code.TENANT_STATE_CONFLICT));
        if (tenant.lifecycle() != TenantLifecycle.ACTIVE && tenant.lifecycle() != TenantLifecycle.SUSPENDED) {
            throw new RestaurantProvisioningException(Code.TENANT_STATE_CONFLICT);
        }
    }

    private void requireTenantIdentity(TenantId tenantId, String tenantSlug, boolean requireSuspended) {
        var tenant = tenantProvisioning.inspectProvisioningState(tenantId)
                .orElseThrow(() -> new RestaurantProvisioningException(Code.TENANT_STATE_CONFLICT));
        if (!tenant.identity().slug().equals(tenantSlug)
                || (requireSuspended && tenant.lifecycle() != TenantLifecycle.SUSPENDED)
                || (!requireSuspended
                        && tenant.lifecycle() != TenantLifecycle.ACTIVE
                        && tenant.lifecycle() != TenantLifecycle.SUSPENDED)) {
            throw new RestaurantProvisioningException(Code.TENANT_STATE_CONFLICT);
        }
    }

    private static UUID deterministicId(DefaultRestaurantSpec spec) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("default-restaurant:v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(spec.tenantId().value().toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(spec.restaurantSlug().getBytes(StandardCharsets.UTF_8));
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
