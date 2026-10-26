CREATE TABLE tenant_assets (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    object_key varchar(1024) NOT NULL,
    asset_type varchar(64) NOT NULL,
    content_type varchar(127) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_tenant_assets_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT uq_tenant_assets_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_tenant_assets_object_key UNIQUE (tenant_id, object_key),
    CONSTRAINT uq_tenant_assets_content UNIQUE (tenant_id, sha256, asset_type),
    CONSTRAINT ck_tenant_assets_object_key CHECK (length(btrim(object_key)) > 0),
    CONSTRAINT ck_tenant_assets_type CHECK (asset_type ~ '^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$'),
    CONSTRAINT ck_tenant_assets_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_tenant_assets_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tenant_assets_status CHECK (status IN ('pending', 'active', 'deleted'))
);

CREATE INDEX ix_tenant_assets_status
    ON tenant_assets (tenant_id, status, created_at DESC);

ALTER TABLE tenant_branding
    ADD CONSTRAINT fk_tenant_branding_logo_asset FOREIGN KEY (tenant_id, logo_asset_id)
        REFERENCES tenant_assets (tenant_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_tenant_branding_favicon_asset FOREIGN KEY (tenant_id, favicon_asset_id)
        REFERENCES tenant_assets (tenant_id, id) ON DELETE RESTRICT;

ALTER TABLE menu_categories
    ADD CONSTRAINT fk_menu_categories_image_asset FOREIGN KEY (tenant_id, image_asset_id)
        REFERENCES tenant_assets (tenant_id, id) ON DELETE RESTRICT;

ALTER TABLE menu_items
    ADD CONSTRAINT fk_menu_items_image_asset FOREIGN KEY (tenant_id, image_asset_id)
        REFERENCES tenant_assets (tenant_id, id) ON DELETE RESTRICT;

CREATE TABLE notification_destinations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    channel varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    display_name varchar(160) NOT NULL,
    secret_reference varchar(512) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_notification_destinations_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT uq_notification_destinations_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_notification_destinations_name UNIQUE (tenant_id, channel, display_name),
    CONSTRAINT ck_notification_destinations_channel CHECK (channel IN ('whatsapp', 'telegram', 'email', 'pos')),
    CONSTRAINT ck_notification_destinations_status CHECK (status IN ('active', 'disabled')),
    CONSTRAINT ck_notification_destinations_secret_reference CHECK (
        length(btrim(secret_reference)) > 0
        AND secret_reference !~ '^https?://'
    ),
    CONSTRAINT ck_notification_destinations_metadata CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_notification_destinations_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_notification_destinations_active
    ON notification_destinations (tenant_id, channel, status);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(120) NOT NULL,
    schema_version integer NOT NULL,
    payload jsonb NOT NULL,
    processing_state varchar(32) NOT NULL DEFAULT 'pending',
    attempt_count integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    leased_until timestamptz,
    lease_owner varchar(160),
    last_error_code varchar(120),
    last_error_at timestamptz,
    processed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_outbox_events_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT uq_outbox_events_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_outbox_events_aggregate_type CHECK (aggregate_type ~ '^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$'),
    CONSTRAINT ck_outbox_events_event_type CHECK (event_type ~ '^[a-z][a-z0-9]*(?:\.[a-z0-9]+)+$'),
    CONSTRAINT ck_outbox_events_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_outbox_events_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_events_state CHECK (processing_state IN ('pending', 'processing', 'published', 'failed')),
    CONSTRAINT ck_outbox_events_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_events_lease CHECK (
        (processing_state = 'processing' AND leased_until IS NOT NULL AND lease_owner IS NOT NULL)
        OR processing_state <> 'processing'
    ),
    CONSTRAINT ck_outbox_events_processed CHECK (
        (processing_state = 'published' AND processed_at IS NOT NULL)
        OR processing_state <> 'published'
    )
);

CREATE INDEX ix_outbox_events_poll
    ON outbox_events (processing_state, available_at, created_at, id)
    WHERE processing_state IN ('pending', 'processing');

CREATE INDEX ix_outbox_events_tenant_aggregate
    ON outbox_events (tenant_id, aggregate_type, aggregate_id, created_at);

CREATE TABLE notification_deliveries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    outbox_event_id uuid NOT NULL,
    destination_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    status varchar(32) NOT NULL,
    provider_message_id varchar(255),
    error_code varchar(120),
    error_message varchar(1000),
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT fk_notification_deliveries_event FOREIGN KEY (tenant_id, outbox_event_id)
        REFERENCES outbox_events (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_deliveries_destination FOREIGN KEY (tenant_id, destination_id)
        REFERENCES notification_destinations (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_notification_deliveries_attempt UNIQUE (
        tenant_id,
        outbox_event_id,
        destination_id,
        attempt_number
    ),
    CONSTRAINT ck_notification_deliveries_attempt CHECK (attempt_number > 0),
    CONSTRAINT ck_notification_deliveries_status CHECK (status IN ('started', 'succeeded', 'failed')),
    CONSTRAINT ck_notification_deliveries_completion CHECK (
        (status IN ('succeeded', 'failed') AND completed_at IS NOT NULL)
        OR status = 'started'
    )
);

CREATE INDEX ix_notification_deliveries_event
    ON notification_deliveries (tenant_id, outbox_event_id, attempt_number);
