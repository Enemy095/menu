# SC-VAL-001 — full-system validation foundation

Status: `in_progress`
Wave: P7
Dependencies: completed `SC-API-001`, `SC-BE-001`, `SC-SEC-001`, `SC-WEB-001`,
`SC-ORD-001`, `SC-OUT-001`; release acceptance waits for `SC-DATA-001` and
`SC-CART-001`

## Objective

Create one reproducible, fail-fast validation entry point and an auditable release
evidence model for the single-client application. The executable foundation must prove
all neutral behavior available without a production dataset, while representing blocked
content/cart/cutover scenarios honestly instead of replacing them with synthetic claims.

The canonical local command is:

```text
pnpm validate:full
```

After the owner-approved Stambul dataset exists, its additive disposable full-stack
acceptance command is:

```text
pnpm validate:acceptance
```

This second command does not replace the neutral gate. It validates only the pinned
local-test dataset and uses a separately built acceptance JAR whose deterministic
notification provider is proven absent from the production JAR.

It performs a frozen workspace install, architecture guard, OpenAPI/generated-client
validation, frontend production-shaped checks, complete backend checks and `bootJar`, a
real-jar disposable PostgreSQL smoke, then `git diff --check`.

## Non-goals

- Do not choose, import, copy or infer a Stambul/Adamas production dataset.
- Do not duplicate cart/checkout UI, browser persistence or order payload assembly.
- Do not send a real order, notification, email, message, POS request or asset upload.
- Do not make cutover, rollback or production readiness claims.
- Do not add runtime client/tenant/slug/host-based business-data selection.
- Do not add Redis, Kafka, Kubernetes, WebFlux, R2DBC or a synthetic application image.
- Do not weaken an existing contract, security, migration or module test to make the
  aggregate command green.

## Immutable decisions

- A single deployment serves one client; the runtime architecture scan rejects the old
  compatibility `tenant*` scope outside immutable V1-V7 migration history and three exact
  defensive metric deny-list literals.
- Historical production Flyway migrations remain immutable and are excluded by exact
  version/path rule. V8 and every future runtime migration are scanned.
- The smoke harness uses a unique PostgreSQL container whose only mount is an ephemeral
  `tmpfs` for PostgreSQL data; bind, named and anonymous volumes are forbidden. The worker
  is disabled, object storage is unreachable, fixtures are absent and only GET requests
  are made. Inherited
  Spring/application/JVM option environment is scrubbed and authoritative datasource,
  Flyway, JPA, server, security, asset and worker values are command-line arguments.
- Every synchronous Docker, Java, `psql` and Windows `taskkill` invocation has an explicit
  finite timeout and reports the timed-out operation. Cleanup discovers the container by
  its unique random name and run label even when the original `docker run` process exits,
  times out or is interrupted before returning a container ID.
- The empty-database assertion discovers every `public` schema table, requires the exact
  current 18-table application set plus `flyway_schema_history`, counts every discovered
  application table and rejects any missing, retained or newly introduced table.
- Empty schema content must fail closed: liveness/readiness stay healthy while public
  config/menu respond `503 SITE_NOT_READY`, `no-store`, with a trace ID.
- Complete acceptance needs an owner-approved staging dataset and the real cart/checkout
  slice. A green neutral gate does not unblock `SC-CUTOVER-001` by itself.
- Evidence records exact commit, dirty state, commands, environment and artifacts. It
  never records secrets, PII, raw order/provider payloads or provider identifiers.

## Exclusive file ownership

```text
scripts/check-forbidden-runtime-scope.mjs
scripts/smoke-disposable-postgres.mjs
scripts/validate.mjs
docs/migration/tasks/SC-VAL-001.md
docs/migration/validation/**
docs/migration/tasks/README.md        # SC-VAL row/note only
.github/workflows/ci.yml              # validation steps only
.github/CI.md
package.json                          # validate:* scripts only
compose.yaml                          # neutral local bucket default only
infra/local/README.md                 # matching bucket documentation only
```

Frontend cart sources, API implementation, OpenAPI/generated code, Flyway history,
production data and legacy sources are read-only for this task.

## Validation interfaces

### Aggregate command

`pnpm validate:full` runs these stages in order and stops at the first failure:

1. `pnpm install --frozen-lockfile`;
2. single-client runtime architecture scan;
3. API contract lint, semantic tests, generated drift check and TypeScript check;
4. API contract package build;
5. frontend lint, typecheck, unit/accessibility tests, production build and HTTP status
   integration test;
6. Gradle `check` plus executable `bootJar`;
7. executable jar against disposable PostgreSQL;
8. `git diff --check`.

For diagnosed partial reruns, `--skip-install` and `--skip-smoke` are supported. They are
not acceptable substitutes for final evidence. Component aliases are
`pnpm validate:architecture`, `pnpm validate:web` and `pnpm validate:smoke`.

### Disposable database smoke

```text
unique postgres:17.5-alpine3.21 container, tmpfs data and no Docker volume/bind
→ wait for pg_isready
→ start built menu-platform-api.jar on free loopback port
→ worker explicitly false; no usable provider/object-storage destination
→ assert liveness/readiness UP
→ assert empty config/menu fail closed with 503/no-store/traceId
→ assert Flyway current version V9
→ assert selected database/user and V9
→ discover the complete public schema table set and count every application table
→ require exactly 18 current application tables + Flyway metadata, all application counts zero
→ terminate application and discover/remove by unique run label/name
→ require process exit and bounded post-remove Docker inspect absence
```

The harness never performs a POST and is therefore safe to run repeatedly. Testcontainers
coverage inside Gradle remains responsible for order/outbox write and concurrency cases.

### Approved Stambul disposable acceptance

`pnpm validate:acceptance` is intentionally local and destructive only to containers it
creates and labels with its unique run ID. Its complete contract is documented in
[the Stambul disposable acceptance runbook](../validation/stambul-disposable-acceptance.md).

In summary, it verifies the independent normalized oracle, builds production and
acceptance artifacts, proves acceptance-only classes are absent from the production JAR,
starts loopback/tmpfs PostgreSQL and MinIO, pre-migrates the schema, executes importer
dry-run and apply, boots the real API and production Next.js build, then asserts exact
configuration/menu/SSR content and one idempotent order through the web route. A
test-source-only provider deterministically fails once and succeeds on retry, allowing
database assertions for order survival, delivery audit and terminal outbox state without
network, file, subprocess or real provider access. Cleanup runs on pass, failure and
interruption and proves that no owned container remains.

### Detailed scenario and evidence contracts

- [E2E and non-functional matrix](../validation/e2e-matrix.md)
- [Evidence template](../validation/evidence-template.md)

## Deliverables

- Cross-platform fail-fast aggregate command for Windows and Linux CI.
- Exact forbidden runtime-scope guard with controlled legacy-history exception.
- Docker-backed real-jar smoke with isolated configuration, verified tmpfs-only data,
  bounded subprocesses, label/name recovery and deterministic cleanup with no business
  side effects.
- CI enforcement for the architecture guard and executable smoke.
- Explicit matrix spanning config, menu, cart, checkout, orders, outbox, security,
  performance, accessibility and observability.
- Release evidence template that distinguishes automated pass, manual pass, blocked and
  not-run states.

## Implementation sequence

1. Inventory existing contract, web, backend, security and infrastructure gates.
2. Freeze task scope, dependencies, blocked cases and evidence vocabulary.
3. Add the runtime compatibility-scope guard without editing migration history.
4. Add the fail-fast aggregate command using only frozen/read-only generators.
5. Add and execute the worker-disabled disposable PostgreSQL smoke.
6. Wire neutral guards into CI and document their safety model.
7. Define scenario matrix, owners, release classification and evidence template.
8. Run the complete aggregate gate on a clean integration snapshot.
9. After `SC-DATA-001` and `SC-CART-001`, execute blocked dataset/cart/browser/load
   scenarios and attach evidence.
10. Move to `done` only when every release-blocking row is passed or has an approved,
    time-bounded exception.

## Required tests and release criteria

### Automated foundation

- frozen install and generated client drift are clean;
- runtime sources contain no compatibility client scope;
- API lint/semantics/types/build pass;
- frontend lint/types/unit/accessibility/build/status integration pass;
- backend unit, PostgreSQL integration, security, Modulith, idempotency, concurrency,
  Flyway, outbox recovery and observability checks pass;
- executable jar migrates an empty PostgreSQL and responds according to the fail-closed
  contract;
- disposable DB has exactly the expected public schema tables and no row in any discovered
  config/asset/menu/order/outbox application table after smoke;
- patch whitespace validation passes.

### Dataset and browser acceptance

- approved dataset mapping/import report is attached and contains no unresolved blocking
  field or destination ambiguity;
- config/menu/metadata/branding match the approved source and assets are reachable;
- persisted cart survives reload, rejects corruption/stale menu and never persists PII;
- checkout covers every enabled order type and disabled feature path;
- double submit creates one order; server totals and snapshots are authoritative;
- notification failure does not lose the order and retry/dead-letter is observable;
- keyboard, responsive viewport, visual theme and automated accessibility checks pass.

### Non-functional acceptance

- the approved load profile, dataset size, environment and percentile evidence are
  recorded before comparing results with targets;
- no high/critical unresolved security finding or PII/secret logging evidence remains;
- readiness, trace correlation, bounded metric labels and outbox backlog/failure signals
  are visible in staging;
- rollback prerequisites and a no-real-notification mode are demonstrated before cutover.

## Acceptance commands

```text
pnpm validate:full
pnpm validate:acceptance
docker compose config --quiet
docker compose --profile assets --profile observability config --quiet
git diff --check
```

Final evidence additionally runs the applicable manual/load/browser commands named in
the matrix after their dependencies exist.

## Failure and edge cases

- Docker unavailable: foundation is blocked, not silently downgraded; `--skip-smoke` is
  diagnostic only.
- Missing jar: smoke fails with an explicit instruction boundary; aggregate ordering
  builds it first.
- Application start failure: last bounded application output is printed, container and
  process are cleaned up. A hung Docker/Java/psql/taskkill command fails at its named
  finite timeout instead of hanging validation.
- Interrupted/timed-out `docker run`: cleanup searches both the unique run label and exact
  random name, removes every owned match within a bounded deadline and proves absence.
- Schema drift: any missing expected table, retained legacy table or unreviewed new table
  fails even when all previously known tables remain empty.
- Selected ports race with another process: run fails and cleanup occurs; rerun uses new
  free ports.
- Unknown/empty content never receives a fabricated default configuration.
- Existing working-tree changes from other tasks are not overwritten; evidence records
  dirty state and integration ownership.
- Historical V1-V7 occurrences do not authorize the forbidden term in V8+, Java, web,
  OpenAPI, infra or default configuration.
- A selected dataset without explicit notification destination approval stays blocked;
  no external call is used to validate it.

## Handoff report

```text
SC-VAL-001 handoff
Changed files: <exact grouped list>
Aggregate command: <command, exit code, duration>
Architecture guard: <files scanned, exceptions, result>
Disposable smoke: <image, Flyway version, probes/fail-closed/zero-row result>
Scenario matrix: <passed/blocked/not-run counts and artifact links>
CI changes: <exact jobs/steps>
Release blockers: <SC-DATA-001, SC-CART-001, staging/load/manual evidence>
Unresolved issues: <NONE or exact issue/owner>
Suggested next unlock: <dependency and owner action>
Git status: <short output; no commit>
```
