# SC-DB-001 — append-only single-client schema pivot

Status: `done`
Wave: P1
Dependencies: `SC-000`, `INF-001`

## Objective

Добавить expand-phase Flyway migration, которая создаёт и заполняет проверяемую
single-client shadow schema без `tenant_id`, сохраняя старые runtime tables до
переключения Java-кода в `SC-BE-001`. Contract/drop migration выполняется только в том
же checkpoint, где callers уже переведены; промежуточный commit обязан оставаться
запускаемым.

V6 не является самостоятельным production deployment. После commit его транзакции
legacy writers снова могут менять V1–V5 tables, поэтому production проходит этот
checkpoint только внутри maintenance sequence SC-BE/V7, описанной ниже.

## Exclusive ownership

Агент владеет:

- новой append-only expand migration после V5;
- Flyway/schema/JPA metadata integration tests, относящимися к schema pivot;
- при необходимости новыми persistence-neutral schema test fixtures.

Не менять Java repositories/controllers, OpenAPI, legacy, V1–V5 или frontend.

## Required schema

- shadow `sc_site_configuration`, `sc_site_branding`, `sc_site_features`
  singleton/global data;
- `sc_restaurants` без tenant FK, global stable external key и один default;
- `sc_menu_versions/categories/items` scoped restaurant/menu version, не tenant;
- `sc_assets` с global content-addressed identity;
- `sc_notification_destinations` без tenant owner;
- `sc_orders/order_items/outbox_events/notification_deliveries` без tenant columns;
- global order idempotency and order-number uniqueness;
- V5/tenant tables сохраняются в expand phase и удаляются contract migration после
  переключения code. Final names теряют `sc_` prefix в contract phase.

## Fail-closed rules

- Migration не выбирает один из нескольких populated tenants автоматически.
- Если сохраняемые rows принадлежат более чем одному tenant, migration abort до
  destructive DDL с stable diagnostic.
- Duplicate future-global slugs, external IDs, idempotency keys, order numbers, assets
  или destination keys abort.
- Cross-owner/orphan FK state abort.
- Для непустого `restaurants` требуется ровно один одновременно active и default row.
- Непустой `orders` требует ровно один legacy order counter с `next_value` больше любого
  существующего `order_number`. При пустом `orders` отсутствие counter допустимо, а
  refresh детерминированно оставляет `sc_order_counter` пустым.
- Поддерживается только outbox aggregate `order`. Неизвестный aggregate type fail closed;
  каждый order aggregate обязан ссылаться на существующий order того же source owner.
- Empty V1–V5 database upgrades to empty single-client schema.
- One-owner data may be transformed without retaining owner UUID.

## Reusable V6/V7 refresh contract

V6 владеет двумя временными database functions:

- `sc_assert_single_client_source()` получает `SHARE` locks на все V1–V5 source tables
  в одном зафиксированном порядке, блокирует legacy writes и выполняет все preflight;
- `sc_refresh_single_client_shadow()` вызывает assertion, одновременно очищает shadow
  tables, backfill их в FK-порядке и двунаправленно проверяет `EXCEPT ALL` по каждой
  переносимой table/column.

Обе functions фиксируют creation-time `search_path`, сразу отзывают `EXECUTE` у
`PUBLIC` и доступны только migration owner. V6 вызывает refresh один раз. После drain
старых readers/writers/workers V7 обязан повторно вызвать тот же refresh внутри contract
transaction до compare/swap и удалить обе functions после финальных catalog assertions.
Это migration helpers, а не runtime API.

Refresh сохраняет `canonical_ready` только если ID, asset type, content type и hash
источника не изменились. Новый или изменённый legacy asset снова становится
`legacy_only`, поэтому V7 fail closed до повторной внешней object-copy verification.

## Asset boundary

SQL меняет только metadata. Перемещение S3 objects из
`tenants/{uuid}/{kind}/{hash}.{ext}` в `assets/{kind}/{hash}.{ext}` выполняется отдельной
application/infrastructure migration до удаления старых objects. Schema должна
поддерживать проверяемое переходное состояние без public alias ambiguity.

Target `content_type` ограничен утверждённым image allowlist. CHECK для
`canonical_object_key` требует точного равенства значению, полученному из `asset_type`,
`sha256` и extension для `content_type`; одной permissive path regex недостаточно.

## Tests

- clean V1→latest;
- upgrade V1–V5 empty;
- upgrade one populated owner preserving all counts/amounts/snapshots;
- late legacy write после V6 и повторный owner-only refresh дают точный новый shadow,
  сохраняя readiness неизменившихся canonical assets;
- two populated owners fail closed and preserve old schema/data;
- collisions, missing/behind counter, invalid active default, unsupported/orphan outbox
  aggregate и unsupported asset content type fail closed;
- direct target writes проверяют exact canonical key и content-type allowlist;
- bidirectional `EXCEPT ALL` внутри refresh охватывает каждую copied table/column;
- `information_schema` proves no `sc_*` target column/index/FK contains `tenant_id`;
- JPA/schema validation updated only after repositories switch; до этого допускается
  dedicated migration-test context без запуска obsolete repositories;
- existing full backend context продолжает работать на legacy tables до SC-BE switch.

## Acceptance commands

```text
./gradlew :apps:api:test --tests "*Flyway*" --tests "*SingleClientSchema*"
./gradlew :apps:api:compileJava :apps:api:compileTestJava
git diff --check
```

Агент не коммитит. Orchestrator выполняет review и integration commit.
