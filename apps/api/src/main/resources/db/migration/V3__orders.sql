CREATE TABLE orders (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
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
    CONSTRAINT fk_orders_restaurant FOREIGN KEY (tenant_id, restaurant_id)
        REFERENCES restaurants (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_orders_menu_version FOREIGN KEY (
        tenant_id,
        restaurant_id,
        menu_version_id,
        client_menu_version
    ) REFERENCES menu_versions (tenant_id, restaurant_id, id, version) ON DELETE RESTRICT,
    CONSTRAINT uq_orders_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_orders_tenant_id_menu_version_id UNIQUE (tenant_id, id, menu_version_id),
    CONSTRAINT uq_orders_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_orders_number UNIQUE (tenant_id, order_number),
    CONSTRAINT ck_orders_number CHECK (order_number > 0),
    CONSTRAINT ck_orders_idempotency_hash CHECK (idempotency_payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_orders_status CHECK (status IN ('new', 'confirmed', 'cancelled', 'completed')),
    CONSTRAINT ck_orders_type CHECK (order_type IN ('preorder', 'delivery', 'pickup')),
    CONSTRAINT ck_orders_channel CHECK (channel IN ('web', 'admin', 'import')),
    CONSTRAINT ck_orders_guest_count CHECK (guest_count IS NULL OR guest_count BETWEEN 1 AND 99),
    CONSTRAINT ck_orders_totals CHECK (client_total_minor >= 0 AND server_total_minor >= 0),
    CONSTRAINT ck_orders_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_orders_menu_version CHECK (client_menu_version > 0),
    CONSTRAINT ck_orders_delivery_address CHECK (order_type <> 'delivery' OR customer_address IS NOT NULL),
    CONSTRAINT ck_orders_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_orders_tenant_created
    ON orders (tenant_id, created_at DESC, id);

CREATE INDEX ix_orders_tenant_status_created
    ON orders (tenant_id, status, created_at DESC);

CREATE TABLE order_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
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
    CONSTRAINT fk_order_items_order FOREIGN KEY (tenant_id, order_id, menu_version_id)
        REFERENCES orders (tenant_id, id, menu_version_id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_menu_item FOREIGN KEY (tenant_id, menu_version_id, menu_item_id)
        REFERENCES menu_items (tenant_id, menu_version_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_order_items_tenant_order_id UNIQUE (tenant_id, order_id, id),
    CONSTRAINT uq_order_items_order_position UNIQUE (tenant_id, order_id, sort_order),
    CONSTRAINT ck_order_items_public_id CHECK (menu_item_public_id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_order_items_price CHECK (unit_price_minor >= 0 AND line_total_minor >= 0),
    CONSTRAINT ck_order_items_modifiers CHECK (jsonb_typeof(modifier_snapshot) = 'array'),
    CONSTRAINT ck_order_items_quantity CHECK (quantity BETWEEN 1 AND 99),
    CONSTRAINT ck_order_items_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX ix_order_items_order
    ON order_items (tenant_id, order_id, sort_order);

CREATE TABLE order_status_history (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    from_status varchar(32),
    to_status varchar(32) NOT NULL,
    actor_type varchar(32) NOT NULL,
    actor_id varchar(160),
    reason varchar(500),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_order_status_history_tenant_order_id UNIQUE (tenant_id, order_id, id),
    CONSTRAINT ck_order_status_history_from_status CHECK (
        from_status IS NULL OR from_status IN ('new', 'confirmed', 'cancelled', 'completed')
    ),
    CONSTRAINT ck_order_status_history_to_status CHECK (
        to_status IN ('new', 'confirmed', 'cancelled', 'completed')
    ),
    CONSTRAINT ck_order_status_history_actor_type CHECK (
        actor_type IN ('system', 'customer', 'tenant_admin', 'platform_admin', 'worker')
    ),
    CONSTRAINT ck_order_status_history_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX ix_order_status_history_order
    ON order_status_history (tenant_id, order_id, occurred_at, id);
