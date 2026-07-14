# Single-client: влияние на PostgreSQL и backend

Дата аудита: 2026-07-14
Область: `apps/api`, `packages/api-contract`, `apps/api/src/main/resources/db/migration`, runtime-конфигурация и связанные тесты.
Статус: read-only impact analysis после решения отказаться от multi-tenancy.

## Итог

Текущий backend не является «слегка tenant-aware»: `tenant_id` — составная часть почти
каждого ключа, внешнего ключа, repository API, public request flow, cache key и object
storage key. Удалять `TenantResolver` отдельно нельзя. Это оставит ложную изоляцию в
схеме и заставит передавать фиктивный `TenantId` по всем слоям.

Целевое состояние должно быть настоящим single-client:

- public API не содержит `{tenantSlug}` и не разрешает tenant по Host/path;
- в runtime нет `TenantId`, `TenantContext`, tenant lifecycle и tenancy module;
- business configuration становится singleton site configuration;
- restaurant, menu, orders, assets, destinations и outbox сохраняют свои IDs и
  поведение, но не содержат `tenant_id`;
- rate limit имеет client-IP scope и один общий application scope;
- cache invalidation использует `site`/`restaurant`/`menu-version`, а не tenant;
- новые object keys не содержат tenant UUID;
- V5 legacy importer выводится из эксплуатации: он предназначен ровно для двух
  tenant и противоречит новой модели.

Рекомендуемый путь для этого workspace — **clean baseline**, но только после явного
подтверждения, что V1–V5 не применялись ни в одной сохраняемой shared/staging/production
БД. Локальный PostgreSQL на момент аудита запущен и healthy, но база `emenu` пуста:
нет `flyway_schema_history`, пользовательских таблиц и PostgreSQL policies. Это сильный
сигнал в пользу замены ещё не выпущенного baseline, но не доказывает состояние других
окружений.

Если хотя бы одна сохраняемая БД уже имеет V1–V5, существующие migrations не меняются:
нужна append-only `V6__single_client_schema.sql` с fail-closed preconditions и переносом
данных. Объединение двух tenant автоматически запрещено.

## Фактическое состояние схемы V1–V5

PostgreSQL Row-Level Security отсутствует: в V1–V5 нет `ENABLE ROW LEVEL SECURITY` и
`CREATE POLICY`. Изоляция реализована application checks, `tenant_id`, composite FK и
unique constraints.

| Migration | Таблица | Tenant scope и связи | Single-client действие |
|---|---|---|---|
| V1 | `tenants` | root: `id`, slug, lifecycle, locale/currency/timezone | заменить singleton `site_settings`; slug/lifecycle tenant удалить |
| V1 | `tenant_domains` | FK `tenant_id -> tenants`; hostname global unique | удалить; canonical/public host хранить в deployment config; если нужен runtime domain registry — создать `site_domains` без owner FK отдельным решением |
| V1 | `tenant_branding` | `tenant_id` PK; JSON branding/SEO | `site_branding` с singleton row |
| V1 | `tenant_profiles` | `tenant_id` PK | `site_profile` с singleton row |
| V1 | `tenant_features` | PK `(tenant_id, feature_key)` | `site_features`, PK `feature_key`; сохранить 13 typed feature rows |
| V1 | `restaurants` | FK tenant; slug/default/status tenant-scoped | удалить `tenant_id`; `slug` global unique; один global default restaurant |
| V1 | `tenant_order_counters` | один counter на tenant | `order_counter` singleton или PostgreSQL sequence; сохранить `next_value` |
| V2 | `menu_versions` | composite FK `(tenant_id, restaurant_id)`; version/current publication tenant-scoped | FK только `restaurant_id`; unique `(restaurant_id, version)`; один published на restaurant |
| V2 | `menu_categories` | composite FK к menu version; public ID tenant/version scoped | FK `menu_version_id`; unique `(menu_version_id, public_id)` |
| V2 | `menu_items` | composite FK к version/category | FK к `menu_version_id` и `(menu_version_id, category_id)`; public ID scoped menu version |
| V3 | `orders` | restaurant/menu/idempotency/number включают tenant | удалить `tenant_id`; idempotency key и order number сделать global unique; сохранить server-side totals/snapshots |
| V3 | `order_items` | composite FK к order и menu item через tenant | FK `(order_id, menu_version_id)` и `(menu_version_id, menu_item_id)` |
| V3 | `order_status_history` | composite FK к order через tenant | FK `order_id`; actor values `tenant_admin`/`platform_admin` заменить versioned role semantics, не переписывая исторические значения молча |
| V4 | `tenant_assets` | FK tenant; content/object uniqueness tenant-scoped | `assets`; global unique `object_key` и `(sha256, asset_type)` |
| V4 | branding/menu asset FK | `(tenant_id, asset_id) -> tenant_assets` | простые FK `asset_id -> assets(id)` с сохранением kind checks в application layer |
| V4 | `notification_destinations` | tenant owner, unique `(tenant_id, channel, display_name)` | global destination registry, unique `(channel, display_name)` |
| V4 | `outbox_events` | tenant FK и tenant aggregate index | удалить `tenant_id`; сохранить aggregate/event/retry/lease fields; новые payload schema не содержат tenant |
| V4 | `notification_deliveries` | tenant composite FK к event/destination | простые FK `outbox_event_id`, `destination_id`; unique `(event,destination,attempt)` |
| V5 | `legacy_import_runs` | сам не содержит `tenant_id`, но описывает dual-tenant staged import | importer остановить; ledger экспортировать/удалить по retention policy |
| V5 | `legacy_import_run_tenants` | tenant slug, live/snapshot IDs, FK к `tenants` | не адаптировать в production API; удалить после подтверждения empty/retention |
| V5 | `legacy_import_run_assets` | tenant/live asset composite FK и tenant object provenance | удалить после empty/retention и завершения orphan audit |

### Важные зависимости FK

Удаление должно идти от листьев к корням. Текущая цепочка:

```text
tenants
├─ tenant_domains
├─ tenant_branding ── tenant_assets
├─ tenant_profiles
├─ tenant_features
├─ restaurants
│  └─ menu_versions
│     ├─ menu_categories ── tenant_assets
│     ├─ menu_items ─────── tenant_assets
│     └─ orders
│        ├─ order_items ─── menu_items
│        └─ order_status_history
├─ tenant_order_counters
├─ tenant_assets
├─ notification_destinations
├─ outbox_events
│  └─ notification_deliveries ── notification_destinations
└─ legacy_import_run_tenants
   └─ legacy_import_run_assets ── tenant_assets
```

UUID primary keys уже global, поэтому для ровно одного сохраняемого tenant IDs можно
перенести без remap. Tenant-scoped natural keys становятся global только внутри выбранного
набора данных.

## Целевая single-client схема

Минимальная схема, сохраняющая существующее поведение:

```text
site_settings              # singleton: locale, currency, timezone, active state
site_branding              # singleton: logo/favicon refs, tokens, localized copy, hero, SEO
site_profile               # singleton: public contacts, schedule, location, social links
site_features              # feature_key PK, enabled, typed config
restaurants                # global IDs/slugs, exactly one default
order_counter              # singleton next_value, либо database sequence
menu_versions
menu_categories
menu_items
assets
orders
order_items
order_status_history
notification_destinations
outbox_events
notification_deliveries
```

Для singleton tables использовать явный `singleton_id smallint PRIMARY KEY CHECK
(singleton_id = 1)`. Не вводить фиктивный `client_id`, константный tenant UUID или
`WHERE tenant_id = DEFAULT_TENANT`: это сохраняет multi-tenant сложность без изоляционной
ценности.

Поведение, которое необходимо сохранить:

- locale/currency/timezone, branding, public profile, SEO и все 13 feature rows;
- один active default restaurant и возможность хранить несколько restaurant records;
- immutable versioned published menu, порядок categories/items, minor-unit prices;
- order idempotency, server-side price calculation, item snapshots и status history;
- S3-compatible assets, content hash, type/status и CDN URL;
- opaque notification secret references;
- transactional outbox, lease/retry/failed state и delivery attempts.

## Выбор стратегии Flyway

### Вариант A — clean baseline (предпочтителен сейчас)

Применять только если владелец подтверждает, что нет сохраняемой БД с V1–V5 и нет
выпущенного артефакта, который обязан обновляться с этой истории.

1. Зафиксировать решение отдельным ADR и записать перечень проверенных environments.
2. Удалить V1–V5 до первого shared release.
3. Создать новый `V1__single_client_baseline.sql` сразу с целевой схемой.
4. Не создавать `tenants`, `tenant_domains`, `tenant_*`, legacy import ledger и
   tenant composite constraints даже временно.
5. Поднять чистую PostgreSQL, применить baseline и сгенерировать/проверить JPA schema.

Почему вариант обоснован: локальная БД фактически пустая, V5 importer не завершён, а
append-only V6 сначала создаст и затем сразу демонтирует архитектуру, которая ещё не
обслуживает данные. Цена лишней migration history и compatibility code здесь выше
ценности upgrade path.

Блокирующее условие: одного неизвестного staging/production schema history достаточно,
чтобы clean baseline стал недопустим.

### Вариант B — append-only V6

Если V1–V5 уже применялись, использовать shadow-table migration, а не длинную серию
`DROP tenant_id` на связанных таблицах.

#### Fail-closed preflight

V6 должна остановиться до DDL, если:

- `tenants` содержит больше одной строки;
- любая runtime table содержит `tenant_id`, отличный от единственного выбранного ID;
- существует незавершённый V5 run или V5 audit retention не подтверждён;
- есть orders/outbox processing во время maintenance window;
- object inventory не подтверждает каждый active asset;
- обнаружены duplicate global keys после удаления scope.

При двух tenant не выбирать строку по `active`, slug, времени или lexical order.
Автоматическое merge/delete запрещено. Оператор обязан отдельно выбрать сохраняемого
клиента, экспортировать второй dataset и утвердить destructive migration.

#### Порядок V6

1. Закрыть writes/worker, взять application advisory lock, сделать backup и immutable
   table/object inventory.
2. Проверить preconditions и зафиксировать единственный source tenant UUID только внутри
   migration transaction.
3. Создать новые `_single` tables в dependency order: singleton config/assets/restaurants,
   menu, orders, destinations/outbox/deliveries.
4. Копировать только строки выбранного tenant, сохраняя UUID, timestamps, JSON, menu/order
   snapshots и secret references. Для пустой БД создать schema без seed.
5. Создать новые простые FK/unique/indexes; затем сравнить row counts и canonical table
   digests source/target.
6. Удалить старые child tables в обратном dependency order и атомарно переименовать
   `_single` tables.
7. V5 tables: если пусты — удалить в V6; если содержат audit records — V6 обязана
   остановиться до согласованного export/retention migration, а не обнулять FK молча.
8. Commit; после commit выполнить API smoke и outbox/order integrity checks.

Shadow copy требует дополнительного места, зато позволяет проверить target constraints
до удаления source и существенно проще composite-FK surgery.

#### Object storage отдельно от V6

Текущий key policy: `tenants/{tenantUuid}/{assetKind}/{sha256}.{ext}`. PostgreSQL и S3
не образуют одну транзакцию. Поэтому для непустого asset registry нужен staged шаг:

1. `HEAD` source object, сверить size/hash/metadata.
2. Идемпотентно copy в `objects/{assetKind}/{sha256}.{ext}`.
3. Повторно сверить target.
4. В database maintenance transaction переключить `assets.object_key`.
5. После rollback window удалить старый object только при отсутствии DB references.

Новые writes сразу используют single-client key. До завершения копирования допустим
узкий read-only compatibility resolver для зарегистрированных legacy keys; он имеет
явную дату удаления. Критерий завершения — ни один active DB record не содержит
`tenants/` prefix.

## Dependency-ordered карта backend изменений

Ниже порядок реализации. Файлы перечислены относительно repository root.

### 1. Контракт и module boundaries

- `packages/api-contract/openapi/openapi.yaml`: заменить
  `/api/v1/public/{tenantSlug}/{restaurant|menu|orders}` на
  `/api/v1/public/{restaurant|menu|orders}`; удалить `TenantSlug`, `TenantNotFound`,
  tenant cache descriptions; сохранить DTO без internal IDs.
- `packages/api-contract/tests/contract.test.mjs`: обновить path/parameter/error/cache tests.
- `packages/api-contract/README.md`, `DECISIONS.md`: убрать host/path tenancy decision.
- `packages/api-contract/generated/typescript-fetch/**`: только regenerate, не править вручную.
- `apps/api/src/main/java/com/salamu/menu/{identity,restaurant,menu,notification}/package-info.java`:
  удалить `tenancy::api` dependencies.
- `apps/api/src/main/java/com/salamu/menu/tenancy/package-info.java` и
  `tenancy/api/package-info.java`: удалить вместе с модулем после переноса assets/site config.

Рекомендуемые новые module boundaries:

```text
site          # singleton branding/profile/features/settings
asset         # registry, content policy, S3/CDN
restaurant -> site::api, asset::api
menu       -> asset::api
identity      # без tenant dependency
notification  # без tenant dependency
ordering -> restaurant/menu/notification APIs по мере реализации use case
```

### 2. Вывести временный dual-tenant importer

- удалить `apps/api/src/main/java/com/salamu/menu/migration/**`, включая
  `JdbcLegacyImportLedger`, `LegacyImportTenantPlan`, `LegacyImportTenantProvenance`,
  `LegacyImportAssetPlan`, state/status/fingerprint types;
- удалить `apps/api/src/test/java/com/salamu/menu/migration/**`, включая
  `LegacyImportLedgerPostgreSqlIntegrationTests` и
  `MigrationTransactionBoundaryIntegrationTests`;
- удалить `legacy-import` profile/wiring и provisioning APIs, созданные только для V5;
- V5 SQL не редактировать при append-only стратегии; при clean baseline V5 не переносить.

До удаления проверить отсутствие внешнего consumer и незавершённых ledger rows.

### 3. Разделить текущий tenancy module на site и asset

Удалить без замены request-resolution slice:

- `tenancy/api/TenantId.java`, `TenantContext.java`, `TenantResolution.java`,
  `TenantRequestAccess.java`, `TenantLifecycle.java`;
- `tenancy/internal/application/TenantResolver.java`, `TenantRegistry.java`,
  `PlatformHostPolicy.java`, `TenantNotFoundException.java`;
- `tenancy/internal/domain/HostNormalizer.java`, `TenantRegistration.java`;
- `tenancy/internal/web/TenantResolutionFilter.java`, `TenantPathSlugExtractor.java`,
  `TenantContextArgumentResolver.java`, `TenantRequestAttributeAccess.java`,
  `TenancyWebConfiguration.java`, `EffectiveHostResolver.java`;
- `tenancy/internal/infrastructure/config/ConfiguredPlatformHostPolicy.java`,
  `PlatformHostsProperties.java`, `ConfiguredTrustedProxyPolicy.java`,
  `TrustedForwardingProperties.java` после проверки, что они не нужны отдельному
  deployment host allowlist.

Перенести и переименовать в `asset` module:

- public API: `TenantAssetId -> AssetId`, `TenantAsset -> Asset`, `TenantAssetService ->
  AssetService`, `AssetKind`, import request/exceptions;
- application: `AssetContentPolicy`, `AssetKeyPolicy`, `AssetObject`, `AssetObjectKey`,
  `AssetObjectStorage`, `AssetPersistence`, `AssetRecord`, `AssetSha256`, `AssetStatus`,
  `TenantAssetApplicationService`;
- infrastructure: `S3AssetObjectStorage`, `CdnAssetPublicUrlResolver`,
  `TenantAssetProperties`, `TenantAssetStorageConfiguration`,
  `TenantAssetPersistenceAdapter`;
- persistence entity/repository parts `TenantAssetEntity` и `TenantAssetJpaRepository`
  из `TenancyPersistenceEntities.java`/`TenancyJpaRepositories.java`.

Каждый asset method теряет `TenantId`; ownership заменяется проверкой существования,
kind, status и exact content hash. Batch limit 500 и active-only public resolution
сохраняются.

Перенести в `site` module, заменив tenant snapshot на singleton snapshot:

- `TenantConfigurationSnapshot`, `TenantFeatureKey`, `TenantSemanticHash`;
- branding/profile/feature persistence из `TenancyPersistenceEntities.java` и
  `TenancyJpaRepositories.java`;
- `TenantConfigurationHasher` после смены schema marker;
- обычный site configuration writer/reader, если он реально нужен.

Удалить migration-only provisioning types и implementations:

- `TenantProvisioningService`, `SuspendedTenantSpec`, `TenantActivationCandidate`,
  `TenantCleanupCandidate`, `ProvisioningRunFingerprint`, `TenantProvisioningState`;
- `TenantProvisioningApplicationService`, `TenantProvisioningRepository`,
  `JdbcTenantProvisioningRepository`;
- `TenantAssetProvisioningService` и все provisioning provenance/cleanup DTO,
  `TenantAssetProvisioningApplicationService`,
  `TenantAssetProvisioningPersistenceAdapter`, если нет нового подтверждённого consumer.

### 4. Restaurant public read и persistence

- `restaurant/internal/web/PublicRestaurantController`: route без slug; убрать
  `TenantContext`; cache tags заменить на `site-restaurant` и при необходимости
  `restaurant-{restaurantSlug}`.
- `restaurant/internal/application/PublicRestaurantQuery#get(TenantContext)` -> `get()`;
  убрать tenant/slug cross-check, site config читать из `site::api`, assets из `asset::api`.
- `PublicRestaurantConfigurationRepository#findActiveDefault(TenantId)` ->
  `findActiveDefault()`; `findFeatures(TenantId)` становится site query.
- `JdbcPublicRestaurantConfigurationRepository`: убрать `tenant_id` predicates/join к
  `tenants`; читать `site_*` и global default restaurant.
- `RestaurantEntity`, `RestaurantJpaRepository`: убрать tenant field и tenant-derived
  repository methods; global slug/default constraints.
- migration-only `restaurant/api/{RestaurantProvisioningService,DefaultRestaurantSpec,
  DefaultRestaurantSnapshot,DefaultRestaurantCleanupCandidate}` и
  `RestaurantProvisioningApplicationService`, `RestaurantProvisioningRepository`,
  `JdbcRestaurantProvisioningRepository`, `RestaurantProvisioningHasher` удалить либо
  заменить обычным admin use case только по отдельной задаче, не сохранять ради importer.

### 5. Menu public read и persistence

- `menu/internal/web/PublicMenuController`: route без slug и `TenantContext`; ETag больше
  не включает tenant slug; cache tag `site-menu` плюс version.
- `PublicMenuQueryService#getCurrentPublished(TenantContext)` -> `getCurrentPublished()`;
  default restaurant определяется server-side; asset lookup через `AssetService`.
- `PublicMenuReadRepository`: убрать `TenantId` из `findCurrentPublished`,
  `findCategories`, `findItems`.
- `JdbcPublicMenuReadRepository`: убрать все `tenant_id = ?`; ограничивать rows точными
  menu version/default restaurant FK.
- `MenuPersistenceEntities.java`, `MenuJpaRepositories.java`: убрать tenant fields и
  `findByTenantId...` methods; сохранить version/category/item relationships.
- `PublishedMenuSnapshot`, `MenuCleanupCandidate`, `MenuProvisioningService`,
  `MenuProvisioningApplicationService`, `MenuProvisioningRepository`,
  `JdbcMenuProvisioningRepository`, `DeterministicMenuIds` и `MenuAssetReference`:
  убрать TenantId/provenance либо удалить, если они остаются только MIG-001A API.

### 6. Ordering persistence и будущий use case

- `OrderingPersistenceEntities.java`: `TenantOrderCounterEntity -> OrderCounterEntity`;
  удалить tenant fields из `OrderEntity`, `OrderItemEntity`, `OrderStatusHistoryEntity`.
- `OrderingJpaRepositories.java`: заменить `findByTenantIdAnd...` на global ID,
  idempotency key, order number и order relation methods.
- будущий create-order use case обязан сохранять прежнюю transaction boundary:
  order + item snapshots + outbox event в одной transaction; price считается server-side.
- idempotency uniqueness становится `(idempotency_key)`, если публичный API имеет один
  order stream. Если ключ scoped по endpoint/version, это должно быть отдельным явным
  contract decision, не фиктивным tenant replacement.

Ordering сейчас содержит schema/entities/repositories, но полноценный create-order
application service/controller ещё отсутствует. Это лучший момент убрать tenant до
реализации write flow.

### 7. Notification и outbox

- `notification/package-info.java`: удалить `tenancy::api` dependency.
- `NotificationDestinationSpec`, `NotificationDestinationSnapshot`,
  `NotificationDestinationCleanupCandidate`, `NotificationProvisioningService` и
  `NotificationProvisioningApplicationService`: убрать `TenantId`; migration-only
  cleanup/write API удалить после retirement importer.
- `NotificationProvisioningRepository` и `JdbcNotificationProvisioningRepository`:
  global natural key `(channel, display_name)` и простые reference checks.
- `NotificationPersistenceEntities.java`: удалить tenant fields у
  `NotificationDestinationEntity`, `OutboxEventEntity`, `NotificationDeliveryEntity`.
- `NotificationJpaRepositories.java`: убрать все `find...ByTenantId...` signatures.
- `WorkerProperties` сохраняется. Реального scheduler/worker в текущем коде нет: есть
  только property, schema, entities и repositories. Новый worker должен poll global
  `outbox_events`, а destination получать из event/application data без tenant scope.
- новые outbox payload получают новую schema version без tenant ID. Исторические
  published payload не переписывать без event migration policy.

### 8. Identity, security, cache и observability

- `identity/package-info.java`: удалить tenancy dependency.
- `SecurityRequestMatchers`: paths без `{tenantSlug}`.
- `SecurityConfiguration`: обновить authorization, CSRF и CORS registrations.
- `PublicOrderRateLimitFilter`: удалить `TenantRequestAccess` и `TENANT_NOT_FOUND` branch;
  rate limit вызывается после trusted client IP resolution напрямую.
- `TenantRateLimiter` переименовать в `OrderRateLimiter`: client key = trusted IP (или
  privacy-preserving fingerprint), второй bucket один application-wide; удалить
  `Cache<TenantId, Bucket>` и tenant bucket cardinality.
- `application.yml`: `app.security.rate-limit.tenant` -> `application`; удалить
  `maximum-tenant-buckets`, `app.platform.hosts`, tenant resolution/trusted-forwarding
  properties, если forwarding больше нигде не нужен.
- MDC keys `tenantId`/`tenantSlug` из `TenantResolutionFilter` исчезают. Не заменять их
  constant client label; оставить trace/correlation и bounded business metrics.
- `PublicRestaurantController`/`PublicMenuController`: `Cache-Tag`/`Surrogate-Key` больше
  не `tenant-*`; invalidation привязать к site config/menu version.

### 9. Runtime infrastructure

- `TenantAssetProperties`, `AssetObjectKey`, `AssetKeyPolicy`,
  `AssetProvisioningDescriptor`, `AssetRecord`, `CdnAssetPublicUrlResolver`:
  убрать `tenants/{uuid}` format.
- `application-local.yml`, `.env.example`, `compose.yaml`, `infra/local/README.md`:
  bucket default `tenant-assets-local` переименовать в neutral `menu-assets-local`;
  миграция bucket не должна терять существующие objects.
- ADR-0001 больше не описывает target namespace; заменить новым ADR после выбора
  storage migration. ADR-0002 закрыть как superseded, не переписывать историю решения.

## Риски данных и обязательные решения

1. **Два существующих tenant.** Их нельзя слить без выбора владельца данных. Возможны
   duplicate order numbers, idempotency keys, restaurant slugs, destination names и
   asset natural keys. V6 обязана abort.
2. **Удаление второго tenant.** Это destructive business action, не технический default.
   Нужны export, owner approval, retention и rollback plan.
3. **Object storage.** DB commit не перемещает S3 object. Нужен staged copy/hash/repoint/
   delete workflow.
4. **Outbox in flight.** Нельзя менять event/destination FKs при `pending|processing`
   без drain или version-aware worker.
5. **Orders.** IDs должны сохраниться. Изменение order number/idempotency semantics может
   создать conflict только при merge; для одного выбранного tenant оно безопасно после
   uniqueness precheck.
6. **Cache/CDN.** Старые `tenant-*` tags и URL способны отдавать stale content после
   route switch. Нужна purge/versioned deployment.
7. **Host behavior.** Сейчас unknown/mismatched host/slug даёт tenant 404. После удаления
   resolver допустимые origins/hosts контролируются ingress/CORS, а public endpoint
   обслуживает единственный site. Это security behavior change, его нужно тестировать.
8. **Canonical SEO/domain.** Значение переносится в site config/deployment, но ownership
   verification перестаёт быть tenant lifecycle и становится deployment procedure.
9. **V5 retention.** Нельзя удалить ledger с audit records только потому, что importer
   отменён. Нужна проверка empty либо утверждённый export/retention.
10. **Rollback.** После schema swap rollback — restore backup/previous app. Down migration
    Flyway не является безопасным способом вернуть tenant composite constraints.

## Тестовая карта

### Schema/Flyway

- clean install новой single-client schema;
- upgrade V1–V5 -> V6 с пустой БД;
- upgrade с одним tenant и полным graph: config, restaurant, two menu versions, assets,
  orders/items/history, destination, outbox/delivery;
- V6 fail-closed при двух tenant, foreign tenant rows, duplicate будущих global keys,
  pending V5 run и processing outbox;
- source/target row counts, UUIDs, timestamps, JSON, money, hashes и FK equivalence;
- `information_schema.columns` не содержит `tenant_id`; runtime tables/FK/index names
  не содержат tenant; `pg_policies` остаётся пустым, если RLS не вводится отдельно;
- Hibernate `ddl-auto=validate` на upgraded и clean schema.

### Domain/repositories

- Modulith verification: tenancy module отсутствует, ни один module не зависит от
  `tenancy::api`, новые `site`/`asset` boundaries не цикличны;
- global default restaurant uniqueness;
- one published menu per restaurant, version/category/item constraints;
- asset kind/hash/status checks и batch resolution без ownership parameter;
- order idempotency replay/conflict и concurrent global order number allocation;
- outbox/delivery retry/lease queries без tenant predicate.

### HTTP/security/cache

- GET restaurant/menu и POST orders работают без slug;
- старые slug routes возвращают controlled 404/410 и не становятся alias навсегда;
- нет request attribute, argument resolver или MDC tenant fields;
- rate limit разделяет client IP buckets и защищает один application-wide capacity;
- CORS/CSRF matchers совпадают с новым OpenAPI;
- ETag остаётся стабильным для того же content, меняется при config/menu publication;
- cache tags не содержат tenant и корректно invalidated.

### Assets/outbox failure paths

- existing object key compatibility test на время staged migration;
- copy/HEAD/hash mismatch/orphan/idempotent rerun tests;
- ни один active asset после cutover не использует старый prefix;
- pending/processing outbox drain, retry и delivery FK integrity;
- новые payload/log/report не содержат tenant ID.

### Regression inventory

Переписать или удалить tenant-oriented suites:

- `tenancy/internal/application/{TenantResolverTests,TenantResolutionPostgreSqlIntegrationTests}`;
- `tenancy/internal/web/{TenantResolutionFilterTests,TenantContextArgumentResolverTests}`;
- `tenancy/internal/application/provisioning/*`;
- `tenancy/internal/application/asset/*` и
  `tenancy/internal/infrastructure/asset/TenantAssetMinioIntegrationTests` — перенести в asset;
- `restaurant/internal/{application,web}/*` tenant fixtures;
- `menu/internal/{application,web}/*` tenant fixtures;
- `identity/internal/security/{TenantRateLimiterTests,PublicOrderRateLimitFilterTests,
  SecurityIntegrationTests}`;
- `notification/internal/application/*Provisioning*`;
- `persistence/FlywaySchemaIntegrationTests`;
- все `migration/internal/**` tests удалить после V5 decision.

## Критерии полного удаления tenant runtime

Работа не завершена, пока не выполнены все условия:

- в `apps/api/src/main/java`, `apps/api/src/main/resources` и актуальном OpenAPI нет
  `TenantId`, `TenantContext`, tenant resolver/filter, `{tenantSlug}` или `tenant_id`;
- top-level module/package `com.salamu.menu.tenancy` отсутствует;
- `information_schema` не содержит runtime tenant tables/columns/composite tenant FK;
- API-generated client не содержит `tenantSlug` parameter или tenant errors;
- cache/rate-limit/MDC/storage keys не содержат tenant scope;
- active S3 registry не ссылается на `tenants/{uuid}/...`;
- новые outbox events/jobs не содержат tenant ID и обрабатываются без tenant context;
- site branding/profile/features/menu/orders/assets/destinations проходят regression tests;
- clean build, Modulith verification, Flyway clean/upgrade, Testcontainers, contract tests,
  object-storage tests и `git diff --check` зелёные;
- V5 ledger либо доказанно пуст и удалён, либо экспортирован и удалён после retention;
- старые migrations V1–V5 считаются допустимым историческим исключением для text search
  только при append-only V6; runtime compatibility code исключением не является.

## Решение, необходимое до реализации

Владелец должен подтвердить один факт: **существует ли хотя бы одна сохраняемая БД, где
Flyway уже применил V1–V5**.

- Нет: использовать clean baseline и не строить временный V6 upgrade machinery.
- Да: использовать append-only V6, выбрать единственный сохраняемый client dataset,
  отдельно решить retention второго dataset/V5 ledger и выполнить staged asset migration.

Без этого подтверждения начинать destructive schema edits нельзя.
