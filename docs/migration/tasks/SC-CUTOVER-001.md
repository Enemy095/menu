# SC-CUTOVER-001 — production cutover and legacy retirement

Status: `blocked`
Wave: P8
Dependencies: `SC-VAL-001`, `SC-DATA-001`, approved production deployment design

## Objective

Safely switch one production deployment from the read-only legacy reference to the
single-client application, while preserving PostgreSQL data, immutable assets and every
accepted order. The cutover must be rehearsed from a production-shaped V1–V5 backup,
apply Flyway V6–V8 exactly once, publish exactly one approved content dataset, establish
one authoritative order writer and retain a tested rollback path for the declared
observation window.

This document is an operational task specification and runbook. It does not authorize a
deployment, choose `Stambul`/`Adamas`, import business data, contact a provider or handle
production secrets. `SC-CUTOVER-001` remains blocked until both validation and content
gates are closed with reviewable evidence.

## Non-goals

- Do not select a production dataset or infer it from a branch, hostname, legacy default,
  row order or historical two-tenant manifest.
- Do not add a second runtime dataset, tenant/client context or host-based data switch.
- Do not implement a production provider, data importer, deployment platform or secret
  manager adapter in this task.
- Do not use root `compose.yaml` as a production deployment specification. It is a local
  development and CI dependency stack only.
- Do not put database passwords, provider locators, raw destinations, signed URLs,
  customer data or tokens in this document, terminal history, tickets or evidence.
- Do not edit an applied Flyway migration or invent a down migration for V7/V8.
- Do not test production notifications with a real customer order or real customer PII.
- Do not delete the legacy directory, old release artifacts, database backup or retained
  objects during the cutover itself.

## Immutable decisions

1. One deployment serves one approved client and one dataset.
2. PostgreSQL is the order/config/menu source of truth. S3-compatible storage contains
   immutable content-addressed assets; neither CDN nor Redis is authoritative.
3. V6 is an expand checkpoint, not a production steady state. V7 activates the
   single-client schema and drops V1–V5 runtime/import tables in one migration. V8 removes
   untrusted legacy order fields. There is no in-place application downgrade after V7.
4. Database rollback after V7/V8 means switching to a verified pre-cutover restore, not
   editing `flyway_schema_history`, running reverse SQL or starting an old binary on V8.
5. No order is double-written. The legacy direct-notification path and the new order API
   must never accept real submissions at the same time.
6. The only new order writer is `POST /api/v1/public/orders`. The browser never receives
   or selects a notification destination.
7. The worker stays disabled until an opaque destination is valid, unique, active,
   provider-reachable and approved. Notification delivery is at-least-once.
8. Content publication is single-dataset, non-HTTP and idempotent. It creates no order,
   outbox event or notification delivery.
9. Existing/new cache entries are disposable. Cache invalidation can never replace
   database or object-store restoration.
10. A rollback that could discard an accepted post-cutover order is forbidden. After the
    first live order, the default incident action is stop writes and forward-fix; database
    restoration requires explicit reconciliation evidence and incident-owner approval.

## Current implementation baseline

The operator must verify these statements against the release commit and artifact digest;
they describe the current repository, not an already deployed production environment.

### Database

- Flyway contains append-only migrations `V1` through `V8`.
- `V6__single_client_expand.sql` builds and verifies `sc_*` shadow data.
- `V7__activate_single_client_schema.sql` refreshes/verifies the shadow graph, drops
  obsolete V1–V5 tables, renames the final tables and asserts that tenant artifacts are
  absent.
- `V8__modernize_order_contract.sql` removes `orders.client_total_minor` and
  `orders.client_created_at`, preserving authoritative `server_total_minor` and order
  history.
- `FlywaySchemaIntegrationTests` exercises clean sequential V1→V8 and V7→V8 order
  compatibility. `SingleClientSchemaFlywayIntegrationTests` and
  `SingleClientActivationFlywayIntegrationTests` cover populated pivot invariants.
- Production schema migrations run during API startup. No separate production migration
  image or platform command is currently defined in this repository.

### API and worker

- Public routes are `GET /api/v1/public/config`, `GET /api/v1/public/menu` and
  `POST /api/v1/public/orders`.
- `/livez` checks process liveness without database dependency. `/readyz` includes
  `readinessState` and `db`; database outage returns `503`.
- Production management defaults to private `127.0.0.1:8081`. A container deployment
  may bind another private address, but the management port must not be public.
- The outbox worker defaults to `APP_WORKER_ENABLED=false`. It exposes bounded
  concurrency, lease, timeout, retry and shutdown configuration.
- Notification readiness is represented by `NotificationDestinationQuery`: it returns
  fixed channel/display metadata, status, a SHA-256 reference fingerprint and `ready`,
  never the raw reference. The current repository has no operator HTTP endpoint for this
  query and no production provider implementation; `SC-VAL-001` must supply an approved
  non-HTTP validation path and provider evidence before cutover.

### Web

- Next.js serves `/` and resolves its API only from server-side `MENU_API_BASE_URL`.
- Public content uses on-demand ISR/data caching. A separate no-store API readiness probe
  lets the proxy return real HTML `503`, `Cache-Control: no-store` and `noindex` even when
  content cache is warm.
- The frontend production build and healthy→warm-cache→API-outage integration test are CI
  gates. There is no repository-owned production ingress/CDN deployment.

### Infrastructure and observability

- Root Compose provides local PostgreSQL 17, optional MinIO and optional local
  Prometheus/OTel Collector. Its credentials, bucket and exporters are intentionally local.
- Local Prometheus scrapes the host API on port `8080`; production Actuator uses the
  private management port, `8081` by default. Production scrape discovery must be supplied
  by the deployment and must not copy the local target blindly.
- Current telemetry includes structured production logs, W3C trace propagation, HTTP/JVM/
  database-pool metrics and worker metrics for backlog, oldest age, provider calls,
  transitions, failures and duration.
- There are no dedicated application counters for every requested business KPI yet.
  `SC-VAL-001` must prove that the cutover dashboard can derive order acceptance,
  rejection and idempotent replay signals from approved low-cardinality metrics/logs or
  add them in a separate reviewed implementation slice.

## Exclusive ownership

This documentation slice owns only:

```text
docs/migration/tasks/SC-CUTOVER-001.md
docs/migration/tasks/README.md  # SC-CUTOVER status only, if status changes later
```

It must not change runtime code, migrations, CI, Compose, content inventories, generated
clients, legacy files or deployment state. The registry remains `blocked` until the gates
in this document are satisfied.

## Deliverables and operational interfaces

This slice delivers:

- this versioned public runbook with no secrets or environment-specific commands;
- a hard-gate checklist for SC-VAL, SC-DATA, backup/restore, destination/provider,
  environment, routing and staffing evidence;
- the V1→V8 rehearsal and data-compatibility contract;
- the zero-writer, canary, single-order-writer and observation state machine;
- separate pre-write and post-write rollback procedures;
- legacy retirement criteria and the handoff evidence schema.

Before execution, the private deployment command sheet must bind these interfaces without
changing their semantics:

| Interface | Contract |
|---|---|
| PostgreSQL operator connection | Protected libpq service/passfile, backup, new-target restore and audited read-only SQL. |
| API release | Immutable CI-built jar; Flyway V1–V8; `/livez`, `/readyz`, public v1 routes and private Actuator. |
| Web release | Immutable CI-built Next artifact; server-only API origin; `/` readiness/ISR semantics. |
| Object origin/CDN | Private S3-compatible bucket, immutable hash keys, verified public CDN origin and inventory/snapshot. |
| Content publisher | Approved non-HTTP single-dataset validate/apply/compare/rerun command. |
| Notification readiness | Non-HTTP `NotificationDestinationQuery` adapter emitting only status/channel/display/fingerprint/ready. |
| Provider smoke | Controlled non-customer call with timeout and stable idempotency evidence. |
| Deployment/traffic control | Scale, terminate, route-weight, exact order-route ownership, maintenance and rollback operations. |
| Observability | Private metrics/traces/logs, alert evaluation and aggregate reconciliation without PII/secrets. |

The repository currently supplies only part of these interfaces. Missing production
bindings are blockers, not placeholders that an operator may improvise during cutover.

## Required roles and evidence

No person should perform all destructive steps alone. Record role names in the private
change record, not in this repository.

| Role | Responsibility |
|---|---|
| Incident commander | Owns go/no-go, trigger evaluation and final rollback decision. |
| Database operator | Creates/verifies backup, rehearsal restore, migration and recovery. |
| Deployment operator | Controls API/web/worker/ingress/CDN releases and traffic weights. |
| Content approver | Signs the selected dataset, domain, contacts, prices, schedule and policy. |
| Notification owner | Verifies opaque reference/provider route and duplicate-safe handling. |
| Observer | Watches dashboards/logs, records timestamps and independently checks gates. |

Every evidence item has: release commit, artifact digest, environment, UTC timestamp,
operator, command/tool version, pass/fail and a redacted attachment/checksum. Evidence may
contain counts, durations, Flyway versions, public representation hashes and opaque
fingerprints. It may not contain secrets, raw notification locators, order/customer IDs,
phones, addresses, comments or provider bodies.

## Hard go/no-go gates

All rows must be `PASS`; `WAIVED` is not a pass.

| Gate | Required evidence | Owner |
|---|---|---|
| Release | One immutable API jar, web build and OpenAPI artifact from the same reviewed commit; CI green. | Deployment |
| Validation | `SC-VAL-001` is `done`, including browser/API/order/outbox/security/load/restore evidence. | Incident commander |
| Dataset | `SC-DATA-001` names exactly one approved dataset and immutable source/bundle digest. | Content |
| Importer | Approved non-HTTP importer dry-run is zero-write; apply and identical rerun are tested and redacted. | Content/DB |
| Database | Production-shaped backup restores; restored V1–V5 clone migrates through V8 with invariants intact. | DB |
| Backup | Fresh pre-cutover PostgreSQL backup and asset inventory/snapshot are encrypted, checksummed and restorable. | DB |
| Destination | Exactly the approved active destinations are `ready`; only opaque fingerprint appears in evidence. | Notification |
| Provider | Production provider adapter/configuration has a controlled non-customer smoke test and timeout/idempotency evidence. | Notification |
| Environment | Required web/API/worker/CDN/S3/OTel values are present in secret/config stores and pass validation. | Deployment |
| Health | API liveness/readiness, web root/outage behavior, private Actuator scrape and dependency alerts work. | Observer |
| Routing | Canary and rollback traffic controls are tested; exact POST order ownership is proven. | Deployment |
| Legacy | Legacy checkout/direct notification can be disabled before the new writer is enabled. | Deployment |
| Staffing | Incident, DB, deploy, content and notification owners are present for the whole rollback window. | Incident commander |

Immediate `NO-GO` conditions include a missing/ambiguous dataset, an unverified restore,
an unavailable production provider, raw destination data in evidence, any failed V6/V7
assertion, an active legacy submit path, or inability to route all order writes to exactly
one backend.

## Production configuration inventory

Values come from the production configuration/secret manager. Store only presence,
validation outcome and safe fingerprint in the change record.

### API

| Variable/group | Requirement |
|---|---|
| `SPRING_PROFILES_ACTIVE=production` | Mandatory; local defaults must never apply. |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Dedicated least-privilege runtime identity; migration privilege strategy documented. |
| `PUBLIC_BASE_URL` | Approved canonical HTTPS origin, no credentials/path/query/fragment. |
| `ASSET_BASE_URL` | Approved HTTPS CDN origin, no signed query or origin credential. |
| `APP_SECURITY_ALLOWED_HOSTS` | Exact canonical ingress hosts; no wildcard. |
| `APP_SECURITY_CORS_ALLOWED_ORIGINS` | Exact approved web origins or empty for same-origin topology. |
| `APP_SECURITY_TRUSTED_PROXIES_ENABLED/CIDRS` | Enabled only for known ingress CIDRs; forwarded headers tested. |
| `ASSET_STORAGE_ENDPOINT/REGION/BUCKET` | Private S3-compatible origin and exact production bucket. |
| `ASSET_STORAGE_ACCESS_KEY/SECRET_KEY` | Server-only least-privilege object credentials. |
| `ASSET_STORAGE_PATH_STYLE_ACCESS` | Matches provider; production choice recorded. |
| rate-limit variables | Capacity and memory bounds validated by SC-VAL load tests. |
| `MANAGEMENT_SERVER_ADDRESS/PORT` | Private network only; scrape and probes can reach it. |
| OTel export variables | Private authenticated endpoints; headers stay in secret manager. |

The database runtime identity should not receive superuser, database-create or arbitrary
schema-create privileges. If Flyway needs stronger DDL privileges, use a separate
short-lived migration identity and a deployment step reviewed by the database owner;
the current application-startup behavior must be adapted in a separate implementation
slice before claiming this split exists.

### Worker

| Variable | Cutover policy |
|---|---|
| `APP_WORKER_ENABLED` | `false` through migration/import/read canary; enable only after destination/provider gate. |
| `APP_WORKER_ID` | Stable, unique per process/replica and visible only as safe operational metadata. |
| poll/batch/concurrency/max destinations | Bounded and matched to provider/database capacity. |
| lease/provider timeout | Provider timeout is strictly shorter than lease. |
| attempts/backoff/jitter | Reviewed against provider retry policy and rollback window. |
| shutdown timeout | Deployment termination grace exceeds configured worker shutdown timeout. |

Scale-to-zero is preferred for maintenance. Merely setting a new replica's worker flag to
false does not stop an old worker; prove old processes exited and no lease is active.

### Web and edge

| Variable/control | Requirement |
|---|---|
| `NODE_ENV=production` | Mandatory. |
| `MENU_API_BASE_URL` | Pure internal HTTP(S) API origin without credentials/path/query/fragment. |
| canonical DNS/TLS | Approved hostname, valid certificate, renewal and rollback TTL ready. |
| CDN behavior | Versioned assets cache immutably; HTML/RSC and 503 obey application cache headers. |
| ingress routes | `/`, static assets, the three API routes and health boundaries explicitly mapped. |
| request authority | Effective Host and trusted forwarded headers match API security policy. |

Do not place database, object-store, destination or provider secrets in `NEXT_PUBLIC_*`,
Next.js build arguments, public runtime config, HTML/RSC payload or client source maps.

## PostgreSQL backup and restore plan

### Backup properties

The pre-cutover recovery point must be a transactionally consistent PostgreSQL backup
that includes data, schema, extensions and `flyway_schema_history`. A provider snapshot is
acceptable only after a successful restore drill proves it can create an isolated
database. Logical custom-format backup is recommended as an additional portable copy.

Run backup tooling from a hardened operator host. Inject credentials through the approved
secret mechanism; never include a password in the command line. The application
`DATABASE_URL` is a JDBC URL and must not be passed to libpq tools. Configure a protected
libpq service/passfile instead and expose only its service name. Example command shape for
a POSIX shell:

```bash
umask 077
pg_dump \
  --dbname="service=${PGSERVICE}" \
  --format=custom \
  --no-owner \
  --no-privileges \
  --file="backup/pre-cutover-${CUTOVER_ID}.dump"
sha256sum "backup/pre-cutover-${CUTOVER_ID}.dump" \
  > "backup/pre-cutover-${CUTOVER_ID}.dump.sha256"
pg_restore --list "backup/pre-cutover-${CUTOVER_ID}.dump" \
  > "backup/pre-cutover-${CUTOVER_ID}.restore-list.txt"
```

The concrete production path, encryption and upload commands belong to the private
deployment runbook. Do not commit generated backup/list files; even catalog metadata is
operational evidence.

Record safely:

- PostgreSQL server and client major versions;
- backup start/end UTC timestamps and recovery-point identifier;
- encrypted object/version identifier and SHA-256;
- database size, Flyway version and table row counts without row data;
- restore target identifier, restore duration and validation result;
- retention/expiry policy and two-person deletion control.

### Restore drill

Restore only into a newly created isolated database with no production routing. Never
use `--clean` against the active database.

```bash
createdb \
  --maintenance-db="service=${RESTORE_ADMIN_PGSERVICE}" \
  "$RESTORE_DATABASE_NAME"
pg_restore \
  --dbname="service=${RESTORE_PGSERVICE}" \
  --no-owner \
  --no-privileges \
  --exit-on-error \
  "backup/pre-cutover-${CUTOVER_ID}.dump"
```

After restore, verify:

```sql
SELECT installed_rank, version, description, type, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT current_database(), current_user;
SELECT count(*) AS orders FROM orders;
SELECT count(*) AS order_items FROM order_items;
SELECT processing_state, count(*) FROM outbox_events GROUP BY processing_state ORDER BY 1;
SELECT status, count(*) FROM notification_deliveries GROUP BY status ORDER BY 1;
```

If the source is V1–V5, use the corresponding legacy table shape for pre-migration
counts. Do not run V8-only SQL before migration. The restore drill is incomplete until
the restored database can be migrated to V8 and queried by the candidate API.

### Object storage recovery evidence

Database rows reference immutable objects and cannot restore missing bytes. Before
cutover:

1. Freeze deletion/garbage collection for the rollback and retirement windows.
2. Export a provider inventory containing object key, size, ETag/version and checksum
   where supported. Encrypt it and store it outside the bucket.
3. Verify every approved `assets.object_key` exists and matches database size/SHA-256.
4. Prove the bucket is private and the CDN can read only through the approved origin path.
5. Prove a sampled object can be restored/read from the provider snapshot/versioning
   mechanism without changing its key or bytes.
6. Keep unreferenced import uploads; garbage collection waits until retirement.

Do not download or print object credentials/signed origin URLs as evidence.

## V1→V8 production-shaped rehearsal

Run at least once on a recent restored production backup and again after any migration,
database-major-version or relevant persistence change.

### Automated baseline

```text
./gradlew :apps:api:test --tests "*FlywaySchemaIntegrationTests"
./gradlew :apps:api:test --tests "*SingleClientSchemaFlywayIntegrationTests"
./gradlew :apps:api:test --tests "*SingleClientActivationFlywayIntegrationTests"
./gradlew :apps:api:test --tests "*JpaMappingIntegrationTests"
```

These tests are necessary but do not replace a restored-data rehearsal.

### Rehearsal sequence

1. Restore the current production recovery point into an isolated database.
2. Confirm its `flyway_schema_history` checksum and expected source version. Stop on any
   failed, missing, out-of-order or edited migration.
3. Record pre-migration counts for tenants/site graph, restaurants, menu graph, assets,
   destinations, orders/items/history, outbox and deliveries using version-aware queries.
4. Confirm the source satisfies V6's single-client assertion. More than one candidate
   tenant/dataset, ambiguous ownership or unsupported outbox state is a no-go; do not
   delete rows to make the migration pass.
5. Stop all restored old writers/workers. Confirm no outbox row has an active processing
   lease and no transaction remains in flight.
6. Start the exact candidate API artifact against the clone with worker disabled and no
   ingress. Let Flyway apply V6, V7 and V8; capture sanitized startup/Flyway logs.
7. Verify final Flyway version `8`, validation success and absence of `sc_*`, tenant and
   legacy-import runtime objects. Do not modify history manually.
8. Compare post-migration entity counts and aggregate totals to the pre-migration report.
   V7's internal digest/assertion must pass. Any unexplained count/hash difference fails
   the rehearsal.
9. Prove V8 removed only the untrusted client total/time fields and preserved
   authoritative total, confirmation state, order snapshots/history and idempotency keys.
10. Start candidate read paths; require `/livez=200`, `/readyz=200`, public config/menu
    contract success and no secret/internal field exposure.
11. With writes enabled only in the isolated environment, create a synthetic order using
    a synthetic contact, repeat the same `Idempotency-Key`, and prove one order/one outbox
    event. Do not call a real provider.
12. Restart the candidate, rerun Flyway validation and prove no migration/data drift.
13. Destroy the isolated clone according to evidence-retention policy.

### Final-schema read-only checks

Run only after V8:

```sql
SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 8;

SELECT count(*) AS active_default_restaurants
FROM restaurants
WHERE status = 'active' AND is_default;

SELECT count(*) AS published_menus
FROM menu_versions
WHERE status = 'published';

SELECT processing_state, count(*)
FROM outbox_events
GROUP BY processing_state
ORDER BY processing_state;

SELECT count(*) AS active_destinations
FROM notification_destinations
WHERE status = 'active';
```

Expected restaurant/menu counts come from the approved single dataset, not from this
runbook. Never add `LIMIT 1` or choose an arbitrary row to mask ambiguity.

## Content approval and import gate

`SC-DATA-001` owns the selection. Before the maintenance window, the content owner must
approve all of the following as one immutable decision artifact:

- outcome `STAMBUL_APPROVED`, `ADAMAS_APPROVED` or `NEW_BUNDLE_REQUIRED`;
- source and normalized bundle SHA-256;
- legal/public display identity, contacts and asset rights;
- `ru-RU`, `RUB`, IANA timezone and exact seven-day schedule;
- canonical HTTPS URL and SEO text;
- current item names, prices, availability and order types;
- delivery fee/minimum/threshold policy, with marketing copy excluded unless executable;
- Stambul location resolution or feature disablement;
- Adamas logo/hero semantic asset decision if applicable;
- opaque destination fingerprint and notification/provider owner approval.

The approved importer must provide these modes before cutover:

```text
validate/dry-run  -> no DB/object writes, redacted deterministic plan and digest
apply             -> immutable assets first, one atomic database publication
compare           -> public config/menu and persisted graph equal approved bundle
rerun             -> identical digest is a no-op; changed digest fails closed
```

The current repository does not yet contain this single-dataset importer. Do not replace
the missing command with manual production SQL. Until its implementation is reviewed and
`SC-DATA-001` is `done`, this task remains blocked.

Import acceptance requires:

- exactly one site graph, one active default restaurant and one published menu;
- no selected values inferred from placeholders or OpenAPI examples;
- public config/menu representation matches approved hashes;
- every database asset reference resolves to verified immutable object bytes;
- unselected dataset markers/rows/assets are absent from runtime configuration;
- orders, order items, history, outbox and deliveries unchanged by import;
- identical rerun produces zero writes;
- any failed asset/database step leaves no partially public graph.

After import, invalidate only the relevant Next/CDN config/menu entries and prewarm `/`.
Never purge immutable content-addressed asset URLs.

## Opaque notification destination gate

The raw locator exists only in PostgreSQL/secret-managed provisioning and the
notification persistence/provider boundary. Operator evidence must use
`NotificationDestinationReadiness.referenceFingerprint` and `ready`.

Required validation:

1. No value starts with historical `required-secret:` and no raw phone/email/chat ID is
   stored as the reference.
2. Reference scheme is `secret://` or `vault://`; it has host and non-root path, and no
   user info, query or fragment.
3. Status is `active`, the active `(channel, displayName)` is unique and readiness is
   true for every intended destination.
4. The referenced secret exists, can be read by the provider workload identity and
   cannot be read by web/browser identities.
5. Reference fingerprint in the approval artifact exactly matches runtime readiness.
6. Provider configuration, timeout and stable idempotency key behavior pass a synthetic
   non-customer smoke test.
7. Disabled/malformed/duplicate/no destination fails closed. It must never fall back to
   legacy `managerPhone`, client payload or a hardcoded default.

Safe database evidence returns only counts, never `secret_reference`:

```sql
SELECT status, channel, count(*)
FROM notification_destinations
GROUP BY status, channel
ORDER BY status, channel;

SELECT channel, display_name, count(*)
FROM notification_destinations
WHERE status = 'active'
GROUP BY channel, display_name
HAVING count(*) <> 1;
```

Both the second query returning any row and the non-HTTP readiness report containing any
`ready=false` are no-go. SQL shape checks do not replace the Java readiness/provider
check. Do not select or hash the raw reference ad hoc in terminal output.

## Canary and cutover state machine

Use UTC timestamps. Each transition requires an incident-commander decision and observer
confirmation. A deployment-specific command sheet must replace `<deployment-cli>` and
`<traffic-cli>` placeholders before the event; placeholders must never be executed.

### C0 — frozen preparation

- Freeze migration/runtime/content changes.
- Record release commit and API/web/container/artifact digests.
- Require green CI jobs: legacy preflight, API contract/generated drift, frontend
  lint/typecheck/test/build/production status test, backend check/bootJar and both Compose
  model checks.
- Complete the restored-data V1→V8 rehearsal and restore-time objective measurement.
- Verify backup destination capacity, DNS TTL, certificate, on-call access and rollback
  commands.
- Freeze rollback thresholds and observation/retirement durations in the change record.

### C1 — enter maintenance and establish zero writers

1. Disable legacy checkout/direct WhatsApp/provider submission and return an explicit
   maintenance/unavailable state for order attempts.
2. Block `POST /api/v1/public/orders` at ingress; public reads may remain available only
   if they cannot expose mixed content.
3. Scale old/new workers to zero or stop them gracefully.
4. Wait at least the configured worker shutdown timeout; verify no running worker process,
   no new provider call and no active `processing` lease.
5. Record order/outbox/delivery counts and last accepted legacy-submission timestamp.
6. Prove both old and new order submission paths are unavailable before continuing.

There must be a short interval with zero writers. There must never be an interval with two.

### C2 — final recovery point

1. Drain database sessions from application writers.
2. Create the final encrypted PostgreSQL backup/provider snapshot and checksums.
3. Export final immutable object inventory and freeze deletion/GC.
4. Verify the backup is visible to the restore account and the latest restore drill is
   still valid.
5. Set `CUTOVER_T0` only after the recovery point is durable.

### C3 — migrate database

1. Start exactly one migration-capable candidate instance with no public ingress and
   worker disabled.
2. Apply V6→V8 through normal Flyway startup; never stop intentionally at V6.
3. Require Flyway validation and V7 assertions to pass.
4. Run final-schema read-only checks and compare counts/hashes to rehearsal.
5. On any migration/assertion discrepancy, stop candidate and follow pre-write database
   rollback; do not repair production rows interactively.

### C4 — publish and verify approved content

1. Run approved dry-run and compare its digest to the signed decision.
2. Apply exactly one dataset using the approved non-HTTP importer.
3. Verify assets privately and through CDN, then compare public config/menu hashes.
4. Run identical importer rerun and require no-op.
5. Confirm import did not change order/outbox/delivery counts.
6. Keep worker disabled and order POST blocked.

### C5 — API/read-only canary

1. Start candidate API replicas with worker disabled.
2. Require `/livez=200` and `/readyz=200`; verify private Prometheus scrape and trace/log
   export without PII/secrets.
3. Send internal/canary GET traffic to config/menu and verify contract, cache headers,
   latency and approved content.
4. Start candidate web behind an internal/canary route, prewarm `/`, verify canonical SEO,
   assets and warm-cache API-outage `503/no-store/noindex` behavior.
5. Increase read-only web traffic through predefined stages, for example 1% → 10% → 50%
   → 100%, only after the configured hold and successful thresholds at each stage.

Percentages and holds are deployment decisions frozen before C0. They do not authorize
order traffic. Cookie/header canary keys must not select business data.

### C6 — establish the single order writer

1. Reconfirm legacy submit remains disabled and cannot call a real destination.
2. Reconfirm destination readiness/provider smoke evidence and worker capacity.
3. Route **all** `POST /api/v1/public/orders` traffic to the candidate API. Do not split
   order writes between versions or shadow-copy requests.
4. Remove the order maintenance block only after the exclusive route is active.
5. Submit one controlled canary order through the public contract using approved
   synthetic operational data and a UUID idempotency key. Repeat the identical request and
   require the original response, one order and one outbox event.
6. Enable one worker replica. Observe one delivery audit and expected provider result;
   repeat handling must use the stable provider idempotency key.
7. Expand worker replicas/concurrency only after backlog/latency remain within thresholds.

If production policy forbids synthetic orders, the notification owner must define an
equivalent approved non-customer validation before C0. Never use a real customer as the
test subject.

### C7 — full traffic and observation

- Route 100% public web/API reads to candidate.
- Keep one authoritative order POST route and legacy submission disabled.
- Observe continuously for the declared high-attention window.
- Record order accepted/rejected rates, idempotent replays, response latency/status,
  database saturation, outbox backlog/age/states, provider calls/failures/duration,
  delivery states, API/web readiness and CDN failures.
- Reconcile accepted orders against outbox and notification audit counts without exposing
  identifiers/PII in shared evidence.
- Announce cutover complete only after all hold periods and acceptance checks pass.

## Double-write prohibition

The following are forbidden in every phase:

- legacy browser direct WhatsApp/provider send while the new order API is enabled;
- mirroring a production order POST into old and new APIs/databases;
- a “shadow” candidate that persists orders/outbox while the legacy path is live;
- enabling workers against both restored and candidate databases;
- retrying a user action with a new idempotency key because the first response timed out;
- replaying captured customer requests in staging/canary;
- restoring a pre-cutover database and silently discarding post-cutover orders.

Allowed shadowing is limited to redacted/idempotent public GETs and offline validation on
an isolated restored database. The order route has a single explicit owner recorded at
each state transition.

## Observation dashboard and queries

### Current safe metric families

Depending on registry naming conventions, current Micrometer meters expose the following
logical series:

- `http.server.requests` latency/count/status for API routes;
- JVM, process and database connection-pool saturation;
- `menu.notification.outbox.claimed`;
- `menu.notification.outbox.pending`, `.processing`, `.failed` and
  `.oldest.pending.seconds`;
- `menu.notification.provider.calls` and `.provider.duration` by bounded channel/outcome;
- `menu.notification.outbox.transitions`, `.poll.failures` and
  `menu.notification.delivery.stale.reconciled`.

Confirm the exact Prometheus-normalized names in staging. Do not build the runbook around
an assumed suffix. Never add order/customer/destination ID, phone, host or unbounded error
text as a label.

### Read-only database reconciliation

Use an audited read-only role and aggregate results only:

```sql
SELECT date_trunc('minute', created_at) AS minute, count(*) AS orders
FROM orders
WHERE created_at >= :cutover_t0
GROUP BY minute
ORDER BY minute;

SELECT processing_state, count(*) AS events,
       max(attempt_count) AS max_attempts,
       min(available_at) FILTER (WHERE processing_state = 'pending') AS oldest_available
FROM outbox_events
GROUP BY processing_state
ORDER BY processing_state;

SELECT status, count(*) AS deliveries
FROM notification_deliveries
WHERE started_at >= :cutover_t0
GROUP BY status
ORDER BY status;

SELECT count(*) AS duplicate_idempotency_groups
FROM (
    SELECT idempotency_key
    FROM orders
    GROUP BY idempotency_key
    HAVING count(*) > 1
) duplicates;
```

Bind `:cutover_t0`; do not paste it by string concatenation into an operator script. A
duplicate idempotency group must always be zero. Do not select customer/order identifiers
into shared logs or metric labels.

### Signals required before C0

If current instrumentation cannot provide a required signal, `SC-VAL-001` must add and
validate it in a separate implementation task or define an audited aggregate query. At
minimum the observer needs:

- order POST request count by bounded status/outcome and latency;
- idempotent replay/conflict signal;
- order/outbox transaction failure signal;
- public config/menu/root availability and latency;
- database connections/transactions/locks and Flyway/startup failures;
- worker backlog count/oldest age, transitions and terminal failures;
- provider call latency/outcome and notification audit status;
- web/API 5xx, readiness failures, CDN asset errors and cache behavior.

## Rollback triggers

Numeric thresholds must be baselined in SC-VAL and frozen before C0. The following are
minimum semantic triggers; recommended initial thresholds are conservative, not a waiver
to skip baseline approval.

| Trigger | Default action |
|---|---|
| Migration/Flyway assertion/checksum failure | Immediate stop; pre-write DB restore. |
| Wrong/ambiguous dataset, price, currency, schedule or canonical identity | Block writes; content rollback/forward correction. |
| Any duplicate order for one idempotency key | Stop order ingress and worker; preserve DB; incident. |
| Accepted order without exactly one committed outbox event | Stop order ingress; preserve DB; incident. |
| Server total/snapshot differs from published menu | Stop order ingress; preserve DB; incident. |
| Sustained API order 5xx above approved threshold or readiness failure | Stop writes; evaluate API/DB rollback. |
| Web serves healthy outage as 200 fallback or indexes 503 content | Roll back web/edge release. |
| Destination readiness false, provider routed incorrectly or secret exposed | Disable worker and order ingress; rotate secret if exposed. |
| Terminal outbox failure > 0 during canary | Pause worker/order expansion; investigate before rollback choice. |
| Backlog age/count exceeds approved bound or grows continuously | Hold traffic; scale/fix worker or stop writes. |
| DB pool/CPU/locks exceed approved saturation bound | Hold/stop writes; evaluate capacity or rollback. |
| Asset hash mismatch/private origin exposure/material CDN failures | Hold web traffic; restore CDN/object policy. |
| PII/secret in logs, traces, metrics or browser | Stop affected components; revoke/rotate and incident response. |

Recommended canary defaults to validate/replace in SC-VAL: two consecutive readiness
failures, order 5xx ≥1% over five minutes, duplicate/integrity/security count >0, terminal
notification failure >0, or oldest pending event > five minutes. The incident commander
may stop earlier; continuing beyond a hard semantic trigger is not allowed.

## Rollback procedures

### Component-only rollback hierarchy

Choose the narrowest safe rollback:

1. **Worker:** disable/scale to zero, wait bounded shutdown, preserve pending/processing
   rows. Order creation may also be blocked while notification is unavailable.
2. **Web/CDN:** route to the previous contract-compatible web artifact or maintenance
   page, purge mutable HTML/RSC/config/menu caches, retain immutable assets. Never
   re-enable legacy direct submission.
3. **API without schema downgrade:** route to the previous release only if it is proven
   V8-compatible. The pre-single-client/V1–V5 binary is not compatible.
4. **Database/schema:** stop all writers/workers and restore the pre-cutover recovery
   point into a new database, then route only a V1–V5-compatible release to it.

### R0 — rollback before the first candidate order

This is the only simple database rollback window.

1. Keep legacy/new order submit blocked and all workers stopped.
2. Remove candidate API/web from ingress.
3. Stop candidate instances and close database sessions.
4. Preserve failed database and logs as encrypted incident evidence; do not mutate it.
5. Restore the verified pre-cutover backup into a new database endpoint.
6. Run restore-version checks and start the exact previous compatible release with worker/
   legacy submit still disabled.
7. Require old release health/read parity, then restore old read traffic.
8. Re-enable legacy order submission only by an explicit incident decision after proving
   the new order route remains blocked. There is still exactly one writer.
9. Invalidate mutable CDN/Next caches and verify no candidate content/503 is cached.

### R1 — incident after one or more candidate orders

Never blindly restore the T0 database: it would discard accepted orders and outbox state.

1. Immediately block new order POSTs and legacy submit; disable/stop worker claims while
   allowing bounded in-flight shutdown.
2. Keep public reads on a safe maintenance/previous compatible web path.
3. Take a new encrypted **post-incident V8 backup** before any correction.
4. Record aggregate counts and preserve all post-T0 orders/items/history/outbox/deliveries.
5. Prefer a forward application/config/content fix on V8 when database integrity holds.
6. If full DB restore is unavoidable, prepare a separately reviewed reconciliation plan
   for every post-T0 accepted order and provider outcome. Do not auto-map V8 orders back
   into V1–V5 tables during the incident.
7. Obtain incident commander, DB owner and business/order-operations approval that no
   accepted order will be lost or sent twice.
8. Restore the T0 backup to a new endpoint, validate it, start the previous compatible
   release with submits disabled, then complete manual/business reconciliation from the
   preserved V8 evidence.
9. Re-enable one writer only after reconciliation and provider duplicate risk are signed
   off.

An accepted order remains valid even if notification failed. Do not delete or cancel it
merely to simplify rollback.

### Cache, DNS and asset rollback

- Reduce DNS TTL before C0; restore the prior route using the tested traffic control, not
  emergency record guessing.
- Purge HTML/RSC/config/menu paths and surrogate tags after route/content rollback.
- Do not purge or overwrite content-addressed asset URLs.
- Keep S3 deletion/GC frozen. A restored DB may reference an older object set.
- Verify canonical tags, CSP/CORS/cookies and allowed hosts after every route change.

## Data compatibility matrix

| State | Compatible runtime | Rollback rule |
|---|---|---|
| V1–V5 | Historical tenant-era binary only | Candidate rehearsal may migrate clone. |
| V6 | Transitional shadow schema; not steady state | Continue immediately to reviewed V7/V8 or restore. |
| V7 | Single-client tables; legacy tables dropped | Old binary forbidden; restore for downgrade. |
| V8 | Current API/order contract | Current V8-compatible releases only. |

Additional rules:

- Do not run V6 while legacy writers continue; their later writes would not be copied.
- V7 must see a quiescent source and acceptable outbox state.
- V8 drops non-authoritative client fields; no rollback code may recreate them as truth.
- Pending/processing/published/failed outbox and delivery audit semantics must survive the
  migration; stop workers before the pivot.
- Idempotency keys stay globally unique in single-client schema.
- Object keys are stable across database restore; never rewrite asset URLs in-place.
- Candidate web must consume the exact released OpenAPI/V8 API contract.

## Legacy retirement window

Define three durations in the approved change record:

- **high-attention window** — staffed continuous observation immediately after C7;
- **operational rollback window** — previous release, T0 backup, failed/new DB snapshots,
  object inventory and routing controls remain immediately available;
- **retirement retention** — encrypted backups/artifacts/evidence remain under normal
  retention after old runtime is removed.

Recommended starting points, subject to business/RTO/RPO approval, are 24 hours of high
attention, at least 7 stable days before runtime retirement and at least 30 days before
cutover-specific backup/artifact deletion. A shorter window requires explicit risk
acceptance; the presence of live V8 orders still makes restoration a reconciliation event.

Legacy runtime retirement requires all of these:

- no rollback trigger throughout the approved stability window;
- no traffic/DNS/ingress reference to the old frontend/API/provider path;
- legacy submit and direct WhatsApp/provider path permanently disabled;
- all pre-retirement outbox events terminal (`published` or deliberately reviewed
  `failed`), no active lease and notification failures reconciled;
- order, financial/operational and content owners approve parity;
- current database backup/restore drill and object inventory remain valid;
- dashboards/alerts/on-call docs and production deployment specification are current;
- secret manager access for obsolete legacy destinations is revoked/rotated;
- previous artifacts and backups are retained until their declared expiry.

Removing `Электронное меню/`, old migrations, compatibility documentation, object bytes or
database backups is a separate reviewed task after the window. Applied V1–V8 migration
files remain immutable in source control permanently.

## Failure and edge cases

- **Backup checksum mismatch or restore failure:** no-go; create/verify a new recovery
  point before maintenance.
- **Source changed after content approval:** invalidate approval and rerun SC-DATA; no
  automatic normalization.
- **V6 detects multiple/ambiguous clients:** no-go; do not delete one ad hoc.
- **V7 assertion fails:** restore pre-cutover DB; do not bypass the assertion.
- **API starts but `/readyz` is down:** keep it out of ingress; liveness alone is not enough.
- **Management endpoint unreachable:** do not expose it publicly; repair private network/
  scrape configuration.
- **Web shows stale approved content while API is down:** its no-store readiness proxy must
  still produce 503; verify edge is not overriding status/cache headers.
- **CDN contains old mutable HTML:** purge only mutable keys and verify canonical metadata.
- **Asset upload completed but DB publication failed:** leave immutable unreferenced object
  for later GC; do not partially publish DB.
- **Destination disappears/changes after canary:** worker re-resolves and fails closed;
  pause orders/worker, never use a historical raw value.
- **Provider succeeded before worker crash:** duplicate remains possible; reuse stable
  provider idempotency key and inspect audit before retry.
- **Worker lease active at maintenance:** wait for graceful shutdown/expiry; do not migrate
  while an external call can still complete.
- **Client timed out after POST:** replay the same payload/key; never create a new key for
  the same logical order.
- **Rollback after live orders:** preserve V8 database and reconcile; T0 restore alone is
  prohibited.
- **Both old/new frontends are reachable:** acceptable only when old submit is provably
  disabled and all order POSTs have one candidate owner.
- **Compose works locally:** not production evidence; Compose has local credentials,
  local-only MinIO and debug-only telemetry export.

## Required validation before status can change

### Repository/CI gates

```text
node docs/migration/import/verify-preflight.mjs
pnpm install --frozen-lockfile
pnpm --filter @e-menu/api-contract validate
pnpm --filter web check
./gradlew --no-daemon :apps:api:check :apps:api:bootJar --stacktrace
docker compose config --quiet
docker compose --profile assets --profile observability config --quiet
git diff --check
```

The historical import preflight verifies source integrity only; it neither selects nor
publishes a dataset.

### Operational acceptance evidence

- production-shaped restore report and measured restore duration;
- V1–V5→V8 rehearsal report with pre/post counts, Flyway result and candidate reads;
- approved single-dataset decision, import dry-run/apply/compare/no-op reports;
- object inventory/private-origin/CDN hash verification;
- opaque destination readiness fingerprint and provider smoke result;
- environment presence/validation matrix with no values;
- health/readiness/metrics/trace/log redaction evidence;
- canary routing test proving order POST single ownership;
- rollback drill before first write and tabletop/post-write reconciliation exercise;
- approved trigger thresholds, observation durations and retirement dates.

## Implementation sequence for this task

1. Keep task and registry `blocked`; review this runbook against current release.
2. Complete `SC-DATA-001` decision/import implementation and evidence.
3. Complete `SC-CART-001` and create/complete `SC-VAL-001` task/evidence.
4. Supply production provider, deployment topology and private observability in separate
   reviewed implementation tasks.
5. Rehearse backup/restore, V1→V8, canary and rollback in staging.
6. Freeze release, content, environment, thresholds, roles and schedule.
7. Change status to `ready` only when every hard gate is `PASS`.
8. Execute C0–C7 under the approved private command sheet.
9. Observe, reconcile and close the operational rollback window.
10. Retire legacy runtime only after the retention criteria; perform repository cleanup in
    a separate task.

## Handoff report format

```text
Task ID: SC-CUTOVER-001
Status: blocked | ready | in_progress | review | done
Release commit/artifact digests: <safe identifiers>
SC-VAL evidence: <artifact id/result>
SC-DATA decision/bundle digest: <approved outcome and sha256>
Backup/restore: <recovery id, checksum, restore duration/result>
V1->V8 rehearsal: <source/final version, counts/hash result>
Asset inventory/CDN: <inventory id and verification result>
Destination/provider: <channel, readiness fingerprint, smoke result; no raw value>
Environment: <presence/validation only>
Health/observability: <probe and dashboard result>
Single order writer proof: <routing state/result>
Canary stages: <UTC stage/hold/result>
Orders/outbox/delivery reconciliation: <aggregate result>
Rollback decision/drill: <pre-write and post-write result>
Observation/retirement window: <UTC boundaries/status>
Changed files: <paths>
Commands run: <command + exit code>
Unresolved issues: <NONE or exact blocker>
Suggested next action: <gate owner/action>
Git status: <short output; no commit>
```

## Current blockers

At documentation time:

- `SC-DATA-001` is `blocked / decision-required`; no production dataset or single-dataset
  importer is approved.
- `SC-CART-001` and `SC-VAL-001` are blocked; no full-system/cutover evidence exists.
- No production deployment topology, backup target, CDN/S3 configuration, provider adapter
  or non-HTTP destination readiness command is defined in the repository.
- Root Compose and observability files are local-only and cannot satisfy production gates.

Therefore `SC-CUTOVER-001` must remain `blocked`. This document creates no permission to
deploy, migrate production, import content, enable the worker or retire legacy.
