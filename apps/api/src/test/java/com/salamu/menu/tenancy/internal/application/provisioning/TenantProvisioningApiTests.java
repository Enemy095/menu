package com.salamu.menu.tenancy.internal.application.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.SuspendedTenantSpec;
import com.salamu.menu.tenancy.api.TenantAssetId;
import com.salamu.menu.tenancy.api.TenantConfigurationSnapshot;
import com.salamu.menu.tenancy.api.TenantFeatureKey;
import com.salamu.menu.tenancy.api.TenantId;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class TenantProvisioningApiTests {

    @Test
    void acceptsOnlyCanonicalLowercaseRunFingerprint() {
        assertThat(new ProvisioningRunFingerprint("a".repeat(64)).value()).hasSize(64);
        assertThatThrownBy(() -> new ProvisioningRunFingerprint("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProvisioningRunFingerprint("a".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configurationRequiresExactlyOneOfEveryKnownFeature() {
        var tenantId = new TenantId(UUID.randomUUID());
        var identity = new SuspendedTenantSpec("tenant-a", "ru-RU", "RUB", "Europe/Moscow");
        var valid = TenantProvisioningTestFixtures.configuration(
                tenantId,
                identity,
                new TenantAssetId(UUID.randomUUID()));
        var missing = new ArrayList<>(valid.features());
        missing.removeLast();

        assertThatThrownBy(() -> new TenantConfigurationSnapshot(
                tenantId,
                identity,
                valid.branding(),
                valid.profile(),
                missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thirteen");

        var duplicate = new ArrayList<>(valid.features());
        duplicate.set(duplicate.size() - 1, duplicate.getFirst());
        assertThatThrownBy(() -> new TenantConfigurationSnapshot(
                tenantId,
                identity,
                valid.branding(),
                valid.profile(),
                duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThat(valid.features()).extracting(feature -> feature.key())
                .containsExactlyInAnyOrder(TenantFeatureKey.values());
    }

    @Test
    void snapshotCollectionsAreImmutableCopies() {
        var tenantId = new TenantId(UUID.randomUUID());
        var identity = new SuspendedTenantSpec("tenant-a", "ru-RU", "RUB", "Europe/Moscow");
        var source = new ArrayList<>(TenantProvisioningTestFixtures.configuration(
                tenantId,
                identity,
                new TenantAssetId(UUID.randomUUID())).features());
        var snapshot = new TenantConfigurationSnapshot(
                tenantId,
                identity,
                TenantProvisioningTestFixtures.configuration(
                        tenantId, identity, new TenantAssetId(UUID.randomUUID())).branding(),
                TenantProvisioningTestFixtures.configuration(
                        tenantId, identity, new TenantAssetId(UUID.randomUUID())).profile(),
                source);
        source.clear();

        assertThat(snapshot.features()).hasSize(13);
        assertThatThrownBy(() -> snapshot.features().add(snapshot.features().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void semanticHashNormalizesFeatureRowOrder() {
        var tenantId = new TenantId(UUID.randomUUID());
        var identity = new SuspendedTenantSpec("tenant-a", "ru-RU", "RUB", "Europe/Moscow");
        var original = TenantProvisioningTestFixtures.configuration(
                tenantId, identity, new TenantAssetId(UUID.randomUUID()));
        var reversed = new ArrayList<>(original.features());
        Collections.reverse(reversed);
        var reordered = new TenantConfigurationSnapshot(
                tenantId, identity, original.branding(), original.profile(), reversed);

        assertThat(TenantConfigurationHasher.hash(reordered))
                .isEqualTo(TenantConfigurationHasher.hash(original));
    }

    @Test
    void classifiesOnlyPostgreSqlUniqueViolationAsRollbackThenRetry() {
        var uniqueViolation = new DataIntegrityViolationException(
                "must not leak",
                new SQLException("raw SQL detail", "23505"));
        var checkViolation = new DataIntegrityViolationException(
                "must not leak",
                new SQLException("raw SQL detail", "23514"));

        var retryable = TenantProvisioningApplicationService.classifySkeletonInsertFailure(uniqueViolation);
        var persistedFailure = TenantProvisioningApplicationService.classifySkeletonInsertFailure(checkViolation);

        assertThat(retryable.code())
                .isEqualTo(com.salamu.menu.tenancy.api.TenantProvisioningException.Code
                        .CONCURRENT_CREATE_RETRY_REQUIRED);
        assertThat(retryable.code().retryableAfterRollback()).isTrue();
        assertThat(persistedFailure.code())
                .isEqualTo(com.salamu.menu.tenancy.api.TenantProvisioningException.Code.PERSISTED_STATE_INVALID);
        assertThat(persistedFailure.code().retryableAfterRollback()).isFalse();
        assertThat(retryable.getMessage()).doesNotContain("SQL", "raw", "23505");
        assertThat(persistedFailure.getMessage()).doesNotContain("SQL", "raw", "23514");
    }

    @Test
    void tenantIdInspectionUsesRequiredReadOnlyRepeatableReadTransaction() throws NoSuchMethodException {
        var method = TenantProvisioningApplicationService.class.getMethod(
                "inspectProvisioningState",
                TenantId.class);
        var transaction = method.getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);

        var acquisition = TenantProvisioningApplicationService.class.getMethod(
                "provisionSuspendedSkeleton",
                SuspendedTenantSpec.class,
                ProvisioningRunFingerprint.class).getAnnotation(Transactional.class);
        assertThat(acquisition).isNotNull();
        assertThat(acquisition.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
