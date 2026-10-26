-- SC-BE-001 switch/contract phase. Flyway runs this migration transactionally.
-- Old readers, writers and workers must be stopped before this migration starts.

SELECT pg_advisory_xact_lock(1396917553, 1111838769);

-- The approval fingerprint serializes timestamptz values. Pin the representation so a
-- connection timezone cannot change the cutover candidate identity.
SET LOCAL TIME ZONE 'UTC';

-- V6 intentionally omits size_bytes from its ready-state identity. Capture the complete
-- identity before the final refresh and reject a readiness carry-over after any change.
CREATE TEMP TABLE sc_v7_ready_assets ON COMMIT DROP AS
SELECT
    id,
    asset_type,
    content_type,
    sha256,
    size_bytes,
    canonical_object_key
FROM sc_assets
WHERE storage_state = 'canonical_ready';

SELECT sc_refresh_single_client_shadow();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_temp.sc_v7_ready_assets previous
        LEFT JOIN sc_assets refreshed ON refreshed.id = previous.id
        WHERE refreshed.id IS NULL
           OR refreshed.storage_state <> 'canonical_ready'
           OR refreshed.asset_type IS DISTINCT FROM previous.asset_type
           OR refreshed.content_type IS DISTINCT FROM previous.content_type
           OR refreshed.sha256 IS DISTINCT FROM previous.sha256
           OR refreshed.size_bytes IS DISTINCT FROM previous.size_bytes
           OR refreshed.canonical_object_key IS DISTINCT FROM previous.canonical_object_key
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_ASSET_READINESS_IDENTITY_CHANGED';
    END IF;

    IF (SELECT count(*) FROM sc_site_configuration) = 0 THEN
        IF EXISTS (SELECT 1 FROM sc_assets)
           OR EXISTS (SELECT 1 FROM sc_site_branding)
           OR EXISTS (SELECT 1 FROM sc_site_profile)
           OR EXISTS (SELECT 1 FROM sc_site_features)
           OR EXISTS (SELECT 1 FROM sc_restaurants)
           OR EXISTS (SELECT 1 FROM sc_order_counter)
           OR EXISTS (SELECT 1 FROM sc_menu_versions)
           OR EXISTS (SELECT 1 FROM sc_menu_categories)
           OR EXISTS (SELECT 1 FROM sc_menu_items)
           OR EXISTS (SELECT 1 FROM sc_orders)
           OR EXISTS (SELECT 1 FROM sc_order_items)
           OR EXISTS (SELECT 1 FROM sc_order_status_history)
           OR EXISTS (SELECT 1 FROM sc_notification_destinations)
           OR EXISTS (SELECT 1 FROM sc_outbox_events)
           OR EXISTS (SELECT 1 FROM sc_notification_deliveries) THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'SC_BE_001_EMPTY_INSTALLATION_NOT_EMPTY';
        END IF;
    ELSE
        IF (SELECT count(*) FROM sc_site_configuration) <> 1
           OR EXISTS (SELECT 1 FROM sc_site_configuration WHERE status <> 'active') THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'SC_BE_001_ACTIVE_SITE_REQUIRED';
        END IF;
        IF (SELECT count(*) FROM sc_site_branding) <> 1
           OR (SELECT count(*) FROM sc_site_profile) <> 1 THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'SC_BE_001_SITE_PROJECTION_INVALID';
        END IF;
        IF (SELECT count(*) FROM sc_restaurants WHERE status = 'active' AND is_default) <> 1 THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'SC_BE_001_ACTIVE_DEFAULT_RESTAURANT_REQUIRED';
        END IF;
        IF (
            SELECT count(*)
            FROM sc_menu_versions version
            JOIN sc_restaurants restaurant ON restaurant.id = version.restaurant_id
            WHERE restaurant.status = 'active'
              AND restaurant.is_default
              AND version.status = 'published'
        ) <> 1 THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'SC_BE_001_CURRENT_MENU_REQUIRED';
        END IF;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM sc_assets
        WHERE storage_state <> 'canonical_ready'
           OR canonical_object_key <> 'assets/' || asset_type || '/' || sha256 || CASE content_type
               WHEN 'image/png' THEN '.png'
               WHEN 'image/jpeg' THEN '.jpg'
               WHEN 'image/webp' THEN '.webp'
               WHEN 'image/x-icon' THEN '.ico'
               WHEN 'image/vnd.microsoft.icon' THEN '.ico'
           END
           OR asset_type NOT IN (
               'logo', 'favicon', 'hero', 'open_graph', 'menu_category_image', 'menu_item_image'
           )
           OR size_bytes <= 0
           OR (asset_type = 'logo' AND (
               size_bytes > 5242880 OR content_type NOT IN ('image/png', 'image/jpeg', 'image/webp')
           ))
           OR (asset_type = 'favicon' AND (
               size_bytes > 1048576
               OR content_type NOT IN ('image/png', 'image/x-icon', 'image/vnd.microsoft.icon')
           ))
           OR (asset_type IN ('hero', 'open_graph', 'menu_category_image', 'menu_item_image') AND (
               size_bytes > 10485760 OR content_type NOT IN ('image/png', 'image/jpeg', 'image/webp')
           ))
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_CANONICAL_ASSET_REQUIRED';
    END IF;

    -- MenuResponse owns its currency after cutover. A populated version can only inherit
    -- one item currency and that currency must equal the singleton site currency. Empty
    -- draft/archived versions inherit the same site currency explicitly below.
    IF (EXISTS (SELECT 1 FROM sc_menu_versions)
            AND (SELECT count(*) FROM sc_site_configuration) <> 1)
       OR EXISTS (
            SELECT 1
            FROM sc_menu_versions version
            CROSS JOIN sc_site_configuration site
            LEFT JOIN sc_menu_items item ON item.menu_version_id = version.id
            GROUP BY version.id, site.currency
            HAVING site.currency !~ '^[A-Z]{3}$'
                OR count(DISTINCT item.currency) > 1
                OR bool_or(item.currency IS NOT NULL AND item.currency !~ '^[A-Z]{3}$')
                OR (count(item.id) > 0 AND min(item.currency) <> site.currency)
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_MENU_CURRENCY_INVALID';
    END IF;

    IF EXISTS (SELECT 1 FROM legacy_import_runs)
       OR EXISTS (SELECT 1 FROM legacy_import_run_tenants)
       OR EXISTS (SELECT 1 FROM legacy_import_run_assets) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_LEGACY_IMPORT_LEDGER_NOT_EMPTY';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM sc_order_status_history
        WHERE actor_type IN ('tenant_admin', 'platform_admin')
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_LEGACY_ORDER_ACTOR';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM sc_outbox_events
        WHERE payload::text ~ '"(tenant|tenant_id|tenantId|tenant_slug|tenantSlug|tenant_domain|tenantDomain|tenant_host|tenantHost|platform_host|platformHost)"[[:space:]]*:'
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_TENANT_ROUTING_PAYLOAD';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM sc_notification_destinations
        WHERE length(secret_reference) > 512
           OR secret_reference <> btrim(secret_reference)
           OR secret_reference ~ '^[[:space:]]|[[:space:]]$'
           OR secret_reference LIKE 'required-secret:%'
           OR secret_reference !~* '^(secret|vault)://[^/@?#[:space:]]+/[^?#[:space:]]+$'
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_NOTIFICATION_DESTINATION_INVALID';
    END IF;

    -- pending is a durable backlog owned by the future worker and is preserved unchanged.
    -- Only actively leased/started work has an ambiguous in-flight outcome at cutover.
    IF EXISTS (SELECT 1 FROM sc_outbox_events WHERE processing_state = 'processing')
       OR EXISTS (SELECT 1 FROM sc_notification_deliveries WHERE status = 'started') THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_OUTBOX_RECONCILIATION_REQUIRED';
    END IF;
END
$$;

-- SQL-side fail-closed validation for the persisted public projection. The Java cutover
-- validator remains the canonical mapper; these checks prevent a malformed candidate
-- from reaching destructive DDL even if an approval value is supplied incorrectly.
DO $$
DECLARE
    branding sc_site_branding%ROWTYPE;
    profile sc_site_profile%ROWTYPE;
    limits jsonb;
BEGIN
    IF (SELECT count(*) FROM sc_site_configuration) = 0 THEN
        RETURN;
    END IF;

    SELECT * INTO STRICT branding FROM sc_site_branding;
    SELECT * INTO STRICT profile FROM sc_site_profile;

    IF length(btrim(branding.display_name)) NOT BETWEEN 1 AND 160
       OR length(btrim(branding.business_type)) NOT BETWEEN 1 AND 80
       OR branding.logo_asset_id IS NULL
       OR NOT EXISTS (
            SELECT 1 FROM sc_assets asset
            WHERE asset.id = branding.logo_asset_id
              AND asset.status = 'active'
              AND asset.asset_type = 'logo'
       )
       OR (branding.favicon_asset_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM sc_assets asset
            WHERE asset.id = branding.favicon_asset_id
              AND asset.status = 'active'
              AND asset.asset_type = 'favicon'
       )) THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;

    IF jsonb_typeof(branding.design_tokens) <> 'object'
       OR (SELECT count(*) FROM jsonb_object_keys(branding.design_tokens)) <> 9
       OR NOT branding.design_tokens ?& ARRAY[
            'primaryColor', 'secondaryColor', 'accentColor', 'backgroundColor', 'surfaceColor',
            'textColor', 'mutedTextColor', 'borderRadius', 'fontFamily'
       ]
       OR EXISTS (
            SELECT 1
            FROM unnest(ARRAY[
                'primaryColor', 'secondaryColor', 'accentColor', 'backgroundColor',
                'surfaceColor', 'textColor', 'mutedTextColor'
            ]) field_name
            WHERE jsonb_typeof(branding.design_tokens -> field_name) <> 'string'
               OR branding.design_tokens ->> field_name !~ '^#[0-9A-Fa-f]{6}$'
       )
       OR jsonb_typeof(branding.design_tokens -> 'borderRadius') <> 'string'
       OR branding.design_tokens ->> 'borderRadius' !~ '^[0-9]+(?:\.[0-9]+)?(?:px|rem)$'
       OR length(branding.design_tokens ->> 'borderRadius') > 32
       OR jsonb_typeof(branding.design_tokens -> 'fontFamily') <> 'string'
       OR length(btrim(branding.design_tokens ->> 'fontFamily')) NOT BETWEEN 1 AND 120 THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;

    IF jsonb_typeof(branding.localized_texts) <> 'array'
       OR jsonb_array_length(branding.localized_texts) = 0
       OR EXISTS (
            SELECT 1
            FROM jsonb_array_elements(branding.localized_texts) localized(value)
            WHERE CASE
                WHEN jsonb_typeof(localized.value) <> 'object' THEN true
                ELSE (SELECT count(*) FROM jsonb_object_keys(localized.value)) <> 10
                  OR NOT localized.value ?& ARRAY[
                      'locale', 'displayName', 'shortName', 'businessTypeLabel', 'cuisineText',
                      'slogan', 'introText', 'menuSectionTitle', 'cartTitle', 'orderTypeLegend'
                  ]
                  OR jsonb_typeof(localized.value -> 'locale') <> 'string'
                  OR localized.value ->> 'locale' !~ '^[a-z]{2,3}(?:-[A-Z]{2})?$'
                  OR jsonb_typeof(localized.value -> 'displayName') <> 'string'
                  OR length(btrim(localized.value ->> 'displayName')) NOT BETWEEN 1 AND 120
                  OR jsonb_typeof(localized.value -> 'shortName') <> 'string'
                  OR length(btrim(localized.value ->> 'shortName')) NOT BETWEEN 1 AND 80
                  OR jsonb_typeof(localized.value -> 'businessTypeLabel') <> 'string'
                  OR length(btrim(localized.value ->> 'businessTypeLabel')) NOT BETWEEN 1 AND 80
                  OR jsonb_typeof(localized.value -> 'cuisineText') <> 'string'
                  OR length(localized.value ->> 'cuisineText') > 160
                  OR jsonb_typeof(localized.value -> 'slogan') <> 'string'
                  OR length(localized.value ->> 'slogan') > 200
                  OR jsonb_typeof(localized.value -> 'introText') <> 'string'
                  OR length(localized.value ->> 'introText') > 500
                  OR jsonb_typeof(localized.value -> 'menuSectionTitle') <> 'string'
                  OR length(btrim(localized.value ->> 'menuSectionTitle')) NOT BETWEEN 1 AND 120
                  OR jsonb_typeof(localized.value -> 'cartTitle') <> 'string'
                  OR length(btrim(localized.value ->> 'cartTitle')) NOT BETWEEN 1 AND 120
                  OR jsonb_typeof(localized.value -> 'orderTypeLegend') <> 'string'
                  OR length(btrim(localized.value ->> 'orderTypeLegend')) NOT BETWEEN 1 AND 160
            END
       ) THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;

    IF jsonb_typeof(branding.hero) <> 'object'
       OR EXISTS (
            SELECT 1 FROM jsonb_object_keys(branding.hero) field_name
            WHERE field_name <> ALL (ARRAY[
                'imageAssetId', 'imageAlt', 'eyebrow', 'title', 'subtitle', 'ctaLabel', 'benefits'
            ])
       )
       OR NOT branding.hero ?& ARRAY['title', 'ctaLabel']
       OR jsonb_typeof(branding.hero -> 'title') <> 'string'
       OR length(btrim(branding.hero ->> 'title')) NOT BETWEEN 1 AND 160
       OR jsonb_typeof(branding.hero -> 'ctaLabel') <> 'string'
       OR length(btrim(branding.hero ->> 'ctaLabel')) NOT BETWEEN 1 AND 80
       OR (branding.hero ? 'imageAlt' AND (
            jsonb_typeof(branding.hero -> 'imageAlt') <> 'string'
            OR length(branding.hero ->> 'imageAlt') > 160
       ))
       OR (branding.hero ? 'eyebrow' AND (
            jsonb_typeof(branding.hero -> 'eyebrow') <> 'string'
            OR length(branding.hero ->> 'eyebrow') > 100
       ))
       OR (branding.hero ? 'subtitle' AND (
            jsonb_typeof(branding.hero -> 'subtitle') <> 'string'
            OR length(branding.hero ->> 'subtitle') > 240
       ))
       OR (branding.hero ? 'benefits' AND (
            jsonb_typeof(branding.hero -> 'benefits') <> 'array'
            OR CASE WHEN jsonb_typeof(branding.hero -> 'benefits') = 'array'
                    THEN jsonb_array_length(branding.hero -> 'benefits') > 6
                    ELSE false END
       ))
       OR EXISTS (
            SELECT 1
            FROM jsonb_array_elements(
                CASE WHEN jsonb_typeof(branding.hero -> 'benefits') = 'array'
                     THEN branding.hero -> 'benefits' ELSE '[]'::jsonb END
            ) benefit(value)
            WHERE CASE
                WHEN jsonb_typeof(benefit.value) <> 'object' THEN true
                ELSE (SELECT count(*) FROM jsonb_object_keys(benefit.value)) <> 2
                  OR NOT benefit.value ?& ARRAY['icon', 'text']
                  OR jsonb_typeof(benefit.value -> 'icon') <> 'string'
                  OR length(btrim(benefit.value ->> 'icon')) NOT BETWEEN 1 AND 16
                  OR jsonb_typeof(benefit.value -> 'text') <> 'string'
                  OR length(btrim(benefit.value ->> 'text')) NOT BETWEEN 1 AND 100
            END
       )
       OR (branding.hero ? 'imageAssetId' AND NOT EXISTS (
            SELECT 1 FROM sc_assets asset
            WHERE asset.id::text = lower(branding.hero ->> 'imageAssetId')
              AND branding.hero ->> 'imageAssetId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
              AND asset.status = 'active'
              AND asset.asset_type = 'hero'
       )) THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;

    IF jsonb_typeof(branding.seo_metadata) <> 'object'
       OR EXISTS (
            SELECT 1 FROM jsonb_object_keys(branding.seo_metadata) field_name
            WHERE field_name <> ALL (ARRAY['title', 'description', 'canonicalUrl', 'openGraphAssetId'])
       )
       OR NOT branding.seo_metadata ?& ARRAY['title', 'description', 'canonicalUrl']
       OR jsonb_typeof(branding.seo_metadata -> 'title') <> 'string'
       OR length(btrim(branding.seo_metadata ->> 'title')) NOT BETWEEN 1 AND 120
       OR jsonb_typeof(branding.seo_metadata -> 'description') <> 'string'
       OR length(btrim(branding.seo_metadata ->> 'description')) NOT BETWEEN 1 AND 320
       OR jsonb_typeof(branding.seo_metadata -> 'canonicalUrl') <> 'string'
       OR length(branding.seo_metadata ->> 'canonicalUrl') NOT BETWEEN 1 AND 2048
       OR branding.seo_metadata ->> 'canonicalUrl'
            !~* '^https?://[^/@?#[:space:]]+(/[^#[:space:]]*)?(\?[^#[:space:]]*)?$'
       OR (branding.seo_metadata ? 'openGraphAssetId' AND NOT EXISTS (
            SELECT 1 FROM sc_assets asset
            WHERE asset.id::text = lower(branding.seo_metadata ->> 'openGraphAssetId')
              AND branding.seo_metadata ->> 'openGraphAssetId'
                    ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
              AND asset.status = 'active'
              AND asset.asset_type = 'open_graph'
       )) THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;

    IF profile.public_phone IS NULL
       OR length(btrim(profile.public_phone)) NOT BETWEEN 5 AND 32
       OR profile.public_phone_label IS NULL
       OR length(btrim(profile.public_phone_label)) NOT BETWEEN 1 AND 80
       OR (profile.public_email IS NOT NULL AND length(profile.public_email) > 254)
       OR (profile.address_text IS NOT NULL AND length(profile.address_text) > 240)
       OR profile.working_hours_text IS NULL
       OR length(btrim(profile.working_hours_text)) NOT BETWEEN 1 AND 240
       OR ((profile.latitude IS NOT NULL OR profile.longitude IS NOT NULL)
           AND (profile.address_text IS NULL OR btrim(profile.address_text) = ''))
       OR jsonb_typeof(profile.social_links) <> 'array'
       OR jsonb_array_length(profile.social_links) > 20 THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM jsonb_array_elements(profile.social_links) link(value)
        WHERE CASE
            WHEN jsonb_typeof(link.value) <> 'object' THEN true
            ELSE (SELECT count(*) FROM jsonb_object_keys(link.value)) <> 3
              OR NOT link.value ?& ARRAY['type', 'label', 'url']
              OR jsonb_typeof(link.value -> 'type') <> 'string'
              OR link.value ->> 'type' NOT IN ('website', 'instagram', 'telegram', 'vk', 'facebook', 'other')
              OR jsonb_typeof(link.value -> 'label') <> 'string'
              OR length(btrim(link.value ->> 'label')) NOT BETWEEN 1 AND 80
              OR jsonb_typeof(link.value -> 'url') <> 'string'
              OR length(link.value ->> 'url') NOT BETWEEN 1 AND 2048
              OR link.value ->> 'url'
                    !~* '^https?://[^/@?#[:space:]]+(/[^#[:space:]]*)?(\?[^#[:space:]]*)?$'
        END
    ) THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;

    IF jsonb_typeof(profile.working_hours) <> 'array'
       OR EXISTS (
            SELECT 1
            FROM jsonb_array_elements(profile.working_hours) interval(value)
            WHERE CASE
                WHEN jsonb_typeof(interval.value) <> 'object' THEN true
                ELSE (SELECT count(*) FROM jsonb_object_keys(interval.value)) <> 3
                  OR NOT interval.value ?& ARRAY['day', 'opensAt', 'closesAt']
                  OR jsonb_typeof(interval.value -> 'day') <> 'string'
                  OR interval.value ->> 'day' NOT IN (
                      'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'
                  )
                  OR jsonb_typeof(interval.value -> 'opensAt') <> 'string'
                  OR interval.value ->> 'opensAt' !~ '^(?:[01][0-9]|2[0-3]):[0-5][0-9]$'
                  OR jsonb_typeof(interval.value -> 'closesAt') <> 'string'
                  OR interval.value ->> 'closesAt' !~ '^(?:[01][0-9]|2[0-3]):[0-5][0-9]$'
                  OR interval.value ->> 'opensAt' >= interval.value ->> 'closesAt'
            END
       )
       OR EXISTS (
            SELECT 1
            FROM jsonb_array_elements(profile.working_hours) interval(value)
            GROUP BY interval.value ->> 'day'
            HAVING count(*) > 8
       )
       OR EXISTS (
            WITH intervals AS (
                SELECT ordinality, value
                FROM jsonb_array_elements(profile.working_hours) WITH ORDINALITY interval(value, ordinality)
            )
            SELECT 1
            FROM intervals left_interval
            JOIN intervals right_interval ON left_interval.ordinality < right_interval.ordinality
            WHERE left_interval.value ->> 'day' = right_interval.value ->> 'day'
              AND left_interval.value ->> 'opensAt' <= right_interval.value ->> 'closesAt'
              AND right_interval.value ->> 'opensAt' <= left_interval.value ->> 'closesAt'
       ) THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;

    IF (SELECT count(*) FROM sc_site_features WHERE feature_key = 'orders') <> 1
       OR EXISTS (
            SELECT 1 FROM sc_site_features feature
            WHERE feature.feature_key = 'orders'
              AND ((SELECT count(*) FROM jsonb_object_keys(feature.config)) <> 1
                   OR NOT feature.config ? 'limits'
                   OR jsonb_typeof(feature.config -> 'limits') <> 'object')
       )
       OR EXISTS (
            SELECT 1 FROM sc_site_features feature
            WHERE feature.feature_key IN ('preorder', 'delivery', 'pickup')
              AND ((SELECT count(*) FROM jsonb_object_keys(feature.config)) <> 2
                   OR NOT feature.config ?& ARRAY['label', 'default']
                   OR jsonb_typeof(feature.config -> 'label') <> 'string'
                   OR length(btrim(feature.config ->> 'label')) NOT BETWEEN 1 AND 80
                   OR jsonb_typeof(feature.config -> 'default') <> 'boolean')
       )
       OR EXISTS (
            SELECT 1 FROM sc_site_features feature
            WHERE feature.feature_key IN (
                'contacts', 'geo_location', 'social_links', 'custom_theme', 'whatsapp_fallback',
                'promotions', 'loyalty', 'reservations', 'delivery_blocks'
            )
              AND feature.config <> '{}'::jsonb
       ) THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;

    SELECT config -> 'limits' INTO STRICT limits
    FROM sc_site_features WHERE feature_key = 'orders';
    IF NOT limits ?& ARRAY[
            'maxItemQuantity', 'maxTotalItems', 'maxNameLength', 'maxPhoneLength',
            'maxAddressLength', 'maxCommentLength', 'delayedOrderConfirmationMinutes'
       ]
       OR EXISTS (
            SELECT 1 FROM jsonb_object_keys(limits) field_name
            WHERE field_name <> ALL (ARRAY[
                'maxItemQuantity', 'maxTotalItems', 'maxNameLength', 'maxPhoneLength',
                'maxAddressLength', 'maxCommentLength', 'delayedOrderConfirmationMinutes',
                'minOrderMinor', 'freeDeliveryThresholdMinor'
            ])
       )
       OR EXISTS (
            SELECT 1
            FROM unnest(ARRAY[
                'maxItemQuantity', 'maxTotalItems', 'maxNameLength', 'maxPhoneLength',
                'maxAddressLength', 'maxCommentLength', 'delayedOrderConfirmationMinutes'
            ]) field_name
            WHERE jsonb_typeof(limits -> field_name) <> 'number'
               OR limits ->> field_name !~ '^-?[0-9]+$'
       )
       OR (limits ->> 'maxItemQuantity')::numeric NOT BETWEEN 1 AND 99
       OR (limits ->> 'maxTotalItems')::numeric NOT BETWEEN 1 AND 200
       OR (limits ->> 'maxNameLength')::numeric NOT BETWEEN 1 AND 80
       OR (limits ->> 'maxPhoneLength')::numeric NOT BETWEEN 5 AND 32
       OR (limits ->> 'maxAddressLength')::numeric NOT BETWEEN 1 AND 240
       OR (limits ->> 'maxCommentLength')::numeric NOT BETWEEN 1 AND 500
       OR (limits ->> 'delayedOrderConfirmationMinutes')::numeric NOT BETWEEN 0 AND 1440
       OR EXISTS (
            SELECT 1 FROM unnest(ARRAY['minOrderMinor', 'freeDeliveryThresholdMinor']) field_name
            WHERE limits ? field_name
              AND (jsonb_typeof(limits -> field_name) <> 'number'
                   OR limits ->> field_name !~ '^[0-9]+$'
                   OR CASE WHEN limits ->> field_name ~ '^[0-9]+$'
                           THEN (limits ->> field_name)::numeric > 9223372036854775807
                           ELSE false END)
       ) THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;

    IF (SELECT count(*) FROM sc_site_features
        WHERE feature_key IN ('preorder', 'delivery', 'pickup') AND enabled) = 0
       OR (SELECT count(*) FROM sc_site_features
           WHERE feature_key IN ('preorder', 'delivery', 'pickup')
             AND enabled AND (config ->> 'default')::boolean) <> 1
       OR EXISTS (
            SELECT 1 FROM sc_site_features
            WHERE feature_key IN ('preorder', 'delivery', 'pickup')
              AND NOT enabled AND (config ->> 'default')::boolean
       ) THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM sc_menu_versions version
        JOIN sc_restaurants restaurant ON restaurant.id = version.restaurant_id
        WHERE restaurant.status = 'active' AND restaurant.is_default AND version.status = 'published'
          AND (
              (SELECT count(*) FROM sc_menu_categories category
               WHERE category.menu_version_id = version.id) > 500
              OR (SELECT count(*) FROM sc_menu_items item
                  WHERE item.menu_version_id = version.id) > 10000
              OR EXISTS (
                  SELECT 1 FROM sc_menu_categories category
                  WHERE category.menu_version_id = version.id
                    AND (length(btrim(category.name)) NOT BETWEEN 1 AND 120
                         OR (category.description IS NOT NULL AND length(category.description) > 500)
                         OR (category.image_asset_id IS NOT NULL AND NOT EXISTS (
                              SELECT 1 FROM sc_assets asset
                              WHERE asset.id = category.image_asset_id
                                AND asset.status = 'active'
                                AND asset.asset_type = 'menu_category_image'
                         )))
              )
              OR EXISTS (
                  SELECT 1 FROM sc_menu_items item
                  WHERE item.menu_version_id = version.id
                    AND (length(btrim(item.name)) NOT BETWEEN 1 AND 160
                         OR (item.description IS NOT NULL AND length(item.description) > 1000)
                         OR item.price_minor NOT BETWEEN 0 AND 1000000000000
                         OR (item.image_asset_id IS NOT NULL AND NOT EXISTS (
                              SELECT 1 FROM sc_assets asset
                              WHERE asset.id = item.image_asset_id
                                AND asset.status = 'active'
                                AND asset.asset_type = 'menu_item_image'
                         )))
              )
          )
    ) THEN
        RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'SC_BE_001_BUSINESS_PROJECTION_INVALID';
    END IF;
END
$$;

-- Complete deterministic shadow-graph fingerprint. md5 is intentionally used only as a
-- compact change detector for an operator-approved artifact, never as authentication or
-- a security boundary.
CREATE TEMP TABLE sc_v7_source_fingerprint ON COMMIT DROP AS
WITH shadow_rows(table_name, row_key, row_data) AS (
    SELECT 'sc_site_configuration', singleton_id::text, to_jsonb(source_row)::text FROM sc_site_configuration source_row
    UNION ALL SELECT 'sc_assets', id::text, to_jsonb(source_row)::text FROM sc_assets source_row
    UNION ALL SELECT 'sc_site_branding', singleton_id::text, to_jsonb(source_row)::text FROM sc_site_branding source_row
    UNION ALL SELECT 'sc_site_profile', singleton_id::text, to_jsonb(source_row)::text FROM sc_site_profile source_row
    UNION ALL SELECT 'sc_site_features', feature_key, to_jsonb(source_row)::text FROM sc_site_features source_row
    UNION ALL SELECT 'sc_restaurants', id::text, to_jsonb(source_row)::text FROM sc_restaurants source_row
    UNION ALL SELECT 'sc_order_counter', singleton_id::text, to_jsonb(source_row)::text FROM sc_order_counter source_row
    UNION ALL SELECT 'sc_menu_versions', id::text, to_jsonb(source_row)::text FROM sc_menu_versions source_row
    UNION ALL SELECT 'sc_menu_categories', id::text, to_jsonb(source_row)::text FROM sc_menu_categories source_row
    UNION ALL SELECT 'sc_menu_items', id::text, to_jsonb(source_row)::text FROM sc_menu_items source_row
    UNION ALL SELECT 'sc_orders', id::text, to_jsonb(source_row)::text FROM sc_orders source_row
    UNION ALL SELECT 'sc_order_items', id::text, to_jsonb(source_row)::text FROM sc_order_items source_row
    UNION ALL SELECT 'sc_order_status_history', id::text, to_jsonb(source_row)::text FROM sc_order_status_history source_row
    UNION ALL SELECT 'sc_notification_destinations', id::text, to_jsonb(source_row)::text FROM sc_notification_destinations source_row
    UNION ALL SELECT 'sc_outbox_events', id::text, to_jsonb(source_row)::text FROM sc_outbox_events source_row
    UNION ALL SELECT 'sc_notification_deliveries', id::text, to_jsonb(source_row)::text FROM sc_notification_deliveries source_row
)
SELECT md5(COALESCE(string_agg(
    table_name || chr(31) || row_key || chr(31) || row_data,
    chr(30) ORDER BY table_name, row_key
), '')) AS value
FROM shadow_rows;

DO $$
DECLARE
    approved_fingerprint text;
    actual_fingerprint text;
    runtime_role_name text;
    runtime_role_oid oid;
    populated boolean := (SELECT count(*) FROM sc_site_configuration) > 0;
BEGIN
    -- A completely empty bootstrap has no public projection or runtime history to approve,
    -- but a configured runtime role is still validated and receives the final allowlist.
    IF populated THEN
        approved_fingerprint := current_setting('app.sc_be_001_approved_source_fingerprint', true);
        IF approved_fingerprint IS NULL OR approved_fingerprint !~ '^[0-9a-f]{32}$' THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'SC_BE_001_APPROVED_PROJECTION_MISSING';
        END IF;
        SELECT value INTO STRICT actual_fingerprint FROM sc_v7_source_fingerprint;
        IF approved_fingerprint <> actual_fingerprint THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = 'SC_BE_001_APPROVED_SOURCE_FINGERPRINT_MISMATCH';
        END IF;
    END IF;

    runtime_role_name := current_setting('app.sc_be_001_runtime_role', true);
    IF runtime_role_name IS NULL AND NOT populated THEN
        RETURN;
    END IF;
    SELECT oid INTO runtime_role_oid FROM pg_roles WHERE rolname = runtime_role_name;
    IF runtime_role_name IS NULL
       OR btrim(runtime_role_name) = ''
       OR runtime_role_oid IS NULL
       OR runtime_role_name = current_user
       OR EXISTS (
            SELECT 1 FROM pg_roles role_record
            WHERE role_record.oid = runtime_role_oid
              AND (role_record.rolsuper OR role_record.rolcreaterole OR role_record.rolcreatedb)
       )
       OR pg_has_role(runtime_role_oid, (SELECT oid FROM pg_roles WHERE rolname = current_user), 'MEMBER')
       OR has_schema_privilege(runtime_role_oid, current_schema(), 'CREATE')
       OR has_function_privilege(
            runtime_role_oid,
            to_regprocedure(current_schema() || '.sc_refresh_single_client_shadow()'),
            'EXECUTE'
       )
       OR has_function_privilege(
            runtime_role_oid,
            to_regprocedure(current_schema() || '.sc_assert_single_client_source()'),
            'EXECUTE'
       )
       OR EXISTS (
            SELECT 1
            FROM pg_class object_record
            JOIN pg_namespace namespace ON namespace.oid = object_record.relnamespace
            WHERE namespace.nspname = current_schema()
              AND object_record.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
              AND pg_has_role(runtime_role_oid, object_record.relowner, 'MEMBER')
       )
       OR EXISTS (
            SELECT 1
            FROM pg_class object_record
            JOIN pg_namespace namespace ON namespace.oid = object_record.relnamespace
            WHERE namespace.nspname = current_schema()
              AND object_record.relkind IN ('r', 'p')
              AND (has_table_privilege(runtime_role_oid, object_record.oid, 'TRUNCATE')
                   OR has_table_privilege(runtime_role_oid, object_record.oid, 'REFERENCES')
                   OR has_table_privilege(runtime_role_oid, object_record.oid, 'TRIGGER'))
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_DATABASE_ROLE_SEPARATION_REQUIRED';
    END IF;
END
$$;

-- Drop V1-V5 objects explicitly in dependency order. RESTRICT is intentional.
DROP TABLE legacy_import_run_assets;
DROP TABLE legacy_import_run_tenants;
DROP TABLE legacy_import_runs;
DROP TABLE notification_deliveries;
DROP TABLE outbox_events;
DROP TABLE notification_destinations;
DROP TABLE order_status_history;
DROP TABLE order_items;
DROP TABLE orders;
DROP TABLE menu_items;
DROP TABLE menu_categories;
DROP TABLE menu_versions;
DROP TABLE tenant_order_counters;
DROP TABLE tenant_branding;
DROP TABLE tenant_profiles;
DROP TABLE tenant_features;
DROP TABLE tenant_assets;
DROP TABLE restaurants;
DROP TABLE tenant_domains;
DROP TABLE tenants;

ALTER TABLE sc_site_configuration RENAME TO site_configuration;
ALTER TABLE sc_assets RENAME TO assets;
ALTER TABLE sc_site_branding RENAME TO site_branding;
ALTER TABLE sc_site_profile RENAME TO site_profile;
ALTER TABLE sc_site_features RENAME TO site_features;
ALTER TABLE sc_restaurants RENAME TO restaurants;
ALTER TABLE sc_order_counter RENAME TO order_counter;
ALTER TABLE sc_menu_versions RENAME TO menu_versions;
ALTER TABLE sc_menu_categories RENAME TO menu_categories;
ALTER TABLE sc_menu_items RENAME TO menu_items;
ALTER TABLE sc_orders RENAME TO orders;
ALTER TABLE sc_order_items RENAME TO order_items;
ALTER TABLE sc_order_status_history RENAME TO order_status_history;
ALTER TABLE sc_notification_destinations RENAME TO notification_destinations;
ALTER TABLE sc_outbox_events RENAME TO outbox_events;
ALTER TABLE sc_notification_deliveries RENAME TO notification_deliveries;

ALTER TABLE assets DROP CONSTRAINT uq_sc_assets_legacy_object_key;
ALTER TABLE assets DROP CONSTRAINT ck_sc_assets_legacy_object_key;
ALTER TABLE assets DROP CONSTRAINT ck_sc_assets_storage_state;
ALTER TABLE assets DROP CONSTRAINT ck_sc_assets_storage_transition;
ALTER TABLE assets DROP CONSTRAINT ck_sc_assets_canonical_object_key;
ALTER TABLE assets DROP COLUMN legacy_object_key;
ALTER TABLE assets DROP COLUMN storage_state;
ALTER TABLE assets RENAME COLUMN canonical_object_key TO object_key;
ALTER TABLE assets RENAME CONSTRAINT uq_sc_assets_canonical_object_key TO uq_assets_object_key;
ALTER TABLE assets ADD CONSTRAINT ck_assets_object_key CHECK (
    object_key = 'assets/' || asset_type || '/' || sha256 || CASE content_type
        WHEN 'image/png' THEN '.png'
        WHEN 'image/jpeg' THEN '.jpg'
        WHEN 'image/webp' THEN '.webp'
        WHEN 'image/x-icon' THEN '.ico'
        WHEN 'image/vnd.microsoft.icon' THEN '.ico'
    END
);

ALTER TABLE site_configuration DROP CONSTRAINT ck_sc_site_configuration_status;
ALTER TABLE site_configuration ADD CONSTRAINT ck_site_configuration_status
    CHECK (status IN ('active', 'inactive'));

ALTER TABLE order_status_history DROP CONSTRAINT ck_sc_order_status_history_actor_type;
ALTER TABLE order_status_history ADD CONSTRAINT ck_order_status_history_actor_type
    CHECK (actor_type IN ('system', 'customer', 'client_admin', 'worker', 'import'));

ALTER TABLE menu_versions ADD COLUMN currency varchar(3);
UPDATE menu_versions version
SET currency = COALESCE(
    (
        SELECT min(item.currency)
        FROM menu_items item
        WHERE item.menu_version_id = version.id
    ),
    (SELECT site.currency FROM site_configuration site)
);
ALTER TABLE menu_versions ALTER COLUMN currency SET NOT NULL;
ALTER TABLE menu_versions ADD CONSTRAINT ck_menu_versions_currency
    CHECK (currency ~ '^[A-Z]{3}$');

-- Constraint-backed indexes follow their constraint rename. Rename every remaining
-- shadow constraint and standalone index deterministically.
DO $$
DECLARE
    item record;
    neutral_name text;
BEGIN
    FOR item IN
        SELECT target.relname AS table_name, constraint_record.conname AS object_name
        FROM pg_constraint constraint_record
        JOIN pg_class target ON target.oid = constraint_record.conrelid
        JOIN pg_namespace namespace ON namespace.oid = target.relnamespace
        WHERE namespace.nspname = current_schema()
          AND (constraint_record.conname LIKE 'sc\_%' ESCAPE '\'
               OR constraint_record.conname LIKE '%\_sc\_%' ESCAPE '\')
        ORDER BY target.relname, constraint_record.conname
    LOOP
        neutral_name := CASE
            WHEN item.object_name LIKE 'sc\_%' ESCAPE '\' THEN substring(item.object_name FROM 4)
            ELSE replace(item.object_name, '_sc_', '_')
        END;
        EXECUTE format(
            'ALTER TABLE %I.%I RENAME CONSTRAINT %I TO %I',
            current_schema(), item.table_name, item.object_name, neutral_name);
    END LOOP;

    FOR item IN
        SELECT index_record.relname AS object_name
        FROM pg_class index_record
        JOIN pg_namespace namespace ON namespace.oid = index_record.relnamespace
        WHERE namespace.nspname = current_schema()
          AND index_record.relkind = 'i'
          AND (index_record.relname LIKE 'sc\_%' ESCAPE '\'
               OR index_record.relname LIKE '%\_sc\_%' ESCAPE '\')
        ORDER BY index_record.relname
    LOOP
        neutral_name := CASE
            WHEN item.object_name LIKE 'sc\_%' ESCAPE '\' THEN substring(item.object_name FROM 4)
            ELSE replace(item.object_name, '_sc_', '_')
        END;
        EXECUTE format(
            'ALTER INDEX %I.%I RENAME TO %I',
            current_schema(), item.object_name, neutral_name);
    END LOOP;
END
$$;

CREATE TABLE menu_modifier_groups (
    id uuid PRIMARY KEY,
    menu_version_id uuid NOT NULL,
    menu_item_id uuid NOT NULL,
    public_id varchar(120) NOT NULL,
    name varchar(120) NOT NULL,
    min_selections integer NOT NULL,
    max_selections integer NOT NULL,
    sort_order integer NOT NULL,
    CONSTRAINT uq_menu_modifier_groups_version_id
        UNIQUE (menu_version_id, id),
    CONSTRAINT uq_menu_modifier_groups_item_public_id
        UNIQUE (menu_version_id, menu_item_id, public_id),
    CONSTRAINT fk_menu_modifier_groups_item
        FOREIGN KEY (menu_version_id, menu_item_id)
        REFERENCES menu_items (menu_version_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_menu_modifier_groups_public_id
        CHECK (public_id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_menu_modifier_groups_name
        CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_menu_modifier_groups_selections
        CHECK (min_selections BETWEEN 0 AND 99
           AND max_selections BETWEEN 1 AND 99
           AND min_selections <= max_selections),
    CONSTRAINT ck_menu_modifier_groups_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX ix_menu_modifier_groups_item_ordered
    ON menu_modifier_groups (menu_version_id, menu_item_id, sort_order, public_id);

CREATE TABLE menu_modifier_options (
    id uuid PRIMARY KEY,
    menu_version_id uuid NOT NULL,
    modifier_group_id uuid NOT NULL,
    public_id varchar(120) NOT NULL,
    name varchar(120) NOT NULL,
    price_delta_minor bigint NOT NULL,
    available boolean NOT NULL,
    sort_order integer NOT NULL,
    CONSTRAINT uq_menu_modifier_options_group_public_id
        UNIQUE (menu_version_id, modifier_group_id, public_id),
    CONSTRAINT fk_menu_modifier_options_group
        FOREIGN KEY (menu_version_id, modifier_group_id)
        REFERENCES menu_modifier_groups (menu_version_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_menu_modifier_options_public_id
        CHECK (public_id ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT ck_menu_modifier_options_name
        CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_menu_modifier_options_price_delta
        CHECK (price_delta_minor BETWEEN -1000000000000 AND 1000000000000),
    CONSTRAINT ck_menu_modifier_options_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX ix_menu_modifier_options_group_ordered
    ON menu_modifier_options (menu_version_id, modifier_group_id, sort_order, public_id);

DROP FUNCTION sc_refresh_single_client_shadow();
DROP FUNCTION sc_assert_single_client_source();

-- Hand the contracted schema to the configured runtime without transferring ownership.
-- The explicit table allowlist prevents Flyway metadata or future tables from inheriting
-- application privileges accidentally.
DO $$
DECLARE
    runtime_role_name text := current_setting('app.sc_be_001_runtime_role', true);
    runtime_role_oid oid;
    migrator_role_oid oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    application_tables constant text[] := ARRAY[
        'site_configuration', 'assets', 'site_branding', 'site_profile', 'site_features',
        'restaurants', 'order_counter', 'menu_versions', 'menu_categories', 'menu_items',
        'orders', 'order_items', 'order_status_history', 'notification_destinations',
        'outbox_events', 'notification_deliveries', 'menu_modifier_groups', 'menu_modifier_options'
    ];
    table_name text;
    sequence_record record;
BEGIN
    IF runtime_role_name IS NULL THEN
        RETURN;
    END IF;
    SELECT oid INTO runtime_role_oid FROM pg_roles WHERE rolname = runtime_role_name;
    IF runtime_role_oid IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_DATABASE_ROLE_SEPARATION_REQUIRED';
    END IF;

    EXECUTE format('REVOKE ALL PRIVILEGES ON SCHEMA %I FROM %I', current_schema(), runtime_role_name);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', current_schema(), runtime_role_name);
    EXECUTE format(
        'REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA %I FROM %I',
        current_schema(), runtime_role_name
    );
    EXECUTE format(
        'REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %I FROM %I',
        current_schema(), runtime_role_name
    );
    EXECUTE format(
        'REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA %I FROM %I',
        current_schema(), runtime_role_name
    );

    FOREACH table_name IN ARRAY application_tables LOOP
        EXECUTE format(
            'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %I.%I TO %I',
            current_schema(), table_name, runtime_role_name
        );
    END LOOP;
    FOR sequence_record IN
        SELECT sequence_object.relname
        FROM pg_class sequence_object
        JOIN pg_namespace namespace ON namespace.oid = sequence_object.relnamespace
        WHERE namespace.nspname = current_schema() AND sequence_object.relkind = 'S'
    LOOP
        EXECUTE format(
            'GRANT USAGE ON SEQUENCE %I.%I TO %I',
            current_schema(), sequence_record.relname, runtime_role_name
        );
    END LOOP;

    IF NOT has_schema_privilege(runtime_role_oid, current_schema(), 'USAGE')
       OR has_schema_privilege(runtime_role_oid, current_schema(), 'CREATE')
       OR EXISTS (
            SELECT 1
            FROM pg_class application_table
            JOIN pg_namespace namespace ON namespace.oid = application_table.relnamespace
            WHERE namespace.nspname = current_schema()
              AND application_table.relname = ANY (application_tables)
              AND (
                  application_table.relowner <> migrator_role_oid
                  OR NOT has_table_privilege(runtime_role_oid, application_table.oid, 'SELECT')
                  OR NOT has_table_privilege(runtime_role_oid, application_table.oid, 'INSERT')
                  OR NOT has_table_privilege(runtime_role_oid, application_table.oid, 'UPDATE')
                  OR NOT has_table_privilege(runtime_role_oid, application_table.oid, 'DELETE')
                  OR has_table_privilege(runtime_role_oid, application_table.oid, 'TRUNCATE')
                  OR has_table_privilege(runtime_role_oid, application_table.oid, 'REFERENCES')
                  OR has_table_privilege(runtime_role_oid, application_table.oid, 'TRIGGER')
              )
       )
       OR (
            SELECT count(*)
            FROM pg_class application_table
            JOIN pg_namespace namespace ON namespace.oid = application_table.relnamespace
            WHERE namespace.nspname = current_schema()
              AND application_table.relname = ANY (application_tables)
              AND application_table.relkind IN ('r', 'p')
       ) <> cardinality(application_tables)
       OR EXISTS (
            SELECT 1
            FROM pg_class non_application_table
            JOIN pg_namespace namespace ON namespace.oid = non_application_table.relnamespace
            WHERE namespace.nspname = current_schema()
              AND non_application_table.relkind IN ('r', 'p')
              AND non_application_table.relname <> ALL (application_tables)
              AND (has_table_privilege(runtime_role_oid, non_application_table.oid, 'SELECT')
                   OR has_table_privilege(runtime_role_oid, non_application_table.oid, 'INSERT')
                   OR has_table_privilege(runtime_role_oid, non_application_table.oid, 'UPDATE')
                   OR has_table_privilege(runtime_role_oid, non_application_table.oid, 'DELETE')
                   OR has_table_privilege(runtime_role_oid, non_application_table.oid, 'TRUNCATE')
                   OR has_table_privilege(runtime_role_oid, non_application_table.oid, 'REFERENCES')
                   OR has_table_privilege(runtime_role_oid, non_application_table.oid, 'TRIGGER'))
       )
       OR EXISTS (
            SELECT 1
            FROM pg_class sequence_object
            JOIN pg_namespace namespace ON namespace.oid = sequence_object.relnamespace
            WHERE namespace.nspname = current_schema()
              AND sequence_object.relkind = 'S'
              AND (NOT has_sequence_privilege(runtime_role_oid, sequence_object.oid, 'USAGE')
                   OR has_sequence_privilege(runtime_role_oid, sequence_object.oid, 'SELECT')
                   OR has_sequence_privilege(runtime_role_oid, sequence_object.oid, 'UPDATE'))
       )
       OR EXISTS (
            SELECT 1
            FROM pg_proc function_record
            JOIN pg_namespace namespace ON namespace.oid = function_record.pronamespace
            WHERE namespace.nspname = current_schema()
              AND has_function_privilege(runtime_role_oid, function_record.oid, 'EXECUTE')
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_DATABASE_ROLE_SEPARATION_REQUIRED';
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_class object_record
        JOIN pg_namespace namespace ON namespace.oid = object_record.relnamespace
        WHERE namespace.nspname = current_schema()
          AND object_record.relkind IN ('r', 'p', 'i', 'S')
          AND (object_record.relname LIKE 'sc\_%' ESCAPE '\'
               OR lower(object_record.relname) LIKE '%tenant%')
    ) OR EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND (column_name LIKE 'sc\_%' ESCAPE '\'
               OR lower(column_name) LIKE '%tenant%')
    ) OR EXISTS (
        SELECT 1
        FROM pg_constraint constraint_record
        JOIN pg_class target ON target.oid = constraint_record.conrelid
        JOIN pg_namespace namespace ON namespace.oid = target.relnamespace
        WHERE namespace.nspname = current_schema()
          AND (constraint_record.conname LIKE 'sc\_%' ESCAPE '\'
               OR constraint_record.conname LIKE '%\_sc\_%' ESCAPE '\'
               OR lower(constraint_record.conname) LIKE '%tenant%')
    ) OR EXISTS (
        SELECT 1
        FROM pg_policy policy_record
        JOIN pg_class target ON target.oid = policy_record.polrelid
        JOIN pg_namespace namespace ON namespace.oid = target.relnamespace
        WHERE namespace.nspname = current_schema()
          AND (policy_record.polname LIKE 'sc\_%' ESCAPE '\'
               OR policy_record.polname LIKE '%\_sc\_%' ESCAPE '\'
               OR lower(policy_record.polname) LIKE '%tenant%')
    ) OR to_regprocedure(current_schema() || '.sc_refresh_single_client_shadow()') IS NOT NULL
       OR to_regprocedure(current_schema() || '.sc_assert_single_client_source()') IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = 'SC_BE_001_CONTRACT_CATALOG_DIRTY';
    END IF;
END
$$;
