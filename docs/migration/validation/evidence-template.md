# SC-VAL-001 evidence template

Copy this template into a revision-specific evidence artifact. Do not overwrite prior
evidence. Store summaries and safe artifact links only; exclude secrets, PII, raw order or
provider payloads, destination locators and provider message identifiers.

## Assessment identity

| Field | Value |
|---|---|
| Assessed commit SHA | `<sha>` |
| Working tree | `clean` / `<exact dirty paths and owner>` |
| Started/finished UTC | `<timestamps>` |
| Operator/reviewer | `<names or team>` |
| OS/architecture | `<value>` |
| Node/pnpm | `<versions>` |
| Java/Gradle | `<versions>` |
| Docker/PostgreSQL | `<versions/image>` |
| Environment | `local disposable` / `preview` / `staging` |
| Deployment/build IDs | `<safe identifiers>` |
| Dataset manifest/hash | `NONE` / `<approved artifact>` |
| Notification mode | `worker disabled` / `deterministic fake` / `approved sandbox` |

## Automated gate summary

| Command | Exit | Duration | Safe artifact/log link | Notes |
|---|---:|---:|---|---|
| `pnpm validate:full` | `<code>` | `<duration>` | `<link>` | `<first failure or pass>` |
| `pnpm validate:acceptance` | `<code>` | `<duration>` | `<link>` | `<bundle/oracle hash, dry-run/apply, exact API/SSR, order/outbox result>` |
| `docker compose config --quiet` | `<code>` | `<duration>` | `<link>` | |
| all-profile Compose validation | `<code>` | `<duration>` | `<link>` | |
| security workflow | `<status>` | `<duration>` | `<link>` | `<findings>` |

## Scenario result register

Use IDs from `e2e-matrix.md`. Allowed result values are `pass`, `fail`, `blocked`,
`not-run` and `approved-exception`. `approved-exception` is forbidden for every config,
menu, web, cart, checkout, order, outbox, security, privacy and resilience row. It cannot
replace missing evidence or an unexecuted test.

| Matrix ID | Result | Exact evidence | Environment/data | Owner/action |
|---|---|---|---|---|
| `<ID>` | `<result>` | `<test/report/screenshot/query>` | `<context>` | `<next action>` |

## Disposable smoke assertions

| Assertion | Result/evidence |
|---|---|
| Unique tmpfs-only database container removed | `<result>` |
| Flyway current version | `9` / `<actual>` |
| Liveness/readiness | `<status>` |
| Empty config/menu fail-closed | `<HTTP/code/cache/trace result>` |
| Exact public schema set | `<18 expected application tables + flyway_schema_history; no missing/unexpected>` |
| Every discovered application table | `<table-by-table zero counts>` |
| Worker/provider mode | `disabled; zero external calls` / `<actual>` |
| Inherited Spring/JVM configuration | `<scrubbed variable names/count; CLI authority>` |
| Container isolation | `<image, label, loopback binding, data tmpfs, zero bind/volume mounts>` |
| Cleanup proof | `<unique run label/name, finite command timeouts, application exit, post-remove inspect absence>` |

## Dataset and browser evidence

- Approved mapping/import report: `<link or BLOCKED: SC-DATA-001>`
- Sanitized manifest hash and asset verification: `<value>`
- Config/menu/API snapshots: `<links>`
- Cart persistence/corruption/stale-menu evidence: `<links or BLOCKED: SC-CART-001>`
- Checkout order-type and double-submit evidence: `<links>`
- Responsive viewport and visual baseline evidence: `<links>`
- Keyboard/screen-reader/axe evidence: `<links>`

For local approved-dataset evidence, also record the pinned PostgreSQL/MinIO images,
loopback/tmpfs assertions, importer dry-run zero-write result, deterministic ID anchors,
production/acceptance JAR isolation result, synthetic destination insertion point,
notification audit sequence, final order/outbox counts and post-run owned-container count.
Never record disposable credentials, phone/address/comment payloads, destination locators
or provider identifiers.

## Performance and resilience

Record before results: load generator/version, exact route mix, request count, duration,
concurrency/arrival rate, cold/warm state, dataset sizes, app/DB/worker replicas and limits,
network region, worker/provider mode and acceptance targets.

| Profile | Throughput | p50 | p95 | p99 | Error rate | Integrity/backlog result |
|---|---:|---:|---:|---:|---:|---|
| `<read/write/SSR/worker>` | `<value>` | `<value>` | `<value>` | `<value>` | `<value>` | `<DB/outbox assertion>` |

Resilience drills and recovery times:

- database/API interruption: `<evidence>`
- application restart: `<evidence>`
- worker/provider transient/permanent failure: `<evidence>`
- no duplicate/partial business write assertion: `<evidence>`

## Security, privacy and observability

- dependency/secret/container/config scan: `<link and unresolved findings>`
- public/admin/host/CORS/rate-limit evidence: `<link>`
- PII/secret/provider-data log query: `<query description and zero-result evidence>`
- trace correlation sample without PII: `<link>`
- health/readiness and metric scrape: `<link>`
- metric-label cardinality review: `<result>`
- outbox backlog/oldest-age/failure dashboard and alert: `<link>`

## Deviations and decisions

| Item/matrix ID | Failed target and evidence | Risk/impact | Compensating control | Accountable owner | Approver | Expiry | Approval record |
|---|---|---|---|---|---|---|---|
| `<waivable row only>` | `<exact result>` | `<impact>` | `<control>` | `<owner>` | `<approver>` | `<date>` | `<ADR/ticket>` |

Any exception without every field above is invalid. Non-waivable matrix groups must never
appear in this table; their failure keeps the disposition `not ready`.

## Final disposition

- Non-waivable rows: `<passed>/<total; must equal total>`
- Governed rows: `<passed>/<total>`
- Blocking failures: `<list or NONE>`
- Blocked dependencies: `<SC-DATA-001 / SC-CART-001 / staging / NONE>`
- Approved governed exceptions: `<list with expiry or NONE>`
- Recommendation: `not ready` / `ready for staging` / `ready for cutover review`
- Reviewer sign-off and UTC timestamp: `<value>`

An empty or synthetic dataset cannot produce `ready for cutover review`. A green neutral
foundation supports only the next staging/acceptance phase.
