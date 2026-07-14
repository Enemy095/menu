# Инструкции для агентов

## 1. Назначение и область действия

Этот файл обязателен для всех агентов и распространяется на весь monorepo. Он фиксирует целевую архитектуру multi-tenant white-label платформы и инженерные ограничения на время миграции.

- Не меняйте утверждённые stack, tenancy model, persistence strategy или API style без отдельного решения и ADR.
- Архитектурные отклонения документируйте в `docs/adr/` с причиной, последствиями и условиями отмены.
- Не добавляйте преждевременно WebFlux, R2DBC, Kafka, Kubernetes, microservices, GraalVM или Redis.
- Не создавайте инфраструктурные abstractions без реальной границы, альтернативной реализации или тестовой необходимости.
- Каталог `Электронное меню/` является legacy-реализацией и источником существующего поведения. Изменяйте его только в рамках согласованного migration slice.
- Этот документ описывает целевую архитектуру, но не заменяет отдельный пошаговый migration plan.

## 2. Утверждённый технологический стек

### Frontend

- Next.js App Router и React.
- Strict TypeScript.
- pnpm workspace и единый lockfile.
- Server Components по умолчанию; Client Components только для интерактивности и browser APIs.
- Zustand для корзины и client workflow state.
- React Hook Form для форм.
- Zod для runtime validation на внешних границах.
- TanStack Query только для действительно клиентского server state.
- Vitest, Testing Library и Playwright.

### Backend

- Java 25 через Gradle toolchain.
- Gradle Wrapper и Gradle Kotlin DSL.
- Spring Boot, Spring MVC и Spring Modulith.
- Spring Data JPA и блокирующий JDBC stack.
- PostgreSQL и Flyway.
- Spring Security.
- Spring Boot Actuator, Micrometer и OpenTelemetry.
- JUnit и Testcontainers.

### Инфраструктура

- OpenAPI-first контракт.
- S3-compatible object storage и CDN для tenant assets.
- Transactional outbox и worker для внешних уведомлений.
- Redis только после появления измеримой потребности в shared cache, distributed rate limiting или coordination.
- Начальная deployment-модель — modular monolith, а не microservices.

## 3. Целевая структура monorepo

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
  Электронное меню/       # legacy до отдельного migration step
```

- `apps/web` — публичное white-label меню.
- `apps/api` — multi-tenant backend и worker.
- `packages/api-contract` — OpenAPI и generated TypeScript client.
- `packages/shared-config` — общая tooling-конфигурация без бизнес-логики.
- `infra` — local и deployment infrastructure.
- `docs/adr` — архитектурные решения.
- `docs/migration` — карта и результаты переноса legacy-функций.

Не создавайте эти каталоги пустыми только ради соответствия схеме. Они появляются вместе с первым реальным содержимым.

## 4. Multi-tenant white-label модель

Главный архитектурный принцип:

```text
Один codebase
+ одно frontend-приложение
+ один backend
+ много заведений
```

Примеры адресации:

```text
adamas.platform.example
stambul.platform.example
custom-restaurant-domain.ru
platform.example/adamas
```

Целевой request flow:

```text
HTTP request
→ normalize host/path
→ TenantResolver
→ trusted domain/slug mapping
→ tenantId
→ immutable tenant context
→ загрузка config/menu/features/theme по tenantId
→ SSR/API response
```

### Tenant resolution

- `tenantId` — внутренний неизменяемый идентификатор.
- `slug`, platform subdomain и custom domain — изменяемые внешние identifiers.
- Host/path разрешается в tenant только через серверный trusted registry.
- Переданный клиентом `tenantId` не считается доверенным.
- Query-параметр не может переопределять tenant.
- После resolution внутренние операции используют `tenantId`, а не повторно разбирают host.
- Tenant context создаётся на границе запроса, остаётся immutable и явно передаётся в application layer.
- Resolver поддерживает platform subdomain, custom domain, platform path и локальный development mapping.
- Неизвестный, неактивный или неподтверждённый domain возвращает контролируемый `404`; production fallback на default tenant запрещён.
- Default tenant допустим только в local development или на явно настроенном platform landing route.
- Custom domain активируется только после проверки владения.
- Host header нормализуется и сверяется с registry; не доверяйте произвольному forwarded host без trusted proxy configuration.
- Background jobs, scheduled jobs и outbox events всегда содержат `tenantId`.
- Cache key, storage key и rate-limit key обязательно включают tenant scope.
- Один tenant не может читать, изменять, инвалидировать или кэшировать данные другого.

### White-label данные tenant

По `tenantId` загружаются:

- display name и business type;
- logo, favicon и изображения;
- theme и design tokens;
- contacts, schedule и location;
- custom domains и SEO metadata;
- menu и menu version;
- available order types;
- feature flags;
- locale, currency и timezone;
- notification destinations;
- operational limits;
- delivery configuration.

Программный код остаётся общим. Запрещено:

- создавать отдельную копию frontend или backend для каждого заведения;
- добавлять ветки вида `if (tenantSlug === "adamas")` в общий UI или domain logic;
- хранить tenant-specific JSX либо бизнес-правила в shared code;
- создавать отдельные таблицы на каждого tenant без ADR;
- добавлять custom behavior, который нельзя выразить через config, feature module или domain policy.

## 5. Общесистемная архитектура

```text
                         CDN
                          │
               Next.js white-label frontend
                    SSR / cached pages
                          │
                Spring Boot modular monolith
                          │
       ┌──────────────────┼──────────────────┐
       │                  │                  │
   PostgreSQL        Object Storage       Redis
 tenant data,         tenant assets      optional:
 menus, orders          + CDN           cache/rate limit
       │
 Transactional outbox
       │
 Background worker
       │
 WhatsApp / Telegram / email / POS
```

- Backend является источником истины для меню, цен, feature availability и заказов.
- Frontend не является доверенной средой.
- Публичные reads проектируются для server/CDN caching.
- Writes некэшируемы и идемпотентны.
- Внешние integrations не выполняются внутри транзакции создания заказа.
- Модули внутри monolith имеют проверяемые границы.
- Используйте синхронный межмодульный вызов, если вызывающей стороне нужен немедленный результат.
- Используйте domain/application events для независимых последствий уже совершённой операции.
- Микросервис выделяется только по доказанной нагрузочной, deployment или ownership-причине.

## 6. Backend modules

Целевые модули:

```text
com.project.menu
  tenancy
  restaurant
  menu
  ordering
  notification
  identity
  shared
```

Рекомендуемая структура модуля:

```text
module/
  api/
  internal/
    domain/
    application/
    infrastructure/
    web/
```

- `tenancy` владеет domain mapping и tenant resolution.
- `api` — единственный публичный пакет модуля.
- Другие модули не импортируют `internal`.
- Domain не зависит от Spring MVC, JSON DTO или JPA entities.
- Application layer содержит use cases и transaction boundaries.
- Controllers выполняют HTTP mapping, validation и вызов use case; бизнес-логика в controller запрещена.
- Repository abstractions принадлежат соответствующему domain/application module.
- API DTO, domain object и JPA entity являются разными моделями.
- Межмодульные циклы запрещены и проверяются Spring Modulith.
- `shared` не используется как склад случайных utilities или business abstractions.

## 7. Spring MVC, concurrency и persistence

### Web stack

- Используйте Spring MVC и блокирующую JDBC/JPA модель.
- WebFlux и R2DBC требуют отдельного ADR и подтверждённой нагрузочной необходимости.
- Virtual threads включаются только после load tests и проверки pinned threads, pool limits и graceful shutdown.
- Все executors, schedulers и workers имеют ограниченную concurrency, timeout и корректный shutdown.
- Не запускайте business-critical работу через необслуживаемый `@Async` или fire-and-forget thread.

### JPA и PostgreSQL

- Open Session in View должен быть отключён.
- JPA entities не сериализуются в API и не выходят за persistence boundary.
- Associations и fetch strategy задаются осознанно.
- N+1 контролируется integration tests, query counting и SQL monitoring.
- Для read models используйте projections, DTO queries или отдельные query repositories.
- Pagination обязательна для потенциально растущих коллекций.
- Bulk operations не реализуются циклом `save`.
- Денежные значения хранятся в integer minor units либо точном PostgreSQL `numeric`; `float`/`double` запрещены.
- Время хранится в UTC; business timezone tenant хранится отдельно.
- Все schema changes выполняются Flyway.
- Применённые production migrations не редактируются; создавайте новую migration.
- Индексы проектируются под реальные запросы и проверяются через query plans при необходимости.

Tenant-зависимые таблицы включают `tenant_id`. Целевая модель содержит как минимум:

```text
tenants
tenant_domains
tenant_branding
tenant_features
restaurants
menu_versions
menu_categories
menu_items
orders
order_items
outbox_events
```

- Уникальные ограничения tenant-owned данных обычно включают `tenant_id`.
- Repository API для tenant-owned данных требует `TenantId` или tenant context.
- Запрос без tenant scope запрещён, кроме явно обозначенного platform-admin use case.
- Native queries проходят отдельную проверку tenant isolation.
- PostgreSQL Row-Level Security может быть дополнительным слоем, но не заменяет application checks.
- Создание order, order items и outbox event выполняется одной транзакцией.

## 8. Security и tenant isolation

- Разделяйте public, tenant-admin и platform-admin security boundaries.
- Tenant admin управляет только собственным tenant.
- Platform admin имеет отдельную роль и audit trail.
- Feature flag не заменяет authorization.
- Все tenant-bound commands проверяют принадлежность ресурса tenant.
- Знание ID ресурса не предоставляет доступ.
- Исключите cross-tenant cache poisoning.
- CORS, CSP, CSRF и cookie domain должны учитывать custom domains и deployment topology.
- Admin authentication не должна зависеть только от подменяемого Host.
- Секреты tenant не отправляются в Next.js client bundle.
- Персональные данные не попадают в application logs, metric labels или traces.
- Order creation и authentication endpoints защищаются rate limiting.
- Input validation выполняется и на frontend, и на backend; backend всегда повторно проверяет business invariants.

Обязательные security/integration tests:

- tenant A не читает и не изменяет ресурс tenant B;
- одинаковые slugs и resource identifiers не конфликтуют между tenant;
- custom domain разрешается только в подтверждённый активный tenant;
- неизвестный host не открывает default restaurant;
- cache одного tenant не возвращается другому;
- platform-admin и tenant-admin имеют разные границы доступа.

## 9. Order lifecycle, idempotency и outbox

```text
HTTP request
→ tenant resolution
→ rate limit
→ schema validation
→ idempotency lookup within tenant
→ restaurant/menu validation
→ server-side price calculation
→ transaction:
     order
     order_items with snapshots
     outbox_event with tenantId
→ commit
→ response
→ worker
→ tenant-specific notification/POS
```

- `clientTotal` не является источником истины.
- Backend рассчитывает и сохраняет `serverTotal`.
- `Idempotency-Key` обязателен для создания заказа.
- Минимальная database uniqueness: `(tenant_id, idempotency_key)`.
- Повтор идентичного запроса возвращает исходный результат.
- Тот же ключ с другим canonical payload hash возвращает conflict.
- `order_items` хранит snapshots названия, цены и выбранных модификаторов.
- Destination внешнего уведомления определяется backend по tenant config.
- Worker поддерживает retry, bounded exponential backoff, attempt count и dead-letter/failed state.
- Ошибка уведомления не отменяет созданный заказ.
- Side effects не выполняются до успешного commit.

## 10. OpenAPI-first

Изменение API выполняется только в таком порядке:

1. Изменить OpenAPI в `packages/api-contract`.
2. Выполнить lint и backward-compatibility check.
3. Сгенерировать TypeScript client.
4. Обновить Spring implementation.
5. Запустить contract и integration tests.
6. Только затем использовать endpoint во frontend.

Правила:

- Используйте один versioned public API namespace.
- Generated code не редактируется вручную.
- Ошибка имеет единый envelope с `code`, `message`, `details` и `traceId`.
- Tenant-aware routes используют host/slug context согласованно.
- Внутренний `tenantId` не раскрывается без необходимости.
- Breaking change требует новой API version либо явно согласованной migration strategy.
- Устраните legacy-расхождение между `/api/public/orders` и `/api/v1/public/{slug}/orders`; не переносите оба варианта в новый API.
- Java controllers реализуют контракт, но не определяют его независимо от OpenAPI.

## 11. Next.js white-label frontend

- Используйте App Router.
- Platform route — `/[tenantSlug]`.
- Custom domains разрешаются server-side до загрузки tenant data.
- Server Components используются по умолчанию.
- Client Components создаются только для state, event handlers, forms и browser APIs.
- Tenant config, branding, menu и features загружаются на сервере.
- Данные других tenant не включаются в HTML, RSC payload или client bundle.
- Metadata, canonical URL, sitemap и Open Graph формируются по tenant и canonical domain.
- Кэширование и revalidation задаются явно по типу данных.
- TanStack Query не дублирует уже полученный server state без реальной клиентской необходимости.
- Zustand содержит только cart и client workflow state.
- React Hook Form владеет состоянием формы.
- Zod проверяет данные на внешних runtime boundaries.
- Tenant branding выражается через typed config, design tokens и CSS variables.
- Feature flags типизированы и поступают с backend.
- Tenant-specific JSX, CSS forks и hardcoded slug branches запрещены.
- Не обращайтесь к DOM напрямую, если поведение можно выразить React component/state.
- Не импортируйте server-only modules в Client Components.

Целевые frontend layers:

```text
src/
  app/
  pages/
  widgets/
  features/
  entities/
  shared/
```

Разрешённое направление зависимостей:

```text
app/pages
→ widgets
→ features
→ entities
→ shared
```

- `entities` не зависят от `features`, `widgets` или `pages`.
- Feature представляет законченное пользовательское действие.
- UI components не выполняют скрытые API calls.
- Формирование order payload изолировано от rendering.
- Денежное и локализованное форматирование централизовано.

## 12. Cart persistence

Целевая persisted-модель:

```ts
type PersistedCart = {
  schemaVersion: number;
  tenantId: string;
  restaurantId: string;
  menuVersion: number;
  orderType: OrderType;
  items: Array<{
    menuItemId: string;
    quantity: number;
  }>;
};
```

- Storage key scoped по tenant и restaurant.
- Корзина одного tenant не открывается у другого.
- Персональные данные по умолчанию не сохраняются.
- Повреждённое состояние удаляется безопасно.
- Неизвестная `schemaVersion` не интерпретируется без migration.
- При восстановлении позиции проверяются по актуальному меню.
- Persisted price и name не являются доверенными полями.
- Недоступные позиции удаляются с понятным уведомлением.
- Quantity limits проверяются в UI/store и повторно на backend.

## 13. Cache, CDN и assets

- Static assets и versioned images обслуживаются CDN.
- Cache key включает tenant, locale и menu version.
- Host/path участвует в tenant resolution до обращения к tenant cache.
- Cache invalidation всегда ограничивается конкретным tenant.
- Custom domains не создают независимые несогласованные копии tenant cache.
- Публикация меню инвалидирует только страницы и данные соответствующего tenant.
- Redis не является обязательной зависимостью MVP.
- Redis разрешён для distributed rate limiting, shared cache или coordination после измерений.
- Redis никогда не является единственным источником истины заказа.
- Tenant assets хранятся в S3-compatible object storage.
- Object keys включают tenant scope и content/version hash.
- Upload authorization проверяет tenant ownership.

## 14. Observability

- Используйте structured JSON logs.
- Correlation/trace ID проходит через web, API, database-related spans и worker.
- Безопасный `tenantId` или slug включается в tracing context.
- Не используйте phone, user ID или order ID как metric labels.
- Actuator endpoints разделяются на internal и public exposure.
- Micrometer собирает HTTP, JVM, DB pool и business metrics.
- OpenTelemetry используется для distributed traces.
- Sentry или эквивалент собирает frontend/backend exceptions.
- Health и readiness не смешиваются: instance не ready, если не может обслужить обязательные зависимости.

Минимальные business metrics:

- orders created/rejected;
- idempotent replays;
- outbox backlog;
- notification attempts/failures;
- tenant resolution failures;
- menu load latency;
- cache hit ratio;
- checkout conversion events.

Следите за cardinality при добавлении tenant dimension; не добавляйте unbounded tenant labels без оценки стоимости monitoring backend.

## 15. Тестовая стратегия

### Backend

- Unit tests для domain policies.
- Spring Modulith module tests и verification.
- PostgreSQL integration tests через Testcontainers.
- Repository tests для tenant isolation и SQL behavior.
- JPA query/N+1 tests.
- OpenAPI contract tests.
- Idempotency и concurrency tests.
- Outbox retry/recovery tests.
- Public/admin security tests.

### Frontend

- Unit tests на Vitest.
- Component tests через Testing Library.
- Zod/schema tests.
- Zustand persistence и migration tests.
- Playwright E2E.
- Accessibility checks.
- Visual regression для tenant themes.
- SSR, host resolution, metadata и cache tests.

### Обязательные E2E-сценарии

- открыть tenant по platform URL;
- открыть tenant по custom domain;
- получить `404` для неизвестного domain;
- отфильтровать меню и изменить корзину;
- восстановить корзину после reload;
- убедиться, что смена tenant не переносит корзину;
- оформить preorder, delivery и pickup согласно tenant features;
- корректно обработать validation error и устаревшее меню;
- двойной submit создаёт один order;
- tenant branding и features применяются корректно;
- data и cache не пересекаются между tenant;
- падение внешнего уведомления не приводит к потере заказа.

## 16. Tooling и CI

- Java 25 задаётся через Gradle toolchain.
- Используется только Gradle Wrapper и `build.gradle.kts`.
- Frontend использует pnpm workspace и единый lockfile.
- TypeScript работает в strict mode.
- Используются ESLint и Prettier.
- Docker Compose поднимает PostgreSQL и только реально необходимые локальные зависимости.
- `.env.example` содержит имена переменных и безопасные примеры, но не secrets.
- Generated sources проверяются в CI.

Целевые команды после появления соответствующего scaffolding:

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

Не утверждайте, что целевая команда уже работает, пока соответствующий build file не создан и команда не проверена.

Минимальный CI pipeline:

```text
OpenAPI lint/compatibility
→ generated-code check
→ frontend lint/typecheck/unit
→ backend compile/unit/module verification
→ PostgreSQL integration tests
→ production builds
→ Playwright E2E
→ dependency/secret/container scans
→ preview или staging deployment
```

Merge блокируется при нарушении module boundaries, рассинхронизации generated client, Flyway validation error, failing tenant isolation/idempotency test, failing production build или обнаруженном committed secret.

## 17. Migration guardrails

- Legacy остаётся поведенческим эталоном, но не архитектурным образцом.
- Сначала зафиксируйте существующие пользовательские сценарии E2E-тестами.
- Перенос выполняйте vertical slices, а не одновременной заменой всех слоёв.
- Первый фундаментальный slice — tenant resolution и immutable tenant context.
- Старый и новый frontend не должны параллельно создавать реальные дублирующиеся заказы.
- Каждая перенесённая функция получает acceptance tests.
- Не переносите глобальные `window.*`, двойной state и legacy API inconsistencies.
- Tenant-specific legacy branches преобразуйте в config, feature modules или domain policies.
- Любое временное compatibility behavior документируйте в `docs/migration/`.
- Legacy удаляется только после production validation, observability review и rollback window.

## 18. Definition of Done для архитектурных изменений

Архитектурное изменение завершено, когда:

- соблюдены tenant boundaries во всех queries, caches, storage keys, jobs и events;
- OpenAPI, generated client и Spring implementation синхронизированы;
- добавлены unit/integration/E2E tests соразмерно риску;
- проверены security, idempotency и failure paths;
- миграции Flyway проходят на чистой и существующей schema;
- добавлена необходимая observability без утечки PII;
- документация и ADR обновлены;
- relevant build, tests и `git diff --check` проходят;
- legacy behavior не изменён случайно.
