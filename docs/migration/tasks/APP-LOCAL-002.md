# APP-LOCAL-002: approved photography, branding and owner calendar

Status: implemented; targeted acceptance passed. Commit/push authorized by the user.

## Objective and non-goals

Publish all 23 approved restaurant-style dish photographs, rename the site to
“Вам пицца”, remove the delivery promotional hero and show “Заказ в разработке”
after checkout. Let authorized Telegram owners publish date-specific and weekly
closures. Preserve existing local data while distributing the release through Git.
Checkout delivery methods, payment flow and order lifecycle are outside this change.

## Dependencies and immutable decisions

Single-client modular monolith; PostgreSQL is authoritative. V1–V9 migrations and
the published v1 bootstrap remain immutable. V10 adds schema only; public content
updates live in the versioned v2 bootstrap. No raw legacy sources are restored.
OpenAPI defines the additive read-only availability endpoint; generated code is
regenerated. Bot credentials and numeric owner IDs remain in ignored local config.

## File ownership, deliverables and interfaces

- Restaurant owns calendar tables and use cases; site exposes
  `GET /api/v1/public/availability` with business date/timezone and no-store caching.
- Notification owns private-chat authorization, bot keyboards, polling and durable
  update cursor. Explicit desired-state callbacks make repeated delivery safe.
- Web owns the 23 immutable WebP assets, item mapping, branding presentation,
  checkout text and periodically refreshed availability notice.
- Local bootstrap owns the guarded, once-only `vam-pizza-v2` branding release.

## Implementation sequence

Approved photos → versioned assets → OpenAPI/client → V10/calendar use cases →
owner bot → public notice → v2 upgrade → production Docker/browser verification.

## Required tests and acceptance commands

- `pnpm --filter web lint`, `typecheck`, `test`: frontend checks and calendar tests.
- API-contract lint, semantic tests and deterministic generated-client check.
- Backend calendar PostgreSQL, Telegram authorization/keyboard, security, module,
  Flyway clean/upgrade and JPA tests.
- `start-docker.cmd`: actual production images and healthy services.
- `scripts/test-local-bootstrap.ps1`: isolated fresh content, 40 frontend assets,
  integrity failures and replay preserving prices and calendar choices.
- Browser: 23 distinct loaded photos, desktop/mobile fit, absent promo hero, calendar
  notice through mocked browser response without changing real business dates.
- `git diff --check`.

## Results and unresolved issues

Production Docker startup, browser checks and isolated bootstrap acceptance passed.
Frontend suite: 67 tests passed. Contract semantic suite: 20 tests passed. Selected
backend tests for this release passed. Full backend run: 237 tests, seven failures,
one skipped. All seven failures are existing Stambul bundle/import tests referring
to deleted raw legacy inputs (two loader tests and five PostgreSQL import tests).
Those retired test fixtures/workflows need separate cleanup; this release does not
restore the forbidden directory or claim a fully green backend suite.

## Failure and edge cases

Unknown owners/group callbacks cannot mutate dates. Past/out-of-range dates are
rejected; duplicate set commands remain idempotent. A durable cursor and advisory
lock coordinate pollers sharing a database. Independent deployments must not poll
the same bot token. Public calendar failures retain the last confirmed browser state.
The calendar announces closures without changing order acceptance rules. Existing
prices, orders and calendar entries survive bootstrap replay; v2 branding applies
once, then later local edits survive. A fork must sync upstream before pulling.

## Handoff

Report the commit, frontend/contract/targeted backend and bootstrap results, full
suite limitation, owner `/start` controls, and Git pull plus launcher update steps.
