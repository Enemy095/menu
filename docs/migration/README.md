# Master migration plan

## Цель

Перенести существующее электронное меню в single-client приложение без big-bang
rewrite и потери подтверждённого поведения.

```text
CDN
  → Next.js App Router / React / strict TypeScript
  → Spring Boot MVC modular monolith / Java 25
  → PostgreSQL
  → transactional outbox worker
  → configured WhatsApp / Telegram / email / POS destination
```

Один deployment frontend/backend обслуживает одного клиента. Multi-tenancy,
`tenantId`, resolver/context, tenant routes и tenant-scoped persistence удаляются.

Источники решений:

- [`AGENTS.md`](../../AGENTS.md) — обязательные engineering rules;
- [`ADR-0003`](../adr/0003-single-client-deployment.md) — single-client decision;
- [`implementation-plan.md`](../architecture/single-client/implementation-plan.md) —
  подробная последовательность;
- [`tasks/README.md`](tasks/README.md) — executable task registry.

## Current checkpoint

Старые `TEN/AST/CFG/MENU/MIG` задачи были выполнены или начаты для multi-tenant target
и теперь имеют status `superseded`. Их код является migration input для refactor, но не
целевой архитектурой. Two-tenant importer не продолжается.

Сохраняются нейтральные foundations:

- Java 25/Spring Boot/Gradle scaffold;
- Spring Modulith verification;
- PostgreSQL/Flyway/Testcontainers;
- OpenAPI generation pipeline;
- observability/CI/local infrastructure;
- domain behavior menu/order/assets/notification, очищенное от tenant scope.

## Dependency graph

```text
SC-000
  ├── SC-DB-001 ──┐
  └── SC-API-001 ─┴→ SC-BE-001 → SC-SEC-001
                              └→ SC-DATA-001
                                   ├→ SC-WEB-001 → SC-CART-001
                                   └→ SC-ORD-001 → SC-OUT-001
                                                     │
                                      SC-VAL-001 ◄───┘
                                           │
                                      SC-CUTOVER-001
```

## Waves and gates

### P0 — architecture reset

- ADR, AGENTS, impact audits, task registry.
- Old multi-tenant work clearly marked historical/superseded.

Gate: architecture docs consistent and checkpoint commit created.

### P1 — schema and public contract

- Append-only final schema without `tenant_id`.
- Public OpenAPI routes without tenant slug.

Gate: Flyway clean/upgrade and contract generation pass independently.

### P2 — backend refactor

- `site` and `asset` ownership extracted.
- `tenancy` resolver/context/lifecycle and migration provisioning removed.
- Config/menu reads switched to single-client persistence.
- Public/admin security boundary rebuilt without tenant roles.

Gate: module, PostgreSQL, MinIO, security and zero-tenant-runtime tests pass.

### P3 — selected content

- Owner selects exactly one production dataset.
- One idempotent import/configuration operation loads it.

Gate: public config/menu match the selected approved baseline; other dataset absent.

### P4 — frontend

- Next.js SSR shell, branding, contacts and menu at `/`.

Gate: production build, SSR, visual and accessibility tests pass.

### P5 — cart, checkout and ordering

- Versioned single-client cart.
- RHF/Zod checkout.
- Server totals, global idempotency, order snapshots and outbox event.

Gate: duplicate/concurrent submit creates one order.

### P6 — external delivery

- Outbox claiming, retries, backoff, dead-letter and configured destination.

Gate: provider failure never loses a committed order.

### P7/P8 — validation and cutover

- Contract, security, performance, visual, E2E и production-build gates.
- Backup/rollback rehearsal, deployment, observation и legacy retirement.

## Scope

В migration scope:

- один клиентский dataset;
- branding/config/menu/assets;
- preorder/delivery/pickup;
- responsive cart/checkout;
- secure transactional order creation;
- notification delivery through outbox;
- CDN/cache/observability/CI;
- controlled cutover.

Не входят в первый release:

- multi-tenancy и platform administration;
- online payment;
- loyalty/reservations/promotions beyond approved behavior;
- Redis/Kafka/Kubernetes/microservices;
- параллельные Stambul/Adamas runtime modes.

## Product decision gates

До production content import владелец должен выбрать ровно один dataset: Stambul,
Adamas или новый approved bundle. До реальной notification delivery должен быть
настроен opaque destination reference. Эти два gate не блокируют schema/API/backend
framework/frontend scaffold work.

## Completion criteria

- runtime code, live schema и public API не содержат tenant abstraction;
- выбранный config/menu отображается с подтверждённым поведением;
- order создаётся transactionally с item snapshots и outbox event;
- identical retry возвращает исходный order, conflicting retry получает `409`;
- notification failure не меняет successful order result;
- PII/secrets отсутствуют в logs и client bundle;
- clean/upgrade database, production builds и E2E suites зелёные;
- cutover/rollback проверены;
- legacy удалён после observation window.
