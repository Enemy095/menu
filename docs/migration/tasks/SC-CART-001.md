# SC-CART-001 — single-client cart and checkout

Status: `done`
Wave: P5
Dependencies: `SC-WEB-001`, `SC-API-001`, `SC-ORD-001`

## Objective

Deliver the public single-client cart and checkout vertical slice on top of the published
menu. A customer can select items and modifiers, retain a non-PII cart across reloads,
choose one configured fulfillment type and submit an idempotent order through the
generated OpenAPI client. The backend remains authoritative for availability, limits and
the final total.

## Non-goals

- admin configuration, menu editing or order management;
- tenant/client resolver, host-selected data or multiple runtime datasets;
- browser-side pricing authority or persistence of customer fields;
- payment, authentication, loyalty, promotions or reservations;
- Redis, WebFlux, an independent Next.js business backend or direct database access;
- production content selection/import (`SC-DATA-001` remains a separate decision).

## Immutable decisions

- Cart storage is `e-menu:cart:v1`, schema-versioned and not tenant-scoped.
- The persisted value contains only restaurant/menu scope, order type, item IDs,
  quantities and modifier selections. Name, phone, address and comment never enter
  Zustand persistence.
- Unknown/corrupt envelopes are discarded and replaced by a valid empty state. They are
  never partially interpreted.
- `maxItemQuantity` and `maxTotalItems` honor stricter public configuration while retaining
  absolute client safety ceilings of 99/item and 200 total items.
- Catalog/menu data remains an immutable prop/context. Zustand stores cart workflow state,
  not server state or copied names/prices.
- Browser totals are labelled `Предварительно`; no price/total is sent in the order request.
- A successful response displays only `serverTotalMinor` as the final total and formats it
  with the response `currency`; site configuration currency remains estimate-only.
- The backend accepts one line per `menuItemId`. Identical modifier selections aggregate
  quantity; a second variant of the same item is rejected in the UI and can never enter the
  generated request. Duplicate group/option pairs are canonicalized and the 50-selection
  contract limit is checked again at the payload boundary. Every source line quantity is a
  positive safe integer within the contract bound before duplicate aggregation, and the
  aggregated quantity is checked again.
- The idempotency UUID exists in memory for a semantic checkout draft. A retry of the exact
  same generated payload reuses it; any changed payload receives a new UUID. Persisting a
  PII-derived fingerprint to survive reload is deliberately forbidden.
- The generated `OrdersApi` is the only browser order contract client. A transport-only
  same-origin route forwards the canonical request because `MENU_API_BASE_URL` is
  server-only; it owns no business validation and forwards no cookie/authorization header.

## Exclusive write ownership

```text
apps/web/**
apps/web/package.json
pnpm-lock.yaml
docs/migration/tasks/SC-CART-001.md
docs/migration/tasks/README.md        # own status row only
```

Legacy, backend, OpenAPI and generated contract sources are read-only for this task.

## Architecture and interfaces

```text
PublicMenuPage (Server Component)
→ CartExperience / CartProvider (bounded client catalog context)
  → server-rendered MenuBrowser
    → AddToCartControl (IDs + modifier selection)
  → Zustand persisted cart (no server data, no PII)
  → responsive CartPanel
    → RHF + dynamic Zod checkout
    → generated OrdersApi
    → POST /api/v1/public/orders (same-origin transport)
    → Spring canonical POST /api/v1/public/orders
```

The client catalog contains the public menu item and modifier data required to render
choices, reconcile stored IDs and display an estimate. It is never persisted in Zustand.
`restaurantId` protects a stale cart from accidental reuse if deployment data is replaced;
it does not create multi-client runtime selection.

## Cart lifecycle

```text
hydrate persisted envelope
→ strict schema/version validation
→ reconcile restaurantId + menuVersion against current catalog
→ remove unavailable/missing item or invalid modifier selection
→ clamp quantities to effective limits
→ merge one deterministic line per menuItemId
→ persist only normalized cart identifiers
```

Menu-version changes are surfaced to the customer. If an item or required modifier becomes
invalid the complete line is removed instead of silently manufacturing a new selection.
Changing a restaurant scope clears the items rather than carrying them into unrelated data.

## Checkout and submission lifecycle

```text
configured order-type radio
→ RHF input state (memory only)
→ Zod normalization + limits + conditional delivery address
→ generated CreateOrderRequest without totals
→ canonical payload fingerprint (memory only)
→ UUID Idempotency-Key
→ generated OrdersApi.createPublicOrderRaw
→ replay header + strict success/error response validation
→ success / retryable error / menu-changed state
```

Delivery sends an address and optional guest count. Preorder sends optional guest count.
Pickup sends neither address nor an invented default. All variants send normalized name and
phone, item/modifier IDs, quantities and `clientMenuVersion`. The UI prevents double-submit
and locks every cart mutation around an immutable revision snapshot while a request is in
flight. Network/dependency retries preserve the key. A menu/item/
modifier conflict directs the user to refresh and re-reconcile the cart. On confirmed
success the submitted persisted revision is cleared synchronously, while an immutable
in-memory receipt remains visible with the order number, authoritative total, confirmation
requirement and replay indicator. Reload therefore cannot resubmit the confirmed cart with a
new key. A defensive revision comparison preserves any later out-of-band additions instead
of clearing them.

## Security and privacy behavior

- Next.js forwards only JSON, `Accept` and a validated UUID `Idempotency-Key`.
- Cookies, browser authorization, forwarded host and arbitrary request headers are not sent
  to the Spring API.
- Request bodies are limited to 64 KiB at the same-origin boundary. An oversized declared
  `Content-Length` is rejected before reading; undeclared bodies are consumed through a
  bounded stream reader and cancelled as soon as the cap is crossed.
- Upstream responses are always `Cache-Control: no-store`; only safe replay/retry/trace
  headers are copied.
- Generated responses pass an additional strict, no-extra-properties Zod boundary with all
  OpenAPI string, array and money bounds before use.
- External error text is mapped to controlled Russian UI states; details are not rendered.
- No PII is stored in local storage, logging, cache keys or metric labels by this slice.

## Deliverables

- pure cart reconciliation, deterministic lines and catalog-based estimate functions;
- Zustand v1 persistence with safe hydration and configured/absolute limits;
- item/modifier selection controls and accessible quantity/removal controls;
- desktop fixed panel plus mobile bottom bar/dialog presentation;
- modal focus entry/trap/restore and Escape handling, including the submitting state where
  controls inherited-disabled by a fieldset are excluded, plus associated live field errors;
- configured order types and conditional RHF/Zod checkout fields;
- generated OrdersApi adapter with replay/error handling and stable retry key;
- transport-only App Router POST route to the canonical backend endpoint;
- unit, store persistence, RTL workflow, accessibility and route-boundary tests.

## Required tests and edge cases

- corrupt and unknown storage versions become safe empty state;
- another restaurant scope clears items;
- menu update preserves valid lines and drops unavailable/invalid selections;
- configuration and absolute item/total ceilings cannot be exceeded;
- normal UI and defensive payload construction never emit duplicate `menuItemId`; identical
  group/option selections deduplicate, malformed duplicate quantities and aggregate overflow
  are rejected, and different item variants are rejected;
- browser estimate includes selected modifier deltas but request contains no total/price;
- delivery requires address and disabled order types fail validation;
- text/phone normalization removes unsafe boundary characters;
- identical retry uses the same key and double-submit cannot race;
- replay response, backend total and backend response currency are visible;
- confirmed persistence is already empty while the immutable receipt is visible;
- all cart mutations are disabled during the submitted revision snapshot;
- `MENU_CHANGED`/unavailable errors require catalog refresh;
- same-origin route rejects invalid keys and does not forward cookies;
- declared and streaming request bodies cannot exceed 64 KiB;
- modal focus is trapped/restored (also while fieldset controls are disabled during submit),
  Escape closes it and invalid inputs reference live errors;
- populated cart/checkout has no detectable axe violations.

## Acceptance commands

```text
pnpm --filter @e-menu/api-contract validate
pnpm --filter @e-menu/api-contract check:generated
pnpm --filter web lint
pnpm --filter web typecheck
pnpm --filter web test
pnpm --filter web build
pnpm --filter web test:integration
git diff --check
```

## Failure and rollback behavior

- Failed hydration never blocks the public menu; the cart starts empty.
- Missing/unpublished menu disables ordering instead of creating fallback content.
- Network/internal failures keep the draft and idempotency key for retry.
- Notification failure is outside this browser transaction and cannot remove an accepted
  order (`SC-OUT-001`).
- The transport returns a controlled 503 envelope when the Spring API is unreachable.
- Removing the cart widgets restores the previous read-only menu without schema/database
  rollback; persisted v1 data is isolated behind its versioned key.

## Handoff report

```text
Task ID: SC-CART-001
Changed files: apps/web cart/checkout/transport/tests/styles, package dependency files,
  this task file and registry status
Decisions made within allowed scope: in-memory idempotency draft; transport-only bounded
  route; one backend-compatible item line; immutable submit revision; immediate persisted
  success clear with in-memory receipt; invalid required modifier line removal
Commands run and results: recorded by orchestrator handoff
Unresolved issues: production dataset selection and full-system/cutover validation remain
Suggested next dependency unlocks: SC-VAL-001 after all SC tasks are review-ready
```
