# Single-client API и security impact

- Status: proposed implementation map
- Date: 2026-07-14
- Scope: public API, request boundary, security, cache/rate limiting, module boundaries и generated client
- Architectural decision: приложение обслуживает ровно одного клиента; multi-tenancy удаляется полностью

## 1. Итоговое решение

Целевой runtime не должен определять бизнес-контекст по `Host`, path slug, header,
query parameter или скрытому constant tenant ID. В приложении существует одна
конфигурация заведения, одно текущее опубликованное меню и единый поток заказов.

Целевой public API:

```text
GET  /api/v1/public/config
GET  /api/v1/public/menu
POST /api/v1/public/orders
```

`tenantSlug`, `tenantId`, `TenantContext`, domain registry, tenant lifecycle и
tenant-scoped cache/rate/storage keys в целевой модели отсутствуют. `Host` остаётся
обычным HTTP/security input: ingress может проверять allowlist canonical host, но backend
не выбирает по нему данные, права, notification destination или configuration.

Нельзя реализовывать single-client модель как сохранение multi-tenant кода с одним
зашитым `tenantId`. Это оставит прежние failure modes, схему, module coupling и ложную
возможность выбора другого tenant. Временный migration adapter с выбранным source ID
допустим только на время переноса данных и не входит в runtime API.

## 2. Обязательные decision gates перед mutation

До изменения schema или импорта требуется явно зафиксировать два решения:

1. Какой legacy dataset становится единственным production client. В текущем контракте
   и migration artifacts одновременно представлены `stambul` и `adamas`; автоматически
   объединять их branding, menu, assets, order settings или notification destination
   нельзя.
2. Был ли текущий `/api/v1/public/{tenantSlug}/...` опубликован внешним consumers.
   Если нет, контракт можно исправить in-place до первого release. Если да, удаление
   path parameter является breaking change: нужен `/api/v2/public/...` и ограниченный
   compatibility window. Поддерживать slug route бессрочно запрещено, потому что это
   сохраняет multi-tenant surface.

Ни один из этих gates не требует runtime `clientId`. Выбранный source фиксируется в
одноразовом migration plan, а compatibility policy — в API ADR/release notes.

## 3. Подтверждённое текущее состояние

Аудит выполнен по текущим source files. В runtime коде, Flyway и OpenAPI найдено 868
упоминаний `tenant_id`, `tenantId`, `tenantSlug`, `TenantContext` или `TenantId` в 92
файлах. Это сквозная архитектурная зависимость, а не только URL parameter.

### 3.1 OpenAPI и generated client

`packages/api-contract/openapi/openapi.yaml` определяет ровно три операции:

| Method | Текущий path | Operation ID | Tenant surface |
|---|---|---|---|
| GET | `/api/v1/public/{tenantSlug}/restaurant` | `getPublicRestaurant` | required `TenantSlug`, tenant 404, tenant cache tags |
| GET | `/api/v1/public/{tenantSlug}/menu` | `getPublicMenu` | required `TenantSlug`, tenant 404, tenant cache tags |
| POST | `/api/v1/public/{tenantSlug}/orders` | `createPublicOrder` | required `TenantSlug`, tenant-scoped idempotency |

Дополнительные tenant-specific элементы:

- описание контракта и server description декларируют trusted host/custom domains;
- `TenantSlug` является reusable parameter;
- `TENANT_NOT_FOUND` входит в `ErrorCode` и error examples;
- `Cache-Tag`/`Surrogate-Key` имеют значения `tenant-*`;
- descriptions category/item IDs называют identifiers tenant-scoped;
- checked-in examples одновременно содержат Stambul и Adamas;
- `packages/api-contract/DECISIONS.md` закрепляет host + slug resolution;
- `packages/api-contract/tests/contract.test.mjs` требует старые paths, `TenantSlug`,
  tenant cache metadata и оба набора examples.

Generated `typescript-fetch` client переносит это напрямую:

```ts
getPublicRestaurant({ tenantSlug, ifNoneMatch })
getPublicMenu({ tenantSlug, ifNoneMatch })
createPublicOrder({ tenantSlug, idempotencyKey, createOrderRequest })
```

После изменения OpenAPI весь каталог
`packages/api-contract/generated/typescript-fetch/` должен быть regenerated. Ручные
правки generated files запрещены. Source-breaking effects для frontend: удаляется
`tenantSlug` из трёх request interfaces, `RestaurantApi` заменяется на configuration API,
меняются paths и descriptions, исчезает `TENANT_NOT_FOUND`.

### 3.2 Реализованные HTTP endpoints

В backend реализованы только два public read controller:

- `PublicRestaurantController` —
  `GET /api/v1/public/{tenantSlug}/restaurant`;
- `PublicMenuController` — `GET /api/v1/public/{tenantSlug}/menu`.

Оба получают `TenantContext` через custom MVC argument resolver. Restaurant controller
строит `Cache-Tag`/`Surrogate-Key` из slug. Menu controller дополнительно включает slug в
strong ETag (`menu-{tenantSlug}-v{version}-{hash}`).

Production `POST orders` пока не реализован: он существует в OpenAPI, generated client,
security matcher и test-only controller, но в `apps/api/src/main` нет order controller.
Это важно для sequencing: новый order endpoint нужно реализовывать сразу в single-client
форме, а не сначала переносить старый tenant route.

### 3.3 Request boundary и Host trust

Текущая цепочка:

```text
TenantPathSlugExtractor
→ TenantResolutionFilter
→ EffectiveHostResolver
→ TenantResolver
→ TenantRegistry/JpaTenantRegistry
→ TenantResolution request attribute
→ TenantContextArgumentResolver
→ controller/application query
```

Она использует:

- path prefix `/api/v1/public/{tenantSlug}/...`;
- direct `Host`, либо `Forwarded`/`X-Forwarded-Host` от trusted proxy;
- `tenant_domains` и active tenant lookup;
- fallback к active slug только для platform/local hosts;
- request attributes и MDC keys `tenantId`/`tenantSlug`.

Реализация корректно не доверяет клиентским `tenantId` header/query/body, однако сама
граница больше не нужна в single-client runtime. Удалению подлежат resolution filter,
path extractor, MVC argument resolver, request access, domain/host registry и весь
`TenantResolution`/`TenantContext` API.

`EffectiveHostResolver` также удаляется. Общая проверка trusted proxy для client IP
нужна rate limiter и должна быть перенесена из tenancy в identity/security package; она
не должна сохранять зависимость на tenancy ради одного `TrustedProxyPolicy`.

### 3.4 Public reads и persistence access

`PublicRestaurantQuery` и `PublicMenuQueryService` принимают `TenantContext`, а их
repository interfaces требуют `TenantId`. Все SQL projections фильтруют `tenant_id` и
проверяют active tenant. Asset lookup также требует tenant scope.

Это даёт следующие обязательные изменения:

- public application services становятся parameterless reads (`getCurrent()`);
- repository ports выбирают единственную active configuration/current published menu;
- отсутствие или множественность singleton state считается schema/configuration error;
- menu/restaurant lookup использует реальный `restaurantId` только как domain relation,
  не как замену tenant routing;
- asset API переходит на global content identity/asset ID без tenant owner;
- ни controller, ни request DTO не принимают internal scope ID.

### 3.5 Security и public/admin separation

Текущая `SecurityConfiguration` имеет две filter chains:

- management endpoints: health/info/prometheus разрешены, прочее запрещено;
- application: разрешены только три exact tenant public routes и probes, всё остальное
  `denyAll`.

Положительные свойства, которые нужно сохранить:

- stateless public chain;
- exact method/path allowlist;
- exact-origin CORS allowlist без wildcard и credentials;
- no form login/basic auth для public API;
- management boundary и default deny;
- trusted-proxy-aware client IP parsing;
- error responses без отражения forwarded input.

Текущий код не реализует настоящий tenant-admin или platform-admin endpoint/authentication:
такие paths присутствуют только в security test и ожидаемо получают `403`. В single-client
модели не должно появляться `platform-admin`/`tenant-admin`. Если admin API понадобится,
единственный контур — authenticated operator/admin с отдельной filter chain и audit trail.

Текущий CSRF ignore допустим только для public stateless order endpoint без cookies.
Будущий cookie-based admin contour обязан иметь собственную CSRF policy; public ignore
нельзя расширять на `/api/v1/admin/**`.

### 3.6 Rate limiting

`PublicOrderRateLimitFilter` сейчас:

- применяется только к POST tenant order path;
- требует resolved `TenantContext`;
- возвращает `TENANT_NOT_FOUND`, если context отсутствует;
- вызывает `TenantRateLimiter`.

`TenantRateLimiter` имеет два bucket scope:

```text
(tenantId, trustedClientIp)
tenantId aggregate
```

В target нужны:

```text
trustedClientIp          # client abuse boundary
global order-create      # aggregate protection единственного приложения
```

Ключ не включает `Host`, slug, phone, order ID или произвольный header. Класс должен
стать `OrderRateLimiter`; tenant cache/config properties переименовываются в global.
In-memory Caffeine/Bucket4j остаётся корректным только для single instance. При
горизонтальном deployment aggregate limit является per-instance; distributed limiter
требует отдельного измеримого решения (например, Redis), но не нужен заранее.

OpenAPI сейчас обещает `429` также на GET config/menu, хотя application rate-limit filter
ограничивает только POST orders. Целевой контракт должен быть честным: либо read limiting
реально обеспечивается edge/application и `429` остаётся, либо `429` удаляется из GET
responses. Нельзя оставлять документированное поведение без реализации.

### 3.7 Cache/CDN

Текущий cache surface tenant-scoped:

- path содержит slug;
- config/menu ETag включает tenant representation/slug;
- `Cache-Tag` и `Surrogate-Key` используют `tenant-{slug}`;
- assets хранятся под `tenants/{tenantId}/...`.

Целевые безопасные keys:

```text
GET /api/v1/public/config → site-config, config-{semanticHash}
GET /api/v1/public/menu   → menu, menu-v{version}, menu-{semanticHash}
asset object             → assets/{kind}/{contentHash}.{extension}
```

Один canonical origin и URL являются естественной cache namespace. Host не участвует в
business cache key. Ingress должен отклонять неизвестный Host (обычно `421`/`400`) до CDN
origin caching, но это защита HTTP authority/cache poisoning, а не выбор данных.

Рекомендуемые headers:

- strong representation `ETag` без slug;
- текущие явные `Cache-Control` policies;
- `Cache-Tag: site-config` для config;
- `Cache-Tag: menu` и `Surrogate-Key: menu menu-v{version}` для menu;
- `Vary` только по реально поддерживаемым content negotiation inputs, не по tenant/Host;
- `no-store` для order writes и error responses с чувствительным request context.

CORS exposed headers следует синхронизировать с client behavior. Если frontend вручную
использует conditional requests, нужно expose как минимум `ETag`, `Cache-Control`,
`Retry-After`, correlation/trace headers. Generated JSON convenience method может
попытаться разобрать bodyless `304`; wrapper/frontend tests должны использовать raw
response либо полагаться на browser/server cache, не редактируя generated runtime.

### 3.8 Error model

Envelope остаётся правильным и стабильным:

```json
{
  "code": "MENU_CHANGED",
  "message": "Меню обновилось, проверьте корзину",
  "details": [{"field": "clientMenuVersion", "reason": "STALE_VERSION"}],
  "traceId": "..."
}
```

Целевой набор public codes:

| Code | HTTP | Назначение |
|---|---:|---|
| `VALIDATION_ERROR` | 400 | malformed structure/field validation |
| `MENU_NOT_FOUND` | 404 | опубликованное меню отсутствует |
| `MENU_CHANGED` | 409 | submitted menu version устарела |
| `ITEM_UNAVAILABLE` | 409 | item более недоступен |
| `TOTAL_MISMATCH` | 409 | server-calculated total отличается |
| `IDEMPOTENCY_CONFLICT` | 409 | key повторно использован с другим payload hash |
| `RATE_LIMITED` | 429 | client/global order limit |
| `SITE_NOT_READY` | 503 | обязательная singleton config/dependency не готова |
| `INTERNAL_ERROR` | 500 | redacted unexpected error |

`TENANT_NOT_FOUND` и detail field `tenantSlug` удаляются. Missing/corrupt единственная
configuration — не неизвестный пользовательский resource, а readiness/operational
failure (`SITE_NOT_READY`); unpublished menu остаётся предметным `MENU_NOT_FOUND`.

`ApiErrorHandler` сейчас сводит любой `404` к `TENANT_NOT_FOUND`, а validation details
возвращает пустыми, несмотря на более точные OpenAPI examples. Нужны typed application
exceptions и единый error writer. `PublicOrderRateLimitFilter` не должен вручную собирать
JSON строку отдельно от общего envelope: это создаёт drift и обход serializer/contract
tests. Provider/SQL exception messages, PII и request headers в ответ не включаются.

## 4. Целевой OpenAPI-first контракт

### 4.1 Operations

| Method/path | Operation ID | Request | Success | Cache |
|---|---|---|---|---|
| `GET /api/v1/public/config` | `getPublicConfiguration` | optional `If-None-Match` | `200 PublicConfigurationResponse`, `304` | public, conditional |
| `GET /api/v1/public/menu` | `getPublicMenu` | optional `If-None-Match` | `200 MenuResponse`, `304` | public, conditional |
| `POST /api/v1/public/orders` | `createPublicOrder` | `Idempotency-Key` + `CreateOrderRequest` | `201 CreateOrderResponse` | `no-store` |

Ни одна операция не имеет selector parameter. `tenantId`, `tenantSlug`, `businessSlug`,
`clientId`, `X-Tenant-*` и routing fields запрещены в headers/query/path/body.

`PublicConfigurationResponse` сохраняет branding, SEO, public profile, features,
available order types, limits, locale/currency/timezone и стабильный `restaurantId`.
Текущий top-level `slug` удаляется: singular URL не нуждается в tenant slug. Если
restaurant slug нужен будущему admin resource, он остаётся внутренним domain field и не
выбирает public configuration.

`MenuResponse.restaurantId` можно сохранить для согласования cart/menu/order telemetry;
он является ID business resource, а не scope selector. Клиент не передаёт его при order
creation: backend использует текущий active restaurant/menu и повторно валидирует
`clientMenuVersion` и item IDs.

### 4.2 Idempotency

В single-client API `Idempotency-Key` остаётся обязательным UUID header. Database
uniqueness становится глобальной для order endpoint:

```text
UNIQUE (idempotency_key)
```

Повтор с тем же canonical payload hash возвращает исходный результат. Тот же key с
другим hash возвращает `409 IDEMPOTENCY_CONFLICT`. Host, client IP и user-entered phone
не входят в idempotency namespace. Order number/counter также становится singleton,
без tenant prefix/sequence scope.

### 4.3 Generated client after regeneration

Ожидаемые TypeScript signatures:

```ts
getPublicConfiguration({ ifNoneMatch? })
getPublicMenu({ ifNoneMatch? })
createPublicOrder({ idempotencyKey, createOrderRequest })
```

OpenAPI Generator по-прежнему может требовать request object даже при одном optional
parameter; frontend обязан использовать реально сгенерированную signature. Результат
проверяется `check-generated.mjs`, TypeScript compile и contract tests. Старые
`tenantSlug` request interfaces и generated docs не сохраняются compatibility aliases.

## 5. Целевая security model

### Public boundary

- Exact allowlist только для трёх method/path pairs и probes.
- Stateless, anonymous public reads/order creation.
- Exact CORS origins; credentials `false`.
- Public order CSRF-ignore только пока endpoint не использует cookie authentication.
- Order body size, JSON depth, field lengths и item count ограничены до application use case.
- Trusted client IP принимается из forwarding headers только от configured trusted proxy.
- Rate limiting выполняется до expensive validation/DB work, но через единый error model.
- `Idempotency-Key` валидируется до transaction; payload hash считается backend.

### Host boundary

- Host не выбирает configuration, menu, rate scope, authorization или destination.
- Canonical host/alias allowlist на ingress предотвращает DNS rebinding/cache poisoning.
- Forwarded host вообще не нужен application business flow и не читается.
- Absolute canonical/asset URLs берутся из validated deployment configuration, а не из
  request Host.
- Тестовый запрос с другим разрешённым host alias возвращает ту же representation/ETag.

### Admin boundary

- Текущий default-deny сохраняется; до реализации auth admin routes отсутствуют.
- Будущий `/api/v1/admin/**` получает отдельную ordered filter chain, authentication,
  `ROLE_OPERATOR`, audit trail и отдельную CORS/CSRF policy.
- Роли `TENANT_ADMIN` и `PLATFORM_ADMIN`, tenant ownership checks и host-based admin
  selection удаляются; single-client operator не передаёт scope.
- Actuator остаётся на private management port в production.

## 6. Целевые Spring Modulith boundaries

Tenancy module удаляется. Его asset/storage ответственность переносится в реальный
отдельный `asset` module; это не искусственная abstraction, потому что уже существуют S3,
CDN, persistence и test boundaries.

```text
com.salamu.menu
  asset
  restaurant
  menu
  ordering
  notification
  identity
```

Целевые dependencies:

| Module | Allowed dependencies | Ownership |
|---|---|---|
| `asset` | none | content validation, object storage, registry, public URL |
| `restaurant` | `asset::api` | singleton public config, profile, features, restaurant |
| `menu` | `asset::api`, `restaurant::api` | published menu and item/category reads |
| `ordering` | `menu::api`, `restaurant::api` | idempotent order transaction and outbox |
| `notification` | `ordering::api` | destination config and outbox delivery |
| `identity` | none | public security, trusted proxy/IP, future operator auth |

Правила:

- `TenantId` не переезжает в `shared` под другим именем.
- Public queries не получают fake scope object.
- Если menu нужен typed restaurant ID, тип экспортируется `restaurant::api`.
- Notification destination является единственной configured destination set, не
  определяется из request Host.
- Temporary `migration` module перепроектируется для одного выбранного dataset и
  удаляется после cutover; существующие tenant provisioning APIs не становятся частью
  target modules.
- `ModularityTests` в конечном состоянии ожидает шесть модулей выше, отсутствие tenancy
  и отсутствие cycles/internal imports.

## 7. Точная карта изменений

### 7.1 Contract package

1. `packages/api-contract/openapi/openapi.yaml`
   - заменить три paths на singular paths;
   - удалить `TenantSlug`, `TenantNotFound`, `TENANT_NOT_FOUND`, host/custom-domain text;
   - rename restaurant operation/schema в configuration terminology;
   - удалить response `slug` и tenant-scoped descriptions/cache examples;
   - оставить один approved client example;
   - синхронизировать реальные error/status/cache headers.
2. `packages/api-contract/DECISIONS.md` — зафиксировать absence of selector и Host trust.
3. `packages/api-contract/tests/contract.test.mjs` — заменить exact operation matrix,
   forbidden fields/terms, examples и cache assertions.
4. Запустить generation; принять regenerated `generated/typescript-fetch/**` целиком.
5. Обновить frontend imports/calls одним vertical slice; generated code не редактировать.

### 7.2 HTTP/request boundary

1. Изменить `PublicRestaurantController` на singular configuration controller/path;
   убрать `TenantContext` parameter и tenant cache tags.
2. Изменить `PublicMenuController`: singular path, parameterless query, ETag/tag без slug.
3. Реализовать новый `PublicOrderController` сразу на singular path.
4. Удалить:
   - `TenantResolutionFilter`;
   - `TenantPathSlugExtractor`;
   - `EffectiveHostResolver`;
   - `TenantContextArgumentResolver`;
   - `TenancyWebConfiguration`;
   - `TenantRequestAttributeAccess` и `TenantRequestAccess`;
   - `TenantResolver`, `TenantRegistry`, host/domain registration classes;
   - `TenantContext`, `TenantResolution`, `TenantId` после переноса всех consumers.
5. Удалить tenant MDC keys. Оставить correlation/trace ID; не добавлять constant client
   labels в metrics/logs.

### 7.3 Application and persistence ports

1. `PublicRestaurantQuery.get(TenantContext)` → `getCurrent()`.
2. `PublicRestaurantConfigurationRepository.findActiveDefault/findFeatures(TenantId)` →
   singleton/restaurant-scoped methods без request scope.
3. `PublicMenuQueryService.getCurrentPublished(TenantContext)` → `getCurrentPublished()`.
4. `PublicMenuReadRepository` и JDBC SQL удалить `tenant_id` predicates/joins после schema
   migration; сохранить explicit published/default status predicates.
5. Asset types/services вынести из tenancy, удалить owner parameter и tenant object key.
6. Ordering repositories заменить tenant composite lookups глобальными order/resource
   lookups; idempotency и order number constraints сделать global.
7. Notification repository/destination убрать tenant identity/slug; destination никогда
   не приходит из request.

### 7.4 Security/configuration

1. `SecurityRequestMatchers` заменить singular paths.
2. `SecurityConfiguration` убрать `TenantRequestAccess`, подключить renamed
   `OrderRateLimiter`, сохранить exact deny-by-default rules.
3. `PublicOrderRateLimitFilter` убрать tenant-not-found branch и использовать shared
   typed error renderer.
4. `TenantRateLimiter` → `OrderRateLimiter`; scopes IP + global.
5. Перенести `TrustedClientIpResolver` и proxy allowlist contract полностью в identity;
   удалить dependency `identity -> tenancy::api`.
6. Properties:
   - удалить `app.platform.hosts`;
   - `app.platform.trusted-forwarding` переименовать в `app.security.trusted-proxies`;
   - tenant rate/cache property names заменить global;
   - сохранить validated `PUBLIC_BASE_URL`/`ASSET_BASE_URL` как источник absolute URLs.
7. CORS paths и exposed headers синхронизировать с новым контрактом.
8. `ApiErrorHandler` и filter errors привести к единому typed envelope.

### 7.5 Module declarations

1. Добавить `asset` module и named `asset::api`, переместить asset API/implementation.
2. Обновить `restaurant`, `menu`, `identity`, `notification`, `ordering` package-info по
   dependency table.
3. Удалить `tenancy` module после последнего compile consumer.
4. Обновить `ModularityTests` expected module set и добавить запрет dependency на
   отсутствующий tenancy package.
5. Temporary migration code не должен мешать normal web context и удаляется после data
   cutover/retention decision.

### 7.6 Database migration constraint

V1–V5 уже существуют и не должны редактироваться в deployed database. Для upgrade path:

1. новая Flyway migration создаёт singleton target tables/constraints;
2. одноразовая migration копирует только явно выбранный dataset;
3. validation доказывает ровно одну configuration/default restaurant/current menu и
   отсутствие смешанных source rows;
4. application переключается на target tables;
5. следующая migration после rollback window удаляет tenant domains/tables, tenant
   columns/composite FKs, tenant counters и obsolete V5 import ledger;
6. финальная live schema не содержит `tenant_id`.

Если ни одна V1–V5 migration никогда не применялась вне disposable development DB,
допустим отдельный согласованный baseline reset вместо upgrade chain. Это решение нельзя
принимать молча: оно меняет Flyway checksums и требует пересоздания всех environments.

Ключевые target constraints:

- ровно одна singleton configuration row;
- ровно один active/default restaurant для текущего product scope;
- global unique order `idempotency_key` и `order_number`;
- menu uniqueness по real restaurant/version, без tenant prefix;
- asset uniqueness по kind/content hash либо asset ID, без tenant prefix;
- outbox/notification rows не содержат tenant routing;
- order history actor types `operator|system|customer|worker`, без tenant/platform admin.

## 8. Implementation sequencing

Порядок уменьшает риск параллельных несовместимых routes:

1. Зафиксировать два decision gates и новый API ADR.
2. Изменить OpenAPI/tests, regenerate client, но не подключать frontend к несуществующим
   endpoints.
3. Создать singleton schema/data migration и asset module.
4. Перевести config/menu application queries на target schema.
5. Перевести controllers/security/cache headers на singular routes; старые routes не
   должны одновременно принимать production traffic без явно ограниченного versioned
   compatibility window.
6. Реализовать singular order vertical slice: validation → idempotency → menu price
   calculation → order/items/outbox transaction → response → worker.
7. Перевести frontend/generated client calls и E2E.
8. Удалить tenant resolver/filter/API/module, tenant DB schema и obsolete tests/config.
9. Выполнить production validation, observability review и rollback window; удалить
   temporary migration code.

## 9. Acceptance tests

### Contract/generated client

- Contract содержит ровно три singular operations и ни одного path parameter.
- В paths/parameters/public schemas/descriptions/error examples нет `tenant`,
  `tenantSlug`, `tenantId`, custom-domain routing или `X-Tenant-*`.
- Config response не содержит routing slug; internal destination/storage fields закрыты.
- Generated request interfaces не требуют `tenantSlug`.
- Generated client строит exact singular URLs.
- Generated-code check и strict TypeScript compile проходят.
- `304` обрабатывается без попытки parse пустого JSON body.
- Breaking-version policy соответствует decision gate.

### Host/request boundary

- Два разрешённых ingress host aliases возвращают одинаковые config/menu body и ETag.
- Подмена `Host`, `Forwarded`, `X-Forwarded-Host`, query/header/body `tenantId` не меняет
  выбранные данные и notification destination.
- Неизвестный Host отклоняется ingress/application host allowlist до cacheable response,
  но не приводит к lookup другого набора данных.
- В application context отсутствуют TenantResolver/filter/argument-resolver beans.
- Public controller methods не имеют scope/tenant parameter.
- Absolute URLs формируются из validated config, не request Host.

### Security

- Разрешены только exact GET config, GET menu, POST orders и explicit probes.
- Старые `/{tenantSlug}/...`, admin/platform-admin/tenant-admin и unknown routes не
  становятся public.
- CORS принимает только configured exact origins/methods/headers, credentials false.
- Public order не использует cookies; будущий admin CSRF test изолирован.
- Management endpoints/port сохраняют private/default-deny behavior.
- Ошибки не отражают Host/forwarding headers, IP, phone, address, secret references.

### Rate limiting

- Один IP исчерпывает client bucket независимо от Host/header manipulation.
- Разные IP совместно ограничиваются global bucket.
- Bucket keys не содержат phone/order/Host/user input и caches bounded/expiring.
- `429` имеет `Retry-After`, common error envelope и `no-store`.
- Multi-instance limitation документирована либо проверена distributed implementation.

### Cache/CDN

- Config/menu `ETag` детерминирован и не содержит slug/host/internal DB ID namespace.
- `If-None-Match` возвращает bodyless `304` с теми же cache headers.
- Tags только `site-config`, `menu`, `menu-v{version}`.
- Menu publish инвалидирует только singular menu representations.
- Order success/error никогда не shared-cacheable.
- Asset key не содержит tenant/client scope и остаётся immutable/content-addressed.

### Error contract

- `TENANT_NOT_FOUND` полностью отсутствует.
- No published menu → `404 MENU_NOT_FOUND`.
- Missing/corrupt singleton config → `503 SITE_NOT_READY`, readiness false.
- Validation fields/reasons соответствуют OpenAPI и не содержат raw rejected values.
- Idempotency/menu/item/total conflicts имеют стабильные `409` codes.
- Filter, controller advice и order use case используют один serializer/envelope.

### Modules and persistence

- Modulith verification видит target module set без tenancy и cycles.
- Ни один source import не ссылается на `com.salamu.menu.tenancy` после removal stage.
- Fresh schema и upgrade schema приводят к одинаковой target structure/data.
- Live target tables не имеют `tenant_id`, tenant domain/lifecycle либо tenant-scoped
  unique/FK/index names.
- Migration выбирает ровно один approved source и fail-closed при двух/missing sources.
- Global idempotency concurrency test создаёт один order для одинакового key/payload.
- Ordering transaction атомарно сохраняет order, snapshots и outbox.
- Notification worker берёт единственную server-side destination; request не маршрутизует
  destination.

### Regression behavior

- Config/menu сохраняют выбранные branding, features, order types, prices, ordering и
  asset metadata.
- Cart reload использует menu version/item IDs без tenant storage scope.
- Double submit создаёт один order.
- Notification failure не теряет committed order.
- Full OpenAPI, backend, PostgreSQL, security, Modulith и frontend E2E suites проходят.

## 10. Definition of Done

Single-client API/security migration завершена, когда:

- production request не содержит и не вычисляет tenant selector;
- Host не влияет на business data/authorization/routing;
- public contract и generated client имеют только singular URLs;
- tenancy module/API/filter/registry удалены, assets имеют отдельного owner;
- live schema и repository signatures не содержат tenant scope;
- cache, rate limiting, idempotency, storage и notification используют single-client
  semantics без constant fake tenant key;
- public/operator/management security boundaries проверены;
- выбранный dataset перенесён без смешения второго legacy dataset;
- все перечисленные acceptance tests и generated-code/Modulith/Flyway checks зелёные.
