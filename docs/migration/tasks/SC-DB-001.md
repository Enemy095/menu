# SC-DB-001 — append-only single-client schema pivot

Status: `ready`
Wave: P1
Dependencies: `SC-000`, `INF-001`

## Objective

Добавить Flyway migration, после которой live PostgreSQL schema является настоящей
single-client моделью без `tenant_id`, tenant domain/lifecycle и dual-tenant import
ledger, сохранив menu/order/assets/notification invariants.

## Exclusive ownership

Агент владеет:

- новой append-only migration после V5;
- Flyway/schema/JPA metadata integration tests, относящимися к schema pivot;
- при необходимости новыми persistence-neutral schema test fixtures.

Не менять Java repositories/controllers, OpenAPI, legacy, V1–V5 или frontend.

## Required schema

- `site_configuration`, `site_branding`, `site_features` singleton/global data;
- `restaurants` без tenant FK, global stable external key и один default;
- `menu_versions/categories/items` scoped restaurant/menu version, не tenant;
- `assets` с global content-addressed identity;
- `notification_destinations` без tenant owner;
- `orders/order_items/outbox_events/notification_deliveries` без tenant columns;
- global order idempotency and order-number uniqueness;
- V5 migration ledger/provenance removed after safe checks.

## Fail-closed rules

- Migration не выбирает один из нескольких populated tenants автоматически.
- Если сохраняемые rows принадлежат более чем одному tenant, migration abort до
  destructive DDL с stable diagnostic.
- Duplicate future-global slugs, external IDs, idempotency keys, order numbers, assets
  или destination keys abort.
- Cross-owner/orphan FK state abort.
- Empty V1–V5 database upgrades to empty single-client schema.
- One-owner data may be transformed without retaining owner UUID.

## Asset boundary

SQL меняет только metadata. Перемещение S3 objects из
`tenants/{uuid}/{kind}/{hash}.{ext}` в `assets/{kind}/{hash}.{ext}` выполняется отдельной
application/infrastructure migration до удаления старых objects. Schema должна
поддерживать проверяемое переходное состояние без public alias ambiguity.

## Tests

- clean V1→latest;
- upgrade V1–V5 empty;
- upgrade one populated owner preserving all counts/amounts/snapshots;
- two populated owners fail closed and preserve old schema/data;
- collisions/orphans fail closed;
- `information_schema` proves no target column/index/FK contains `tenant_id`;
- JPA/schema validation updated only after repositories switch; до этого допускается
  dedicated migration-test context без запуска obsolete repositories.

## Acceptance commands

```text
./gradlew :apps:api:test --tests "*Flyway*" --tests "*SingleClientSchema*"
./gradlew :apps:api:compileJava :apps:api:compileTestJava
git diff --check
```

Агент не коммитит. Orchestrator выполняет review и integration commit.
