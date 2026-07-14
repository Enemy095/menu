# SC-BE-001 — single-client backend boundary and tenancy removal

Status: `blocked`
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
   - отдельная одобренная процедура уже скопировала каждый active object в canonical key,
     сверила hash/size и выставила `sc_assets.storage_state = 'canonical_ready'`.
5. `SC-SEC-001` уже применён при интеграции либо его diff готов к применению первым.

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
    String locale,
    String currency,
    String timezone,
    String representationHash
) {}
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
- representation hash covers the complete public response in deterministic field/list
  order, excluding DB revision, Host, internal singleton ID and secrets;
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

Rules:

- resolve the restaurant only through `DefaultRestaurantQuery`; no Host/slug/context;
- repository performs bounded ordered reads from final `menu_versions`,
  `menu_categories`, `menu_items`; no N+1;
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

### Pre-contract gates in V7

The migration must raise stable diagnostics before destructive DDL when any check fails:

1. the V6 bidirectional `EXCEPT ALL` refresh comparison reports any difference across
   every migrated table/column;
2. site configuration is not empty or exactly one row; populated site is not `active`;
3. active/default restaurant cardinality is not exactly one for populated data;
4. current published menu cardinality is invalid;
5. any `sc_assets.storage_state <> 'canonical_ready'`, canonical object key is invalid,
   or active asset still depends on `legacy_object_key`;
6. any V5 ledger table contains a row; non-empty ledger requires export/retention decision
   outside this task;
7. an order history row contains `tenant_admin` or `platform_admin`; mapping historical
   roles requires explicit ADR/data decision, not a silent rewrite;
8. any `sc_outbox_events.payload` contains a tenant routing field or any destination has
   an invalid opaque reference;
9. orphan/collision checks from V6 no longer hold.

Flyway must not call S3. Object copy/hash verification occurs before V7. A failed gate
rolls back the entire migration and leaves V1–V6 intact.

### Contract transaction order

Within one PostgreSQL/Flyway transaction and maintenance window:

1. after every old reader/writer/worker is drained, acquire an advisory lock dedicated to
   `SC-BE-001`;
2. call `sc_refresh_single_client_shadow()`. It reacquires all V1–V5 source `SHARE` locks
   in the V6-defined stable order, locks/truncates all shadow tables, backfills them and
   performs the bidirectional `EXCEPT ALL` preservation check. Do not copy this SQL into
   V7 or run a weaker count-only refresh;
3. run all remaining pre-contract gates while those transaction locks are still held;
4. drop old tables in FK-safe order:
   `legacy_import_run_assets`, `legacy_import_run_tenants`, `legacy_import_runs`,
   `notification_deliveries`, `outbox_events`, `notification_destinations`,
   `order_status_history`, `order_items`, `orders`, `menu_items`, `menu_categories`,
   `menu_versions`, `tenant_order_counters`, `tenant_branding`, `tenant_profiles`,
   `tenant_features`, `tenant_assets`, `restaurants`, `tenant_domains`, `tenants`;
5. rename all `sc_*` tables to final names, including `sc_site_profile` and
   `sc_order_counter`, and rename constraints/indexes so live metadata has no `sc_` or
   tenant naming;
6. for `assets`, require canonical readiness, drop `legacy_object_key` and
   `storage_state`, then rename `canonical_object_key` to `object_key`;
7. replace site status constraint with single-site operational values `active/inactive`;
   old suspended/deleted data must already have failed the preflight;
8. replace order actor constraint with target values such as
   `system/customer/client_admin/worker/import`; do not preserve tenant/platform admin as
   values writable by new code;
9. drop `sc_refresh_single_client_shadow()` and `sc_assert_single_client_source()`;
10. assert through catalog queries that no live table/column/constraint/index/policy name
    contains tenant scope, no table starts with `sc_`, and neither helper function exists.

Java entities/repositories map only final names. There is no dual-read, fallback to old
tables, runtime feature flag or compatibility tenant context. Deployment order is:

```text
enter maintenance and stop old writers/workers
→ apply/confirm V6 while writers remain stopped; never restart at the V6-only checkpoint
→ refresh, verify and copy canonical assets outside Flyway
→ deploy artifact containing V7 + single-client Java
→ Flyway V7 transaction
→ Hibernate validate
→ start new readers
→ run smoke/security checks
```

Rollback after V7 is restore/forward-fix from verified database/object backup, not running
the old binary against the contracted schema. Record this in the handoff.

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
- site feature/order-type/limits validation and deterministic hash;
- restaurant zero/one/multiple default behavior;
- menu ordering, hash, availability, money and asset-kind validation;
- notification redaction: secret reference absent from `toString`, exceptions and API;
- exact Modulith graph and no imports from `internal` packages.

### PostgreSQL/Testcontainers

- clean V1→V7 produces only final tables and Hibernate validation succeeds;
- V1–V5 empty → V6 → V7 succeeds;
- V6 snapshot followed by a legacy write, maintenance drain and V7 refresh preserves the
  late write; source locks prevent a concurrent writer during refresh;
- one complete owner graph preserves IDs/counts/order amounts/snapshots and reads through
  final repositories, provided asset rows are marked canonical-ready by fixture;
- V7 refuses non-empty V5 ledger, legacy-only asset, old actor role, invalid singleton,
  orphan and collision without dropping source/shadow tables;
- catalog assertion: no live `tenant_id`, tenant table/index/FK, `sc_*` object or composite
  tenant FK, and both V6 helper functions are absent;
- query-count tests prove config/menu use bounded reads and one asset batch.

### HTTP

- `/config` and `/menu` work without slug; legacy slug routes return 404/403 and never
  select data;
- same allowed Host aliases produce byte-equivalent bodies and ETags;
- Host/Forwarded/X-Forwarded-Host/query/header/body tenant values do not affect data;
- config/menu `200`, ETag/cache tags and generic failures match OpenAPI; no unsupported
  conditional `304` flow is advertised;
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
Dataset state: <empty | approved canonical-ready dataset evidence>
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
- Tenancy and legacy import modules/runtime are absent; no fake global tenant remains.
- V7 is append-only, fail-closed and verified from clean and V1–V5 upgrade paths.
- Live schema, Java, logs, cache tags, asset paths, outbox payload and notification
  metadata contain no tenant scope.
- OpenAPI/generated client remain unchanged and synchronized.
- All required tests/commands pass and the handoff contains evidence.
- Executor has not committed.
