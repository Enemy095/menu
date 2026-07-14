package com.salamu.menu.tenancy.internal.application.provisioning;

import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.SuspendedTenantSpec;
import com.salamu.menu.tenancy.api.TenantActivationCandidate;
import com.salamu.menu.tenancy.api.TenantCleanupCandidate;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot;
import com.salamu.menu.tenancy.api.TenantId;
import com.salamu.menu.tenancy.api.TenantLifecycle;
import com.salamu.menu.tenancy.api.TenantProvisioningException;
import com.salamu.menu.tenancy.api.TenantProvisioningException.Code;
import com.salamu.menu.tenancy.api.TenantProvisioningService;
import com.salamu.menu.tenancy.api.TenantProvisioningState;
import com.salamu.menu.tenancy.api.TenantProvisioningState.ConfigurationPresence;
import com.salamu.menu.tenancy.api.TenantSemanticHash;
import com.salamu.menu.tenancy.internal.application.provisioning.TenantProvisioningRepository.StoredTenant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.context.annotation.Profile("legacy-import")
public class TenantProvisioningApplicationService implements TenantProvisioningService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Comparator<StoredTenant> TENANT_ORDER = Comparator
            .comparing((StoredTenant tenant) -> tenant.identity().slug())
            .thenComparing(tenant -> tenant.tenantId().value());

    private final TenantProvisioningRepository repository;
    private final Clock clock;

    public TenantProvisioningApplicationService(TenantProvisioningRepository repository) {
        this.repository = repository;
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TenantProvisioningState provisionSuspendedSkeleton(
            SuspendedTenantSpec spec,
            ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        var existing = repository.findBySlugForUpdate(spec.slug());
        if (existing.isPresent()) {
            var tenant = existing.orElseThrow();
            requireSuspendedIdentity(tenant, spec, Code.TENANT_STATE_CONFLICT);
            return state(tenant);
        }

        var tenantId = new TenantId(UUID.randomUUID());
        try {
            repository.insertSuspended(tenantId, spec, now());
        } catch (DataIntegrityViolationException exception) {
            throw classifySkeletonInsertFailure(exception);
        }
        var inserted = repository.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new TenantProvisioningException(Code.PERSISTED_STATE_INVALID));
        return state(inserted);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<TenantProvisioningState> inspectProvisioningState(String slug) {
        var canonicalSlug = canonicalSlug(slug);
        return repository.findBySlug(canonicalSlug).map(tenant -> {
            return inspectableState(tenant);
        });
    }

    @Override
    @Transactional(
            readOnly = true,
            propagation = Propagation.REQUIRED,
            isolation = Isolation.REPEATABLE_READ)
    public Optional<TenantProvisioningState> inspectProvisioningState(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return repository.findById(tenantId).map(this::inspectableState);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TenantSemanticHash writeTenantConfiguration(
            TenantConfigurationSnapshot snapshot,
            ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        var tenant = lockedExactSuspended(snapshot.tenantId(), snapshot.identity());
        var stored = repository.readConfiguration(tenant);
        var expectedHash = TenantConfigurationHasher.hash(snapshot);
        if (stored.presence() == ConfigurationPresence.COMPLETE) {
            requireSemanticEquality(stored.snapshot(), expectedHash);
            return expectedHash;
        }
        if (stored.presence() == ConfigurationPresence.PARTIAL) {
            throw new TenantProvisioningException(Code.CONFIGURATION_INCOMPLETE);
        }
        if (!repository.configurationAssetsMatch(snapshot)) {
            throw new TenantProvisioningException(Code.CONFIGURATION_CONFLICT);
        }
        repository.insertConfiguration(snapshot, now());
        var prepared = repository.readConfiguration(tenant);
        if (prepared.presence() != ConfigurationPresence.COMPLETE) {
            throw new TenantProvisioningException(Code.CONFIGURATION_INCOMPLETE);
        }
        requireSemanticEquality(prepared.snapshot(), expectedHash);
        return expectedHash;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TenantSemanticHash verifyPreparedConfiguration(
            TenantConfigurationSnapshot expected,
            ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        var tenant = lockedExactSuspended(expected.tenantId(), expected.identity());
        var stored = repository.readConfiguration(tenant);
        if (stored.presence() != ConfigurationPresence.COMPLETE) {
            throw new TenantProvisioningException(Code.CONFIGURATION_INCOMPLETE);
        }
        var expectedHash = TenantConfigurationHasher.hash(expected);
        requireSemanticEquality(stored.snapshot(), expectedHash);
        if (!repository.configurationAssetsMatch(stored.snapshot())) {
            throw new TenantProvisioningException(Code.CONFIGURATION_CONFLICT);
        }
        return expectedHash;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void activateCompletedBatch(
            List<TenantActivationCandidate> candidates,
            ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        var ordered = activationCandidates(candidates);
        var locked = new ArrayList<StoredTenant>(ordered.size());
        for (var candidate : ordered) {
            var tenant = repository.findByIdForUpdate(candidate.tenantId())
                    .orElseThrow(() -> new TenantProvisioningException(Code.BATCH_CONFLICT));
            if (tenant.lifecycle() != TenantLifecycle.SUSPENDED
                    || !tenant.identity().slug().equals(candidate.slug())) {
                throw new TenantProvisioningException(Code.BATCH_CONFLICT);
            }
            locked.add(tenant);
        }
        locked.sort(TENANT_ORDER);
        for (int index = 0; index < locked.size(); index++) {
            var tenant = locked.get(index);
            var candidate = ordered.get(index);
            if (!tenant.tenantId().equals(candidate.tenantId())) {
                throw new TenantProvisioningException(Code.BATCH_CONFLICT);
            }
            var configuration = repository.readConfiguration(tenant);
            if (configuration.presence() != ConfigurationPresence.COMPLETE
                    || !TenantConfigurationHasher.hash(configuration.snapshot())
                            .equals(candidate.expectedConfigurationHash())
                    || !repository.configurationAssetsMatch(configuration.snapshot())) {
                throw new TenantProvisioningException(Code.BATCH_CONFLICT);
            }
        }
        var timestamp = now();
        for (var tenant : locked) {
            repository.activate(tenant.tenantId(), timestamp);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cleanupTenantConfiguration(
            UUID runId,
            ProvisioningRunFingerprint runFingerprint,
            List<TenantCleanupCandidate> candidates) {
        requireRunIdentity(runId, runFingerprint);
        var ordered = cleanupCandidates(candidates);
        for (var candidate : ordered) {
            var tenant = lockedCleanupTenant(candidate);
            var configuration = repository.readConfiguration(tenant);
            if (candidate.expectedConfigurationHash() == null) {
                if (configuration.presence() != ConfigurationPresence.ABSENT) {
                    throw new TenantProvisioningException(Code.CLEANUP_CONFLICT);
                }
                continue;
            }
            if (configuration.presence() != ConfigurationPresence.COMPLETE
                    || !TenantConfigurationHasher.hash(configuration.snapshot())
                            .equals(candidate.expectedConfigurationHash())) {
                throw new TenantProvisioningException(Code.CLEANUP_CONFLICT);
            }
        }
        ordered.forEach(candidate -> repository.deleteConfiguration(candidate.tenantId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cleanupSuspendedRun(
            UUID runId,
            ProvisioningRunFingerprint runFingerprint,
            List<TenantCleanupCandidate> candidates) {
        requireRunIdentity(runId, runFingerprint);
        var ordered = cleanupCandidates(candidates);
        for (var candidate : ordered) {
            var tenant = lockedCleanupTenant(candidate);
            if (repository.readConfiguration(tenant).presence() != ConfigurationPresence.ABSENT
                    || repository.hasTenancyOwnedReferences(tenant.tenantId())) {
                throw new TenantProvisioningException(Code.CLEANUP_CONFLICT);
            }
        }
        for (var candidate : ordered) {
            try {
                if (!repository.deleteSuspendedTenant(candidate.tenantId())) {
                    throw new TenantProvisioningException(Code.CLEANUP_CONFLICT);
                }
            } catch (DataIntegrityViolationException exception) {
                throw new TenantProvisioningException(Code.CLEANUP_CONFLICT);
            }
        }
    }

    private StoredTenant lockedExactSuspended(TenantId tenantId, SuspendedTenantSpec identity) {
        var tenant = repository.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new TenantProvisioningException(Code.TENANT_STATE_CONFLICT));
        requireSuspendedIdentity(tenant, identity, Code.TENANT_STATE_CONFLICT);
        return tenant;
    }

    private StoredTenant lockedCleanupTenant(TenantCleanupCandidate candidate) {
        var tenant = repository.findByIdForUpdate(candidate.tenantId())
                .orElseThrow(() -> new TenantProvisioningException(Code.CLEANUP_CONFLICT));
        if (tenant.lifecycle() != TenantLifecycle.SUSPENDED
                || !tenant.identity().slug().equals(candidate.slug())) {
            throw new TenantProvisioningException(Code.CLEANUP_CONFLICT);
        }
        return tenant;
    }

    private TenantProvisioningState state(StoredTenant tenant) {
        var configuration = repository.readConfiguration(tenant);
        if (configuration.presence() != ConfigurationPresence.COMPLETE) {
            return new TenantProvisioningState(
                    tenant.tenantId(),
                    tenant.identity(),
                    tenant.lifecycle(),
                    configuration.presence(),
                    null,
                    null);
        }
        var snapshot = configuration.snapshot();
        return new TenantProvisioningState(
                tenant.tenantId(),
                tenant.identity(),
                tenant.lifecycle(),
                ConfigurationPresence.COMPLETE,
                snapshot,
                TenantConfigurationHasher.hash(snapshot));
    }

    private TenantProvisioningState inspectableState(StoredTenant tenant) {
        if (tenant.lifecycle() == TenantLifecycle.DELETED) {
            throw new TenantProvisioningException(Code.TENANT_STATE_CONFLICT);
        }
        return state(tenant);
    }

    private static void requireSuspendedIdentity(StoredTenant tenant, SuspendedTenantSpec expected, Code code) {
        if (tenant.lifecycle() != TenantLifecycle.SUSPENDED || !tenant.identity().equals(expected)) {
            throw new TenantProvisioningException(code);
        }
    }

    private static void requireSemanticEquality(
            TenantConfigurationSnapshot stored,
            TenantSemanticHash expectedHash) {
        if (!TenantConfigurationHasher.hash(stored).equals(expectedHash)) {
            throw new TenantProvisioningException(Code.CONFIGURATION_CONFLICT);
        }
    }

    private static List<TenantActivationCandidate> activationCandidates(
            List<TenantActivationCandidate> candidates) {
        var copy = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (copy.isEmpty() || copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Activation batch must not be empty");
        }
        var tenantIds = new HashSet<TenantId>();
        var slugs = new HashSet<String>();
        for (var candidate : copy) {
            if (!tenantIds.add(candidate.tenantId()) || !slugs.add(candidate.slug())) {
                throw new IllegalArgumentException("Activation batch contains duplicate tenant identity");
            }
        }
        return copy.stream()
                .sorted(Comparator.comparing(TenantActivationCandidate::slug)
                        .thenComparing(candidate -> candidate.tenantId().value()))
                .toList();
    }

    private static List<TenantCleanupCandidate> cleanupCandidates(List<TenantCleanupCandidate> candidates) {
        var copy = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (copy.isEmpty() || copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Cleanup batch must not be empty");
        }
        var tenantIds = new HashSet<TenantId>();
        var slugs = new HashSet<String>();
        for (var candidate : copy) {
            if (!tenantIds.add(candidate.tenantId()) || !slugs.add(candidate.slug())) {
                throw new IllegalArgumentException("Cleanup batch contains duplicate tenant identity");
            }
        }
        return copy.stream()
                .sorted(Comparator.comparing(TenantCleanupCandidate::slug)
                        .thenComparing(candidate -> candidate.tenantId().value()))
                .toList();
    }

    private static void requireRunIdentity(UUID runId, ProvisioningRunFingerprint runFingerprint) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
    }

    private static String canonicalSlug(String slug) {
        Objects.requireNonNull(slug, "slug");
        if (slug.length() > 63 || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug has an invalid canonical value");
        }
        return slug;
    }

    static TenantProvisioningException classifySkeletonInsertFailure(
            DataIntegrityViolationException exception) {
        Objects.requireNonNull(exception, "exception");
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                for (SQLException candidate = sqlException;
                        candidate != null;
                        candidate = candidate.getNextException()) {
                    if ("23505".equals(candidate.getSQLState())) {
                        return new TenantProvisioningException(Code.CONCURRENT_CREATE_RETRY_REQUIRED);
                    }
                }
            }
        }
        return new TenantProvisioningException(Code.PERSISTED_STATE_INVALID);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
