# SC-ORD-001 — transactional single-client ordering

Status: `done`
Wave: P5
Dependencies: `SC-BE-001`, `SC-API-001`, `SC-SEC-001`

## Objective

Implement the frozen `POST /api/v1/public/orders` contract as one production-grade,
single-client ordering use case:

- required UUID `Idempotency-Key`;
- deterministic canonical payload hash;
- current site/restaurant/menu and fulfillment validation;
- authoritative server-side modifier and item pricing;
- immutable order item snapshots;
- one transaction for order, items, initial status history and pending outbox event;
- deployment-global idempotency and order numbers;
- safe identical replay and conflicting-key behavior under concurrency;
- non-cacheable success and error responses using the common error envelope.

## Non-goals

- notification provider calls, polling loop, retries or external worker scheduling;
- frontend/cart/checkout implementation;
- selected legacy dataset import;
- client-admin order management or status commands;
- payments, delivery fee/minimum-order invention or client-supplied totals;
- Redis, Kafka, WebFlux, R2DBC or a second database;
- changing the frozen OpenAPI request to reintroduce obsolete client timestamps/totals.

## Immutable decisions

- OpenAPI remains authoritative; generated sources are not hand-edited.
- There is no tenant/client runtime scope or host-selected data.
- `Idempotency-Key` is a UUID and globally unique in the deployment.
- Request item/modifier IDs are public menu IDs; persistence stores both stable internal
  menu UUIDs and public/name/price snapshots.
- Site configuration and the current published menu are loaded through their module APIs.
- External notification never runs in the order transaction.
- The accepted request has neither `clientTotal` nor `clientCreatedAt`. V8 removes the
  obsolete columns. `server_received_at` and `created_at` are authoritative and
  `needs_confirmation=false` until a future accepted asynchronous confirmation rule.
- The initial event is `order.created`, schema version `1`, with no destination reference
  or request authority.

## Exclusive write ownership

Primary scope:

```text
apps/api/src/main/java/com/salamu/menu/ordering/**
apps/api/src/test/java/com/salamu/menu/ordering/**
apps/api/src/main/resources/db/migration/V8__modernize_order_contract.sql
docs/migration/tasks/SC-ORD-001.md
docs/migration/tasks/README.md
```

Integration scope required for the common HTTP contract:

```text
apps/api/src/main/java/com/salamu/menu/ApiErrorHandler.java
apps/api/src/main/java/com/salamu/menu/menu/api/CurrentPublishedMenuGuard.java
apps/api/src/main/java/com/salamu/menu/menu/internal/infrastructure/persistence/JdbcCurrentPublishedMenuGuard.java
apps/api/src/test/java/com/salamu/menu/ApiErrorHandlerTests.java
apps/api/src/test/java/com/salamu/menu/persistence/*FlywayIntegrationTests.java
packages/api-contract/openapi/openapi.yaml
packages/api-contract/generated/typescript-fetch/**
packages/api-contract/scripts/generate-client.mjs
packages/api-contract/tests/contract.test.mjs
```

Do not edit frontend, legacy, data import or provider/worker implementation. OpenAPI is
changed first and generated TypeScript is refreshed only through the generator.

## Module and public interfaces

`ordering` remains a closed Spring Modulith module. Real synchronous dependencies are
limited to:

```text
menu::api
site::api
```

`ordering::api` exports the order command/result/failure types required by its HTTP
adapter and the outbox claim/complete/fail port consumed later by `SC-OUT-001`. The port
exposes typed event metadata and opaque JSON payload, never JPA/JDBC entities.

The future notification worker may depend on `ordering::api`; it must not import
`ordering.internal` or query `outbox_events` directly.

## HTTP contract

```text
POST /api/v1/public/orders
Content-Type: application/json
Idempotency-Key: <UUID>
```

Success:

```text
201 Created
Cache-Control: no-store
Idempotency-Replayed: false|true
```

Body fields exactly match `CreateOrderResponse`: `orderId`, decimal-string
`orderNumber`, `status=new`, `serverTotalMinor`, `currency`, `serverReceivedAt`, and
`needsConfirmation`.

The controller maps/validates HTTP only. It never calculates price or opens a transaction.
Unknown JSON fields fail because the OpenAPI schemas use `additionalProperties: false`.

## Canonical request and idempotency

Canonicalization occurs after typed request validation and before mutable menu reads:

1. Normalize the accepted order type and phone representation.
2. Require 5–15 phone digits after removing accepted display punctuation.
3. Preserve semantic customer/fulfillment/comment values after bounded trimming; a blank
   optional comment is equivalent to absence.
4. Reject duplicate menu item IDs and duplicate modifier `(group, option)` selections.
5. Sort items by public menu item ID and modifiers by group/option ID for hashing only.
6. Serialize a versioned internal canonical record with deterministic field order.
7. Compute lowercase SHA-256.

The original display order is retained as `order_items.sort_order`; canonical sorting does
not alter the order snapshot.

Behavior:

- existing key + same hash returns the original persisted response with replay header;
- existing key + different hash returns `409 IDEMPOTENCY_CONFLICT`;
- concurrent creators rely on the global database unique constraint as final arbiter;
- the unique-race loser rolls back, rereads the committed order outside the failed
  transaction and applies the same hash comparison;
- an unrelated integrity failure is never mislabeled as an idempotent replay.

## Validation and pricing

For every new order:

- site is ready and `features.orders` is enabled;
- submitted order type is enabled in `availableOrderTypes`;
- default/current restaurant ID agrees across site and menu snapshots;
- `clientMenuVersion` equals the current published version, otherwise `MENU_CHANGED`;
- customer and fulfillment values obey current `OrderLimits`;
- delivery requires a non-blank address; other fulfillment shapes cannot provide one;
- item quantities obey per-item and aggregate limits;
- every public item ID exists once in the current menu and is available;
- every selected modifier group/option exists and is available;
- modifier quantities and each group selection total obey min/max selections;
- required groups are satisfied and undeclared/duplicate selections are rejected;
- unit price is `item.priceMinor + Σ(option.priceDeltaMinor × modifier quantity)`;
- multiplication/addition is overflow checked; totals remain within accepted money bounds;
- `serverTotalMinor` is calculated only from current menu data.

Unavailable item/option maps to the specific accepted conflict code. Structurally invalid
selection and quantity violations map to validation errors. Missing current menu preserves
`MENU_NOT_FOUND`.

## Transaction and persistence

The write transaction performs exactly:

```text
recheck idempotency
→ lock/revalidate the selected menu version as current and published
→ ensure singleton order_counter row
→ allocate one global order number with an atomic row update
→ insert order
→ batch insert order_items snapshots
→ insert initial order_status_history(new/system)
→ insert pending order.created outbox event
→ commit
```

No external calls or unbounded loops occur inside the transaction. A failure rolls back
all five persistence effects, including the counter increment.

Snapshot JSON records selected modifier group/option public IDs, names, price delta and
quantity. Outbox payload contains only the versioned data
approved for the worker boundary and no destination secret.

## V8 append-only migration

`V8__modernize_order_contract.sql`:

1. treats legacy `client_total_minor` as an untrusted assertion and does not block upgrade
   when it differs from `server_total_minor`;
2. drops the totals constraint that references both columns;
3. drops `client_total_minor` and `client_created_at`;
4. adds/retains a bounded authoritative `server_total_minor` check;
5. preserves IDs, order numbers, hashes, `server_total_minor`, `needs_confirmation`,
   snapshots, timestamps, history and outbox data;
6. is tested from clean V1→V8 and differing-total populated V7→V8 paths.

Applied V1–V7 migrations are immutable.

## Error mapping and redaction

| Condition | HTTP/code |
|---|---|
| invalid header/body/limits/selection | `400 VALIDATION_ERROR` |
| no published menu | `404 MENU_NOT_FOUND` |
| stale version | `409 MENU_CHANGED` |
| item unavailable | `409 ITEM_UNAVAILABLE` |
| modifier unavailable | `409 MODIFIER_UNAVAILABLE` |
| key reused for different payload | `409 IDEMPOTENCY_CONFLICT` |
| required database dependency unavailable | `503 DEPENDENCY_UNAVAILABLE` |
| unexpected failure | `500 INTERNAL_ERROR` |

All order successes and errors use `Cache-Control: no-store`. Errors use the common
four-field envelope and bounded field/reason details. Logs, exception messages, traces and
metric labels never include phone, address, comment, idempotency key, raw payload,
destination reference or order ID.

## Implementation sequence

1. Freeze this task contract and mark registry `in_progress`.
2. Add V8 and update order persistence mappings/tests.
3. Add typed domain command, canonicalizer, validation/pricing policy and exceptions.
4. Add JDBC repositories and separate proxied transaction executor.
5. Add order application service with replay/race recovery.
6. Add exact Spring MVC request/response adapter.
7. Add common error mappings and non-cacheable headers.
8. Add outbox public port/types agreed with `SC-OUT-001` without worker code.
9. Run unit, PostgreSQL, concurrency, MVC, security and module tests.
10. Run full backend check and reconcile task/registry status.

## Required tests

### Unit

- canonical hash is deterministic across item/modifier input ordering and changes for
  every semantic field;
- all order types and fulfillment rules;
- exact site limits and menu-version check;
- unavailable/missing item and modifier paths;
- modifier min/max, duplicates, quantity and overflow;
- server totals and complete immutable snapshots;
- no PII in exception strings.

### PostgreSQL/Testcontainers

- V8 clean and populated upgrade with preservation assertions;
- historical client/server total mismatch upgrades while preserving the authoritative
  server total and confirmation state;
- one successful transaction writes order/items/history/outbox and increments counter;
- forced item/outbox failure rolls back every row and counter increment;
- identical sequential replay returns one persisted result;
- simultaneous identical requests create one order/history/outbox event;
- simultaneous conflicting requests create at most one and one conflict;
- menu publication between preparation and write returns `MENU_CHANGED` and writes no
  order, item, history, outbox or counter row;
- global key and order number uniqueness;
- required JDBC failures return only safe `DEPENDENCY_UNAVAILABLE` diagnostics;
- no network/provider interaction.

### HTTP/security

- exact path and required UUID header;
- three accepted request shapes and exact `201` response;
- replay header false/true and `Cache-Control: no-store`;
- malformed/unknown JSON and fulfillment shape receive common validation envelope;
- accepted conflict codes/details and no PII leakage;
- rate limiter/security chain remains before controller persistence.

## Failure and edge cases

- Empty database without site/menu returns safe accepted errors and writes nothing.
- Missing order counter is initialized transactionally; more than one singleton row is
  impossible by schema.
- Empty optional comment/address values normalize consistently and never bypass delivery
  validation.
- A modifier cannot make unit/line/order total negative or overflow `long`.
- Published menu replacement between validation and insert is detected by locking and
  revalidating the selected current published menu row inside the write transaction; stale
  input returns `MENU_CHANGED` before any order or counter insert.
- Database outage is not an idempotency conflict.
- Outbox worker absence leaves a durable pending event and does not change `201`.

## Acceptance commands

```text
pnpm --filter @e-menu/api-contract validate
./gradlew :apps:api:compileJava :apps:api:compileTestJava
./gradlew :apps:api:test --tests "*Order*" --tests "*Idempotency*"
./gradlew :apps:api:test --tests "*Ordering*PostgreSql*" --tests "*Flyway*"
./gradlew :apps:api:test --tests "*SecurityIntegration*" --tests "*ApiErrorHandler*"
./gradlew :apps:api:test --tests "*ApplicationModule*" --tests "*Modularity*"
./gradlew :apps:api:test
./gradlew :apps:api:check
git diff --check
```

## Handoff report

- Contract/OpenAPI base: frozen `SC-API-001` contract plus a compatible phone-format
  constraint requiring 5–15 digits; generated TypeScript is refreshed deterministically
  with generator `7.22.0`.
- V8: obsolete client total/time columns are removed without rejecting historical
  client/server mismatches; clean and populated upgrades preserve authoritative order data.
- Module graph: closed `ordering` depends only on `menu::api` and `site::api`; worker access
  is the opaque, redacted `ordering::api` outbox port.
- Idempotency: normalized UUID key, versioned deterministic SHA-256, database global unique
  arbiter, original-result replay and post-rollback race recovery are implemented.
- Validation/pricing: exact JSON fields, site limits/features, current menu version,
  item/modifier availability, selection bounds, overflow-safe server totals and immutable
  line/modifier snapshots are implemented.
- Transaction: order, batch items, initial history and pending `order.created` event commit
  together with the global counter allocation. Initial history uses the `system` actor.
  The selected published menu is locked/revalidated before the first insert. No external
  provider call occurs.
- Outbox persistence evidence covers concurrent `SKIP LOCKED`, live/stale leases,
  retry availability, max-attempt terminal state and backlog projection.
- HTTP: exact route and headers, `201` response, common bounded errors and `no-store` on
  success/error are covered. Diagnostic outbox representations omit IDs, payload and PII.
- Targeted gate passed on 2026-07-14:

```text
./gradlew.bat :apps:api:test \
  --tests "*StrictOrderRequestParserTests" \
  --tests "*OrderPreparationServiceTests" \
  --tests "*PublicOrderControllerTests" \
  --tests "*PublicOrderCreationPostgreSqlIntegrationTests" \
  --tests "*OrderingModuleTests" \
  --tests "*OrderingPersistence*" \
  --tests "*OrderingJpaPostgreSqlIntegrationTests" \
  --tests "*FlywaySchemaIntegrationTests" \
  --tests "*SingleClientActivationFlywayIntegrationTests" \
  --tests "*ModularityTests" --no-daemon
BUILD SUCCESSFUL
```

- Independent review hardening covers stale-menu publication races, simultaneous conflicting
  keys, forced item/outbox rollback including the counter, safe JDBC dependency mapping,
  blank-comment hash equivalence and phone digit bounds.
- Fresh review gates passed on 2026-07-14 after all SC-ORD and integrated SC-OUT edits:

```text
pnpm --filter @e-menu/api-contract validate
PASS: OpenAPI lint, 20 contract tests, deterministic generated-code check, TypeScript

./gradlew.bat :apps:api:test \
  --tests "*PublicOrderCreationPostgreSqlIntegrationTests" \
  --tests "*PublicOrderApplicationServiceTests" --no-daemon
BUILD SUCCESSFUL in 23s

./gradlew.bat :apps:api:check --no-daemon
BUILD SUCCESSFUL in 1m 15s
```

- Unresolved implementation blocker: none.
- Suggested next unlock: integrated `SC-OUT-001` review, then `SC-VAL-001` after data/web/cart.
- Git status: shared dirty worktree; no commit was created by this task agent.
