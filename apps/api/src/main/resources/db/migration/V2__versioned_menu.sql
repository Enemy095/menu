CREATE TABLE menu_versions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    restaurant_id uuid NOT NULL,
    version bigint NOT NULL,
    status varchar(32) NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_menu_versions_restaurant FOREIGN KEY (tenant_id, restaurant_id)
        REFERENCES restaurants (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_menu_versions_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_menu_versions_restaurant_version UNIQUE (tenant_id, restaurant_id, version),
    CONSTRAINT uq_menu_versions_full_reference UNIQUE (tenant_id, restaurant_id, id, version),
    CONSTRAINT ck_menu_versions_version CHECK (version > 0),
    CONSTRAINT ck_menu_versions_status CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT ck_menu_versions_published_at CHECK (
        (status = 'published' AND published_at IS NOT NULL)
        OR status <> 'published'
    ),
    CONSTRAINT ck_menu_versions_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_menu_versions_current_published
    ON menu_versions (tenant_id, restaurant_id)
    WHERE status = 'published';

CREATE INDEX ix_menu_versions_current_read
    ON menu_versions (tenant_id, restaurant_id, status, published_at DESC);

CREATE TABLE menu_categories (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    menu_version_id uuid NOT NULL,
    public_id varchar(120) NOT NULL,
    name varchar(120) NOT NULL,
    description varchar(500),
    image_asset_id uuid,
    sort_order integer NOT NULL,
    available boolean NOT NULL DEFAULT true,
    CONSTRAINT fk_menu_categories_version FOREIGN KEY (tenant_id, menu_version_id)
        REFERENCES menu_versions (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_menu_categories_tenant_version_id UNIQUE (tenant_id, menu_version_id, id),
    CONSTRAINT uq_menu_categories_public_id UNIQUE (tenant_id, menu_version_id, public_id),
    CONSTRAINT ck_menu_categories_public_id CHECK (public_id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_menu_categories_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX ix_menu_categories_ordered
    ON menu_categories (tenant_id, menu_version_id, sort_order, public_id);

CREATE TABLE menu_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
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
    CONSTRAINT fk_menu_items_version FOREIGN KEY (tenant_id, menu_version_id)
        REFERENCES menu_versions (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_menu_items_category FOREIGN KEY (tenant_id, menu_version_id, category_id)
        REFERENCES menu_categories (tenant_id, menu_version_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_menu_items_tenant_version_id UNIQUE (tenant_id, menu_version_id, id),
    CONSTRAINT uq_menu_items_public_id UNIQUE (tenant_id, menu_version_id, public_id),
    CONSTRAINT ck_menu_items_public_id CHECK (public_id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_menu_items_price CHECK (price_minor >= 0),
    CONSTRAINT ck_menu_items_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_menu_items_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX ix_menu_items_ordered
    ON menu_items (tenant_id, menu_version_id, category_id, sort_order, public_id);
