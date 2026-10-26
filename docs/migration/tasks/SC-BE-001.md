# SC-BE-001 — single-client backend boundary and tenancy removal

Status: `done`
Wave: P2
Development dependencies: `SC-000`, `SC-DB-001`, `SC-API-001`
Integration prerequisite: `SC-SEC-001` must be applied first so `identity` no longer imports
`tenancy::api` when this task deletes the tenancy module.

## Objective

Переключить Spring backend с V1–V5 tenant runtime на single-client schema и fixed public
contract, выделить реальные `site` и `asset` modules, удалить tenancy/provisioning/import
runtime и оставить один запускаемый modular monolith без скрытого scope selector.

После выполнения:

- `GET /api/v1/public/config` читает singleton site configuration и active default
  restaurant;
- `GET /api/v1/public/menu` читает current published menu этого restaurant;
- asset lookup и object keys не содержат owner/tenant;
- notification configuration не выбирается из Host/request;
- Java source и live PostgreSQL schema не содержат tenant runtime;
- `POST /api/v1/public/orders` остаётся только contract/security boundary до `SC-ORD-001`;
  этот task не реализует фиктивный order use case.

## Unblock checklist

Агент начинает mutation только после всех пунктов:

1. `SC-DB-001` принят, а `V6__single_client_expand.sql` и его schema tests зелёные.
2. `SC-API-001` принят; generated contract содержит только `/config`, `/menu`, `/orders`
   и не содержит tenant selector/error.
3. Orchestrator зафиксировал SHA/commit обоих P1 checkpoints в handoff.
4. Для текущей БД подтверждено одно из двух:
   - V1–V5 dataset пуст, включая V5 ledger и `tenant_assets`; либо
   - отдельная одобренная процедура обработала **каждую сохраняемую** asset metadata row,
     включая `pending`/`deleted`: object скопирован в canonical key, hash и `size_bytes`
     проверены, а соответствующая `sc_assets` row имеет `storage_state =
     'canonical_ready'`. Ненужная `pending`/`deleted` row может быть удалена до cutover
     только отдельным одобренным retention/delete шагом, если она не referenced; V7 не
     отбрасывает такие rows молча.
5. Preflight validator построил полный business projection из shadow schema, validation
   прошла, а `sourceDataDigest` и `businessProjectionDigest` вошли в одобренный cutover
   artifact. Порядок вычисления и проверки digest определён ниже.
6. Старые readers, writers и workers остановлены; нет `processing` outbox events,
   `started` notification deliveries или внешней доставки с неустановленным outcome.
7. Flyway запускается dedicated migration role. Runtime role отлична от неё, не владеет
   V6 helpers, не имеет `EXECUTE` на них и не имеет DDL privileges.
8. `SC-SEC-001` уже применён при интеграции либо его diff готов к применению первым.

Если контракт, V6 table/column names или data preconditions отличаются, не угадывать и не
создавать compatibility layer. Остановиться и передать расхождение orchestrator.

## Exclusive ownership

Разрешённая write scope:

```text
apps/api/src/main/java/com/salamu/menu/site/**
apps/api/src/main/java/com/salamu/menu/asset/**
apps/api/src/main/java/com/salamu/menu/restaurant/**
apps/api/src/main/java/com/salamu/menu/menu/**
apps/api/src/main/java/com/salamu/menu/notification/**
apps/api/src/main/java/com/salamu/menu/ordering/**
apps/api/src/main/java/com/salamu/menu/tenancy/**              # delete after last import
apps/api/src/main/java/com/salamu/menu/migration/**            # delete
apps/api/src/main/java/com/salamu/menu/ApiErrorHandler.java
apps/api/src/test/java/com/salamu/menu/site/**
apps/api/src/test/java/com/salamu/menu/asset/**
apps/api/src/test/java/com/salamu/menu/restaurant/**
apps/api/src/test/java/com/salamu/menu/menu/**
apps/api/src/test/java/com/salamu/menu/notification/**
apps/api/src/test/java/com/salamu/menu/ordering/**
apps/api/src/test/java/com/salamu/menu/tenancy/**               # move useful tests, delete rest
apps/api/src/test/java/com/salamu/menu/migration/**             # delete
apps/api/src/test/java/com/salamu/menu/ApiErrorHandlerTests.java
apps/api/src/test/java/com/salamu/menu/MenuPlatformApplicationTests.java
apps/api/src/test/java/com/salamu/menu/modularity/**
apps/api/src/test/java/com/salamu/menu/persistence/**
apps/api/src/main/resources/db/migration/V7__activate_single_client_schema.sql
```

Запрещённая scope:

- `apps/api/src/main/java/com/salamu/menu/identity/**` и identity tests — владелец
  `SC-SEC-001`;
- `apps/api/src/main/resources/application*.yml` и
  `apps/api/src/test/resources/application-test.yml` — владелец `SC-SEC-001`;
- `packages/api-contract/**` — владелец `SC-API-001`;
- `V1`–`V6`; legacy frontend; `apps/web`; infra; task registry и status других задач.

Не менять `AGENTS.md`, ADR-0003 или impact audits. Не коммитить.

## Required module contracts

Ниже фиксируется минимальный public Java surface. Имена можно уточнить только для
совпадения с Java style, но смысл, ownership и отсутствие scope parameter менять нельзя.

### `asset::api`

Создать closed module `com.salamu.menu.asset` и named interface `asset::api`:

```java
record AssetId(UUID value) {}
enum AssetKind { LOGO, FAVICON, HERO, OPEN_GRAPH, CATEGORY, MENU_ITEM }
record PublicAsset(AssetId id, AssetKind kind, URI publicUrl, String sha256) {}

interface AssetCatalog {
    PublicAsset requireActive(AssetId id);
    Map<AssetId, PublicAsset> requireActive(Collection<AssetId> ids);
}
```

Contract rules:

- batch input: 0–500 unique IDs; empty input returns immutable empty map without SQL;
- duplicate input is collapsed; missing/deleted/wrong-kind data fails as configuration or
  menu corruption, never as another-owner result;
- public record never exposes object key, bucket, credentials or secret material;
- persistence reads final `assets`; S3 adapter accepts only
  `assets/{kind}/{sha256}.{ext}`;
- content type, extension, size and SHA-256 are validated before registration/upload;
- public URL is built from validated `app.assets.cdn-base-url`, never request Host;
- no exported import/provisioning/cleanup API. Future admin upload receives its own use
  case and authorization task.

Move reusable content validation, hash, S3/CDN and persistence logic from
`tenancy/internal/**/asset`, rename every tenant type, and delete owner checks. Preserve
the MinIO roundtrip and batch-query behavior as asset-module tests.

### `restaurant::api`

Create/retain named interface `restaurant::api`:

```java
record RestaurantId(UUID value) {}
enum RestaurantStatus { ACTIVE, INACTIVE }
record RestaurantSnapshot(
    RestaurantId id,
    String externalKey,
    String displayName,
    RestaurantStatus status
) {}

interface DefaultRestaurantQuery {
    RestaurantSnapshot requireActiveDefault();
}
```

Contract rules:

- query has no request/context/slug parameter;
- zero active defaults and more than one active default both fail closed as
  `SITE_NOT_READY`; never pick first row;
- repository reads final `restaurants`, with stable typed `RestaurantId` used by menu and
  future ordering;
- `slug` may remain persistence/admin data but is not a public site selector and does not
  enter cache/error lookup;
- provisioning/hash/cleanup types are not a target API.

### `site::api`

Create closed module `com.salamu.menu.site` owning settings, branding, SEO, contacts,
locale/currency/timezone, feature flags, order types and limits. Export:

```java
interface SiteConfigurationQuery {
    SiteConfigurationSnapshot current();
}

record SiteConfigurationSnapshot(
    RestaurantId restaurantId,
    Branding branding,
    Seo seo,
    PublicProfile profile,
    SiteFeatures features,
    List<AvailableOrderType> orderTypes,
    OrderLimits limits,
    WeeklySchedule schedule,
    DeliveryConfiguration deliveryConfiguration,
    String locale,
    String currency,
    String timezone,
    String representationHash
) {}

record WeeklySchedule(
    String timezone,
    String displayText,
    List<WeeklyScheduleDay> days
) {}

record WeeklyScheduleDay(
    DayOfWeek dayOfWeek,
    List<OpeningInterval> intervals
) {}

record OpeningInterval(LocalTime opensAt, LocalTime closesAt) {}

record DeliveryConfiguration(
    boolean enabled,
    boolean addressRequired,
    DeliveryFeePolicy feePolicy
) {}

enum DeliveryFeePolicy { NONE }
```

The nested value objects must preserve the accepted OpenAPI fields from `SC-API-001`.
Do not reuse generated HTTP DTOs as domain/application records.

Implementation rules:

- one repeatable-read query loads singleton rows `site_configuration`, `site_branding`,
  `site_profile`, `site_features`, calls `DefaultRestaurantQuery` and resolves configured
  asset IDs with one `AssetCatalog` batch call;
- singleton ID is a persistence detail and is not exposed;
- exactly the known feature keys are interpreted; unknown keys are ignored for forward
  compatibility; duplicate known keys or invalid typed config fail closed;
- exactly one enabled order type is default; money remains minor units; timezone and
  locale are validated;
- `WeeklySchedule` is typed and matches the accepted OpenAPI exactly: seven entries in
  Monday-through-Sunday order, absent legacy days become empty interval lists, values use
  `HH:mm`, at most eight intervals are allowed per day, and unknown/duplicate days,
  duplicate or overlapping intervals and invalid `opensAt >= closesAt` fail closed.
  `schedule.timezone` equals the validated site timezone; `displayText` is the migrated
  non-blank public text of at most 240 characters, not a replacement for intervals;
- `DeliveryConfiguration` is typed and matches the accepted OpenAPI exactly. `enabled`
  derives from the typed delivery feature, `addressRequired` follows the accepted order
  contract, and MVP `feePolicy` is `NONE`. Do not expose unapproved minimum-order,
  free-delivery or delivery-fee fields from legacy JSON;
- public `OrderLimits` are checked against the exact accepted contract bounds:
  `maxItemQuantity` 1–99, `maxTotalItems` 1–200, `maxNameLength` 1–80,
  `maxPhoneLength` 5–32, `maxAddressLength` 1–240, `maxCommentLength` 1–500 and
  `delayedOrderConfirmationMinutes` 0–1440; an out-of-range DB value is not silently
  clamped;
- representation hash covers the complete public response in deterministic field/list
  order, including schedule and delivery configuration, but excluding DB revision, Host,
  internal singleton ID and secrets;
- missing/inactive site, missing branding/profile/default restaurant, invalid JSON,
  missing required logo or wrong asset kind maps to `503 SITE_NOT_READY` with no internal
  detail leakage.

`PublicSiteConfigurationController` belongs to `site/internal/web` and maps the accepted
OpenAPI schema:

```text
ETag: "config-{representationHash}"
Cache-Control: public, max-age=<contract value>, stale-while-revalidate=<contract value>
Cache-Tag: site-config
Surrogate-Key: site-config
Vary: Accept-Encoding
```

Use exact cache directives from `SC-API-001`; do not invent a second contract. MVP
contract exposes ETag/version metadata but deliberately does not expose
`If-None-Match`/`304` through the generated client.

### `menu::api`

Replace provisioning-oriented API with:

```java
record MenuVersionId(UUID value) {}
record MenuItemId(UUID value) {}

interface PublishedMenuQuery {
    PublishedMenuSnapshot current();
}
```

`PublishedMenuSnapshot` contains typed `RestaurantId`, version number, ordered categories
and items, availability, minor-unit prices/currency, modifiers required by the accepted
contract, resolved public asset URLs and deterministic representation hash.

V1–V6 have no normalized modifier catalog. V7 therefore creates the following **final**
tables after the shadow tables have been renamed; it does not add a second temporary
modifier model:

```sql
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
```

The V7 legacy backfill for both tables is deliberately empty. Do not invent modifier
definitions from `order_items.modifier_snapshot`: that JSON is immutable order history,
not menu master data. Until `SC-DATA-001` imports an approved catalog, every migrated item
returns `modifierGroups: []`; once rows exist, the query returns groups/options in
`sort_order, public_id` order. IDs and `public_id` values are stable catalog identifiers,
not localized labels. Application/import validation also enforces the accepted maximum of
20 groups per item, one to 100 options per persisted group, and exact OpenAPI identifier,
name, selection and price-delta bounds; a group without options is never published.

Rules:

- resolve the restaurant only through `DefaultRestaurantQuery`; no Host/slug/context;
- repository performs bounded ordered reads from final `menu_versions`,
  `menu_categories`, `menu_items`, `menu_modifier_groups` and
  `menu_modifier_options`; no N+1 and no per-item/per-group query;
- `menu_versions.currency` is the required version-level ISO 4217 currency. Every item
  must use that currency, so an empty published menu still has a deterministic public
  currency without introducing a dependency on `site::api`;
- one published menu is required for a menu response; zero maps to
  `404 MENU_NOT_FOUND`; duplicates/corrupt FK/currency/price/order data map to a generic
  server error and are logged only with trace ID;
- resolve all category/item assets in one `AssetCatalog` batch; enforce expected kinds;
- ETag is `"menu-v{version}-{representationHash}"`; tags are only `menu` and
  `menu-v{version}`; Host, slug and DB UUID namespace are absent;
- response ordering/hash are stable; ETag/version metadata matches the accepted contract.

The controller remains `menu/internal/web/PublicMenuController`, but implements exactly
`GET /api/v1/public/menu` and accepted `SC-API-001` DTO/error/cache semantics.

### `notification::api`

Notification remains a closed module without request-derived routing. P2 exports only
sanitized readiness needed by future diagnostics/use cases:

```java
record NotificationDestinationId(UUID value) {}
enum NotificationChannel { WHATSAPP, TELEGRAM, EMAIL, POS }
enum NotificationDestinationStatus { ACTIVE, DISABLED }
record NotificationDestinationReadiness(
    NotificationDestinationId id,
    NotificationChannel channel,
    String displayName,
    NotificationDestinationStatus status,
    String referenceFingerprint,
    boolean ready
) {}

interface NotificationDestinationQuery {
    List<NotificationDestinationReadiness> configured();
}
```

Rules:

- query is parameterless and reads final `notification_destinations`;
- no Host, site selector, restaurant selector or client-supplied destination;
- raw `secret_reference` is internal to notification persistence/provider adapter;
  `OpaqueSecretReference.toString()` remains redacted and its value is never returned,
  logged, traced or used as a metric label;
- duplicate active `(channel, display_name)` or malformed reference fails readiness; no
  arbitrary fallback destination;
- remove provisioning/hash/cleanup APIs. Worker/outbox behavior is implemented by
  `SC-OUT-001`, not here.

### `ordering`

P2 only maps existing entities/repositories to final `orders`, `order_items`,
`order_status_history`, `order_counter`, `outbox_events` without tenant columns. Do not
add the public command/controller before `SC-ORD-001`.

- Retain IDs, idempotency payload hash, global key/number uniqueness, price snapshots,
  statuses, timestamps and optimistic/locking behavior required by the schema.
- Java must never emit historical `tenant_admin` or `platform_admin` actor values.
- No notification network call, async side effect or fake destination selection.

## Spring Modulith dependency matrix

At this checkpoint package declarations must be exactly this maximum dependency graph:

| Module | `allowedDependencies` in P2 |
|---|---|
| `asset` | `{}` |
| `restaurant` | `{}` |
| `site` | `{"asset::api", "restaurant::api"}` |
| `menu` | `{"asset::api", "restaurant::api"}` |
| `ordering` | `{}` |
| `notification` | `{}` |
| `identity` | `{}` (delivered by `SC-SEC-001`) |

Each module is `Type.CLOSED`; only `module/api/package-info.java` has
`@NamedInterface("api")`. No module imports another module's `internal` package. Do not
pre-authorize future `ordering -> menu/site/restaurant` or
`notification -> ordering` dependencies; later vertical slices add them when real calls
exist. `shared` is not used to hide a cycle or relocate `TenantId`.

Add a Modulith verification test that asserts the exact module set includes `site` and
`asset`, excludes `tenancy` and `migration`, and calls `ApplicationModules.verify()`.

## Expand → switch → contract coordination

`V6__single_client_expand.sql` remains immutable and keeps V1–V5 tables for rollback.
This task owns the next append-only migration:

```text
apps/api/src/main/resources/db/migration/V7__activate_single_client_schema.sql
```

If accepted expand migration is not V6, stop; do not silently choose another version.

V6 is not safe as a standalone production deployment because legacy writes can occur
after its initial snapshot. It intentionally leaves owner-only migration helpers
`sc_assert_single_client_source()` and `sc_refresh_single_client_shadow()`. Their
creation-time `search_path` is fixed and `PUBLIC` has no `EXECUTE`; do not grant them to
the application role. V7 must reuse and then drop them.

### Migration/runtime database roles

Database privilege separation is a deployment gate, not a recommendation:

- logical role `flyway_migrator` applies V6/V7, owns both V6 helper functions and has the
  DDL privileges needed for the contract transaction;
- logical role `application_runtime` is a distinct login used by the running service. It
  has only the final schema `USAGE` and required table DML privileges; it has no schema
  `CREATE`, table/function ownership, `ALTER`/`DROP`, role membership in
  `flyway_migrator`, or direct/role-inherited `EXECUTE` on either V6 helper;
- migrations run as a separate deployment step with migrator credentials. The service
  starts afterwards with runtime credentials and must not acquire migrator credentials;
- if V6 was applied by the runtime owner, cutover is blocked until a DBA-controlled step
  transfers helper/schema ownership to `flyway_migrator`, revokes direct and inherited
  runtime privileges and records the privilege audit. Merely executing `REVOKE PUBLIC`
  is insufficient because a function owner retains control;
- `SC-SEC-001`/infra owns datasource wiring outside this task's file scope. SC-BE-001 owns
  verification tests and must not weaken this requirement by reusing one credential.

Before mutation, query `pg_roles`, `pg_auth_members`, `pg_proc`, `information_schema` and
`has_function_privilege` as both logical roles. Stable diagnostic
`SC_BE_001_DATABASE_ROLE_SEPARATION_REQUIRED` aborts V7 if the runtime role owns or can
execute either helper or has DDL capability on the target schema.

### Approved business projection

Bidirectional row equality is necessary but does not prove that migrated data can satisfy
the accepted API. While old writers/workers remain stopped, a cutover-only validator must:

1. call the accepted V6 refresh with the migrator role and commit that non-destructive
   refresh;
2. read only the `sc_*` candidate tables and build the same typed
   `SiteConfigurationSnapshot`, `PublishedMenuSnapshot`, asset readiness and sanitized
   notification readiness that final application readers build. It reuses the same
   validation/mapping/hash code; it is not a runtime dual-read or fallback;
3. fail on every condition that would make `/config` non-`200` for a populated site,
   make the required current menu invalid, violate the accepted OpenAPI bounds, expose a
   secret/internal field, or leave a referenced asset unresolved;
4. emit an immutable cutover artifact containing release/database identity, V6 checksum,
   deterministic `sourceDataDigest`, `businessProjectionDigest`, representation hashes,
   singleton/default-menu state, retained asset manifest digest and outbox/delivery state
   counts. The source digest covers every value in every `sc_*` row in fixed table, primary
   key and column order; JSON is canonicalized, null is explicit and timestamps are UTC;
5. obtain explicit operator approval for that artifact. Missing approval is a hard stop.

The approved `sourceDataDigest` is supplied to the V7 Flyway connection through a
deployment-controlled PostgreSQL session setting such as
`app.sc_be_approved_source_digest`; it is not interpolated into migration SQL and therefore
does not change the Flyway checksum. Under the V7 source locks, after its final refresh,
V7 recomputes the exact same deterministic source digest and compares it with the setting.
Missing, malformed or mismatched values raise
`SC_BE_001_APPROVED_PROJECTION_MISSING` or
`SC_BE_001_APPROVED_SOURCE_DIGEST_MISMATCH` before destructive DDL. Matching the source
digest proves that the rows used by the approved business projection are the rows being
contracted. After startup, smoke verification compares response representation hashes/body
digest with `businessProjectionDigest` from the artifact.

The digest algorithm and canonicalization are versioned in the validator and V7 tests.
The digest is a change detector inside a trusted deployment path, not an authorization
token; artifact approval and database credentials remain protected by the deployment
system.

### Pre-contract gates in V7

The migration must raise stable diagnostics before destructive DDL when any check fails:

1. migration/runtime role separation is not proven as specified above;
2. the V6 bidirectional `EXCEPT ALL` refresh comparison reports any difference across
   every migrated table/column;
3. the approved projection setting is missing/malformed or the final locked
   `sourceDataDigest` differs;
4. site configuration is not empty or exactly one row; populated site is not `active`, or
   the typed config/schedule/delivery/limits projection is invalid;
5. active/default restaurant cardinality is not exactly one for populated data;
6. published-menu cardinality violates this explicit policy:
   - a completely empty installation (no site, restaurant or menu data) has zero current
     published menus and may migrate;
   - a populated active site has exactly one current published menu for its one active
     default restaurant and that complete menu projection must validate;
   - zero or multiple current published menus for that restaurant fail cutover. Other
     retained restaurants may have at most one current published menu each; they never
     become an implicit public fallback;
7. any retained `sc_assets` row, regardless of `status = pending|active|deleted`, has
   `storage_state <> 'canonical_ready'`, an invalid canonical key, an unverified object, or
   a hash/content-type/`size_bytes` mismatch. A non-active status is not permission for V7
   to discard metadata or skip object verification;
8. any V5 ledger table contains a row; non-empty ledger requires export/retention decision
   outside this task;
9. an order history row contains `tenant_admin` or `platform_admin`; mapping historical
   roles requires explicit ADR/data decision, not a silent rewrite;
10. any `sc_outbox_events.payload` contains a tenant routing field, any destination has an
    invalid opaque reference, any outbox event is `processing`, or any notification
    delivery is `started`;
11. provider reconciliation left an externally accepted delivery with unknown local
    outcome, or a retained `pending` backlog lacks an explicit deferral owner/decision for
    `SC-OUT-001`;
12. orphan/collision checks from V6 no longer hold.

For the outbox gate, `published`/`failed` events and `succeeded`/`failed` deliveries are
terminal and are preserved. `pending` events are preserved, not marked delivered; their
count, oldest age and approved deferral are recorded in the handoff. No legacy worker may
restart after drain. Ambiguous provider outcomes are reconciled before the artifact is
approved so V7 never resolves delivery semantics by guessing.

Flyway must not call S3. Object copy/hash verification occurs before V7. A failed gate
rolls back the entire migration and leaves V1–V6 intact.

### Contract transaction order

Within one PostgreSQL/Flyway transaction and maintenance window:

1. after every old reader/writer/worker is drained, verify database roles and acquire an
   advisory lock dedicated to `SC-BE-001` as `flyway_migrator`;
2. before refresh, capture a transaction-local readiness identity for every
   `canonical_ready` shadow asset: `id`, `asset_type`, `content_type`, `sha256`,
   `size_bytes` and `canonical_object_key`;
3. call `sc_refresh_single_client_shadow()`. It reacquires all V1–V5 source `SHARE` locks
   in the V6-defined stable order, locks/truncates all shadow tables, backfills them and
   performs the bidirectional `EXCEPT ALL` preservation check. Do not copy this SQL into
   V7 or run a weaker count-only refresh;
4. compare every refreshed `canonical_ready` row with the captured readiness identity,
   including `size_bytes`. Any changed/missing field raises
   `SC_BE_001_ASSET_READINESS_IDENTITY_CHANGED`; re-copy and re-verify the object rather
   than preserving readiness. This V7 compensation is mandatory because immutable V6 does
   not include `size_bytes` in its readiness-preservation temp table;
5. recompute/compare the approved source digest and run all remaining pre-contract gates
   while source locks are still held;
6. drop old tables in FK-safe order:
   `legacy_import_run_assets`, `legacy_import_run_tenants`, `legacy_import_runs`,
   `notification_deliveries`, `outbox_events`, `notification_destinations`,
   `order_status_history`, `order_items`, `orders`, `menu_items`, `menu_categories`,
   `menu_versions`, `tenant_order_counters`, `tenant_branding`, `tenant_profiles`,
   `tenant_features`, `tenant_assets`, `restaurants`, `tenant_domains`, `tenants`;
7. rename all `sc_*` tables to final names, including `sc_site_profile` and
   `sc_order_counter`, and rename constraints/indexes so live metadata has no `sc_` or
   tenant naming;
8. add required `menu_versions.currency`: for a version with items, require exactly one
   distinct item currency equal to `site_configuration.currency`; for an empty version,
   use the singleton site currency. Missing site currency, mixed currencies, mismatch or
   invalid ISO value aborts before destructive completion;
9. create empty final `menu_modifier_groups` and `menu_modifier_options` with the exact
   DDL, FK/check/unique constraints and ordered indexes specified above. Do not backfill
   them from order snapshots;
10. for `assets`, require canonical readiness, drop `legacy_object_key` and
   `storage_state`, then rename `canonical_object_key` to `object_key`;
11. replace site status constraint with single-site operational values `active/inactive`;
   old suspended/deleted data must already have failed the preflight;
12. replace order actor constraint with target values such as
   `system/customer/client_admin/worker/import`; do not preserve tenant/platform admin as
   values writable by new code;
13. revoke migration-only grants no longer needed and drop
    `sc_refresh_single_client_shadow()` and `sc_assert_single_client_source()`;
14. assert through catalog queries that no live table/column/constraint/index/policy name
    contains tenant scope, no table starts with `sc_`, neither helper function exists, and
    runtime privileges match the final allowlist.

Java entities/repositories map only final names. There is no dual-read, fallback to old
tables, runtime feature flag or compatibility tenant context. Deployment order is:

```text
enter maintenance and stop old writers/workers
→ apply/confirm V6 while writers remain stopped; never restart at the V6-only checkpoint
→ drain/reconcile in-flight outbox and provider deliveries
→ refresh, copy and verify every retained canonical asset outside Flyway, including size
→ validate the complete business projection and approve its digest artifact
→ deploy artifact containing V7 + single-client Java
→ run Flyway V7 as migration role with the approved source digest session setting
→ Hibernate validate
→ start new readers with the restricted runtime role
→ compare smoke bodies/representation hashes with the approved projection artifact
→ run security and database-privilege checks
```

Rollback after V7 is restore/forward-fix from verified database/object backup, not running
the old binary against the contracted schema. Record this in the handoff.

### Deployment gates

- **Enter maintenance:** verified database and object-store backups exist; old binary and
  every writer/worker are stopped; role audit is green; provider reconciliation and the
  in-flight outbox drain are complete.
- **Approve candidate:** V6 final refresh is green; every retained asset status has a
  verified canonical object including `size_bytes`; the complete typed config/menu
  projection is green; source/business digests and any pending-backlog deferral are
  approved in one artifact.
- **Permit destructive DDL:** V7 holds the advisory/source locks, the readiness identity
  check is green, approved source digest matches, and every numbered pre-contract gate is
  green. A missing artifact/session setting is never an overrideable warning.
- **Start application:** V7 and Hibernate validation are green; helper functions are gone;
  modifier catalog constraints/indexes exist; runtime privilege allowlist is green. Old
  binary/workers remain prohibited.
- **Exit maintenance:** `/config` and `/menu` smoke bodies/representation hashes match the
  approved projection; role/security/MinIO checks are green; monitoring shows no secret or
  legacy-key fallback. Otherwise restore/forward-fix according to the approved runbook.

## Exact deletion and migration inventory

### Delete after reusable asset/site code is moved

Delete the whole `com.salamu.menu.tenancy` tree. In particular, no renamed or deprecated
copy may remain of:

- `TenantId`, `TenantContext`, `TenantResolution`, `TenantResolver`, `TenantRegistry`,
  `TenantRequestAccess`, `TenancyApi`, lifecycle/domain/provisioning records;
- `TenantResolutionFilter`, `TenantContextArgumentResolver`,
  `TenantPathSlugExtractor`, `TenantRequestAttributeAccess`, `EffectiveHostResolver`,
  `TenancyWebConfiguration`;
- `PlatformHostPolicy`, `ConfiguredPlatformHostPolicy`, `PlatformHostsProperties`,
  `HostNormalizer`, `JpaTenantRegistry`, tenant JPA repositories/entities;
- all `TenantAsset*` and tenant provisioning/cleanup/provenance APIs/adapters after their
  reusable content/S3 behavior is implemented in `asset`;
- all tenancy tests except behavior explicitly moved and renamed under `asset` or `site`.

`TrustedProxyPolicy` and its CIDR implementation are not moved by this task; the neutral
identity-owned replacement belongs to `SC-SEC-001` and must already be present at
integration.

### Delete migration runtime

Delete the complete `apps/api/src/main/java/com/salamu/menu/migration/**` and
`apps/api/src/test/java/com/salamu/menu/migration/**`, including the `LegacyImport*`
ledger/run/tenant/asset models and `JdbcLegacyImportLedger`. Remove any component scan,
profile or property that instantiates them. V5 SQL remains immutable history; V7 drops
its live tables only when they are empty.

### Remove obsolete APIs in retained modules

- Restaurant: `DefaultRestaurantCleanupCandidate`, `DefaultRestaurantSpec`,
  `RestaurantProvisioningService`, `RestaurantProvisioningException`,
  `RestaurantSemanticHash`, provisioning application/repository/JDBC/hasher classes.
- Menu: `MenuProvisioning*`, `MenuCleanupCandidate`, `MenuSemanticHash`, deterministic
  import IDs and provisioning repository/JDBC/hasher classes unless a non-import
  invariant is demonstrably reused internally under a neutral name.
- Notification: `NotificationProvisioning*`, destination cleanup/spec/semantic-hash and
  secret provisioning result types; retain only neutral channel/status/readiness and
  internal secret handling.

An executor must run both searches before deleting the final tenancy package:

```text
rg -n "com\.salamu\.menu\.tenancy|Tenant(Id|Context|Resolution|Request|Asset)|tenant_id" apps/api/src
rg -n "LegacyImport|Provisioning|tenantSlug|TENANT_NOT_FOUND" apps/api/src
```

Every hit must be removed or listed in the handoff with a justified immutable-history
exception. V1–V6 SQL text is the only expected tenant-heavy history.

## Failure, logging and redaction requirements

- `ApiErrorHandler` maps only accepted `SC-API-001` codes/envelope. Remove
  `TENANT_NOT_FOUND` and `tenantSlug` details.
- `SITE_NOT_READY` and corruption responses never expose table names, SQL, singleton IDs,
  UUIDs, asset keys, bucket, host headers, secret references or raw JSON.
- `MENU_NOT_FOUND` is only absence of a published menu; corrupt menu/assets are not
  misreported as not found.
- Structured logs carry trace/correlation ID and low-cardinality event code. Do not log
  phone, address, comment, order payload, secret reference, provider response body,
  object key or exception message that embeds these values.
- No tenant/client label or MDC field. Asset/menu/site UUIDs are not metric labels.
- GET errors and all order responses/errors use safe cache policy; no error is shared
  cacheable unless the accepted contract explicitly says so.
- S3 unavailable, missing canonical object or checksum mismatch fails closed; never fall
  back to the legacy key.
- Notification destination unavailable does not trigger request-derived fallback.

## Required tests

### Unit/module

- asset content/hash/key validation and 500-ID batch bound;
- site feature/order-type/limits validation, typed seven-day schedule, typed delivery
  configuration and deterministic hash covering both new value objects;
- restaurant zero/one/multiple default behavior;
- menu ordering, hash, availability, money, asset-kind and modifier group/option bounds;
- empty modifier catalog maps to `modifierGroups: []`; populated groups/options retain
  stable identifiers and deterministic ordering without reading order snapshots;
- candidate projection/source digest canonicalization is deterministic for JSON key order,
  row order, nulls and UTC timestamps, and changes for every covered business field;
- notification redaction: secret reference absent from `toString`, exceptions and API;
- exact Modulith graph and no imports from `internal` packages.

### PostgreSQL/Testcontainers

- clean V1→V7 produces only final tables and Hibernate validation succeeds;
- V1–V5 empty → V6 → V7 succeeds;
- V6 snapshot followed by a legacy write, maintenance drain and V7 refresh preserves the
  late write; source locks prevent a concurrent writer during refresh;
- one complete owner graph preserves IDs/counts/order amounts/snapshots and reads through
  final repositories, provided asset rows are marked canonical-ready by fixture;
- clean/empty data with zero published menu succeeds; a populated active site with zero or
  multiple current menus for the default restaurant fails before destructive DDL; exactly
  one valid current menu succeeds;
- invalid schedule, delivery config, public order limits or complete menu projection fails
  candidate approval/V7 without dropping source/shadow tables;
- missing/malformed approved digest and a one-field source change after approval each fail
  V7 before destructive DDL; unchanged approved input succeeds and produces the recorded
  business representation hashes;
- a late asset `size_bytes` change cannot inherit canonical readiness: the V7 readiness
  identity gate fails even when ID/type/content/hash are unchanged;
- retained `active`, `pending` and `deleted` asset fixtures all require verified
  canonical-ready objects; explicit pre-cutover deletion succeeds only for an unreferenced
  row and is audited outside V7;
- `processing` outbox event, `started` delivery or ambiguous-provider fixture fails V7;
  terminal rows survive, while an approved `pending` backlog survives unchanged;
- V7 refuses non-empty V5 ledger, legacy-only asset, old actor role, invalid singleton,
  orphan and collision without dropping source/shadow tables;
- V7 creates empty `menu_modifier_groups`/`menu_modifier_options` on legacy upgrade; catalog
  tests assert every named FK/check/unique/index, reject invalid/cross-version rows, and
  prove inserts/read queries support the accepted one-to-20/one-to-100 shape;
- privilege tests use distinct migrator/runtime logins: migrator can run V7, runtime cannot
  execute V6 helpers or create/alter/drop schema objects, and final runtime DML works;
- catalog assertion: no live `tenant_id`, tenant table/index/FK, `sc_*` object or composite
  tenant FK, both V6 helper functions are absent, and modifier FK/index names are exact;
- query-count tests prove config/menu (including all modifier groups/options) use bounded
  reads and one asset batch.

### HTTP

- `/config` and `/menu` work without slug; legacy slug routes return 404/403 and never
  select data;
- same allowed Host aliases produce byte-equivalent bodies and ETags;
- Host/Forwarded/X-Forwarded-Host/query/header/body tenant values do not affect data;
- config/menu `200`, ETag/cache tags and generic failures match OpenAPI; no unsupported
  conditional `304` flow is advertised;
- `/config` returns the exact accepted `WeeklySchedule` and `DeliveryConfiguration`, with
  seven ordered days and no unapproved delivery-threshold fields;
- `/menu` returns an empty modifier array after legacy backfill and the exact accepted
  nested group/option shape after controlled catalog insertion;
- missing site → `503 SITE_NOT_READY`; no published menu → `404 MENU_NOT_FOUND`;
- response/log capture contains no tenant fields or secret/object keys.

### MinIO/S3

- new object roundtrip uses exactly canonical content-addressed key;
- wrong content type/hash/size is rejected;
- canonical object missing/unavailable does not use `legacy_object_key`;
- public URL uses configured CDN base and not request authority.

## Acceptance commands

Run after rebasing on accepted P1 and applying `SC-SEC-001` first:

```text
pnpm --filter @e-menu/api-contract validate
./gradlew :apps:api:compileJava :apps:api:compileTestJava
./gradlew :apps:api:test --tests "*ApplicationModule*" --tests "*Modularity*"
./gradlew :apps:api:test --tests "*Flyway*" --tests "*SingleClient*"
./gradlew :apps:api:test --tests "*Site*" --tests "*Restaurant*" --tests "*Menu*"
./gradlew :apps:api:test --tests "*Asset*" --tests "*Notification*"
./gradlew :apps:api:test --tests "*CutoverProjection*" --tests "*Modifier*"
./gradlew :apps:api:test --tests "*DatabasePrivilege*" --tests "*OutboxDrain*"
./gradlew :apps:api:test
./gradlew :apps:api:check
rg -n "com\.salamu\.menu\.tenancy|Tenant(Id|Context|Resolution|Request|Asset)|tenant_id" apps/api/src/main apps/api/src/test
rg -n "tenantSlug|TENANT_NOT_FOUND|LegacyImport|TenantProvisioning" apps/api/src/main apps/api/src/test
git diff --check
```

The `rg` commands must return no Java/YAML/test runtime hit. Tenant text in immutable
V1–V6 SQL may be reported separately and is not edited.

## Out of scope

- real dataset/content selection or import (`SC-DATA-001`);
- order command/idempotent transaction (`SC-ORD-001`);
- outbox polling/provider delivery (`SC-OUT-001`);
- frontend/cart/checkout;
- Redis, WebFlux, Kafka, microservices, RLS or a second database;
- client-admin authentication and asset administration UI.

## Handoff template

Executor sends this exact structure to orchestrator and does not commit:

```text
SC-BE-001 handoff
P1 base: <SC-DB commit/SHA>, <SC-API commit/SHA>
SC-SEC prerequisite applied: <commit/SHA or diff identifier>
Files added/moved/deleted: <grouped by site/asset/restaurant/menu/notification/schema>
Public Java contracts: <actual classes and signatures>
Module graph: <actual allowedDependencies>
V7 gates: <each diagnostic and test proving rollback>
Database roles: <migrator/runtime identities, ownership/grants audit and negative test>
Projection approval: <artifact ID, V6 checksum, source/business digests and smoke match>
Dataset state: <empty | approved canonical-ready dataset evidence for every asset status>
Asset readiness: <manifest digest and kind/content/hash/size/object verification>
Menu cardinality/modifiers: <empty or one current default menu; final DDL/catalog evidence>
Outbox drain: <processing=0, started=0, reconciliation and pending deferral/count/oldest age>
Flyway results: <clean; V1-V5 empty upgrade; populated upgrade; refusal cases>
HTTP results: <config/menu/ETag/errors/cache>
S3/MinIO results: <canonical key/hash/url/failure cases>
Security/redaction evidence: <test names and log/response search>
Search exceptions: <immutable V1-V6 hits only, or NONE>
Commands run: <command + exit code>
Unrun commands/blockers: <reason; never claim green>
Risks/decisions for SC-DATA/SC-ORD/SC-OUT: <explicit list>
Git status: <short output; no commit>
```

## Definition of Done

- Fixed config/menu controllers and target repositories run against final single-client
  tables.
- `site` and `asset` own the responsibilities defined above.
- Typed schedule/delivery projections and normalized final modifier tables implement the
  already accepted OpenAPI without changing generated contract files.
- Tenancy and legacy import modules/runtime are absent; no fake global tenant remains.
- V7 is append-only, fail-closed and verified from clean and V1–V5 upgrade paths; no
  destructive DDL can run without matching approved projection input, complete asset
  readiness (including size) and drained in-flight delivery state.
- Migration/runtime database roles are separated and verified with positive and negative
  privilege tests.
- Live schema, Java, logs, cache tags, asset paths, outbox payload and notification
  metadata contain no tenant scope.
- OpenAPI/generated client remain unchanged and synchronized.
- All required tests/commands pass and the handoff contains evidence.
- Executor has not committed.
