# Инструкции для агентов

## 1. Назначение и область действия

Этот файл обязателен для всего monorepo. Он фиксирует целевую single-client
архитектуру и инженерные ограничения на время миграции.

Один deployment frontend/backend обслуживает ровно одного клиента. Multi-tenancy
удаляется полностью: не добавляйте `tenantId`, tenant context/resolver, tenant-scoped
таблицы, host-to-tenant mapping, tenant routes, tenant cache keys или hidden default
tenant. ADR-0003 является источником решения.

Агент не меняет утверждённый stack, persistence strategy или API style без отдельного
ADR. Архитектурное отклонение требует сначала обновить ADR и migration plan.

Запрещено преждевременно добавлять WebFlux, R2DBC, Kafka, Kubernetes, microservices,
GraalVM, Redis или второй database. Legacy-каталог `Электронное меню/` удалён после
завершения миграции. Не восстанавливайте его, не подключайте как runtime/build/test
dependency и не используйте как источник поведения. Источник истины — текущие
domain rules, OpenAPI, versioned data bundles и automated tests.

## 2. Утверждённый технологический стек

### Frontend

- Next.js App Router.
- React и strict TypeScript.
- pnpm workspace с единым lockfile.
- Server Components по умолчанию.
- Zod для browser/external boundaries.
- TanStack Query только для действительно клиентского server state.
- Zustand только для cart и client workflow state.
- React Hook Form для форм.
- Vitest, Testing Library и Playwright.

### Backend

- Java 25 toolchain.
- Spring Boot и Spring MVC.
- Blocking JDBC/JPA; Spring Data JPA является основной persistence-технологией.
- Spring Modulith для module boundaries.
- Gradle Wrapper и Kotlin DSL.
- PostgreSQL и Flyway.
- Spring Security.
- Actuator, Micrometer и OpenTelemetry.
- Testcontainers для PostgreSQL/object-storage integration tests.
- OpenAPI-first public contract.

### Infrastructure

- PostgreSQL является источником истины.
- S3-compatible private object storage и CDN обслуживают assets.
- Transactional outbox и отдельный worker выполняют внешние интеграции.
- Redis допустим только после измеримой необходимости для shared cache/rate limit.
- Начальная deployment-модель — modular monolith, не microservices.

## 3. Структура monorepo

```text
/
  AGENTS.md
  apps/
    web/
    api/
  packages/
    api-contract/
    shared-config/
  infra/
    docker/
    local/
    observability/
  docs/
    architecture/
    adr/
    migration/
```

- `apps/web` — публичное приложение единственного клиента.
- `apps/api` — backend и outbox worker.
- `packages/api-contract` — OpenAPI и generated TypeScript client.
- `packages/shared-config` — tooling configuration без бизнес-логики.
- `infra` — local/deployment infrastructure.
- `docs/adr` — архитектурные решения.
- `docs/migration` — task registry, parity evidence и cutover plan.

Generated code не редактируется вручную. Shared packages не должны превращаться в
склад domain logic.

## 4. Single-client модель

Главный принцип:

```text
Один codebase
+ один frontend
+ один backend
+ один клиент на deployment
```

Request host не выбирает business data. Он может проверяться ingress allowlist и
использоваться для canonical URL, CSP/CORS/cookie policy, но не создаёт application
context и не участвует в repository queries.

Клиентские данные единственного deployment:

- display name и business type;
- branding, logo, favicon, hero и изображения;
- theme и design tokens;
- contacts, schedule, location и SEO metadata;
- locale, currency и business timezone;
- restaurant/service-point configuration;
- versioned menu;
- order types и default order type;
- delivery/preorder/pickup rules;
- feature configuration;
- notification destinations;
- operational limits.

Эти значения остаются typed configuration/domain data. Запрещено:

- хранить фиктивную единственную tenant row;
- передавать constant tenant UUID;
- сохранять `tenant_id` в новых таблицах, DTO, cache keys, jobs или events;
- механически переименовывать `tenant` в `client` и сохранять прежнюю abstraction;
- выбирать Stambul/Adamas через runtime slug/host/branch;
- создавать второй dataset внутри production deployment без нового ADR.

Stambul и Adamas — исторические названия импортированных datasets. Production
deployment содержит ровно один выбранный dataset; эти названия не создают runtime
mode и не требуют возвращения удалённых raw legacy sources.

## 5. Общесистемная архитектура

```text
                         CDN
                          │
                 Next.js public frontend
                   SSR / cached pages
                          │
                Spring Boot modular monolith
                          │
       ┌──────────────────┼──────────────────┐
       │                  │                  │
   PostgreSQL        Object Storage       Redis
 config, menu,         immutable assets   optional only
 orders, outbox             + CDN        after metrics
       │
 Transactional outbox
       │
 Background worker
       │
 WhatsApp / Telegram / email / POS
```

- Backend является источником истины.
- Frontend не считается доверенной средой.
- Public reads проектируются для server/CDN caching.
- Writes некэшируемы и идемпотентны.
- Внешние интеграции не выполняются внутри transaction создания заказа.
- Modular monolith разделён на проверяемые bounded modules.
- Microservice выделяется только по доказанной нагрузочной/организационной причине.

## 6. Backend modules

Целевая package structure:

```text
com.salamu.menu
  site
  restaurant
  menu
  ordering
  asset
  notification
  identity
  shared
```

Назначение:

- `site` — branding, SEO, contacts, locale/timezone и feature configuration.
- `restaurant` — service point, schedule и operational configuration.
- `menu` — immutable/versioned published menu.
- `ordering` — checkout validation, totals, orders и idempotency.
- `asset` — immutable object metadata/storage operations.
- `notification` — destinations, outbox delivery и retry audit.
- `identity` — client-admin authentication/authorization.
- `shared` — минимальные стабильные primitives без domain ownership.

Внутри модуля:

```text
module/
  api/
  internal/
    domain/
    application/
    infrastructure/
    web/
```

Правила:

- `api` — единственный публичный package модуля.
- Другие модули не импортируют `internal`.
- Domain не зависит от controllers, JSON DTO или JPA entities.
- Application layer содержит use cases и transaction boundaries.
- Controller выполняет mapping, validation и вызов use case.
- API DTO, domain object и persistence entity разделены.
- Межмодульные циклы запрещены.
- Spring Modulith проверяет boundaries.
- Модуль не читает напрямую таблицы другого bounded context.
- `shared` не используется для обхода ownership.

## 7. Spring MVC, transactions и persistence

- Используйте Spring MVC и blocking JDBC/JPA.
- WebFlux/R2DBC запрещены без ADR и нагрузочного доказательства.
- Virtual threads включаются только после измерений.
- Open Session in View отключён.
- JPA entities не сериализуются в API.
- Associations загружаются осознанно.
- N+1 контролируется integration tests и SQL monitoring.
- Read models используют projections/DTO queries.
- Pagination обязательна для растущих коллекций.
- Bulk operations не реализуются циклом `save`.
- Деньги хранятся в integer minor units, не `double`.
- Timestamps хранятся в UTC; business timezone хранится отдельно.
- Все schema changes выполняются Flyway.
- Применённые migrations не редактируются; pivot выполняется append-only migration.

Целевая single-client schema содержит как минимум:

```text
site_configuration
site_branding
site_features
restaurants
menu_versions
menu_categories
menu_items
assets
notification_destinations
orders
order_items
outbox_events
notification_deliveries
```

Ни одна целевая таблица не содержит `tenant_id`. Уникальность выражает business key,
например `restaurant external key`, `(menu_version_id, external_id)` или global
`idempotency_key`, а не scope фиктивного tenant.

Upgrade path V1–V5 → current и clean install должны проходить на PostgreSQL. После
переключения code obsolete tenancy/import tables удаляются отдельной проверяемой
migration. Business fixtures не добавляются в production Flyway.

## 8. Security

- Разделяйте public и client-admin contours.
- Platform-admin и tenant-admin roles отсутствуют.
- Feature configuration не является authorization.
- ID ресурса сам по себе не даёт доступ.
- Admin commands проверяют роль и resource ownership единственного приложения.
- CORS, CSP, CSRF и cookie domain настраиваются для canonical deployment domain.
- Forwarded headers доверяются только известному ingress.
- Не отправляйте destination references/secrets в Next.js client bundle.
- PII не попадает в logs, traces или metric labels.
- Authentication и order creation rate-limited.
- Public error не раскрывает SQL, object key, credentials или provider details.

Обязательные security tests:

- public пользователь не вызывает admin operation;
- unknown/forged Host не ослабляет CORS/cookie/canonical policy;
- forged price/total/menu availability отклоняются;
- ID другого resource type не проходит ownership check;
- cache не смешивает версии config/menu;
- secrets и PII отсутствуют в logs/responses;
- повторный order request не создаёт duplicate.

## 9. Order lifecycle и outbox

```text
HTTP request
→ rate limit
→ schema validation
→ idempotency lookup
→ restaurant/menu validation
→ server-side price calculation
→ transaction:
     order
     order_items snapshots
     outbox_event
→ commit
→ response
→ worker
→ configured notification/POS destination
```

- `clientTotal` не является источником истины.
- Backend рассчитывает `serverTotal`.
- `Idempotency-Key` обязателен.
- Global uniqueness: минимум `UNIQUE (idempotency_key)`.
- Повтор идентичного payload возвращает исходный заказ.
- Тот же key с другим payload возвращает `409 conflict`.
- `order_items` хранит snapshot названия, цены и modifiers.
- Destination определяется server-side configuration.
- Worker поддерживает retry, backoff, attempt count и dead-letter state.
- Ошибка уведомления не отменяет созданный заказ.
- Не обещайте exactly-once внешнюю доставку; outbox обеспечивает durable at-least-once.

## 10. OpenAPI-first

Workflow:

1. Изменить OpenAPI.
2. Выполнить lint и compatibility check.
3. Сгенерировать TypeScript client.
4. Обновить Spring implementation.
5. Запустить contract/integration tests.
6. Использовать endpoint во frontend.

Целевые routes:

```text
GET  /api/v1/public/config
GET  /api/v1/public/menu
POST /api/v1/public/orders
```

Правила:

- Один versioned public namespace.
- Generated code не редактируется.
- Единый error envelope: `code`, `message`, `details`, `traceId`.
- Public DTO не содержит tenant slug/ID/context.
- Breaking change требует новой версии или явной compatibility migration.
- Public routes без tenant slug являются canonical contract.

## 11. Next.js frontend

- Next.js App Router.
- Главный public route — `/`.
- Server Components используются по умолчанию.
- Client Components — только cart, forms и browser APIs.
- Config, branding и menu загружаются server-side.
- Metadata, canonical URL, sitemap и Open Graph формируются для deployment domain.
- TanStack Query применяется только для клиентского server state.
- Zustand содержит cart и client workflow state.
- React Hook Form управляет checkout form.
- Zod проверяет browser/external boundaries.
- Server state не копируется в Zustand.
- Branding выражается typed design tokens/CSS variables.
- Feature configuration типизирована и поступает с backend.

Frontend layers:

```text
src/
  app/
  pages/
  widgets/
  features/
  entities/
  shared/
```

Зависимости:

```text
app/pages
→ widgets
→ features
→ entities
→ shared
```

Не создавайте tenant-aware routes, stores, providers или client branches.

## 12. Cart persistence

```ts
type PersistedCart = {
  schemaVersion: number;
  restaurantId: string;
  menuVersion: number;
  orderType: OrderType;
  items: Array<{
    menuItemId: string;
    quantity: number;
  }>;
};
```

- Storage key versioned, но не tenant-scoped.
- PII по умолчанию не сохраняется.
- Повреждённое состояние удаляется безопасно.
- Неизвестная schema version не интерпретируется.
- Items проверяются по актуальному menu.
- Persisted price/name не считаются доверенными.
- Недоступные items удаляются с уведомлением.
- Quantity limits повторно проверяются backend.

## 13. Cache, CDN и assets

- Static assets и versioned images обслуживаются CDN.
- Cache key включает resource, locale и menu/config version.
- Публикация menu/config инвалидирует только соответствующие versioned entries.
- Redis не обязателен для MVP.
- Redis разрешён после измерений для distributed rate limit/shared cache/coordination.
- Redis не является источником истины orders.
- Assets хранятся в private S3-compatible storage.
- Canonical object key: `assets/{kind}/{sha256}.{ext}`.
- Public URL: `{cdnBase}/assets/{kind}/{sha256}.{ext}`.
- Upload проверяет media type, magic bytes, size и declared kind.
- Content-addressed object immutable; другие bytes не записываются по тому же hash key.
- Bucket listing, credentials и signed origin URLs не раскрываются.

## 14. Observability

- Structured JSON logs.
- Correlation/trace ID проходит через web, API и worker.
- Tenant/client dimension отсутствует.
- Phone, user ID и order ID не используются как metric labels.
- Actuator endpoints разделяются на internal/public.
- Micrometer собирает HTTP, JVM, DB pool и business metrics.
- OpenTelemetry используется для traces.
- Sentry или эквивалент собирает frontend/backend exceptions.

Business metrics:

- orders created/rejected;
- idempotent replays;
- outbox backlog;
- notification failures;
- menu load latency;
- cache hit ratio;
- checkout conversion.

## 15. Тестирование

Backend:

- unit domain tests;
- Spring Modulith module tests;
- PostgreSQL integration tests через Testcontainers;
- JPA query/N+1 tests;
- API contract tests;
- idempotency/concurrency tests;
- outbox recovery tests;
- security and log-redaction tests;
- clean/upgrade Flyway tests.

Frontend:

- Vitest unit tests;
- Testing Library component tests;
- Zod/schema tests;
- Zustand persistence tests;
- Playwright E2E;
- accessibility tests;
- visual regression;
- SSR, metadata и cache tests.

E2E:

- открыть `/` и получить correct branding/menu;
- добавить items и восстановить cart;
- оформить preorder/delivery/pickup согласно configuration;
- double submit создаёт один order;
- stale menu обрабатывается безопасно;
- forged total игнорируется/отклоняется;
- notification failure не теряет order;
- admin/public boundaries не пересекаются;
- sensitive data отсутствует в browser bundle/logs.

## 16. Tooling и CI

- Java 25 toolchain.
- Gradle Wrapper и Kotlin DSL.
- pnpm workspace и единый lockfile.
- TypeScript strict.
- ESLint и Prettier.
- Docker Compose для local dependencies.
- `.env.example` без secrets.
- OpenAPI generation check.
- Secret/dependency/container scanning.

Целевые команды:

```text
pnpm install
pnpm --filter web dev
pnpm --filter web test
pnpm --filter web test:e2e
./gradlew :apps:api:bootRun
./gradlew :apps:api:test
./gradlew :apps:api:check
docker compose up
```

CI:

```text
OpenAPI lint/compatibility
→ generated-code check
→ frontend lint/typecheck/unit
→ backend compile/unit/module verification
→ PostgreSQL integration tests
→ production builds
→ Playwright E2E
→ security scans
→ preview/staging deployment
```

Merge блокируется при нарушении module boundaries, generated-client drift, Flyway
validation error, failing idempotency/security test, production build failure или
committed secret.

## 17. Migration guardrails

- Удалённый legacy-каталог не является поведенческим эталоном и не возвращается.
- Актуальное поведение фиксируется OpenAPI, domain rules и acceptance/E2E tests.
- Перенос выполняется vertical slices.
- ADR-0003 отменяет multi-tenant target и двух-tenant importer.
- V1–V5 не редактируются; single-client pivot append-only.
- Сначала вводится single-client replacement, затем переключаются callers, затем
  удаляются tenancy/migration structures.
- Не оставляйте constant/default tenant compatibility layer.
- Исторические Stambul/Adamas различия выражаются выбранной client configuration,
  feature modules или честным client-specific code, но не runtime tenant switch.
- Один production dataset выбирается до content import.
- Старый и новый frontend не создают параллельные реальные orders.
- Каждая функция получает acceptance tests.
- Не переносите global `window.*`, двойной state и API inconsistencies.
- Historical ADR, Flyway migrations и migration reports могут упоминать legacy как
  завершённый этап, но active code, tests и operator workflows не читают удалённый
  каталог `Электронное меню/`.

## 18. Definition of Done

Изменение завершено, если:

- соблюдены module ownership и dependency direction;
- отсутствуют `tenantId`, tenant context/resolver и tenant-scoped persistence/runtime;
- public contract соответствует single-client routes;
- schema работает на clean PostgreSQL и upgrade V1–V5;
- security, idempotency и transaction invariants покрыты tests;
- logs/metrics не раскрывают PII/secrets и не содержат obsolete tenant context;
- OpenAPI/generated client синхронизированы;
- backend/frontend production builds проходят;
- docs/task registry отражают фактическое состояние;
- `git diff --check` чист.
