CREATE TABLE legacy_import_runs (
    run_id uuid PRIMARY KEY,
    importer_key varchar(120) NOT NULL,
    manifest_id varchar(160) NOT NULL,
    schema_version integer NOT NULL,
    environment varchar(32) NOT NULL,
    run_fingerprint char(64) NOT NULL,
    manifest_sha256 char(64) NOT NULL,
    fixture_sha256 char(64) NOT NULL,
    complete_source_set_sha256 char(64) NOT NULL,
    semantic_plan_sha256 char(64) NOT NULL,
    stage varchar(48) NOT NULL,
    status varchar(24) NOT NULL,
    failure_code varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz,
    completed_at timestamptz,
    CONSTRAINT uq_legacy_import_runs_fingerprint UNIQUE (run_fingerprint),
    CONSTRAINT uq_legacy_import_runs_id_fingerprint UNIQUE (run_id, run_fingerprint),
    CONSTRAINT uq_legacy_import_runs_logical UNIQUE (
        manifest_id,
        environment,
        complete_source_set_sha256,
        semantic_plan_sha256
    ),
    CONSTRAINT ck_legacy_import_runs_importer_key CHECK (
        importer_key ~ '^[a-z0-9][a-z0-9._-]{0,119}$'
    ),
    CONSTRAINT ck_legacy_import_runs_manifest_id CHECK (
        manifest_id ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$'
    ),
    CONSTRAINT ck_legacy_import_runs_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_legacy_import_runs_environment CHECK (
        environment ~ '^[a-z][a-z0-9-]{0,31}$'
    ),
    CONSTRAINT ck_legacy_import_runs_hashes CHECK (
        run_fingerprint ~ '^[0-9a-f]{64}$'
        AND manifest_sha256 ~ '^[0-9a-f]{64}$'
        AND fixture_sha256 ~ '^[0-9a-f]{64}$'
        AND complete_source_set_sha256 ~ '^[0-9a-f]{64}$'
        AND semantic_plan_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_legacy_import_runs_stage CHECK (stage IN (
        'planned',
        'skeletons_suspended',
        'assets_pending',
        'assets_staged',
        'final_tx_pending',
        'db_published',
        'smoke_verified',
        'cutover_approved',
        'cleanup_objects_pending',
        'completed',
        'conflict',
        'failed'
    )),
    CONSTRAINT ck_legacy_import_runs_status CHECK (
        status IN ('staging', 'applying', 'applied', 'failed', 'cleaned')
    ),
    CONSTRAINT ck_legacy_import_runs_state CHECK (
        (stage IN ('planned', 'skeletons_suspended', 'assets_pending', 'assets_staged')
            AND status = 'staging')
        OR (stage = 'final_tx_pending' AND status = 'applying')
        OR (stage IN ('db_published', 'smoke_verified', 'cutover_approved')
            AND status = 'applied')
        OR (stage = 'cleanup_objects_pending' AND status IN ('staging', 'failed'))
        OR (stage = 'completed' AND status IN ('applied', 'cleaned'))
        OR (stage IN ('conflict', 'failed') AND status = 'failed')
    ),
    CONSTRAINT ck_legacy_import_runs_failure CHECK (
        (status = 'failed'
            AND failure_code IS NOT NULL
            AND failure_code ~ '^[A-Z][A-Z0-9_]{0,119}$')
        OR (status <> 'failed' AND failure_code IS NULL)
    ),
    CONSTRAINT ck_legacy_import_runs_applied CHECK (
        (status = 'applied' AND applied_at IS NOT NULL)
        OR status <> 'applied'
    ),
    CONSTRAINT ck_legacy_import_runs_completed CHECK (
        ((status = 'failed' OR stage = 'completed') AND completed_at IS NOT NULL)
        OR (status <> 'failed' AND stage <> 'completed' AND completed_at IS NULL)
    ),
    CONSTRAINT ck_legacy_import_runs_timestamps CHECK (
        updated_at >= created_at
        AND started_at >= created_at
        AND (applied_at IS NULL OR applied_at >= started_at)
        AND (completed_at IS NULL OR completed_at >= started_at)
    )
);

CREATE UNIQUE INDEX uq_legacy_import_runs_open_importer
    ON legacy_import_runs (importer_key)
    WHERE stage NOT IN ('completed', 'conflict', 'failed');

CREATE INDEX ix_legacy_import_runs_status_stage
    ON legacy_import_runs (status, stage, updated_at, run_id);

CREATE TABLE legacy_import_run_tenants (
    run_id uuid NOT NULL,
    tenant_slug varchar(63) NOT NULL,
    tenant_id_snapshot uuid,
    tenant_id uuid,
    run_fingerprint char(64) NOT NULL,
    menu_version bigint NOT NULL,
    tenant_source_set_sha256 char(64) NOT NULL,
    asset_set_sha256 char(64) NOT NULL,
    expected_config_sha256 char(64) NOT NULL,
    expected_menu_sha256 char(64) NOT NULL,
    expected_destination_sha256 char(64) NOT NULL,
    semantic_fingerprint char(64) NOT NULL,
    stage varchar(48) NOT NULL,
    status varchar(24) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, tenant_slug),
    CONSTRAINT uq_legacy_import_run_tenants_id UNIQUE (run_id, tenant_id),
    CONSTRAINT uq_legacy_import_run_tenants_snapshot UNIQUE (run_id, tenant_id_snapshot),
    CONSTRAINT uq_legacy_import_run_tenants_scope UNIQUE (
        run_id,
        tenant_slug,
        run_fingerprint,
        tenant_id_snapshot
    ),
    CONSTRAINT uq_legacy_import_run_tenants_business UNIQUE (
        tenant_slug,
        menu_version,
        asset_set_sha256,
        tenant_source_set_sha256
    ),
    CONSTRAINT fk_legacy_import_run_tenants_run FOREIGN KEY (run_id, run_fingerprint)
        REFERENCES legacy_import_runs (run_id, run_fingerprint) ON DELETE CASCADE,
    CONSTRAINT fk_legacy_import_run_tenants_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE SET NULL,
    CONSTRAINT ck_legacy_import_run_tenants_slug CHECK (
        tenant_slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
    ),
    CONSTRAINT ck_legacy_import_run_tenants_menu_version CHECK (menu_version > 0),
    CONSTRAINT ck_legacy_import_run_tenants_hashes CHECK (
        run_fingerprint ~ '^[0-9a-f]{64}$'
        AND tenant_source_set_sha256 ~ '^[0-9a-f]{64}$'
        AND asset_set_sha256 ~ '^[0-9a-f]{64}$'
        AND expected_config_sha256 ~ '^[0-9a-f]{64}$'
        AND expected_menu_sha256 ~ '^[0-9a-f]{64}$'
        AND expected_destination_sha256 ~ '^[0-9a-f]{64}$'
        AND semantic_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_legacy_import_run_tenants_stage CHECK (stage IN (
        'planned',
        'skeletons_suspended',
        'assets_pending',
        'assets_staged',
        'final_tx_pending',
        'db_published',
        'smoke_verified',
        'cutover_approved',
        'cleanup_objects_pending',
        'completed',
        'failed'
    )),
    CONSTRAINT ck_legacy_import_run_tenants_status CHECK (
        status IN ('skeleton', 'staged', 'applied', 'cleaned')
    ),
    CONSTRAINT ck_legacy_import_run_tenants_state CHECK (
        (status = 'skeleton'
            AND stage IN ('planned', 'skeletons_suspended', 'assets_pending', 'cleanup_objects_pending'))
        OR (status = 'staged'
            AND stage IN ('assets_staged', 'final_tx_pending', 'cleanup_objects_pending', 'failed'))
        OR (status = 'applied'
            AND stage IN ('db_published', 'smoke_verified', 'cutover_approved', 'completed'))
        OR (status = 'cleaned' AND stage = 'completed')
    ),
    CONSTRAINT ck_legacy_import_run_tenants_tenant_reference CHECK (
        (status = 'skeleton' AND stage = 'planned'
            AND tenant_id_snapshot IS NULL AND tenant_id IS NULL)
        OR (stage IN (
                'skeletons_suspended',
                'assets_pending',
                'assets_staged',
                'final_tx_pending',
                'db_published',
                'smoke_verified',
                'cutover_approved',
                'failed'
            )
            AND tenant_id_snapshot IS NOT NULL
            AND tenant_id IS NOT NULL
            AND tenant_id = tenant_id_snapshot)
        OR (status = 'applied' AND stage = 'completed'
            AND tenant_id_snapshot IS NOT NULL
            AND tenant_id IS NOT NULL
            AND tenant_id = tenant_id_snapshot)
        OR (status IN ('skeleton', 'staged') AND stage = 'cleanup_objects_pending'
            AND tenant_id IS NULL)
        OR (status = 'cleaned' AND stage = 'completed' AND tenant_id IS NULL)
    ),
    CONSTRAINT ck_legacy_import_run_tenants_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_legacy_import_run_tenants_tenant
    ON legacy_import_run_tenants (tenant_id, status, stage, run_id)
    WHERE tenant_id IS NOT NULL;

CREATE INDEX ix_legacy_import_run_tenants_snapshot
    ON legacy_import_run_tenants (tenant_id_snapshot, run_id, tenant_slug)
    WHERE tenant_id_snapshot IS NOT NULL;

CREATE INDEX ix_legacy_import_run_tenants_run_state
    ON legacy_import_run_tenants (run_id, status, stage, tenant_slug);

CREATE TABLE legacy_import_run_assets (
    run_id uuid NOT NULL,
    tenant_slug varchar(63) NOT NULL,
    tenant_id_snapshot uuid NOT NULL,
    tenant_id uuid,
    run_fingerprint char(64) NOT NULL,
    asset_id_snapshot uuid,
    asset_id uuid,
    asset_kind varchar(64) NOT NULL,
    source_sha256 char(64) NOT NULL,
    content_sha256 char(64) NOT NULL,
    size_bytes bigint NOT NULL,
    canonical_object_key varchar(1024) NOT NULL,
    stage varchar(48) NOT NULL,
    status varchar(24) NOT NULL,
    provenance varchar(32) NOT NULL DEFAULT 'legacy_import',
    reused boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, tenant_slug, asset_kind, content_sha256),
    CONSTRAINT uq_legacy_import_run_assets_object UNIQUE (run_id, canonical_object_key),
    CONSTRAINT uq_legacy_import_run_assets_identity UNIQUE (
        run_id,
        tenant_id_snapshot,
        asset_kind,
        content_sha256
    ),
    CONSTRAINT fk_legacy_import_run_assets_tenant_run FOREIGN KEY (
        run_id,
        tenant_slug,
        run_fingerprint,
        tenant_id_snapshot
    ) REFERENCES legacy_import_run_tenants (
        run_id,
        tenant_slug,
        run_fingerprint,
        tenant_id_snapshot
    ) ON DELETE CASCADE,
    CONSTRAINT fk_legacy_import_run_assets_asset FOREIGN KEY (tenant_id, asset_id)
        REFERENCES tenant_assets (tenant_id, id) ON DELETE SET NULL,
    CONSTRAINT ck_legacy_import_run_assets_kind CHECK (
        asset_kind ~ '^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$'
    ),
    CONSTRAINT ck_legacy_import_run_assets_hashes CHECK (
        run_fingerprint ~ '^[0-9a-f]{64}$'
        AND source_sha256 ~ '^[0-9a-f]{64}$'
        AND content_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_legacy_import_run_assets_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_legacy_import_run_assets_object_key CHECK (
        length(btrim(canonical_object_key)) > 0
        AND canonical_object_key !~ '(^|/)\.\.(/|$)'
    ),
    CONSTRAINT ck_legacy_import_run_assets_stage CHECK (stage IN (
        'assets_pending',
        'assets_staged',
        'cleanup_objects_pending',
        'completed',
        'failed'
    )),
    CONSTRAINT ck_legacy_import_run_assets_status CHECK (
        status IN ('planned', 'created', 'reused', 'cleaned')
    ),
    CONSTRAINT ck_legacy_import_run_assets_state CHECK (
        (status = 'planned' AND stage IN ('assets_pending', 'cleanup_objects_pending'))
        OR (status IN ('created', 'reused') AND stage IN ('assets_staged', 'cleanup_objects_pending', 'failed'))
        OR (status = 'cleaned' AND stage = 'completed')
    ),
    CONSTRAINT ck_legacy_import_run_assets_reused CHECK (
        (status = 'reused' AND reused)
        OR (status IN ('planned', 'created') AND NOT reused)
        OR status = 'cleaned'
    ),
    CONSTRAINT ck_legacy_import_run_assets_live_references CHECK (
        (status = 'planned' AND stage = 'assets_pending'
            AND asset_id_snapshot IS NULL AND asset_id IS NULL
            AND tenant_id IS NOT NULL
            AND tenant_id = tenant_id_snapshot)
        OR (status = 'planned' AND stage = 'cleanup_objects_pending'
            AND asset_id_snapshot IS NULL AND asset_id IS NULL
            AND tenant_id IS NULL)
        OR (status IN ('created', 'reused')
            AND stage IN ('assets_staged', 'failed')
            AND asset_id_snapshot IS NOT NULL
            AND asset_id IS NOT NULL
            AND asset_id = asset_id_snapshot
            AND tenant_id IS NOT NULL
            AND tenant_id = tenant_id_snapshot)
        OR (status IN ('created', 'reused')
            AND stage = 'cleanup_objects_pending'
            AND asset_id_snapshot IS NOT NULL
            AND asset_id IS NULL
            AND tenant_id IS NULL)
        OR (status = 'cleaned' AND stage = 'completed'
            AND asset_id IS NULL
            AND tenant_id IS NULL)
    ),
    CONSTRAINT ck_legacy_import_run_assets_provenance CHECK (provenance = 'legacy_import'),
    CONSTRAINT ck_legacy_import_run_assets_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_legacy_import_run_assets_tenant
    ON legacy_import_run_assets (tenant_id, status, stage, run_id);

CREATE INDEX ix_legacy_import_run_assets_snapshot
    ON legacy_import_run_assets (tenant_id_snapshot, asset_id_snapshot, run_id);

CREATE INDEX ix_legacy_import_run_assets_registered
    ON legacy_import_run_assets (tenant_id, asset_id, run_id)
    WHERE asset_id IS NOT NULL;

CREATE INDEX ix_legacy_import_run_assets_cleanup
    ON legacy_import_run_assets (run_id, stage, status, tenant_slug, asset_kind);
