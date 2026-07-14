package com.salamu.menu.migration.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LegacyImportLedgerValueTests {

    @Test
    void derivesStableSignedAdvisoryKeyFromFirstEightFingerprintBytes() {
        var zero = new LegacyImportRunFingerprint("0".repeat(64));
        var allBitsSet = new LegacyImportRunFingerprint("f".repeat(64));
        var known = new LegacyImportRunFingerprint("0123456789abcdef" + "0".repeat(48));

        assertThat(LegacyImportAdvisoryKey.from(zero).value()).isZero();
        assertThat(LegacyImportAdvisoryKey.from(allBitsSet).value()).isEqualTo(-1L);
        assertThat(LegacyImportAdvisoryKey.from(known).value()).isEqualTo(0x0123456789abcdefL);
        assertThat(LegacyImportAdvisoryKey.from(known)).isEqualTo(LegacyImportAdvisoryKey.from(known));
    }

    @Test
    void rejectsMalformedOrNonLowercaseFingerprints() {
        assertThatThrownBy(() -> new LegacyImportRunFingerprint("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegacyImportRunFingerprint("a".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LegacyImportRunFingerprint("g".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSkippedAndReversedRunTransitions() {
        var initial = LegacyImportRunState.initial();
        var skeletons = new LegacyImportRunState(
                LegacyImportRunStatus.STAGING,
                LegacyImportRunStage.SKELETONS_SUSPENDED);
        var published = new LegacyImportRunState(
                LegacyImportRunStatus.APPLIED,
                LegacyImportRunStage.DB_PUBLISHED);

        initial.requireValidTransitionTo(skeletons);
        assertThatThrownBy(() -> initial.requireValidTransitionTo(published))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> skeletons.requireValidTransitionTo(initial))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permitsOrderedEarlyCleanupTransitionsForTenantAndPlannedAsset() {
        var tenantCleanup = new LegacyImportTenantState(
                LegacyImportTenantStatus.SKELETON,
                LegacyImportRunStage.CLEANUP_OBJECTS_PENDING);
        var tenantCleaned = new LegacyImportTenantState(
                LegacyImportTenantStatus.CLEANED,
                LegacyImportRunStage.COMPLETED);
        LegacyImportTenantState.initial().requireValidTransitionTo(tenantCleanup);
        tenantCleanup.requireValidTransitionTo(tenantCleaned);

        var assetCleanup = new LegacyImportAssetState(
                LegacyImportAssetStatus.PLANNED,
                LegacyImportRunStage.CLEANUP_OBJECTS_PENDING);
        var assetCleaned = new LegacyImportAssetState(
                LegacyImportAssetStatus.CLEANED,
                LegacyImportRunStage.COMPLETED);
        LegacyImportAssetState.initial().requireValidTransitionTo(assetCleanup);
        assetCleanup.requireValidTransitionTo(assetCleaned);
    }

    @Test
    void redactsCanonicalObjectKeyFromDiagnosticRepresentations() {
        var hash = new LegacyImportSha256("a".repeat(64));
        var plan = new LegacyImportAssetPlan(
                "tenant-a",
                java.util.UUID.randomUUID(),
                "logo",
                hash,
                hash,
                42,
                "tenants/private/key.png");

        assertThat(plan.toString())
                .contains("canonicalObjectKey=<redacted>")
                .doesNotContain("tenants/private/key.png");
    }
}
