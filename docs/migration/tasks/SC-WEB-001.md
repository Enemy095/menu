# SC-WEB-001 — single-client SSR public menu

Status: `done`
Wave: P4
Dependencies: `SC-API-001`; production content cutover remains blocked by `SC-DATA-001`

## Objective

Build the first production-shaped public web slice with Next.js App Router and strict
TypeScript. The `/` route renders configuration and the current menu from the canonical
single-client API on the server, applies typed branding, emits configuration-backed SEO
metadata, and exposes only category filtering as client-side JavaScript.

## Non-goals

- cart, checkout, order submission or persisted browser state;
- admin UI, content editing or asset upload;
- selecting or embedding a Stambul/Adamas dataset;
- tenant/slug/host-based business-data resolution;
- Redis, a Next.js business backend, or direct database access;
- production content import or cutover.

## Immutable decisions

- Canonical reads are `GET /api/v1/public/config` and `GET /api/v1/public/menu`.
- The generated TypeScript client performs transport/model conversion; a strict Zod
  boundary validates successful external JSON before conversion.
- `MENU_API_BASE_URL` is server-only and mandatory in production. No business config or
  secret enters a `NEXT_PUBLIC_*` variable.
- Server Components are the default. Only category controls have `"use client"`; menu
  cards and modifier data never cross the client-prop boundary.
- The current public-web contract intentionally supports `ru-RU` only. Adding another
  locale requires translated shared UI strings and an explicit contract change.
- A missing menu is an explicit empty state. Missing configuration is a route 404 and
  dependency unavailability is controlled without fabricated business defaults.
- Runtime UI contains no restaurant-specific dataset, slug branch or compatibility tenant.

## Exclusive write ownership

```text
apps/web/**
package.json
pnpm-workspace.yaml
pnpm-lock.yaml
packages/api-contract/package.json
packages/api-contract/pnpm-workspace.yaml  # removal only
packages/api-contract/pnpm-lock.yaml       # removal only
.env.example                               # web server URL only
docs/migration/tasks/SC-WEB-001.md
docs/migration/tasks/README.md              # own status row only
```

Legacy and backend source are read-only for this task.

## Architecture and interfaces

```text
app/[[...route]]/page.tsx (on-demand ISR Server Component for `/`)
→ shared/api/public-api.ts (server-only generated client)
→ strict Zod response validation
→ /api/v1/public/config + /api/v1/public/menu
→ app/_components/PublicMenuPage (composition above FSD layers)
→ server widgets: header, hero, contacts
→ widgets/menu-browser (server-rendered cards)
→ features/category-filter (minimal category IDs/names/counts only)
```

`src/proxy.ts` performs the dependency preflight for `/` with fresh `cache: no-store`
requests to both canonical read endpoints. This readiness path is deliberately separate
from cached ISR/content fetches and cancels response bodies after inspecting status, so a
warm cache cannot hide a current outage. A dependency 5xx/transport failure is answered
directly as accessible static HTML with HTTP `503`,
`Cache-Control: no-store` and `X-Robots-Tag: noindex, nofollow`. App Router Server
Components cannot select a 503 status after rendering starts, and a rewrite status is
replaced by Next with 200; therefore the proxy owns the complete outage response. A
post-preflight race throws and becomes an error response, never successful fallback HTML.

The frontend consumes generated OpenAPI model types. API JSON is never cast directly to
domain/component props. The page verifies that menu and configuration restaurant/currency
identifiers agree before rendering.

Branding becomes CSS custom properties at the page boundary. Contact colors use opaque
semantic tokens whose actual surface-on-secondary composition is contrast-tested. Asset,
canonical and social URLs are credential-free HTTP(S) values and are never sourced from a
local restaurant branch.

## Behavior and failure policy

| Condition | Web behavior |
|---|---|
| valid config and menu | branded public page |
| menu `404` | config/contacts remain visible; explicit unpublished-menu state |
| config `404` | Next route `404`; no default restaurant |
| config/menu `5xx` or transport outage | proxy-owned HTML `503` with `noindex` |
| malformed success JSON | controlled render error; no partial unvalidated data |
| restaurant/currency mismatch | consistency error; no mixed snapshot render |
| metadata fetch unavailable | generic `noindex` metadata only |

Fetches use Next server cache metadata and resource-specific tags. The optional catch-all
returns no build-time params, so `/` is generated and cached on its first real request and
revalidated every 60 seconds without live content during `next build`. Invalidation
endpoints remain outside this slice; tags are the stable publishing interface.

## Implementation sequence

1. Establish the root pnpm workspace and one repository lockfile.
2. Scaffold Next.js App Router, strict TypeScript, ESLint and Vitest.
3. Expose generated client source as a workspace subpath without editing generated files.
4. Implement server-only API origin handling, generated client calls and strict Zod schemas.
5. Build typed Russian localization, contrast-checked theme utilities and server widgets.
6. Server-render menu cards and add the smallest client island for category filtering.
7. Add metadata/canonical/Open Graph, on-demand ISR, loading, not-found, proxy 503 and error states.
8. Cover contract, SSR composition, interaction, empty state and accessibility.
9. Run install, lint, typecheck, test and production build; record exact evidence.

## Required tests

- generated client uses exactly the canonical config/menu routes;
- successful JSON is validated and converted to generated `Set`/`Date` models;
- invalid response JSON is rejected before render;
- menu `404`, API outage `503`, and config/menu consistency mismatch;
- SSR page composition and configuration-backed metadata;
- category keyboard/button semantics and filtering;
- empty menu does not fabricate dishes;
- initial page has no detectable axe violations and theme pairs pass WCAG 4.5:1;
- unsafe font CSS, duplicate order types and unsupported locales are rejected;
- URL credentials are rejected for canonical, asset and social URLs;
- computed contact text/background pairs meet WCAG 4.5:1;
- the built Next server proves healthy ISR reuse, then returns fresh HTTP 503/noindex/no-store
  after the upstream fails with caches already warm;
- production build does not require live API content at build time.

## Acceptance commands

```text
pnpm install
pnpm --filter @e-menu/api-contract typecheck
pnpm --filter web lint
pnpm --filter web typecheck
pnpm --filter web test
pnpm --filter web build
pnpm --filter web test:integration
git diff --check
```

## Failure and edge cases

- A production deployment without `MENU_API_BASE_URL` fails closed.
- Arbitrary external payload fields fail the strict schema.
- No API response body, environment value or contract parse details are shown publicly.
- Empty/missing optional hero imagery, contacts and social links do not leave broken UI.
- The public web contract is consistently restricted to `ru-RU`; configured currency drives
  formatting and persisted/client-provided prices do not.
- SC-DATA-001 may remain blocked: test fixtures are isolated from the production bundle and
  do not decide which legacy dataset is imported.

## Handoff report

```text
SC-WEB-001 handoff
Changed files: <exact grouped list>
Workspace/lockfile: <single-root status>
SSR/API boundary: <routes, validation, cache/error behavior>
Rendering/client JS: <server components and client islands>
Tests run: <command + exit code>
Content dependency: <SC-DATA-001 status; no runtime dataset>
Unresolved issues: <NONE or exact blocker>
Suggested next unlock: SC-CART-001 after review; production content still needs SC-DATA-001
Git status: <short output; no commit>
```
