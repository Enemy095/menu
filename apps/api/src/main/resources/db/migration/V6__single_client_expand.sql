-- SC-DB-001 expand phase. Legacy V1-V5 tables remain authoritative until SC-BE-001.
-- V6 is not a standalone production deployment: old writers can continue after its
-- transaction commits, so SC-BE-001/V7 must run in a maintenance window, call
-- sc_refresh_single_client_shadow() once more, compare, and contract in one transaction.
-- Flyway executes PostgreSQL migrations transactionally: every fail-closed guard below
-- rolls back the whole V6, including all shadow-table DDL.

CREATE OR REPLACE FUNCTION sc_assert_single_client_source()
RETURNS void
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
DECLARE
    owner_count integer;
BEGIN
    -- Keep this dependency order stable. SHARE blocks INSERT/UPDATE/DELETE while allowing
    -- diagnostic reads. V7 reuses this function after all legacy writers/workers are drained.
    LOCK TABLE tenants IN SHARE MODE;
    LOCK TABLE tenant_domains IN SHARE MODE;
    LOCK TABLE tenant_assets IN SHARE MODE;
    LOCK TABLE tenant_branding IN SHARE MODE;
    LOCK TABLE tenant_profiles IN SHARE MODE;
    LOCK TABLE tenant_features IN SHARE MODE;
    LOCK TABLE restaurants IN SHARE MODE;
    LOCK TABLE tenant_order_counters IN SHARE MODE;
    LOCK TABLE menu_versions IN SHARE MODE;
    LOCK TABLE menu_categories IN SHARE MODE;
    LOCK TABLE menu_items IN SHARE MODE;
    LOCK TABLE orders IN SHARE MODE;
    LOCK TABLE order_items IN SHARE MODE;
    LOCK TABLE order_status_history IN SHARE MODE;
    LOCK TABLE notification_destinations IN SHARE MODE;
    LOCK TABLE outbox_events IN SHARE MODE;
    LOCK TABLE notification_deliveries IN SHARE MODE;
    LOCK TABLE legacy_import_runs IN SHARE MODE;
    LOCK TABLE legacy_import_run_tenants IN SHARE MODE;
    LOCK TABLE legacy_import_run_assets IN SHARE MODE;

    SELECT count(*) INTO owner_count FROM tenants;
    IF owner_count > 1 THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_MULTI_OWNER';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM restaurants restaurant
        LEFT JOIN tenants owner ON owner.id = restaurant.tenant_id
        WHERE owner.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM tenant_order_counters counter
        LEFT JOIN tenants owner ON owner.id = counter.tenant_id
        WHERE owner.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM tenant_assets asset
        LEFT JOIN tenants owner ON owner.id = asset.tenant_id
        WHERE owner.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM tenant_branding branding
        LEFT JOIN tenants owner ON owner.id = branding.tenant_id
        WHERE owner.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM tenant_profiles profile
        LEFT JOIN tenants owner ON owner.id = profile.tenant_id
        WHERE owner.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM tenant_features feature
        LEFT JOIN tenants owner ON owner.id = feature.tenant_id
        WHERE owner.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM notification_destinations destination
        LEFT JOIN tenants owner ON owner.id = destination.tenant_id
        WHERE owner.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM outbox_events event
        LEFT JOIN tenants owner ON owner.id = event.tenant_id
        WHERE owner.id IS NULL
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_ORPHAN_OWNER';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM menu_versions version
        LEFT JOIN restaurants restaurant
            ON restaurant.id = version.restaurant_id
           AND restaurant.tenant_id = version.tenant_id
        WHERE restaurant.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM menu_categories category
        LEFT JOIN menu_versions version
            ON version.id = category.menu_version_id
           AND version.tenant_id = category.tenant_id
        WHERE version.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM menu_items item
        LEFT JOIN menu_versions version
            ON version.id = item.menu_version_id
           AND version.tenant_id = item.tenant_id
        LEFT JOIN menu_categories category
            ON category.id = item.category_id
           AND category.menu_version_id = item.menu_version_id
           AND category.tenant_id = item.tenant_id
        WHERE version.id IS NULL OR category.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM orders customer_order
        LEFT JOIN restaurants restaurant
            ON restaurant.id = customer_order.restaurant_id
           AND restaurant.tenant_id = customer_order.tenant_id
        LEFT JOIN menu_versions version
            ON version.id = customer_order.menu_version_id
           AND version.restaurant_id = customer_order.restaurant_id
           AND version.version = customer_order.client_menu_version
           AND version.tenant_id = customer_order.tenant_id
        WHERE restaurant.id IS NULL OR version.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM order_items item
        LEFT JOIN orders customer_order
            ON customer_order.id = item.order_id
           AND customer_order.menu_version_id = item.menu_version_id
           AND customer_order.tenant_id = item.tenant_id
        LEFT JOIN menu_items menu_item
            ON menu_item.id = item.menu_item_id
           AND menu_item.menu_version_id = item.menu_version_id
           AND menu_item.tenant_id = item.tenant_id
        WHERE customer_order.id IS NULL OR menu_item.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM order_status_history history
        LEFT JOIN orders customer_order
            ON customer_order.id = history.order_id
           AND customer_order.tenant_id = history.tenant_id
        WHERE customer_order.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM notification_deliveries delivery
        LEFT JOIN outbox_events event
            ON event.id = delivery.outbox_event_id
           AND event.tenant_id = delivery.tenant_id
        LEFT JOIN notification_destinations destination
            ON destination.id = delivery.destination_id
           AND destination.tenant_id = delivery.tenant_id
        WHERE event.id IS NULL OR destination.id IS NULL
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_CROSS_OWNER_OR_ORPHAN_REFERENCE';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tenant_branding branding
        LEFT JOIN tenant_assets logo
            ON logo.id = branding.logo_asset_id
           AND logo.tenant_id = branding.tenant_id
        LEFT JOIN tenant_assets favicon
            ON favicon.id = branding.favicon_asset_id
           AND favicon.tenant_id = branding.tenant_id
        WHERE (branding.logo_asset_id IS NOT NULL AND logo.id IS NULL)
           OR (branding.favicon_asset_id IS NOT NULL AND favicon.id IS NULL)
    ) OR EXISTS (
        SELECT 1
        FROM menu_categories category
        LEFT JOIN tenant_assets asset
            ON asset.id = category.image_asset_id
           AND asset.tenant_id = category.tenant_id
        WHERE category.image_asset_id IS NOT NULL AND asset.id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM menu_items item
        LEFT JOIN tenant_assets asset
            ON asset.id = item.image_asset_id
           AND asset.tenant_id = item.tenant_id
        WHERE item.image_asset_id IS NOT NULL AND asset.id IS NULL
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_ORPHAN_ASSET_REFERENCE';
    END IF;

    IF EXISTS (
        SELECT slug FROM restaurants GROUP BY slug HAVING count(*) > 1
    ) OR EXISTS (
        SELECT 1 FROM restaurants WHERE is_default GROUP BY is_default HAVING count(*) > 1
    ) OR EXISTS (
        SELECT restaurant_id, version
        FROM menu_versions
        GROUP BY restaurant_id, version
        HAVING count(*) > 1
    ) OR EXISTS (
        SELECT restaurant_id
        FROM menu_versions
        WHERE status = 'published'
        GROUP BY restaurant_id
        HAVING count(*) > 1
    ) OR EXISTS (
        SELECT menu_version_id, public_id
        FROM menu_categories
        GROUP BY menu_version_id, public_id
        HAVING count(*) > 1
    ) OR EXISTS (
        SELECT menu_version_id, public_id
        FROM menu_items
        GROUP BY menu_version_id, public_id
        HAVING count(*) > 1
    ) OR EXISTS (
        SELECT idempotency_key FROM orders GROUP BY idempotency_key HAVING count(*) > 1
    ) OR EXISTS (
        SELECT order_number FROM orders GROUP BY order_number HAVING count(*) > 1
    ) OR EXISTS (
        SELECT channel, display_name
        FROM notification_destinations
        GROUP BY channel, display_name
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_FUTURE_GLOBAL_KEY_COLLISION';
    END IF;

    IF EXISTS (SELECT 1 FROM restaurants)
       AND (SELECT count(*) FROM restaurants WHERE status = 'active' AND is_default) <> 1 THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_ACTIVE_DEFAULT_RESTAURANT_REQUIRED';
    END IF;

    IF EXISTS (SELECT 1 FROM orders)
       AND (
           (SELECT count(*) FROM tenant_order_counters) <> 1
           OR COALESCE((SELECT max(next_value) FROM tenant_order_counters), 0)
              <= (SELECT max(order_number) FROM orders)
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_ORDER_COUNTER_INVALID';
    END IF;

    IF EXISTS (SELECT 1 FROM outbox_events WHERE aggregate_type <> 'order') THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_UNSUPPORTED_OUTBOX_AGGREGATE';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM outbox_events event
        LEFT JOIN orders customer_order
            ON customer_order.id = event.aggregate_id
           AND customer_order.tenant_id = event.tenant_id
        WHERE event.aggregate_type = 'order'
          AND customer_order.id IS NULL
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_ORPHAN_OUTBOX_ORDER';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tenant_assets
        WHERE content_type NOT IN (
            'image/png',
            'image/jpeg',
            'image/webp',
            'image/x-icon',
            'image/vnd.microsoft.icon'
        )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_UNSUPPORTED_ASSET_CONTENT_TYPE';
    END IF;

    IF EXISTS (
        SELECT asset_type, sha256
        FROM tenant_assets
        GROUP BY asset_type, sha256
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_ASSET_IDENTITY_COLLISION';
    END IF;
END
$$;

REVOKE ALL ON FUNCTION sc_assert_single_client_source() FROM PUBLIC;
SELECT sc_assert_single_client_source();

CREATE TABLE sc_site_configuration (
    singleton_id smallint PRIMARY KEY DEFAULT 1,
    status varchar(32) NOT NULL,
    locale varchar(16) NOT NULL,
    currency varchar(3) NOT NULL,
    timezone varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_sc_site_configuration_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_sc_site_configuration_status CHECK (status IN ('active', 'suspended', 'deleted')),
    CONSTRAINT ck_sc_site_configuration_locale CHECK (locale ~ '^[a-z]{2,3}(?:-[A-Z]{2})?$'),
    CONSTRAINT ck_sc_site_configuration_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_sc_site_configuration_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE sc_assets (
    id uuid PRIMARY KEY,
    legacy_object_key varchar(1024),
    canonical_object_key varchar(1024) NOT NULL,
    storage_state varchar(32) NOT NULL,
    asset_type varchar(64) NOT NULL,
    content_type varchar(127) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_sc_assets_legacy_object_key UNIQUE (legacy_object_key),
    CONSTRAINT uq_sc_assets_canonical_object_key UNIQUE (canonical_object_key),
    CONSTRAINT uq_sc_assets_content UNIQUE (sha256, asset_type),
    CONSTRAINT ck_sc_assets_legacy_object_key CHECK (
        legacy_object_key IS NULL OR length(btrim(legacy_object_key)) > 0
    ),
    CONSTRAINT ck_sc_assets_content_type CHECK (content_type IN (
        'image/png',
        'image/jpeg',
        'image/webp',
        'image/x-icon',
        'image/vnd.microsoft.icon'
    )),
    CONSTRAINT ck_sc_assets_canonical_object_key CHECK (
        canonical_object_key =
            'assets/' || asset_type || '/' || sha256 || CASE content_type
                WHEN 'image/png' THEN '.png'
                WHEN 'image/jpeg' THEN '.jpg'
                WHEN 'image/webp' THEN '.webp'
                WHEN 'image/x-icon' THEN '.ico'
                WHEN 'image/vnd.microsoft.icon' THEN '.ico'
            END
    ),
    CONSTRAINT ck_sc_assets_storage_state CHECK (storage_state IN ('legacy_only', 'canonical_ready')),
    CONSTRAINT ck_sc_assets_storage_transition CHECK (
        storage_state <> 'legacy_only' OR legacy_object_key IS NOT NULL
    ),
    CONSTRAINT ck_sc_assets_type CHECK (asset_type ~ '^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$'),
    CONSTRAINT ck_sc_assets_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_sc_assets_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sc_assets_status CHECK (status IN ('pending', 'active', 'deleted'))
);

CREATE INDEX ix_sc_assets_status
    ON sc_assets (status, created_at DESC);

CREATE TABLE sc_site_branding (
    singleton_id smallint PRIMARY KEY DEFAULT 1,
    display_name varchar(160) NOT NULL,
    business_type varchar(80) NOT NULL,
    logo_asset_id uuid,
    favicon_asset_id uuid,
    design_tokens jsonb NOT NULL DEFAULT '{}'::jsonb,
    localized_texts jsonb NOT NULL DEFAULT '[]'::jsonb,
    hero jsonb NOT NULL DEFAULT '{}'::jsonb,
    seo_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_sc_site_branding_configuration FOREIGN KEY (singleton_id)
        REFERENCES sc_site_configuration (singleton_id) ON DELETE CASCADE,
    CONSTRAINT fk_sc_site_branding_logo_asset FOREIGN KEY (logo_asset_id)
        REFERENCES sc_assets (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sc_site_branding_favicon_asset FOREIGN KEY (favicon_asset_id)
        REFERENCES sc_assets (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sc_site_branding_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_sc_site_branding_design_tokens CHECK (jsonb_typeof(design_tokens) = 'object'),
    CONSTRAINT ck_sc_site_branding_localized_texts CHECK (jsonb_typeof(localized_texts) = 'array'),
    CONSTRAINT ck_sc_site_branding_hero CHECK (jsonb_typeof(hero) = 'object'),
    CONSTRAINT ck_sc_site_branding_seo CHECK (jsonb_typeof(seo_metadata) = 'object')
);

CREATE TABLE sc_site_profile (
    singleton_id smallint PRIMARY KEY DEFAULT 1,
    public_phone varchar(64),
    public_phone_label varchar(160),
    public_email varchar(254),
    address_text varchar(500),
    working_hours jsonb NOT NULL DEFAULT '[]'::jsonb,
    working_hours_text varchar(500),
    latitude numeric(9, 6),
    longitude numeric(9, 6),
    social_links jsonb NOT NULL DEFAULT '[]'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_sc_site_profile_configuration FOREIGN KEY (singleton_id)
        REFERENCES sc_site_configuration (singleton_id) ON DELETE CASCADE,
    CONSTRAINT ck_sc_site_profile_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_sc_site_profile_working_hours CHECK (jsonb_typeof(working_hours) = 'array'),
    CONSTRAINT ck_sc_site_profile_social_links CHECK (jsonb_typeof(social_links) = 'array'),
    CONSTRAINT ck_sc_site_profile_location_pair CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL)
    ),
    CONSTRAINT ck_sc_site_profile_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_sc_site_profile_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

CREATE TABLE sc_site_features (
    feature_key varchar(80) PRIMARY KEY,
    enabled boolean NOT NULL DEFAULT false,
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_sc_site_features_key CHECK (feature_key ~ '^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$'),
    CONSTRAINT ck_sc_site_features_config CHECK (jsonb_typeof(config) = 'object')
);

CREATE TABLE sc_restaurants (
    id uuid PRIMARY KEY,
    external_key varchar(63) NOT NULL,
    slug varchar(63) NOT NULL,
    display_name varchar(160) NOT NULL,
    status varchar(32) NOT NULL,
    is_default boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_sc_restaurants_external_key UNIQUE (external_key),
    CONSTRAINT uq_sc_restaurants_slug UNIQUE (slug),
    CONSTRAINT ck_sc_restaurants_external_key CHECK (external_key ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_sc_restaurants_slug CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_sc_restaurants_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT ck_sc_restaurants_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_sc_restaurants_default
    ON sc_restaurants (is_default)
    WHERE is_default;

CREATE INDEX ix_sc_restaurants_status
    ON sc_restaurants (status);

CREATE TABLE sc_order_counter (
    singleton_id smallint PRIMARY KEY DEFAULT 1,
    next_value bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_sc_order_counter_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_sc_order_counter_next_value CHECK (next_value > 0)
);

CREATE TABLE sc_menu_versions (
    id uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL,
    version bigint NOT NULL,
    status varchar(32) NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_sc_menu_versions_restaurant FOREIGN KEY (restaurant_id)
        REFERENCES sc_restaurants (id) ON DELETE RESTRICT,
    CONSTRAINT uq_sc_menu_versions_restaurant_version UNIQUE (restaurant_id, version),
    CONSTRAINT uq_sc_menu_versions_full_reference UNIQUE (restaurant_id, id, version),
    CONSTRAINT uq_sc_menu_versions_id_version UNIQUE (id, version),
    CONSTRAINT ck_sc_menu_versions_version CHECK (version > 0),
    CONSTRAINT ck_sc_menu_versions_status CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT ck_sc_menu_versions_published_at CHECK (
        (status = 'published' AND published_at IS NOT NULL)
        OR status <> 'published'
    ),
    CONSTRAINT ck_sc_menu_versions_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_sc_menu_versions_current_published
    ON sc_menu_versions (restaurant_id)
    WHERE status = 'published';

CREATE INDEX ix_sc_menu_versions_current_read
    ON sc_menu_versions (restaurant_id, status, published_at DESC);

CREATE TABLE sc_menu_categories (
    id uuid PRIMARY KEY,
    menu_version_id uuid NOT NULL,
    public_id varchar(120) NOT NULL,
    name varchar(120) NOT NULL,
    description varchar(500),
    image_asset_id uuid,
    sort_order integer NOT NULL,
    available boolean NOT NULL DEFAULT true,
    CONSTRAINT fk_sc_menu_categories_version FOREIGN KEY (menu_version_id)
        REFERENCES sc_menu_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_sc_menu_categories_image_asset FOREIGN KEY (image_asset_id)
        REFERENCES sc_assets (id) ON DELETE RESTRICT,
    CONSTRAINT uq_sc_menu_categories_version_id UNIQUE (menu_version_id, id),
    CONSTRAINT uq_sc_menu_categories_public_id UNIQUE (menu_version_id, public_id),
    CONSTRAINT ck_sc_menu_categories_public_id CHECK (public_id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_sc_menu_categories_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX ix_sc_menu_categories_ordered
    ON sc_menu_categories (menu_version_id, sort_order, public_id);

CREATE TABLE sc_menu_items (
    id uuid PRIMARY KEY,
    menu_version_id uuid NOT NULL,
    category_id uuid NOT NULL,
    public_id varchar(120) NOT NULL,
    name varchar(160) NOT NULL,
    description varchar(1000),
    price_minor bigint NOT NULL,
    currency varchar(3) NOT NULL,
    image_asset_id uuid,
    available boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL,
    CONSTRAINT fk_sc_menu_items_version FOREIGN KEY (menu_version_id)
        REFERENCES sc_menu_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_sc_menu_items_category FOREIGN KEY (menu_version_id, category_id)
        REFERENCES sc_menu_categories (menu_version_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_sc_menu_items_image_asset FOREIGN KEY (image_asset_id)
        REFERENCES sc_assets (id) ON DELETE RESTRICT,
    CONSTRAINT uq_sc_menu_items_version_id UNIQUE (menu_version_id, id),
    CONSTRAINT uq_sc_menu_items_public_id UNIQUE (menu_version_id, public_id),
    CONSTRAINT ck_sc_menu_items_public_id CHECK (public_id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_sc_menu_items_price CHECK (price_minor >= 0),
    CONSTRAINT ck_sc_menu_items_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_sc_menu_items_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX ix_sc_menu_items_ordered
    ON sc_menu_items (menu_version_id, category_id, sort_order, public_id);

CREATE TABLE sc_orders (
    id uuid PRIMARY KEY,
    restaurant_id uuid NOT NULL,
    menu_version_id uuid NOT NULL,
    order_number bigint NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    idempotency_payload_hash varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    order_type varchar(32) NOT NULL,
    channel varchar(32) NOT NULL,
    customer_name varchar(80) NOT NULL,
    customer_phone varchar(32) NOT NULL,
    customer_address varchar(240),
    comment varchar(500),
    guest_count integer,
    client_total_minor bigint NOT NULL,
    server_total_minor bigint NOT NULL,
    currency varchar(3) NOT NULL,
    client_created_at timestamptz NOT NULL,
    server_received_at timestamptz NOT NULL DEFAULT now(),
    client_menu_version bigint NOT NULL,
    needs_confirmation boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_sc_orders_restaurant FOREIGN KEY (restaurant_id)
        REFERENCES sc_restaurants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sc_orders_menu_version FOREIGN KEY (
        restaurant_id,
        menu_version_id,
        client_menu_version
    ) REFERENCES sc_menu_versions (restaurant_id, id, version) ON DELETE RESTRICT,
    CONSTRAINT uq_sc_orders_id_menu_version UNIQUE (id, menu_version_id),
    CONSTRAINT uq_sc_orders_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_sc_orders_number UNIQUE (order_number),
    CONSTRAINT ck_sc_orders_number CHECK (order_number > 0),
    CONSTRAINT ck_sc_orders_idempotency_hash CHECK (idempotency_payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sc_orders_status CHECK (status IN ('new', 'confirmed', 'cancelled', 'completed')),
    CONSTRAINT ck_sc_orders_type CHECK (order_type IN ('preorder', 'delivery', 'pickup')),
    CONSTRAINT ck_sc_orders_channel CHECK (channel IN ('web', 'admin', 'import')),
    CONSTRAINT ck_sc_orders_guest_count CHECK (guest_count IS NULL OR guest_count BETWEEN 1 AND 99),
    CONSTRAINT ck_sc_orders_totals CHECK (client_total_minor >= 0 AND server_total_minor >= 0),
    CONSTRAINT ck_sc_orders_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_sc_orders_menu_version CHECK (client_menu_version > 0),
    CONSTRAINT ck_sc_orders_delivery_address CHECK (order_type <> 'delivery' OR customer_address IS NOT NULL),
    CONSTRAINT ck_sc_orders_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_sc_orders_created
    ON sc_orders (created_at DESC, id);

CREATE INDEX ix_sc_orders_status_created
    ON sc_orders (status, created_at DESC);

CREATE TABLE sc_order_items (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    menu_version_id uuid NOT NULL,
    menu_item_id uuid NOT NULL,
    menu_item_public_id varchar(120) NOT NULL,
    item_name varchar(160) NOT NULL,
    unit_price_minor bigint NOT NULL,
    modifier_snapshot jsonb NOT NULL DEFAULT '[]'::jsonb,
    quantity integer NOT NULL,
    line_total_minor bigint NOT NULL,
    sort_order integer NOT NULL,
    CONSTRAINT fk_sc_order_items_order FOREIGN KEY (order_id, menu_version_id)
        REFERENCES sc_orders (id, menu_version_id) ON DELETE CASCADE,
    CONSTRAINT fk_sc_order_items_menu_item FOREIGN KEY (menu_version_id, menu_item_id)
        REFERENCES sc_menu_items (menu_version_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_sc_order_items_order_id UNIQUE (order_id, id),
    CONSTRAINT uq_sc_order_items_order_position UNIQUE (order_id, sort_order),
    CONSTRAINT ck_sc_order_items_public_id CHECK (menu_item_public_id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_sc_order_items_price CHECK (unit_price_minor >= 0 AND line_total_minor >= 0),
    CONSTRAINT ck_sc_order_items_modifiers CHECK (jsonb_typeof(modifier_snapshot) = 'array'),
    CONSTRAINT ck_sc_order_items_quantity CHECK (quantity BETWEEN 1 AND 99),
    CONSTRAINT ck_sc_order_items_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX ix_sc_order_items_order
    ON sc_order_items (order_id, sort_order);

CREATE TABLE sc_order_status_history (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    from_status varchar(32),
    to_status varchar(32) NOT NULL,
    actor_type varchar(32) NOT NULL,
    actor_id varchar(160),
    reason varchar(500),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_sc_order_status_history_order FOREIGN KEY (order_id)
        REFERENCES sc_orders (id) ON DELETE CASCADE,
    CONSTRAINT uq_sc_order_status_history_order_id UNIQUE (order_id, id),
    CONSTRAINT ck_sc_order_status_history_from_status CHECK (
        from_status IS NULL OR from_status IN ('new', 'confirmed', 'cancelled', 'completed')
    ),
    CONSTRAINT ck_sc_order_status_history_to_status CHECK (
        to_status IN ('new', 'confirmed', 'cancelled', 'completed')
    ),
    CONSTRAINT ck_sc_order_status_history_actor_type CHECK (
        actor_type IN ('system', 'customer', 'tenant_admin', 'platform_admin', 'worker')
    ),
    CONSTRAINT ck_sc_order_status_history_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX ix_sc_order_status_history_order
    ON sc_order_status_history (order_id, occurred_at, id);

CREATE TABLE sc_notification_destinations (
    id uuid PRIMARY KEY,
    channel varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    display_name varchar(160) NOT NULL,
    secret_reference varchar(512) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_sc_notification_destinations_name UNIQUE (channel, display_name),
    CONSTRAINT ck_sc_notification_destinations_channel CHECK (channel IN ('whatsapp', 'telegram', 'email', 'pos')),
    CONSTRAINT ck_sc_notification_destinations_status CHECK (status IN ('active', 'disabled')),
    CONSTRAINT ck_sc_notification_destinations_secret_reference CHECK (
        length(btrim(secret_reference)) > 0
        AND secret_reference !~ '^https?://'
    ),
    CONSTRAINT ck_sc_notification_destinations_metadata CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_sc_notification_destinations_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_sc_notification_destinations_active
    ON sc_notification_destinations (channel, status);

CREATE TABLE sc_outbox_events (
    id uuid PRIMARY KEY,
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
    CONSTRAINT fk_sc_outbox_events_order FOREIGN KEY (aggregate_id)
        REFERENCES sc_orders (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sc_outbox_events_aggregate_type CHECK (aggregate_type = 'order'),
    CONSTRAINT ck_sc_outbox_events_event_type CHECK (event_type ~ '^[a-z][a-z0-9]*(?:\.[a-z0-9]+)+$'),
    CONSTRAINT ck_sc_outbox_events_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_sc_outbox_events_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_sc_outbox_events_state CHECK (processing_state IN ('pending', 'processing', 'published', 'failed')),
    CONSTRAINT ck_sc_outbox_events_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_sc_outbox_events_lease CHECK (
        (processing_state = 'processing' AND leased_until IS NOT NULL AND lease_owner IS NOT NULL)
        OR processing_state <> 'processing'
    ),
    CONSTRAINT ck_sc_outbox_events_processed CHECK (
        (processing_state = 'published' AND processed_at IS NOT NULL)
        OR processing_state <> 'published'
    )
);

CREATE INDEX ix_sc_outbox_events_poll
    ON sc_outbox_events (processing_state, available_at, created_at, id)
    WHERE processing_state IN ('pending', 'processing');

CREATE INDEX ix_sc_outbox_events_aggregate
    ON sc_outbox_events (aggregate_type, aggregate_id, created_at);

CREATE TABLE sc_notification_deliveries (
    id uuid PRIMARY KEY,
    outbox_event_id uuid NOT NULL,
    destination_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    status varchar(32) NOT NULL,
    provider_message_id varchar(255),
    error_code varchar(120),
    error_message varchar(1000),
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT fk_sc_notification_deliveries_event FOREIGN KEY (outbox_event_id)
        REFERENCES sc_outbox_events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sc_notification_deliveries_destination FOREIGN KEY (destination_id)
        REFERENCES sc_notification_destinations (id) ON DELETE RESTRICT,
    CONSTRAINT uq_sc_notification_deliveries_attempt UNIQUE (
        outbox_event_id,
        destination_id,
        attempt_number
    ),
    CONSTRAINT ck_sc_notification_deliveries_attempt CHECK (attempt_number > 0),
    CONSTRAINT ck_sc_notification_deliveries_status CHECK (status IN ('started', 'succeeded', 'failed')),
    CONSTRAINT ck_sc_notification_deliveries_completion CHECK (
        (status IN ('succeeded', 'failed') AND completed_at IS NOT NULL)
        OR status = 'started'
    )
);

CREATE INDEX ix_sc_notification_deliveries_event
    ON sc_notification_deliveries (outbox_event_id, attempt_number);

CREATE OR REPLACE FUNCTION sc_refresh_single_client_shadow()
RETURNS void
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
BEGIN
    -- This call acquires the complete source-table write barrier and reruns every preflight.
    PERFORM sc_assert_single_client_source();

    -- Canonical object copy happens outside Flyway between V6 and V7. Preserve readiness
    -- only when the immutable source identity is unchanged; new/changed assets fail closed
    -- as legacy_only after refresh.
    CREATE TEMP TABLE IF NOT EXISTS sc_asset_refresh_state (
        id uuid PRIMARY KEY,
        asset_type varchar(64) NOT NULL,
        content_type varchar(127) NOT NULL,
        sha256 varchar(64) NOT NULL,
        storage_state varchar(32) NOT NULL
    ) ON COMMIT DROP;
    TRUNCATE TABLE pg_temp.sc_asset_refresh_state;
    INSERT INTO pg_temp.sc_asset_refresh_state (id, asset_type, content_type, sha256, storage_state)
    SELECT id, asset_type, content_type, sha256, storage_state
    FROM sc_assets;

    TRUNCATE TABLE
        sc_notification_deliveries,
        sc_outbox_events,
        sc_notification_destinations,
        sc_order_status_history,
        sc_order_items,
        sc_orders,
        sc_menu_items,
        sc_menu_categories,
        sc_menu_versions,
        sc_order_counter,
        sc_restaurants,
        sc_site_features,
        sc_site_profile,
        sc_site_branding,
        sc_assets,
        sc_site_configuration;

INSERT INTO sc_site_configuration (
    singleton_id, status, locale, currency, timezone, created_at, updated_at
)
SELECT 1, status, locale, currency, timezone, created_at, updated_at
FROM tenants;

INSERT INTO sc_assets (
    id, legacy_object_key, canonical_object_key, storage_state,
    asset_type, content_type, size_bytes, sha256, status, created_at
)
SELECT
    asset.id,
    asset.object_key,
    'assets/' || asset.asset_type || '/' || asset.sha256 || CASE asset.content_type
        WHEN 'image/png' THEN '.png'
        WHEN 'image/jpeg' THEN '.jpg'
        WHEN 'image/webp' THEN '.webp'
        WHEN 'image/x-icon' THEN '.ico'
        WHEN 'image/vnd.microsoft.icon' THEN '.ico'
    END,
    CASE
        WHEN previous.storage_state = 'canonical_ready' THEN 'canonical_ready'
        ELSE 'legacy_only'
    END,
    asset.asset_type,
    asset.content_type,
    asset.size_bytes,
    asset.sha256,
    asset.status,
    asset.created_at
FROM tenant_assets asset
LEFT JOIN pg_temp.sc_asset_refresh_state previous
    ON previous.id = asset.id
   AND previous.asset_type = asset.asset_type
   AND previous.content_type = asset.content_type
   AND previous.sha256 = asset.sha256;

INSERT INTO sc_site_branding (
    singleton_id, display_name, business_type, logo_asset_id, favicon_asset_id,
    design_tokens, localized_texts, hero, seo_metadata, updated_at
)
SELECT
    1, display_name, business_type, logo_asset_id, favicon_asset_id,
    design_tokens, localized_texts, hero, seo_metadata, updated_at
FROM tenant_branding;

INSERT INTO sc_site_profile (
    singleton_id, public_phone, public_phone_label, public_email, address_text,
    working_hours, working_hours_text, latitude, longitude, social_links, updated_at
)
SELECT
    1, public_phone, public_phone_label, public_email, address_text,
    working_hours, working_hours_text, latitude, longitude, social_links, updated_at
FROM tenant_profiles;

INSERT INTO sc_site_features (feature_key, enabled, config, updated_at)
SELECT feature_key, enabled, config, updated_at
FROM tenant_features;

INSERT INTO sc_restaurants (
    id, external_key, slug, display_name, status, is_default, created_at, updated_at
)
SELECT id, slug, slug, display_name, status, is_default, created_at, updated_at
FROM restaurants;

INSERT INTO sc_order_counter (singleton_id, next_value, updated_at)
SELECT 1, next_value, updated_at
FROM tenant_order_counters;

INSERT INTO sc_menu_versions (
    id, restaurant_id, version, status, published_at, created_at, updated_at
)
SELECT id, restaurant_id, version, status, published_at, created_at, updated_at
FROM menu_versions;

INSERT INTO sc_menu_categories (
    id, menu_version_id, public_id, name, description, image_asset_id, sort_order, available
)
SELECT id, menu_version_id, public_id, name, description, image_asset_id, sort_order, available
FROM menu_categories;

INSERT INTO sc_menu_items (
    id, menu_version_id, category_id, public_id, name, description, price_minor,
    currency, image_asset_id, available, sort_order
)
SELECT
    id, menu_version_id, category_id, public_id, name, description, price_minor,
    currency, image_asset_id, available, sort_order
FROM menu_items;

INSERT INTO sc_orders (
    id, restaurant_id, menu_version_id, order_number, idempotency_key,
    idempotency_payload_hash, status, order_type, channel, customer_name,
    customer_phone, customer_address, comment, guest_count, client_total_minor,
    server_total_minor, currency, client_created_at, server_received_at,
    client_menu_version, needs_confirmation, created_at, updated_at
)
SELECT
    id, restaurant_id, menu_version_id, order_number, idempotency_key,
    idempotency_payload_hash, status, order_type, channel, customer_name,
    customer_phone, customer_address, comment, guest_count, client_total_minor,
    server_total_minor, currency, client_created_at, server_received_at,
    client_menu_version, needs_confirmation, created_at, updated_at
FROM orders;

INSERT INTO sc_order_items (
    id, order_id, menu_version_id, menu_item_id, menu_item_public_id, item_name,
    unit_price_minor, modifier_snapshot, quantity, line_total_minor, sort_order
)
SELECT
    id, order_id, menu_version_id, menu_item_id, menu_item_public_id, item_name,
    unit_price_minor, modifier_snapshot, quantity, line_total_minor, sort_order
FROM order_items;

INSERT INTO sc_order_status_history (
    id, order_id, from_status, to_status, actor_type, actor_id, reason, metadata, occurred_at
)
SELECT id, order_id, from_status, to_status, actor_type, actor_id, reason, metadata, occurred_at
FROM order_status_history;

INSERT INTO sc_notification_destinations (
    id, channel, status, display_name, secret_reference, metadata, created_at, updated_at
)
SELECT id, channel, status, display_name, secret_reference, metadata, created_at, updated_at
FROM notification_destinations;

INSERT INTO sc_outbox_events (
    id, aggregate_type, aggregate_id, event_type, schema_version, payload,
    processing_state, attempt_count, available_at, leased_until, lease_owner,
    last_error_code, last_error_at, processed_at, created_at
)
SELECT
    id, aggregate_type, aggregate_id, event_type, schema_version, payload,
    processing_state, attempt_count, available_at, leased_until, lease_owner,
    last_error_code, last_error_at, processed_at, created_at
FROM outbox_events;

INSERT INTO sc_notification_deliveries (
    id, outbox_event_id, destination_id, attempt_number, status,
    provider_message_id, error_code, error_message, started_at, completed_at
)
SELECT
    id, outbox_event_id, destination_id, attempt_number, status,
    provider_message_id, error_code, error_message, started_at, completed_at
FROM notification_deliveries;

    -- Compare every copied column in both directions. EXCEPT ALL preserves duplicate
    -- cardinality, while the table marker prevents equal JSON rows from different tables
    -- from cancelling each other.
    IF EXISTS (
        WITH source_rows(table_name, row_data) AS (
            SELECT 'site_configuration', to_jsonb(mapped)
            FROM (
                SELECT 1::smallint AS singleton_id, status, locale, currency, timezone,
                       created_at, updated_at
                FROM tenants
            ) mapped
            UNION ALL
            SELECT 'assets', to_jsonb(mapped)
            FROM (
                SELECT id, object_key AS legacy_object_key,
                       'assets/' || asset_type || '/' || sha256 || CASE content_type
                           WHEN 'image/png' THEN '.png'
                           WHEN 'image/jpeg' THEN '.jpg'
                           WHEN 'image/webp' THEN '.webp'
                           WHEN 'image/x-icon' THEN '.ico'
                           WHEN 'image/vnd.microsoft.icon' THEN '.ico'
                       END AS canonical_object_key,
                       asset_type, content_type, size_bytes, sha256, status, created_at
                FROM tenant_assets
            ) mapped
            UNION ALL
            SELECT 'site_branding', to_jsonb(mapped)
            FROM (
                SELECT 1::smallint AS singleton_id, display_name, business_type,
                       logo_asset_id, favicon_asset_id, design_tokens, localized_texts,
                       hero, seo_metadata, updated_at
                FROM tenant_branding
            ) mapped
            UNION ALL
            SELECT 'site_profile', to_jsonb(mapped)
            FROM (
                SELECT 1::smallint AS singleton_id, public_phone, public_phone_label,
                       public_email, address_text, working_hours, working_hours_text,
                       latitude, longitude, social_links, updated_at
                FROM tenant_profiles
            ) mapped
            UNION ALL
            SELECT 'site_features', to_jsonb(mapped)
            FROM (
                SELECT feature_key, enabled, config, updated_at
                FROM tenant_features
            ) mapped
            UNION ALL
            SELECT 'restaurants', to_jsonb(mapped)
            FROM (
                SELECT id, slug AS external_key, slug, display_name, status, is_default,
                       created_at, updated_at
                FROM restaurants
            ) mapped
            UNION ALL
            SELECT 'order_counter', to_jsonb(mapped)
            FROM (
                SELECT 1::smallint AS singleton_id, next_value, updated_at
                FROM tenant_order_counters
            ) mapped
            UNION ALL
            SELECT 'menu_versions', to_jsonb(mapped)
            FROM (
                SELECT id, restaurant_id, version, status, published_at, created_at, updated_at
                FROM menu_versions
            ) mapped
            UNION ALL
            SELECT 'menu_categories', to_jsonb(mapped)
            FROM (
                SELECT id, menu_version_id, public_id, name, description, image_asset_id,
                       sort_order, available
                FROM menu_categories
            ) mapped
            UNION ALL
            SELECT 'menu_items', to_jsonb(mapped)
            FROM (
                SELECT id, menu_version_id, category_id, public_id, name, description,
                       price_minor, currency, image_asset_id, available, sort_order
                FROM menu_items
            ) mapped
            UNION ALL
            SELECT 'orders', to_jsonb(mapped)
            FROM (
                SELECT id, restaurant_id, menu_version_id, order_number, idempotency_key,
                       idempotency_payload_hash, status, order_type, channel, customer_name,
                       customer_phone, customer_address, comment, guest_count,
                       client_total_minor, server_total_minor, currency, client_created_at,
                       server_received_at, client_menu_version, needs_confirmation,
                       created_at, updated_at
                FROM orders
            ) mapped
            UNION ALL
            SELECT 'order_items', to_jsonb(mapped)
            FROM (
                SELECT id, order_id, menu_version_id, menu_item_id, menu_item_public_id,
                       item_name, unit_price_minor, modifier_snapshot, quantity,
                       line_total_minor, sort_order
                FROM order_items
            ) mapped
            UNION ALL
            SELECT 'order_status_history', to_jsonb(mapped)
            FROM (
                SELECT id, order_id, from_status, to_status, actor_type, actor_id,
                       reason, metadata, occurred_at
                FROM order_status_history
            ) mapped
            UNION ALL
            SELECT 'notification_destinations', to_jsonb(mapped)
            FROM (
                SELECT id, channel, status, display_name, secret_reference, metadata,
                       created_at, updated_at
                FROM notification_destinations
            ) mapped
            UNION ALL
            SELECT 'outbox_events', to_jsonb(mapped)
            FROM (
                SELECT id, aggregate_type, aggregate_id, event_type, schema_version,
                       payload, processing_state, attempt_count, available_at, leased_until,
                       lease_owner, last_error_code, last_error_at, processed_at, created_at
                FROM outbox_events
            ) mapped
            UNION ALL
            SELECT 'notification_deliveries', to_jsonb(mapped)
            FROM (
                SELECT id, outbox_event_id, destination_id, attempt_number, status,
                       provider_message_id, error_code, error_message, started_at, completed_at
                FROM notification_deliveries
            ) mapped
        ),
        shadow_rows(table_name, row_data) AS (
            SELECT 'site_configuration', to_jsonb(mapped)
            FROM (
                SELECT singleton_id, status, locale, currency, timezone, created_at, updated_at
                FROM sc_site_configuration
            ) mapped
            UNION ALL
            SELECT 'assets', to_jsonb(mapped)
            FROM (
                SELECT id, legacy_object_key, canonical_object_key, asset_type, content_type,
                       size_bytes, sha256, status, created_at
                FROM sc_assets
            ) mapped
            UNION ALL
            SELECT 'site_branding', to_jsonb(mapped)
            FROM (
                SELECT singleton_id, display_name, business_type, logo_asset_id,
                       favicon_asset_id, design_tokens, localized_texts, hero, seo_metadata,
                       updated_at
                FROM sc_site_branding
            ) mapped
            UNION ALL
            SELECT 'site_profile', to_jsonb(mapped)
            FROM (
                SELECT singleton_id, public_phone, public_phone_label, public_email,
                       address_text, working_hours, working_hours_text, latitude, longitude,
                       social_links, updated_at
                FROM sc_site_profile
            ) mapped
            UNION ALL
            SELECT 'site_features', to_jsonb(mapped)
            FROM (
                SELECT feature_key, enabled, config, updated_at
                FROM sc_site_features
            ) mapped
            UNION ALL
            SELECT 'restaurants', to_jsonb(mapped)
            FROM (
                SELECT id, external_key, slug, display_name, status, is_default,
                       created_at, updated_at
                FROM sc_restaurants
            ) mapped
            UNION ALL
            SELECT 'order_counter', to_jsonb(mapped)
            FROM (
                SELECT singleton_id, next_value, updated_at
                FROM sc_order_counter
            ) mapped
            UNION ALL
            SELECT 'menu_versions', to_jsonb(mapped)
            FROM (
                SELECT id, restaurant_id, version, status, published_at, created_at, updated_at
                FROM sc_menu_versions
            ) mapped
            UNION ALL
            SELECT 'menu_categories', to_jsonb(mapped)
            FROM (
                SELECT id, menu_version_id, public_id, name, description, image_asset_id,
                       sort_order, available
                FROM sc_menu_categories
            ) mapped
            UNION ALL
            SELECT 'menu_items', to_jsonb(mapped)
            FROM (
                SELECT id, menu_version_id, category_id, public_id, name, description,
                       price_minor, currency, image_asset_id, available, sort_order
                FROM sc_menu_items
            ) mapped
            UNION ALL
            SELECT 'orders', to_jsonb(mapped)
            FROM (
                SELECT id, restaurant_id, menu_version_id, order_number, idempotency_key,
                       idempotency_payload_hash, status, order_type, channel, customer_name,
                       customer_phone, customer_address, comment, guest_count,
                       client_total_minor, server_total_minor, currency, client_created_at,
                       server_received_at, client_menu_version, needs_confirmation,
                       created_at, updated_at
                FROM sc_orders
            ) mapped
            UNION ALL
            SELECT 'order_items', to_jsonb(mapped)
            FROM (
                SELECT id, order_id, menu_version_id, menu_item_id, menu_item_public_id,
                       item_name, unit_price_minor, modifier_snapshot, quantity,
                       line_total_minor, sort_order
                FROM sc_order_items
            ) mapped
            UNION ALL
            SELECT 'order_status_history', to_jsonb(mapped)
            FROM (
                SELECT id, order_id, from_status, to_status, actor_type, actor_id,
                       reason, metadata, occurred_at
                FROM sc_order_status_history
            ) mapped
            UNION ALL
            SELECT 'notification_destinations', to_jsonb(mapped)
            FROM (
                SELECT id, channel, status, display_name, secret_reference, metadata,
                       created_at, updated_at
                FROM sc_notification_destinations
            ) mapped
            UNION ALL
            SELECT 'outbox_events', to_jsonb(mapped)
            FROM (
                SELECT id, aggregate_type, aggregate_id, event_type, schema_version,
                       payload, processing_state, attempt_count, available_at, leased_until,
                       lease_owner, last_error_code, last_error_at, processed_at, created_at
                FROM sc_outbox_events
            ) mapped
            UNION ALL
            SELECT 'notification_deliveries', to_jsonb(mapped)
            FROM (
                SELECT id, outbox_event_id, destination_id, attempt_number, status,
                       provider_message_id, error_code, error_message, started_at, completed_at
                FROM sc_notification_deliveries
            ) mapped
        )
        SELECT 1
        FROM (
            (SELECT table_name, row_data FROM source_rows
             EXCEPT ALL
             SELECT table_name, row_data FROM shadow_rows)
            UNION ALL
            (SELECT table_name, row_data FROM shadow_rows
             EXCEPT ALL
             SELECT table_name, row_data FROM source_rows)
        ) mismatch
        LIMIT 1
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_DB_001_SHADOW_DATA_MISMATCH';
    END IF;
END
$$;

REVOKE ALL ON FUNCTION sc_refresh_single_client_shadow() FROM PUBLIC;
SELECT sc_refresh_single_client_shadow();
