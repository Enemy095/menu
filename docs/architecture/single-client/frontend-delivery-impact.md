# Single-client frontend and delivery impact

Status: audit and implementation guidance
Date: 2026-07-14
Decision baseline: ADR-0003 supersedes the previous multi-tenant frontend, import and deployment assumptions.

## 1. Outcome

The frontend does not yet exist as a Next.js application, so the single-client pivot can be applied before creating `apps/web`. This is materially cheaper than adapting a completed tenant-aware frontend.

The target is one deployment with one canonical site, one restaurant configuration, one published menu and one order pipeline:

```text
canonical domain
  → CDN / ingress
  → Next.js route `/`
  → fixed public API
      GET  /api/v1/public/config
      GET  /api/v1/public/menu
      POST /api/v1/public/orders
  → Spring modular monolith
  → PostgreSQL + object storage
  → transactional outbox worker
  → configured notification destination
```

Stambul and Adamas remain legacy evidence, but only one dataset may become production content. The other dataset is a visual/behavioral reference, not a runtime client, route, fallback or second row selected by host.

The production runtime must not contain:

- `tenantId`, `TenantContext`, tenant resolver or domain registry;
- `/{tenantSlug}` or `#[/{tenantSlug}]` client routing;
- a Stambul/Adamas registry loaded into the browser;
- a query parameter, hostname, environment variable or public build flag that switches business data at request time;
- `if (client === "stambul")` / `if (client === "adamas")` rendering branches;
- tenant-scoped cart, cache, SEO, asset or rate-limit keys.

## 2. Repository reality

### 2.1 Frontend and packages

| Area | Current state | Delivery implication |
|---|---|---|
| `apps/web` | Does not exist. There is no Next.js application, App Router, React code, frontend tests or production frontend build. | Build the single-client route structure directly; do not scaffold the superseded tenant route first. |
| Root pnpm workspace | Does not exist. The only `pnpm-workspace.yaml` and lockfile are inside `packages/api-contract`. | Create the root workspace together with `apps/web`; migrate the contract package into that workspace without generating a second lockfile. |
| `packages/api-contract` | Exists and has OpenAPI, generation scripts, generated `typescript-fetch` client and contract tests. | Reuse the package/tooling, but update OpenAPI before the web app consumes it. Generated code is currently tenant-route-specific. |
| `packages/shared-config` | Does not exist. | Create only when shared ESLint/TypeScript/Prettier configuration has two real consumers; it is not needed merely to match an old directory diagram. |
| Public OpenAPI | Uses `/api/v1/public/{tenantSlug}/restaurant|menu|orders`, tenant descriptions, tenant errors/cache tags and Stambul/Adamas examples. | This is a breaking contract pivot. Change it first to fixed single-client routes and regenerate the TypeScript client before real frontend integration. |

The current generated client is usable as generation evidence, not as the final single-client client. In particular, `RestaurantApi`, `MenuApi` and `OrdersApi` currently require a tenant slug because the source OpenAPI requires it.

### 2.2 Legacy frontend

`Электронное меню/` is a complete static HTML/CSS/vanilla-JavaScript application. It currently loads both restaurant datasets into one page:

- `restaurants/registry.js` registers `stambul` and `adamas` and sets Adamas as the default;
- hash/history/memory routing resolves a restaurant slug;
- provider registry switches between local JavaScript and API providers and allows a query-string provider override;
- bootstrap merges restaurant config/features and mutates global engine state;
- switching restaurant clears UI state, changes theme/branding and reloads a slug-scoped cart;
- both restaurant scripts and assets are shipped to every browser;
- root `config.js` and `menu.js` are an older Stambul-oriented compatibility layer loaded alongside the newer engine.

This is useful parity evidence, but it is not a foundation to port mechanically. It has duplicated global configuration/state, direct DOM mutation, synthetic clicks, `innerHTML`, runtime registries and test behavior enabled by URL query parameters.

The checked-in preflight currently verifies two legacy datasets, eight categories and 42 items: Stambul 23 and Adamas 19. Those totals must not become an expectation that production serves both datasets after the pivot.

### 2.3 Deployment and infrastructure

`compose.yaml` currently provides PostgreSQL, optional MinIO and optional observability services. It does not define a Next.js service, frontend image, reverse proxy, CDN or production deployment manifest.

The target deployment therefore needs an explicit frontend delivery layer, but only one canonical host and one content source. Object storage remains useful for immutable assets, and the outbox worker remains useful for durable order notifications. Neither needs a tenant namespace.

### 2.4 Ordering implementation status

The repository contains ordering persistence mappings and module boundaries, but not the complete transactional public order use case. Notification provisioning foundations exist, while the outbox delivery worker remains planned. The frontend checkout must therefore be built contract-first and must not claim live order submission until the fixed single-client order endpoint, idempotency transaction and worker are verified.

## 3. Legacy capabilities to preserve

Preserve user-visible behavior and safety intent, not the current global APIs or restaurant-switching implementation.

### 3.1 Site presentation

Common capabilities worth preserving:

- display name, business type, cuisine, slogan and intro copy;
- logo, favicon, hero image/copy/CTA and optional benefit list;
- schedule, public phone, address, map link and safe social links;
- design tokens for colors, surfaces, borders and radii;
- responsive mobile and desktop layouts;
- safe image fallback and explicit image dimensions/loading behavior;
- document title, description, canonical URL, favicon and Open Graph metadata.

Adamas has an implemented delivery presentation: large hero, popular items, category cards and delivery benefit blocks. This should be preserved only as generic config-driven widgets if the selected client needs it. It must not be named or mounted by an Adamas slug branch.

Stambul has the original restaurant presentation and the repository identity/README points to the restaurant "у Ризвана". This is evidence that Stambul may be the intended production baseline, but it is not sufficient product approval.

### 3.2 Menu browsing

Preserve:

- ordered categories and items;
- the synthetic "Все" view and visible-item count;
- category filtering;
- item name, description, image, availability and price;
- quantity controls with per-item and total-item limits;
- localized minor-unit money formatting;
- keyboard-accessible buttons and visible selected state.

Do not preserve prices as JavaScript floating-point/business-unit values. The new API already models `priceMinor`; the frontend must format it with the configured currency/locale.

### 3.3 Cart

Preserve:

- add, decrement, remove and clear;
- mobile bottom cart bar and drawer behavior;
- desktop persistent cart panel behavior;
- empty state, line formula, count and total;
- maximum 99 per item and 200 total unless the selected configuration changes these values;
- safe recovery from corrupt local storage;
- reconciliation against the current menu;
- no persistence of customer name, phone, address or comment.

Remove the slug prefix and tenant switch behavior. A new storage namespace prevents accidental interpretation of old Stambul/Adamas keys.

Recommended persisted shape:

```ts
type PersistedCart = {
  schemaVersion: 1;
  restaurantId: string;
  menuVersion: number;
  orderType: "preorder" | "delivery" | "pickup";
  items: Array<{ menuItemId: string; quantity: number }>;
};
```

Recommended storage key: `e-menu:cart:v1`. `restaurantId` is a stable resource identifier, not a tenant selector. If the product confirms that even restaurant identity can never change inside one deployment, it can be removed in a later schema version; retaining it initially makes stale-content reconciliation explicit without reintroducing multi-tenancy.

### 3.4 Checkout and submission

Preserve:

- selectable configured order types: preorder, delivery and pickup;
- name, phone, optional guest count, conditional delivery address and comment;
- normalization that removes control, bidi and zero-width characters;
- E.164-oriented phone normalization;
- configured text and quantity limits;
- address requirement only for delivery;
- one in-flight submission per UI instance;
- one idempotency key per semantic draft, reused on retry;
- menu version and client total in the request for conflict detection;
- explicit success and recoverable error feedback.

The legacy WhatsApp redirect is a fallback behavior, not the target order transaction. The target remains backend order creation followed by outbox delivery. A private manager phone or destination must never be included in frontend config or bundle.

### 3.5 Backend order and notification guarantees

Preserve these architectural guarantees after removing tenant scope:

- backend recalculates prices and total from the current published menu;
- item snapshots are saved with the order;
- `Idempotency-Key` is mandatory and deployment-global;
- identical replay returns the original result;
- the same key with a different canonical payload returns conflict;
- order, order items, status history and outbox event commit atomically;
- notification is delivered after commit with bounded retries;
- notification failure does not invalidate the order;
- destinations and secrets are resolved server-side and redacted from logs.

The outbox event no longer carries `tenantId`. It should carry the order identifier, event schema version and trace context; the sole deployment destination is resolved by notification configuration.

## 4. Legacy behavior not to carry forward

Remove rather than translate:

- `RestaurantRegistry`, `Router`, `HashStrategy`, `HistoryStrategy` and `MemoryStrategy`;
- runtime `loadRestaurant(newSlug)` and tenant-switch cleanup;
- `ProviderRegistry` mode selection and `?provider=json|api` override;
- shipping both client configs, menus and assets in the browser;
- slug-prefixed local-storage keys and tenant-switch cart tests;
- `window.APP_CONFIG`, `window.BRAND_CONFIG`, `window.FEATURE_CONFIG`, `window.EngineState` and duplicate cart state;
- direct `querySelector` rendering, `innerHTML`, synthetic button clicks and DOM-owned business state;
- `?test=1` self-tests in production JavaScript;
- client-side manager phone and WhatsApp destination;
- stub features with no visible current behavior: promotions, loyalty and reservations;
- comments/contracts that refer to a Go backend or tenant tables;
- default/fallback restaurant selection for an unknown host/path.

Delivery widgets are not removed merely because they originated in Adamas. They are retained only when they represent approved selected-client UX and are expressed as generic components/data.

## 5. Selecting one client dataset

### 5.1 Blocking product decision

Before production content import, explicitly answer:

> Which single dataset is the production client baseline: Stambul / "у Ризвана" (23 items) or Adamas (19 items)?

Evidence favors Stambul / "у Ризвана" as the likely repository owner because the legacy README, root compatibility config and product naming refer to it. Adamas appears to be the delivery-oriented alternate design. This is an inference, not authorization. No agent should choose based only on the default slug in `RestaurantRegistry` because that default is currently Adamas and may reflect development rather than product intent.

Also confirm:

- canonical public name and domain;
- exact 23-or-19 item menu, categories, prices and availability;
- visual direction: restaurant/Stambul, delivery/Adamas or an explicitly approved combination;
- public phone, address, schedule, timezone and location;
- enabled order types and default type;
- backend-only notification destination reference;
- whether WhatsApp fallback remains visible at all.

### 5.2 Recommended selection mechanism

Use a release-owned, versioned single-client content bundle and a controlled import/provisioning command:

```text
approved legacy dataset
  → sanitized single-client content bundle
  → schema/hash validation
  → controlled import into the deployment database
  → exactly one active site config + one restaurant + one published menu
  → fixed runtime reads
```

The bundle contains only the selected client's public/configurable content. It may retain a source/audit label such as `stambul-legacy-v1`, but that label is migration metadata, not a runtime routing key.

The selection must happen before runtime:

- not in React conditionals;
- not by request host/path/query;
- not through a public `NEXT_PUBLIC_CLIENT_SLUG`;
- not by loading both bundles and choosing one;
- not as a hidden singleton tenant row;
- not as Flyway business fixtures.

Recommended runtime source of truth is PostgreSQL through the fixed Spring APIs. Deployment environment variables should configure technical concerns only: API origin, canonical public URL, database/object-storage connections, observability and secret references. They should not select Stambul versus Adamas on each application start.

For early frontend development, one generated contract fixture may be selected by the test/dev harness. Production builds must fail if fixture mode is enabled. The fixture should be generated from the approved single-client bundle and contain no second-client data.

## 6. Assumptions to remove

| Previous assumption | Single-client replacement |
|---|---|
| App route `/[tenantSlug]` | Public page is `/`; supporting routes are technical pages such as `/not-found` only when needed. |
| Host/path resolves tenant | Host is checked against the deployment allowlist and used for canonical URL/security, never for data selection. |
| Custom domains map to tenants | One deployment has one canonical domain plus explicit redirect aliases managed by ingress/DNS, not database business mappings. |
| API route includes `{tenantSlug}` | Fixed `/api/v1/public/config`, `/menu`, `/orders`. |
| `slug` is required in public restaurant DTO | Remove it unless it has an independent public SEO purpose. It must not control authorization or lookup. |
| Unknown tenant returns `TENANT_NOT_FOUND` | Missing deployment configuration/menu returns resource/configuration errors; unknown host is rejected by ingress/host validation. |
| Cart key includes tenant/restaurant | One versioned app key; optionally retain stable `restaurantId` only for content reconciliation. |
| Switching tenant clears/restores another cart | There is no switch. Old tenant keys are ignored/removed during rollout. |
| SSR metadata/canonical are tenant-specific | One config-driven metadata set and one canonical base URL. |
| RSC/client bundle must isolate tenants | It must contain only public data required for the sole client and no secrets; cross-tenant tests disappear. |
| Cache key/tag includes tenant and host | Use resource, locale and config/menu version: for example `config:{version}` and `menu:{locale}:{version}`. |
| Invalidation targets one tenant | Invalidate the sole config/menu tags when their versions publish. |
| Assets use tenant UUID namespace | Use immutable `assets/{assetKind}/{sha256}.{ext}` private/public paths as ADR-0003 defines. |
| Rate limiting has tenant buckets | Use endpoint plus trusted client/network keys and global deployment budgets. |
| Logs/traces include tenant context | Use trace/correlation context; do not add fake client/tenant labels. |
| Outbox/jobs include tenant ID | Event carries order/event identity and resolves the deployment's configured destination. |
| Platform-admin and tenant-admin | Public and client-admin boundaries only. |

## 7. Target Next.js structure

Create only after the OpenAPI route pivot is accepted:

```text
apps/web/
  src/
    app/
      layout.tsx
      page.tsx
      loading.tsx
      error.tsx
      not-found.tsx
    widgets/
      site-header/
      hero/
      contacts/
      menu-browser/
      cart-panel/
      checkout/
    features/
      filter-menu/
      change-cart-quantity/
      select-order-type/
      submit-order/
    entities/
      site-config/
      restaurant/
      menu/
      cart/
      order/
    shared/
      api/
      config/
      format/
      validation/
      ui/
```

Rules:

- `page.tsx` is a Server Component that requests the only config/menu resources;
- the generated client is wrapped in a `server-only` gateway for SSR reads;
- cart, filters, form interactions and browser storage are Client Components;
- menu/config server state is not copied into Zustand;
- Zustand owns cart workflow state only;
- React Hook Form owns checkout fields;
- Zod validates storage and form/external boundaries;
- branding uses typed design tokens/CSS variables, not alternate tenant stylesheets;
- config-driven widget variants use explicit component data such as `hero`, `benefits` or `popularItems`, never client identity;
- no browser-accessible registry contains alternative clients.

## 8. Delivery sequence

### Phase 0 — single-client contract and content decision

Prerequisites before `apps/web` real API integration:

1. Confirm the selected Stambul or Adamas dataset and product fields listed above.
2. Replace tenant OpenAPI paths with fixed config/menu/orders paths.
3. Remove tenant-only parameters, descriptions, error codes, examples and cache headers.
4. Decide whether public `slug` remains meaningful; default is to remove it.
5. Regenerate the TypeScript client and make generation checks green.
6. Produce one sanitized versioned content bundle and one test fixture.

Gate: generated client has no tenant argument and the fixture contains exactly one approved dataset.

### Phase 1 — Next.js shell

1. Create root pnpm workspace and single lockfile.
2. Scaffold `apps/web` with Next.js App Router, strict TypeScript, lint/format, Vitest/Testing Library and Playwright.
3. Implement only route `/`, plus loading/error/not-found boundaries.
4. Add server-only config/menu gateway with timeouts, safe errors and correlation header propagation.
5. Build metadata, canonical URL, favicon and Open Graph from the sole config response.
6. Apply typed design tokens on the server-rendered shell.
7. Add security headers, CSP-compatible asset policy and production fixture-mode denial.

Gate: production build succeeds; `/` renders one fixture/client; no slug route, registry, second-client strings or secret reaches HTML/RSC/client bundle.

### Phase 2 — presentation and menu UI

1. Implement header, hero, schedule and contacts.
2. Implement ordered category navigation, "Все", visible count and menu cards.
3. Format minor-unit prices through locale/currency configuration.
4. Implement generic optional popular/category/benefit widgets only if selected-client acceptance requires them.
5. Add safe image fallback, keyboard behavior, reduced motion and responsive layouts.
6. Capture visual snapshots against the selected legacy baseline only.

Gate: exact selected item count/order and approved visuals pass; the unselected dataset is absent from runtime artifacts.

### Phase 3 — cart

1. Create the single Zustand cart store and selectors.
2. Add the versioned `e-menu:cart:v1` storage adapter.
3. Persist only item IDs, quantities, order type, menu version and optional restaurant ID.
4. Reconcile unavailable/removed items and stale menu versions.
5. Implement mobile bar/drawer and desktop panel with shared totals.
6. Implement focus trap/return, Escape/backdrop close and body-scroll behavior through React, not direct global mutation.

Gate: reload/corruption/reconciliation/99-per-item/200-total/accessibility tests pass; there is no tenant switch or second cart state.

### Phase 4 — checkout UI

1. Build RHF/Zod form and shared normalization helpers.
2. Drive available/default order types and limits from config.
3. Require delivery address conditionally.
4. Build the exact OpenAPI payload from current menu/cart data.
5. Create and persist a non-PII idempotency draft identity until semantic edit or success.
6. Map stable API error codes to recoverable UX.
7. Ensure double click creates one request and every failure releases submit lock.

This phase may use a fake generated-client adapter in tests, but production submission remains disabled until Phase 5 is green.

Gate: form/payload/idempotency/privacy tests pass; no private destination or customer PII is persisted/logged.

### Phase 5 — transactional order and outbox

1. Implement fixed `POST /api/v1/public/orders` from the revised OpenAPI.
2. Validate sole deployment config, restaurant, menu version, order type, limits and item availability.
3. Recalculate prices/total server-side.
4. Store order and item snapshots with global idempotency uniqueness.
5. Insert the outbox event in the same transaction.
6. Implement bounded worker polling/retries/dead-letter handling and deployment destination resolution.
7. Connect the frontend generated client only after contract/integration tests pass.

Gate: concurrent duplicate requests create one order/outbox event; conflicting key returns `409`; notification failure does not lose or roll back the order.

### Phase 6 — validation and cutover

1. Run selected-client legacy parity, visual and accessibility suites.
2. Test config/menu cache versioning and invalidation without tenant dimensions.
3. Test canonical/alias host policy, security headers and no host-based content switching.
4. Test storage corruption, stale menu, all enabled order types and stable error UX.
5. Test notification restart/retry and PII/log redaction.
6. Rehearse frontend/backend/database rollback.
7. Canary one deployment, not one tenant; legacy and new UI must not both submit the same real order.
8. Retire legacy only after observation and rollback windows.

Gate: one approved client passes parity and production smoke; no second dataset is imported or shipped.

## 9. Test impact

Remove or rewrite tests for:

- Stambul/Adamas route switching;
- custom-domain-to-tenant resolution;
- host/path tenant mismatch;
- one tenant's HTML/cache/cart/assets not leaking to another;
- tenant-scoped idempotency and rate limiting;
- two-tenant import counts as a production gate;
- per-tenant canary/cutover.

Keep or add tests for:

- fixed route `/` and fixed public APIs;
- rejection/redirect of non-allowlisted host without changing content;
- exactly one approved config/menu fixture in production artifacts;
- absence of manager destination/secrets/internal persistence IDs;
- selected menu count/order/prices and approved optional widgets;
- config/menu ETag and versioned cache behavior;
- cart corruption/reconciliation/limits;
- checkout normalization, accessibility and idempotency;
- server-side total, snapshots and concurrency;
- outbox recovery, retries and PII redaction;
- clean database and append-only V1–V5 upgrade to the new single-client schema.

Cross-tenant tests disappear because the abstraction disappears, not because isolation is assumed. Add architecture/search checks that reject reintroduction of `TenantContext`, `tenantId`, tenant slug routes and client identity branches in new runtime code.

## 10. Deployment configuration

One release deploys one immutable web artifact and one backend artifact. Runtime configuration contains:

- `PUBLIC_BASE_URL` — sole canonical external URL;
- server-only backend origin for Next.js SSR;
- database connection;
- private object-storage origin/bucket and CDN base URL;
- observability endpoints;
- worker role/concurrency;
- opaque notification secret reference;
- ingress host allowlist and redirect aliases.

It must not contain `TENANT_ID`, `TENANT_SLUG`, `DEFAULT_TENANT`, `CLIENT_SLUG` or a list of client domains. If a content bundle identifier is needed by the one-time importer, it belongs to the audited provisioning command/release input and is not passed to the long-running web/API process.

Deployment aliases may redirect to the canonical domain, but all accepted hosts serve the same data and canonical metadata. Host validation remains a security concern; it is no longer a business resolver.

## 11. Roadmap changes required elsewhere

The existing roadmap remains useful for sequencing but its tenant language is superseded:

- `WEB-001`: replace tenant-aware SSR and `/[tenantSlug]` with fixed `/` shell and canonical-host validation;
- `WEB-002`: preserve config-driven UI, but validate only the selected dataset; remove two-tenant bundle/isolation requirements;
- `CART-001`: remove `tenantId`, tenant-scoped key and tenant-switch scenarios;
- `CHECKOUT-001`: use deployment config/limits and fixed generated-client route;
- `ORD-001`: remove tenant resolution/scope and make idempotency deployment-global;
- `OUT-001`: remove tenant from event/destination lookup while retaining durable delivery;
- `VAL/CUTOVER`: replace two-tenant/cross-tenant/canary-tenant scenarios with one deployment, one canonical host and one selected-content gate;
- import tasks for two suspended tenants remain superseded; replace them with one sanitized content-selection/import task.

These task/doc updates should be performed by the orchestrator in their own ownership slices. This audit does not modify those files.

## 12. Open decisions and blockers

The following decisions block production content and exact visual acceptance:

1. Stambul / "у Ризвана" or Adamas is the sole production dataset.
2. Exact client name, public domain and canonical URL.
3. Visual baseline and whether generic delivery widgets are required.
4. Confirmed menu/prices/availability and source timestamp/version.
5. Public contacts, schedule, address, timezone and non-zero map location.
6. Enabled/default order types and delivery rules.
7. Notification provider/destination secret reference.
8. Whether WhatsApp remains a fallback or backend-only delivery fully replaces it.
9. Which logo/hero/menu images are approved and licensed.
10. Whether `restaurantId` remains public/persisted for reconciliation or the product is an absolute one-restaurant singleton.

Until item 1 is answered, implementation may scaffold the generic shell and component tests, but must not import both datasets, expose a runtime selector or declare either dataset production-ready.
