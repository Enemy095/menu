# Master migration plan

## Цель

Перенести существующее статическое электронное меню в одну multi-tenant white-label платформу без big-bang rewrite и без потери подтверждённого пользовательского поведения.

Целевая система:

```text
CDN
  → Next.js App Router / React / strict TypeScript
  → Spring Boot MVC modular monolith / Java 25
  → PostgreSQL
  → transactional outbox worker
  → tenant-specific WhatsApp / Telegram / email / POS
```

Один frontend и один backend обслуживают много заведений. Tenant определяется по trusted host/path mapping и после resolution представлен внутренним immutable `tenantId`. Код, schema и deployment общие; branding, menu, features, contacts, order types, assets, locale, currency, timezone и notification destinations являются tenant data.

Полные архитектурные ограничения находятся в корневом [`AGENTS.md`](../../AGENTS.md).

## Оркестрация

- Одновременно выполняются не более трёх worker-задач.
- Агент получает одну bounded-задачу и единоличное владение указанными путями.
- Задача без закрытых dependencies не запускается.
- Агент не расширяет scope и не меняет shared contracts без handoff оркестратору.
- Оркестратор проверяет diff, запускает acceptance commands и только затем открывает следующий wave.
- Каждый wave завершается integration checkpoint и осмысленным commit.
- Legacy-каталог не меняется, пока task явно не разрешает это.

## Зафиксированные решения

- Backend: Java 25, Spring Boot 4.1, Spring MVC, Spring Data JPA, Spring Modulith.
- Build: Gradle Wrapper, Gradle Kotlin DSL, version catalog.
- Frontend: Next.js App Router, React, strict TypeScript, pnpm workspace.
- Database: PostgreSQL 17; Flyway — единственный механизм schema migration.
- API: OpenAPI 3.0.3 first, generated `typescript-fetch` client; Java server stubs не генерируются.
- Public API namespace: `/api/v1/public/{tenantSlug}`.
- Money: `bigint` minor units; timestamps: UTC/`timestamptz`.
- `tenantId`: UUID, внутренний и не публикуется без необходимости.
- В MVP один tenant имеет один default restaurant; schema допускает расширение.
- Реальный заказ всегда создаётся backend. Legacy WhatsApp redirect не является целевой отправкой заказа.
- Redis, broker, WebFlux, R2DBC, RLS, virtual threads и microservices не входят в MVP.
- PostgreSQL является источником истины; CDN/cache ускоряет reads, но не подтверждает заказ.

## Public API v1

```text
GET  /api/v1/public/{tenantSlug}/restaurant
GET  /api/v1/public/{tenantSlug}/menu
POST /api/v1/public/{tenantSlug}/orders
```

Tenant resolution precedence:

1. Нормализовать effective host: lowercase, strip port/trailing dot, IDN to ASCII.
2. Forwarded headers принимать только от trusted ingress.
3. Active verified custom domain или platform subdomain определяет tenant.
4. На platform/local host tenant определяется через `{tenantSlug}`.
5. Если host и slug определяют tenant одновременно, они обязаны совпасть.
6. Unknown, pending, disabled domain и inactive tenant возвращают `404 TENANT_NOT_FOUND`.
7. Query, arbitrary header и body `tenantId` не участвуют в resolution.

## Dependency graph

```text
PLAN-001 + LEG-001
        │
        ├──────────────┬──────────────┐
        ▼              ▼              ▼
 BE-001/MOD-001     API-001        INF-001
        └──────────────┼──────────────┘
                       ▼
                    DB-001
                 ┌─────┼───────────┐
                 ▼     ▼           ▼
              TEN-001 OBS-001    CI-001
                 ├──────────────┐
                 ▼              ▼
              AST-001         SEC-001
                 └──────┬───────┘
                        ▼
               CFG-001 + MENU-001
                        │
          ┌─────────────┼──────────────┐
          ▼             ▼              ▼
       MIG-001       WEB-001        ORD-001
          │             ▼              │
          │          WEB-002           ▼
          │             ▼           OUT-001
          │          CART-001          │
          │             ▼              │
          │        CHECKOUT-001 ◄──────┘
          └─────────────┼──────────────┘
                        ▼
                     VAL-001
                        ▼
                   CUTOVER-001
```

## Waves

### Wave 0 — architecture and characterization

- `PLAN-001`: архитектурные правила и данный master plan.
- `LEG-001`: legacy parity, defects и golden scenarios.

Gate: цели, non-goals, legacy defects и task ownership записаны в repository.

### Wave 1 — независимые foundations

- `BE-001/MOD-001`: Spring Boot/Gradle scaffold и Modulith boundaries.
- `API-001`: OpenAPI contract package и deterministic TS generation.
- `INF-001`: PostgreSQL/MinIO/observability local infrastructure.

Gate: фиксируются package names, API schemas, dependency catalog и datasource conventions.

### Wave 2 — persistence and quality foundations

- `DB-001`: V1–V4 Flyway schema, JPA mappings и tenant-aware constraints.
- `OBS-001`: базовые logs/traces/health/metrics.
- `CI-001`: build, contract, migration и security quality gates.

Gate: clean PostgreSQL поднимается и мигрируется; module verification и baseline CI зелёные.

### Wave 3 — tenant boundary and infrastructure adapters

- `TEN-001`: trusted host/path resolver и immutable tenant context.
- `AST-001`: S3-compatible asset adapter и CDN URL policy.
- `SEC-001`: public/admin boundaries и rate limiting.

Gate: host spoofing, unknown tenant и cross-tenant scenarios покрыты integration tests.

### Wave 4 — tenant reads and deterministic import

- `CFG-001`: restaurant/branding/profile/features read API.
- `MENU-001`: published menu read API с bounded query count.
- `MIG-001`: idempotent import Stambul/Adamas и assets.

Gate: API возвращает точные tenant fixtures; 23/19 items и category ordering совпадают с legacy.

### Wave 5 — SSR frontend and browsing

- `WEB-001`: Next.js/pnpm scaffold, tenant-aware SSR shell, metadata и errors.
- `WEB-002`: branding, contacts, menu, filters и Adamas delivery widgets.

Gate: HTML одного tenant не содержит данные другого; visual/a11y parity для обоих tenants.

### Wave 6 — cart and checkout UI

- `CART-001`: единственный Zustand cart store и versioned tenant-scoped persistence.
- `CHECKOUT-001`: RHF/Zod checkout, generated client, pending idempotent draft и result UX.

Gate: reload/switch/corruption/limits/double-submit scenarios проходят; PII не сохраняется.

### Wave 7 — transactional ordering and delivery

- `ORD-001`: server validation, totals, snapshots, idempotency, order transaction.
- `OUT-001`: outbox polling, retries, destination resolution и delivery audit.

Gate: concurrent double submit создаёт один order; notification failure не теряет order.

### Wave 8 — system validation and cutover

- `VAL-001`: contract, isolation, E2E, visual, accessibility, failure и load validation.
- `CUTOVER-001`: canary tenant, rollback rehearsal, production checklist и legacy retirement.

Gate: zero cross-tenant reads, zero duplicate orders, green parity suite и подтверждённый rollback.

## Scope boundaries

В migration scope:

- два tenant: `stambul` и `adamas`;
- white-label configuration;
- config/menu public reads;
- menu browsing, contacts, responsive cart и checkout;
- preorder/delivery/pickup;
- secure backend order creation;
- tenant-aware notifications;
- custom/platform domain architecture;
- assets, caching, observability и CI;
- controlled legacy cutover.

Не входят в первый migration release:

- полноценная tenant/platform admin UI;
- online payment;
- POS-specific production connector;
- loyalty/reservations/promotions beyond current visible behavior;
- multi-restaurant management inside one tenant;
- Redis/Kafka/Kubernetes/microservice decomposition;
- separate frontend deployment per tenant.

## Integration rules

- Не смешивать implementation wave с несвязанным legacy cleanup.
- Не создавать business fixtures в production Flyway migrations.
- Не коммитить реальные manager phones как public config.
- Не обещать exactly-once external delivery: outbox даёт durable at-least-once processing; provider idempotency используется где доступно.
- Не запускать `ORD-001` и `OUT-001` параллельно: они разделяют transaction/event contract.
- Один агент владеет root Gradle setup; один — initial migrations; один — OpenAPI schema.
- Frontend начинает real API integration после фиксации API-001; до этого разрешены contract fixtures.

## Completion criteria

Миграция завершена, когда:

- Stambul и Adamas работают на общем codebase без slug-specific branches;
- tenant определяется только trusted host/path mapping;
- public API не раскрывает `tenantId`, manager destination или secrets;
- 42 legacy menu items импортированы с корректными minor-unit prices;
- order создаётся transactionally с item snapshots и outbox event;
- идентичный retry возвращает исходный order, конфликтный retry получает `409`;
- notification failure не меняет успешный order result;
- tenant data, cache, assets, jobs и logs не пересекаются;
- legacy parity, security, contract, accessibility и E2E suites зелёные;
- canary и rollback проверены;
- legacy удаляется отдельным финальным изменением после rollback window.

## Task registry

Подробные задания находятся в [`tasks/`](./tasks/README.md). Legacy evidence и намеренно не переносимые дефекты описаны в [`legacy-parity.md`](./legacy-parity.md).
