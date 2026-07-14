# SC-API-001 — single-client OpenAPI contract

Status: `ready`
Wave: P1
Dependencies: `SC-000`

## Objective

Перевести public OpenAPI и generated TypeScript client на canonical routes без tenant
slug/ID, сохранив config/menu DTO и определив production-ready order contract.

## Exclusive ownership

- `packages/api-contract` OpenAPI sources/config/fixtures;
- generated TypeScript client outputs;
- contract lint/generation/compatibility tests;
- task-specific contract documentation.

Не менять Spring controllers/repositories, Flyway, legacy или frontend app.

## Canonical operations

```text
GET  /api/v1/public/config
GET  /api/v1/public/menu
POST /api/v1/public/orders
```

## Contract rules

- remove `tenantSlug`, `tenantId` and tenant error codes from public schemas;
- retain typed branding, contacts, schedule, locale, currency, timezone, order types,
  delivery configuration, features and menu/version fields;
- `POST /orders` requires `Idempotency-Key` header;
- request contains item IDs/quantities/modifiers and customer fulfillment data, but
  client total is never authoritative;
- response exposes order reference/status/server total without destination secrets;
- stable error envelope: `code`, `message`, `details`, `traceId`;
- define validation, stale menu, item unavailable, idempotency conflict, rate limit and
  dependency error codes;
- generated files are deterministic and never hand-edited.

## Compatibility decision

Repository has no evidence of a released public client and no production order
controller. Therefore v1 is corrected in place. If release evidence appears before
integration, stop and create a versioning ADR instead of silently breaking clients.

## Tests and acceptance

- OpenAPI lint passes;
- generator passes twice without diff;
- generated client methods have no tenant parameter;
- schema search contains no tenant fields/routes;
- examples cover all order types and stable errors;
- old `/api/public/orders` and slug routes are absent.

```text
pnpm --filter @menu/api-contract lint
pnpm --filter @menu/api-contract generate
pnpm --filter @menu/api-contract check:generated
git diff --check
```

Агент не коммитит. Orchestrator reviews generated diff and creates integration commit.
