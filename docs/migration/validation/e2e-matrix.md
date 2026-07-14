# SC-VAL-001 validation matrix

## Status vocabulary

- `automated` — enforced by the named repository command.
- `manual` — repeatable procedure needs a human/environment assertion and evidence.
- `blocked` — a named dependency is absent; it is not a failure and cannot be marked pass.
- `not-run` — executable but no evidence exists for the assessed revision.
- `approved-exception` — a governed exception for an explicitly waivable row; it is never
  equivalent to `pass`.

The `CFG-*`, `MENU-*`, `WEB-*`, `CART-*`, `CHECKOUT-*`, `ORD-*`, `OUT-*`, `SEC-*` and
`RES-*` groups are non-waivable data, functional-integrity, privacy, security and
resilience gates. Every one must be `pass`; `blocked`, `not-run`, `fail` and
`approved-exception` all prohibit cutover.

An `A11Y-*`, `VIS-*`, `PERF-*` or `OBS-*` exception is allowed only where the row's core
safety invariant is still demonstrated and a named accountable owner records the exact
failed target, evidence, risk, compensating control, scope, approval and expiry before the
next release. Missing data, missing security evidence, unknown integrity, an unexecuted
test, or absent rollback/monitoring cannot be waived. An exception never changes the
underlying result to pass and must appear in the final disposition. CI success alone is
not production acceptance.

## Functional and data integrity

| ID | Scenario and invariant | Method/evidence | Current automation | Release blocker |
|---|---|---|---|---|
| CFG-01 | Empty database has the exact reviewed public schema, every discovered application table is empty, config returns `503 SITE_NOT_READY`, `no-store`, trace ID and readiness remains UP | `pnpm validate:smoke` | automated | yes |
| CFG-02 | Approved site config, contacts, locale, currency, timezone and feature flags match mapping | independent normalized oracle + `pnpm validate:acceptance` | automated | yes |
| CFG-03 | Missing/inactive configuration never falls back to fabricated content | backend tests + smoke | automated | yes |
| MENU-01 | Empty/unpublished menu is explicit and never fabricates dishes | backend/web tests + smoke | automated | yes |
| MENU-02 | Published categories/items/modifiers/order types match approved dataset | independent normalized oracle, import report, API/SSR assertions via `pnpm validate:acceptance` | automated | yes |
| MENU-03 | Config/menu restaurant, currency and version consistency fails closed | web/backend tests | automated | yes |
| WEB-01 | SSR uses canonical config/menu, metadata and branding without browser API duplication | `pnpm --filter web check` | automated | yes |
| WEB-02 | Warm ISR never masks current upstream outage; route returns 503/no-store/noindex | `pnpm --filter web test:integration` | automated | yes |
| CART-01 | Cart add/remove/quantity totals use current menu identifiers, not persisted price/name | Vitest + Playwright | blocked by SC-CART-001 | yes |
| CART-02 | Reload restores valid cart; corrupt/unknown schema clears safely; PII absent | storage unit/E2E evidence | blocked by SC-CART-001 | yes |
| CART-03 | Menu-version change removes/flags unavailable items and revalidates quantities | Playwright with controlled API | blocked by SC-CART-001 | yes |
| CHECKOUT-01 | Every enabled pickup/delivery/preorder flow submits canonical generated payload | Playwright + captured sanitized request | blocked by SC-CART-001 and SC-DATA-001 | yes |
| CHECKOUT-02 | Disabled order type cannot be selected or submitted | browser + backend acceptance | blocked by SC-CART-001 and SC-DATA-001 | yes |
| ORD-01 | Server calculates totals/snapshots; client total is not authoritative | Gradle order PostgreSQL tests | automated | yes |
| ORD-02 | Same idempotency key/payload replays original; changed payload conflicts | Gradle idempotency tests | automated | yes |
| ORD-03 | Concurrent duplicate submit creates one order/outbox event | Gradle concurrency tests; Playwright after cart | backend automated, browser blocked | yes |
| ORD-04 | Validation/rate-limit/transaction failure creates no partial order/outbox rows | Gradle security/order tests | automated | yes |
| OUT-01 | Order and `order.created` outbox event commit atomically | Gradle PostgreSQL tests | automated | yes |
| OUT-02 | Claim/retry/backoff/lease reclaim/attempt fence/dead-letter are bounded | Gradle outbox tests | automated | yes |
| OUT-03 | Provider failure never rolls back order; no real provider is used in validation | isolated acceptance provider, DB delivery/outbox assertions via `pnpm validate:acceptance`; staging no-send evidence | automated/manual | yes |

## Security, privacy and resilience

| ID | Scenario and invariant | Method/evidence | Current automation | Release blocker |
|---|---|---|---|---|
| SEC-01 | Public/admin authorities, method rules and CORS are separated | Gradle security integration tests | automated | yes |
| SEC-02 | Untrusted Host/forwarded headers do not select data or bypass admin boundary | security tests + runtime scope guard | automated | yes |
| SEC-03 | Order creation rate limits are bounded and return contract errors | Gradle rate-limit tests | automated | yes |
| SEC-04 | Dependency/secret/config scans contain no unresolved high/critical issue | `.github/workflows/security.yml` artifacts | automated in CI | yes |
| SEC-05 | Logs/traces/metrics contain no phone, address, comment, raw payload, secret or provider ID | test assertions + staging log query | automated/manual | yes |
| RES-01 | DB/API outage produces controlled response and recovery does not corrupt state | web integration, smoke, PostgreSQL tests | automated | yes |
| RES-02 | Restart with pending/processing outbox safely resumes/reclaims work | Gradle recovery tests; staging restart drill | automated/manual | yes |

## Accessibility and visual behavior

| ID | Scenario and invariant | Method/evidence | Current automation | Release blocker |
|---|---|---|---|---|
| A11Y-01 | Initial SSR page has no axe violations and configured contrast meets WCAG AA | Vitest/axe/theme tests | automated | yes |
| A11Y-02 | Full menu, cart, modal and checkout are keyboard-only usable with visible focus | Playwright/manual recording | blocked by SC-CART-001 | yes |
| A11Y-03 | Labels, errors, live updates and validation are announced without focus loss | Playwright accessibility assertions | blocked by SC-CART-001 | yes |
| VIS-01 | 320/375/768/1024/1440 px layouts have no clipped content or horizontal overflow | Playwright screenshots | blocked by dataset/cart | yes |
| VIS-02 | Approved branding/assets match source and visual baseline | approved screenshots/hash list | blocked by SC-DATA-001 | yes |

## Performance and observability

Targets are provisional until the staging topology and approved dataset size are recorded.
No percentile claim is valid without request count, concurrency, warm/cold state, region,
machine/container limits and error rate.

| ID | Scenario and target | Method/evidence | Current automation | Release blocker |
|---|---|---|---|---|
| PERF-01 | Public config/menu API: p95 <= 250 ms, p99 <= 500 ms, errors < 0.1% under agreed read profile | k6/Gatling report with DB pool metrics | blocked by staging dataset/profile approval | yes |
| PERF-02 | Order API: p95 <= 500 ms, p99 <= 1000 ms, no duplicate/partial writes under agreed write profile | no-send load run + DB assertions | blocked by dataset/profile approval | yes |
| PERF-03 | SSR warm p95 <= 500 ms; cold p95 <= 1500 ms; asset URLs CDN/cacheable | browser/server timing artifact | blocked by staging topology | yes |
| PERF-04 | Worker drains agreed burst within SLO without exceeding configured concurrency; backlog returns to zero | fake/no-send worker metrics | blocked by staging profile | yes |
| OBS-01 | Correlation/trace ID connects web/API/error/worker evidence without PII | trace screenshot/query + contract response | partial automated; staging manual | yes |
| OBS-02 | Liveness excludes dependencies; readiness includes DB and changes correctly | Gradle health tests + smoke | automated | yes |
| OBS-03 | HTTP/JVM/DB pool/order/outbox/notification metrics exist with bounded labels | tests + staging scrape | partial automated; staging manual | yes |
| OBS-04 | Alert evidence covers 5xx/error rate, readiness, DB saturation, outbox backlog/age and failed delivery | alert rules/screenshots/test notification | blocked by staging monitoring | yes |

## Required execution sets

### Neutral pre-merge

```text
pnpm validate:full
docker compose config --quiet
docker compose --profile assets --profile observability config --quiet
```

### Dataset/cart acceptance

Run `pnpm validate:acceptance` locally for the approved Stambul import, exact public
content, SSR, idempotent order and deterministic no-network worker path. Run the remaining
cart/browser scenarios after `SC-CART-001` on an isolated staging database with provider
delivery disabled or a deterministic fake. Record approved dataset manifest hash, browser
version, viewport matrix, generated request IDs only where safe, final order/outbox counts
and cleanup outcome. Dataset, cart, checkout, order and outbox rows are non-waivable.

### Load/resilience acceptance

Use a separately approved load profile and disposable/staging environment. Never point the
profile at production or a real notification destination. Record pre/post DB counts,
outbox terminal states, worker backlog, error rate and percentiles in the evidence bundle.
