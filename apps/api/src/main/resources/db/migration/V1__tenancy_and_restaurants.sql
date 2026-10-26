CREATE TABLE tenants (
    id uuid PRIMARY KEY,
    slug varchar(63) NOT NULL,
    status varchar(32) NOT NULL,
    locale varchar(16) NOT NULL,
    currency varchar(3) NOT NULL,
    timezone varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenants_slug UNIQUE (slug),
    CONSTRAINT ck_tenants_slug CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_tenants_status CHECK (status IN ('active', 'suspended', 'deleted')),
    CONSTRAINT ck_tenants_locale CHECK (locale ~ '^[a-z]{2,3}(?:-[A-Z]{2})?$'),
    CONSTRAINT ck_tenants_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_tenants_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_tenants_status ON tenants (status);

CREATE TABLE tenant_domains (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    hostname varchar(253) NOT NULL,
    domain_type varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    canonical boolean NOT NULL DEFAULT false,
    verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_tenant_domains_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT uq_tenant_domains_hostname UNIQUE (hostname),
    CONSTRAINT uq_tenant_domains_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_tenant_domains_hostname CHECK (
        hostname = lower(hostname)
        AND hostname !~ ':\d+$'
        AND hostname !~ '\.$'
        AND hostname ~ '^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$'
    ),
    CONSTRAINT ck_tenant_domains_type CHECK (domain_type IN ('platform_subdomain', 'custom', 'local')),
    CONSTRAINT ck_tenant_domains_status CHECK (status IN ('pending', 'active', 'disabled')),
    CONSTRAINT ck_tenant_domains_verified CHECK (
        (status = 'active' AND verified_at IS NOT NULL)
        OR status <> 'active'
    ),
    CONSTRAINT ck_tenant_domains_canonical CHECK (NOT canonical OR status = 'active'),
    CONSTRAINT ck_tenant_domains_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_tenant_domains_canonical
    ON tenant_domains (tenant_id)
    WHERE canonical AND status = 'active';

CREATE INDEX ix_tenant_domains_tenant_status
    ON tenant_domains (tenant_id, status);

CREATE INDEX ix_tenant_domains_resolution
    ON tenant_domains (hostname, status, tenant_id);

CREATE TABLE tenant_branding (
    tenant_id uuid PRIMARY KEY,
    display_name varchar(160) NOT NULL,
    business_type varchar(80) NOT NULL,
    logo_asset_id uuid,
    favicon_asset_id uuid,
    design_tokens jsonb NOT NULL DEFAULT '{}'::jsonb,
    localized_texts jsonb NOT NULL DEFAULT '[]'::jsonb,
    hero jsonb NOT NULL DEFAULT '{}'::jsonb,
    seo_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_tenant_branding_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT ck_tenant_branding_design_tokens CHECK (jsonb_typeof(design_tokens) = 'object'),
    CONSTRAINT ck_tenant_branding_localized_texts CHECK (jsonb_typeof(localized_texts) = 'array'),
    CONSTRAINT ck_tenant_branding_hero CHECK (jsonb_typeof(hero) = 'object'),
    CONSTRAINT ck_tenant_branding_seo CHECK (jsonb_typeof(seo_metadata) = 'object')
);

CREATE TABLE tenant_profiles (
    tenant_id uuid PRIMARY KEY,
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
    CONSTRAINT fk_tenant_profiles_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT ck_tenant_profiles_working_hours CHECK (jsonb_typeof(working_hours) = 'array'),
    CONSTRAINT ck_tenant_profiles_social_links CHECK (jsonb_typeof(social_links) = 'array'),
    CONSTRAINT ck_tenant_profiles_location_pair CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL)
    ),
    CONSTRAINT ck_tenant_profiles_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_tenant_profiles_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

CREATE TABLE tenant_features (
    tenant_id uuid NOT NULL,
    feature_key varchar(80) NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, feature_key),
    CONSTRAINT fk_tenant_features_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT ck_tenant_features_key CHECK (feature_key ~ '^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$'),
    CONSTRAINT ck_tenant_features_config CHECK (jsonb_typeof(config) = 'object')
);

CREATE TABLE restaurants (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    slug varchar(63) NOT NULL,
    display_name varchar(160) NOT NULL,
    status varchar(32) NOT NULL,
    is_default boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_restaurants_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT uq_restaurants_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_restaurants_tenant_slug UNIQUE (tenant_id, slug),
    CONSTRAINT ck_restaurants_slug CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_restaurants_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT ck_restaurants_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_restaurants_default
    ON restaurants (tenant_id)
    WHERE is_default;

CREATE INDEX ix_restaurants_tenant_status
    ON restaurants (tenant_id, status);

CREATE TABLE tenant_order_counters (
    tenant_id uuid PRIMARY KEY,
    next_value bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_tenant_order_counters_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT ck_tenant_order_counters_next_value CHECK (next_value > 0)
);
