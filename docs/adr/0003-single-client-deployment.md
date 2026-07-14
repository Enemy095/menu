# ADR-0003: Single-client deployment без multi-tenancy

- Status: accepted
- Date: 2026-07-14
- Scope: вся целевая система и оставшаяся миграция legacy
- Supersedes: tenant-specific decisions ADR-0001 и ADR-0002

## Context

Первоначальная архитектура проектировалась как одна multi-tenant white-label
платформа для Stambul, Adamas и будущих заведений. В ходе продуктового уточнения
принято решение, что каждый экземпляр приложения обслуживает одного клиента.
Будущие клиенты могут требовать принципиально отличающиеся механику, интерфейс и
business workflow, поэтому общий runtime tenant abstraction не даёт достаточной
пользы по сравнению с его стоимостью и риском cross-tenant isolation defects.

При этом выбранный технологический стек, modular-monolith boundaries и существующая
бизнес-модель сохраняются: branding, menu, preorder/delivery/pickup, cart, checkout,
server-side order calculation, idempotency, outbox и внешние уведомления остаются
частями продукта.

## Decision

Один deployment frontend/backend обслуживает ровно одного клиента. Multi-tenancy
удаляется из target runtime, persistence и public contracts:

- отсутствуют `tenantId`, `TenantContext`, `TenantResolver` и domain/slug registry;
- request host не выбирает business data и используется только для canonical URL,
  ingress allowlist и security validation;
- public API не содержит tenant slug или tenant identifier;
- database tables, primary/unique/foreign keys и repository methods не имеют
  `tenant_id` scope;
- cache, rate-limit, object-storage, outbox и background-job keys не содержат tenant;
- frontend не имеет route `/{tenantSlug}` и tenant-scoped client state;
- security разделяет public и client-admin contours, но не tenant-admin/platform-admin;
- observability не добавляет tenant labels/context;
- импорт двух tenant и suspended-tenant activation workflow отменяются.

Целевые public routes:

```text
GET  /api/v1/public/config
GET  /api/v1/public/menu
POST /api/v1/public/orders
```

Клиентские данные остаются конфигурируемыми, но являются единственным набором данных
конкретного deployment: branding/theme, contacts, schedule, SEO, locale, currency,
timezone, order types, delivery rules, menu, assets, feature configuration и
notification destinations.

## Target modules

```text
com.salamu.menu
  site          # branding, SEO, contacts, features, deployment configuration
  restaurant    # заведение/точка обслуживания и schedule
  menu          # versioned published menu
  ordering      # cart validation, checkout, orders, idempotency
  asset         # S3-compatible immutable assets
  notification  # destinations, outbox delivery, retries
  identity      # client-admin authentication/authorization
  shared        # только действительно общие primitives
```

Модуль `tenancy` удаляется. Его полезные site configuration и asset responsibilities
переносятся в `site` и `asset`; tenant resolution/lifecycle/domain registry и
cross-tenant policies не переносятся.

## Persistence and migration

Flyway остаётся единственным механизмом изменения schema. Уже применённые V1–V5 не
редактируются. Append-only migration создаёт/перестраивает single-client tables,
переносит выбранный набор данных, проверяет cardinality и referential integrity, затем
удаляет obsolete tenant/import structures и `tenant_id` columns.

Если production data ещё не существует, migration всё равно должна проходить как
clean install и upgrade V1–V5 → current. Business fixture не встраивается в Flyway:
контент выбранного клиента импортируется отдельной контролируемой операцией или admin
use case.

Order idempotency становится deployment-global. Минимальное ограничение:

```text
UNIQUE (idempotency_key)
```

Если появятся разные trusted order channels, scope может быть явно расширен до
`(channel, idempotency_key)` отдельным ADR. Деньги по-прежнему хранятся в integer
minor units, timestamps — UTC, business timezone — отдельной configuration value.

## Assets and caching

Canonical private object key:

```text
assets/{assetKind}/{sha256}.{ext}
```

Публичный CDN URL:

```text
{cdnBase}/assets/{assetKind}/{sha256}.{ext}
```

Storage остаётся private; content hash immutable. Cache key включает resource type,
locale и published menu/config version, но не tenant. Публикация новой версии меняет
versioned key или выполняет точечную invalidation.

## Frontend

Next.js App Router обслуживает основной public route `/`. Server Components загружают
единственные config/menu данные. Client Components используются для cart, forms и
browser APIs. Branding остаётся typed configuration/design tokens, однако tenant
branching и tenant-specific persistence отсутствуют.

Persisted cart содержит `schemaVersion`, `restaurantId`, `menuVersion`, `orderType` и
item identifiers. Цены, названия и доступность повторно проверяет backend.

## Legacy source selection

Stambul и Adamas остаются источниками подтверждённого поведения и визуальных решений,
но не становятся двумя runtime tenants. Перед production content import должен быть
явно выбран один клиентский dataset. Не выбранный dataset может использоваться только
как reference для feature parity и не загружается в production database.

Выбор dataset является migration/deployment decision, а не runtime slug switch. Нельзя
оставлять `if (client == ...)` или скрытую tenant abstraction ради обоих legacy наборов.

## Consequences

Положительные:

- проще schema, repository API, cache и object-storage lifecycle;
- отсутствует класс cross-tenant data leakage;
- frontend и public API имеют стабильные routes без host/slug resolution;
- проще admin security, debugging, testing и client-specific evolution;
- сохраняются modular monolith, OpenAPI-first, transactional ordering и outbox.

Отрицательные:

- новый клиент требует отдельного deployment и, возможно, отдельной ветки/продукта;
- исправления между расходящимися клиентскими версиями придётся переносить осознанно;
- центральное массовое управление клиентами и единый rollout отсутствуют;
- возврат к shared multi-tenancy потребует нового ADR и отдельной migration, а не
  повторного использования удалённых abstractions.

## Migration guardrails

- Не делать механический rename `tenant` в `client`; abstraction должна исчезнуть.
- Не сохранять единственную фиктивную tenant row как hidden singleton.
- Не использовать constant `tenantId` в repositories, cache keys или events.
- Сначала добавить single-client schema/API, затем переключить reads/writes, после
  этого удалить obsolete code и tables.
- На каждом шаге clean database и V1–V5 upgrade paths должны быть зелёными.
- Сохранять externally visible business behavior acceptance tests до удаления legacy.
- Не смешивать этот pivot с Redis, Kafka, Kubernetes, WebFlux или microservices.

## Rejected alternatives

### Multi-tenant-ready, но один активный tenant

Отклонено по прямому продуктовому решению. Оно сохраняет стоимость tenant scope,
resolver, isolation tests и schema constraints без планируемой общей платформы.

### Constant/default tenant

Отклонено: это скрытая multi-tenancy, усложняющая каждый query и event без полезной
изоляции. Unknown host не должен выбирать данные, но и default tenant row не требуется.

### Отдельная копия текущего multi-tenant runtime на клиента

Отклонено: каждый deployment продолжал бы нести неиспользуемую tenant infrastructure.
Разделяться могут продуктовые реализации будущих клиентов, а текущая система должна
иметь честную single-client domain model.
