package com.salamu.menu.migration.internal.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.salamu.menu.migration.internal.application.LegacyImportAdvisoryKey;
import com.salamu.menu.migration.internal.application.LegacyImportAssetCleanupCandidate;
import com.salamu.menu.migration.internal.application.LegacyImportAssetPlan;
import com.salamu.menu.migration.internal.application.LegacyImportAssetProvenance;
import com.salamu.menu.migration.internal.application.LegacyImportAssetState;
import com.salamu.menu.migration.internal.application.LegacyImportAssetStatus;
import com.salamu.menu.migration.internal.application.LegacyImportConflictScope;
import com.salamu.menu.migration.internal.application.LegacyImportLedger;
import com.salamu.menu.migration.internal.application.LegacyImportRun;
import com.salamu.menu.migration.internal.application.LegacyImportRunDefinition;
import com.salamu.menu.migration.internal.application.LegacyImportRunFingerprint;
import com.salamu.menu.migration.internal.application.LegacyImportRunStage;
import com.salamu.menu.migration.internal.application.LegacyImportRunState;
import com.salamu.menu.migration.internal.application.LegacyImportRunStatus;
import com.salamu.menu.migration.internal.application.LegacyImportSha256;
import com.salamu.menu.migration.internal.application.LegacyImportTenantPlan;
import com.salamu.menu.migration.internal.application.LegacyImportTenantProvenance;
import com.salamu.menu.migration.internal.application.LegacyImportTenantState;
import com.salamu.menu.migration.internal.application.LegacyImportTenantStatus;
import com.salamu.menu.migration.internal.application.LegacyImportUniqueConflictException;

@Component
@org.springframework.context.annotation.Profile("legacy-import")
class JdbcLegacyImportLedger implements LegacyImportLedger {

    private static final String RUN_COLUMNS = """
            run_id, importer_key, manifest_id, schema_version, environment,
            run_fingerprint, manifest_sha256, fixture_sha256,
            complete_source_set_sha256, semantic_plan_sha256,
            stage, status, failure_code, created_at, updated_at, started_at,
            applied_at, completed_at
            """;

    private static final String TENANT_COLUMNS = """
            run_id, tenant_slug, tenant_id_snapshot, tenant_id, run_fingerprint,
            menu_version, tenant_source_set_sha256, asset_set_sha256,
            expected_config_sha256, expected_menu_sha256, expected_destination_sha256,
            semantic_fingerprint, stage, status, created_at, updated_at
            """;

    private static final String ASSET_COLUMNS = """
            run_id, tenant_slug, tenant_id_snapshot, tenant_id, run_fingerprint,
            asset_id_snapshot, asset_id, asset_kind, source_sha256, content_sha256,
            size_bytes, stage, status, reused, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    JdbcLegacyImportLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LegacyImportAdvisoryKey advisoryKey(LegacyImportRunFingerprint fingerprint) {
        return LegacyImportAdvisoryKey.from(fingerprint);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquireTransactionLock(LegacyImportRunFingerprint fingerprint) {
        var key = advisoryKey(fingerprint);
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(?)",
                (RowCallbackHandler) resultSet -> {},
                key.value());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LegacyImportRun createRun(LegacyImportRunDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        var runId = UUID.randomUUID();
        try {
            return jdbcTemplate.queryForObject(
                    """
                    INSERT INTO legacy_import_runs (
                        run_id, importer_key, manifest_id, schema_version, environment,
                        run_fingerprint, manifest_sha256, fixture_sha256,
                        complete_source_set_sha256, semantic_plan_sha256,
                        stage, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'planned', 'staging')
                    RETURNING %s
                    """.formatted(RUN_COLUMNS),
                    RUN_MAPPER,
                    runId,
                    definition.importerKey(),
                    definition.manifestId(),
                    definition.schemaVersion(),
                    definition.environment(),
                    definition.runFingerprint().value(),
                    definition.manifestSha256().value(),
                    definition.fixtureSha256().value(),
                    definition.completeSourceSetSha256().value(),
                    definition.semanticPlanSha256().value());
        } catch (DataIntegrityViolationException exception) {
            throw translateUniqueConflict(LegacyImportConflictScope.RUN, exception);
        }
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<LegacyImportRun> inspectRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return first(jdbcTemplate.query(
                "SELECT %s FROM legacy_import_runs WHERE run_id = ?".formatted(RUN_COLUMNS),
                RUN_MAPPER,
                runId));
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<LegacyImportRun> inspectRun(LegacyImportRunFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        return first(jdbcTemplate.query(
                "SELECT %s FROM legacy_import_runs WHERE run_fingerprint = ?".formatted(RUN_COLUMNS),
                RUN_MAPPER,
                fingerprint.value()));
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<LegacyImportRun> inspectOpenRun(String importerKey) {
        requireImporterKey(importerKey);
        return first(jdbcTemplate.query(
                """
                SELECT %s
                FROM legacy_import_runs
                WHERE importer_key = ? AND stage NOT IN ('completed', 'conflict', 'failed')
                ORDER BY created_at, run_id
                LIMIT 1
                """.formatted(RUN_COLUMNS),
                RUN_MAPPER,
                importerKey));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LegacyImportRun> lockRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return first(jdbcTemplate.query(
                "SELECT %s FROM legacy_import_runs WHERE run_id = ? FOR UPDATE".formatted(RUN_COLUMNS),
                RUN_MAPPER,
                runId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean compareAndSetRun(
            UUID runId,
            LegacyImportRunState expected,
            LegacyImportRunState next,
            String redactedFailureCode) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        expected.requireValidTransitionTo(next);
        validateFailureCode(expected, next, redactedFailureCode);

        return jdbcTemplate.update(
                        """
                        UPDATE legacy_import_runs
                        SET stage = ?,
                            status = ?,
                            failure_code = CASE
                                WHEN ? = 'failed' THEN COALESCE(?, failure_code)
                                ELSE NULL
                            END,
                            applied_at = CASE
                                WHEN ? = 'applied' THEN COALESCE(applied_at, clock_timestamp())
                                ELSE applied_at
                            END,
                            completed_at = CASE
                                WHEN ? = 'failed' OR ? = 'cleaned' OR ? = 'completed'
                                    THEN COALESCE(completed_at, clock_timestamp())
                                ELSE NULL
                            END,
                            updated_at = clock_timestamp()
                        WHERE run_id = ? AND stage = ? AND status = ?
                        """,
                        next.stage().databaseValue(),
                        next.status().databaseValue(),
                        next.status().databaseValue(),
                        redactedFailureCode,
                        next.status().databaseValue(),
                        next.status().databaseValue(),
                        next.status().databaseValue(),
                        next.stage().databaseValue(),
                        runId,
                        expected.stage().databaseValue(),
                        expected.status().databaseValue())
                == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LegacyImportTenantProvenance registerTenantPlan(
            UUID runId,
            LegacyImportRunFingerprint runFingerprint,
            LegacyImportTenantPlan plan) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        Objects.requireNonNull(plan, "plan");
        try {
            return jdbcTemplate.queryForObject(
                    """
                    INSERT INTO legacy_import_run_tenants (
                        run_id, tenant_slug, run_fingerprint, menu_version,
                        tenant_source_set_sha256, asset_set_sha256,
                        expected_config_sha256, expected_menu_sha256,
                        expected_destination_sha256, semantic_fingerprint,
                        stage, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'planned', 'skeleton')
                    RETURNING %s
                    """.formatted(TENANT_COLUMNS),
                    TENANT_MAPPER,
                    runId,
                    plan.tenantSlug(),
                    runFingerprint.value(),
                    plan.menuVersion(),
                    plan.tenantSourceSetSha256().value(),
                    plan.assetSetSha256().value(),
                    plan.expectedConfigSha256().value(),
                    plan.expectedMenuSha256().value(),
                    plan.expectedDestinationSha256().value(),
                    plan.semanticFingerprint().value());
        } catch (DataIntegrityViolationException exception) {
            throw translateUniqueConflict(LegacyImportConflictScope.TENANT, exception);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean attachTenantAndCompareAndSet(
            UUID runId,
            String tenantSlug,
            LegacyImportTenantState expected,
            LegacyImportTenantState next,
            UUID tenantId) {
        requireTenantMutation(runId, tenantSlug, expected, next);
        Objects.requireNonNull(tenantId, "tenantId");
        return jdbcTemplate.update(
                        """
                        UPDATE legacy_import_run_tenants
                        SET tenant_id_snapshot = ?, tenant_id = ?, stage = ?, status = ?,
                            updated_at = clock_timestamp()
                        WHERE run_id = ? AND tenant_slug = ?
                          AND tenant_id_snapshot IS NULL AND tenant_id IS NULL
                          AND stage = ? AND status = ?
                        """,
                        tenantId,
                        tenantId,
                        next.stage().databaseValue(),
                        next.status().databaseValue(),
                        runId,
                        tenantSlug,
                        expected.stage().databaseValue(),
                        expected.status().databaseValue())
                == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean compareAndSetTenant(
            UUID runId,
            String tenantSlug,
            LegacyImportTenantState expected,
            LegacyImportTenantState next) {
        requireTenantMutation(runId, tenantSlug, expected, next);
        var clearsLiveReference = next.stage() == LegacyImportRunStage.CLEANUP_OBJECTS_PENDING
                || next.status() == LegacyImportTenantStatus.CLEANED;
        return jdbcTemplate.update(
                        """
                        UPDATE legacy_import_run_tenants
                        SET tenant_id = CASE WHEN ? THEN NULL ELSE tenant_id END,
                            stage = ?, status = ?, updated_at = clock_timestamp()
                        WHERE run_id = ? AND tenant_slug = ? AND stage = ? AND status = ?
                        """,
                        clearsLiveReference,
                        next.stage().databaseValue(),
                        next.status().databaseValue(),
                        runId,
                        tenantSlug,
                        expected.stage().databaseValue(),
                        expected.status().databaseValue())
                == 1;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<LegacyImportTenantProvenance> inspectTenants(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return List.copyOf(jdbcTemplate.query(
                """
                SELECT %s FROM legacy_import_run_tenants
                WHERE run_id = ? ORDER BY tenant_slug, tenant_id_snapshot NULLS FIRST
                """.formatted(TENANT_COLUMNS),
                TENANT_MAPPER,
                runId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LegacyImportTenantProvenance> lockTenants(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return List.copyOf(jdbcTemplate.query(
                """
                SELECT %s FROM legacy_import_run_tenants
                WHERE run_id = ?
                ORDER BY tenant_slug, tenant_id_snapshot NULLS FIRST
                FOR UPDATE
                """.formatted(TENANT_COLUMNS),
                TENANT_MAPPER,
                runId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LegacyImportAssetProvenance registerAssetPlan(
            UUID runId,
            LegacyImportRunFingerprint runFingerprint,
            LegacyImportAssetPlan plan) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(runFingerprint, "runFingerprint");
        Objects.requireNonNull(plan, "plan");
        try {
            return jdbcTemplate.queryForObject(
                    """
                    INSERT INTO legacy_import_run_assets (
                        run_id, tenant_slug, tenant_id_snapshot, tenant_id, run_fingerprint,
                        asset_kind, source_sha256, content_sha256, size_bytes,
                        canonical_object_key, stage, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'assets_pending', 'planned')
                    RETURNING %s
                    """.formatted(ASSET_COLUMNS),
                    ASSET_MAPPER,
                    runId,
                    plan.tenantSlug(),
                    plan.tenantId(),
                    plan.tenantId(),
                    runFingerprint.value(),
                    plan.assetKind(),
                    plan.sourceSha256().value(),
                    plan.contentSha256().value(),
                    plan.sizeBytes(),
                    plan.canonicalObjectKey());
        } catch (DataIntegrityViolationException exception) {
            throw translateUniqueConflict(LegacyImportConflictScope.ASSET, exception);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean attachAssetAndCompareAndSet(
            UUID runId,
            String tenantSlug,
            String assetKind,
            LegacyImportSha256 contentSha256,
            LegacyImportAssetState expected,
            LegacyImportAssetState next,
            UUID assetId) {
        requireAssetMutation(runId, tenantSlug, assetKind, contentSha256, expected, next);
        Objects.requireNonNull(assetId, "assetId");
        if (next.status() != LegacyImportAssetStatus.CREATED
                && next.status() != LegacyImportAssetStatus.REUSED) {
            throw new IllegalArgumentException("Registered asset must become created or reused");
        }
        return jdbcTemplate.update(
                        """
                        UPDATE legacy_import_run_assets
                        SET asset_id_snapshot = ?, asset_id = ?, reused = ?, stage = ?, status = ?,
                            updated_at = clock_timestamp()
                        WHERE run_id = ? AND tenant_slug = ? AND asset_kind = ? AND content_sha256 = ?
                          AND asset_id_snapshot IS NULL AND asset_id IS NULL
                          AND stage = ? AND status = ?
                        """,
                        assetId,
                        assetId,
                        next.status() == LegacyImportAssetStatus.REUSED,
                        next.stage().databaseValue(),
                        next.status().databaseValue(),
                        runId,
                        tenantSlug,
                        assetKind,
                        contentSha256.value(),
                        expected.stage().databaseValue(),
                        expected.status().databaseValue())
                == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean compareAndSetAsset(
            UUID runId,
            String tenantSlug,
            String assetKind,
            LegacyImportSha256 contentSha256,
            LegacyImportAssetState expected,
            LegacyImportAssetState next) {
        requireAssetMutation(runId, tenantSlug, assetKind, contentSha256, expected, next);
        var clearsLiveReference = next.stage() == LegacyImportRunStage.CLEANUP_OBJECTS_PENDING
                || next.status() == LegacyImportAssetStatus.CLEANED;
        return jdbcTemplate.update(
                        """
                        UPDATE legacy_import_run_assets
                        SET tenant_id = CASE WHEN ? THEN NULL ELSE tenant_id END,
                            asset_id = CASE WHEN ? THEN NULL ELSE asset_id END,
                            stage = ?, status = ?, updated_at = clock_timestamp()
                        WHERE run_id = ? AND tenant_slug = ? AND asset_kind = ? AND content_sha256 = ?
                          AND stage = ? AND status = ?
                        """,
                        clearsLiveReference,
                        clearsLiveReference,
                        next.stage().databaseValue(),
                        next.status().databaseValue(),
                        runId,
                        tenantSlug,
                        assetKind,
                        contentSha256.value(),
                        expected.stage().databaseValue(),
                        expected.status().databaseValue())
                == 1;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<LegacyImportAssetProvenance> inspectAssets(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return List.copyOf(jdbcTemplate.query(
                """
                SELECT %s FROM legacy_import_run_assets
                WHERE run_id = ? ORDER BY tenant_slug, asset_kind, content_sha256
                """.formatted(ASSET_COLUMNS),
                ASSET_MAPPER,
                runId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LegacyImportAssetCleanupCandidate> lockAssetCleanupCandidates(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return List.copyOf(jdbcTemplate.query(
                """
                SELECT run_id, tenant_id_snapshot, asset_id_snapshot, asset_kind,
                       content_sha256, canonical_object_key, reused
                FROM legacy_import_run_assets
                WHERE run_id = ? AND stage = 'cleanup_objects_pending'
                  AND status IN ('planned', 'created', 'reused')
                ORDER BY tenant_slug, asset_kind, content_sha256
                FOR UPDATE
                """,
                CLEANUP_CANDIDATE_MAPPER,
                runId));
    }

    private static void requireTenantMutation(
            UUID runId,
            String tenantSlug,
            LegacyImportTenantState expected,
            LegacyImportTenantState next) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(tenantSlug, "tenantSlug");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        expected.requireValidTransitionTo(next);
    }

    private static void requireAssetMutation(
            UUID runId,
            String tenantSlug,
            String assetKind,
            LegacyImportSha256 contentSha256,
            LegacyImportAssetState expected,
            LegacyImportAssetState next) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(tenantSlug, "tenantSlug");
        Objects.requireNonNull(assetKind, "assetKind");
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        expected.requireValidTransitionTo(next);
    }

    private static void validateFailureCode(
            LegacyImportRunState expected,
            LegacyImportRunState next,
            String redactedFailureCode) {
        if (next.status() == LegacyImportRunStatus.FAILED) {
            var mayReuseExisting = expected.status() == LegacyImportRunStatus.FAILED;
            if (redactedFailureCode == null && !mayReuseExisting) {
                throw new IllegalArgumentException("A transition to failed requires a redacted failure code");
            }
            if (redactedFailureCode != null && !redactedFailureCode.matches("[A-Z][A-Z0-9_]{0,119}")) {
                throw new IllegalArgumentException("redactedFailureCode has an invalid format");
            }
        } else if (redactedFailureCode != null) {
            throw new IllegalArgumentException("Only a failed transition may include a failure code");
        }
    }

    private static void requireImporterKey(String importerKey) {
        Objects.requireNonNull(importerKey, "importerKey");
        if (!importerKey.matches("[a-z0-9][a-z0-9._-]{0,119}")) {
            throw new IllegalArgumentException("importerKey has an invalid format");
        }
    }

    private static LegacyImportUniqueConflictException translateUniqueConflict(
            LegacyImportConflictScope scope,
            DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return new LegacyImportUniqueConflictException(scope);
            }
        }
        throw exception;
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    private static java.time.Instant instant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<LegacyImportRun> RUN_MAPPER = (resultSet, rowNumber) -> {
        var definition = new LegacyImportRunDefinition(
                resultSet.getString("importer_key"),
                resultSet.getString("manifest_id"),
                resultSet.getInt("schema_version"),
                resultSet.getString("environment"),
                new LegacyImportRunFingerprint(resultSet.getString("run_fingerprint")),
                new LegacyImportSha256(resultSet.getString("manifest_sha256")),
                new LegacyImportSha256(resultSet.getString("fixture_sha256")),
                new LegacyImportSha256(resultSet.getString("complete_source_set_sha256")),
                new LegacyImportSha256(resultSet.getString("semantic_plan_sha256")));
        var state = new LegacyImportRunState(
                LegacyImportRunStatus.fromDatabase(resultSet.getString("status")),
                LegacyImportRunStage.fromDatabase(resultSet.getString("stage")));
        return new LegacyImportRun(
                resultSet.getObject("run_id", UUID.class),
                definition,
                state,
                resultSet.getString("failure_code"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                instant(resultSet, "started_at"),
                instant(resultSet, "applied_at"),
                instant(resultSet, "completed_at"));
    };

    private static final RowMapper<LegacyImportTenantProvenance> TENANT_MAPPER = (resultSet, rowNumber) -> {
        var plan = new LegacyImportTenantPlan(
                resultSet.getString("tenant_slug"),
                resultSet.getLong("menu_version"),
                new LegacyImportSha256(resultSet.getString("tenant_source_set_sha256")),
                new LegacyImportSha256(resultSet.getString("asset_set_sha256")),
                new LegacyImportSha256(resultSet.getString("expected_config_sha256")),
                new LegacyImportSha256(resultSet.getString("expected_menu_sha256")),
                new LegacyImportSha256(resultSet.getString("expected_destination_sha256")),
                new LegacyImportSha256(resultSet.getString("semantic_fingerprint")));
        var state = new LegacyImportTenantState(
                LegacyImportTenantStatus.fromDatabase(resultSet.getString("status")),
                LegacyImportRunStage.fromDatabase(resultSet.getString("stage")));
        return new LegacyImportTenantProvenance(
                resultSet.getObject("run_id", UUID.class),
                new LegacyImportRunFingerprint(resultSet.getString("run_fingerprint")),
                plan,
                resultSet.getObject("tenant_id_snapshot", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                state,
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    };

    private static final RowMapper<LegacyImportAssetProvenance> ASSET_MAPPER = (resultSet, rowNumber) ->
            new LegacyImportAssetProvenance(
                    resultSet.getObject("run_id", UUID.class),
                    new LegacyImportRunFingerprint(resultSet.getString("run_fingerprint")),
                    resultSet.getString("tenant_slug"),
                    resultSet.getObject("tenant_id_snapshot", UUID.class),
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getObject("asset_id_snapshot", UUID.class),
                    resultSet.getObject("asset_id", UUID.class),
                    resultSet.getString("asset_kind"),
                    new LegacyImportSha256(resultSet.getString("source_sha256")),
                    new LegacyImportSha256(resultSet.getString("content_sha256")),
                    resultSet.getLong("size_bytes"),
                    new LegacyImportAssetState(
                            LegacyImportAssetStatus.fromDatabase(resultSet.getString("status")),
                            LegacyImportRunStage.fromDatabase(resultSet.getString("stage"))),
                    resultSet.getBoolean("reused"),
                    instant(resultSet, "created_at"),
                    instant(resultSet, "updated_at"));

    private static final RowMapper<LegacyImportAssetCleanupCandidate> CLEANUP_CANDIDATE_MAPPER =
            (resultSet, rowNumber) -> new LegacyImportAssetCleanupCandidate(
                    resultSet.getObject("run_id", UUID.class),
                    resultSet.getObject("tenant_id_snapshot", UUID.class),
                    resultSet.getObject("asset_id_snapshot", UUID.class),
                    resultSet.getString("asset_kind"),
                    new LegacyImportSha256(resultSet.getString("content_sha256")),
                    resultSet.getString("canonical_object_key"),
                    resultSet.getBoolean("reused"));
}
