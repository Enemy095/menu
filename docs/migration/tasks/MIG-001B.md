# MIG-001B — manifest parser and zero-write dry-run

Status: `superseded`
Wave: 4B
Dependencies: `MIG-001A`, `MIG-001` Phase A, `ADR-0002`

## Objective

Реализовать временный `migration` Modulith module, строгий parser утверждённого
manifest/fixture и non-HTTP CLI в режиме `--dry-run`. Dry-run строит immutable
canonical import plan для Stambul и Adamas, сравнивает его с текущим состоянием через
module-owned inspection ports и возвращает redacted decision report без единой записи в
PostgreSQL или S3.

Результат задачи разблокирует `MIG-001C`, но не реализует и не разрешает `--apply`.

## Non-goals

- Не создавать ledger rows, tenant skeletons, assets, restaurants, menu или destinations.
- Не реализовывать apply, verify, cleanup, rollback, ingress gate или production cutover.
- Не выполнять legacy JavaScript и не импортировать значения напрямую из `config.js`.
- Не читать чужие таблицы, JPA entities или `internal` packages.
- Не читать secret material и не выводить opaque secret reference.
- Не поднимать HTTP server, worker, scheduler или admin endpoint.
- Не изменять legacy-каталог, manifest, fixture или применённые Flyway migrations.

## Immutable decisions

- Единственный semantic input — versioned sanitized fixture; raw legacy files
  используются только для byte-level checksum verification.
- Manifest paths разрешены только внутри resolved absolute legacy root; `..`, `.`,
  backslash, empty segment, symlink escape и undeclared file запрещены.
- Parser strict: неизвестные schema versions, fields, enum values и duplicate keys
  отклоняются. Silent defaults и best-effort parsing запрещены.
- Manifest/fixture читаются как raw bytes, strict UTF-8 without BOM; hashes считаются до
  JSON mapping.
- Все business hashes используют versioned canonicalization algorithm, ordinal ordering,
  explicit null markers и length-prefix encoding. Locale/filesystem/map iteration order и
  runtime timestamps не участвуют.
- Dry-run зависит только от `tenancy::api`, `restaurant::api`, `menu::api` и
  `notification::api`; все inspection calls zero-write и поддерживают active/suspended.
- `required-secret:*` marker не является destination. Он сопоставляется с externally
  supplied opaque reference и проверяется только через metadata readiness port.
- Production apply в этой задаче всегда недоступен независимо от flags/profile.

## Exclusive ownership

Агент владеет только:

- `com.salamu.menu.migration` package/module declaration;
- manifest/fixture DTO, strict loader, validators, canonical plan/hash types;
- inspection orchestrator и decision classifier;
- redacted report/error model;
- `legacy-import` profile command wiring только для `--dry-run`;
- unit/CLI/Testcontainers read-only tests и profile configuration.

Не менять module-owned provisioning implementations `MIG-001A`, public controllers,
OpenAPI/generated client, V1–V5, Phase A artifacts или legacy source files.

## CLI contract

Запуск разрешён только при одновременном выполнении условий:

```text
profile includes legacy-import
spring.main.web-application-type=none
exact mode = --dry-run
explicit --manifest <repository-relative path>
explicit --environment <approved non-secret alias>
```

Unknown/duplicate arguments, второй mode, `--apply`, `--force`, interactive prompt,
production mode alias или web application type завершают process до parse/inspection с
non-zero exit code. Обычный `bootRun`, tests и web startup не создают и не запускают
import runner.

Рекомендуемая команда после реализации:

```text
./gradlew :apps:api:bootRun --args="--spring.profiles.active=local,legacy-import --spring.main.web-application-type=none legacy-import --dry-run --manifest docs/migration/import/manifest-v1.json --environment local"
```

## Input verification sequence

1. Resolve repository root and approved import directory without following an escape.
2. Read manifest and fixture as bytes; reject BOM/invalid UTF-8/oversize input.
3. Verify exact schema version, manifest ID and declared fixture path/hash.
4. Resolve every declared legacy source strictly inside `Электронное меню/`.
5. Verify raw byte length, SHA-256, per-tenant/global set hashes and complete set hash.
6. For assets verify PNG signature, IHDR, dimensions, content type, declared kind and size.
7. Reject undeclared semantic sources and path aliases resolving to the same file.
8. Strict-map fixture and reject unknown fields, duplicates and ambiguous numbers.
9. Re-run all semantic invariants before any inspection call.

Minimum expected facts are fixed by the approved manifest:

- exactly two tenants: `stambul`, `adamas`;
- one active default restaurant per planned tenant;
- 8 categories total and 42 items total: Stambul 23, Adamas 19;
- tenant-scoped stable category/item IDs, exact source order and membership;
- integer non-negative legacy prices converted exactly to `priceMinor = price * 100` with
  overflow checks and no floating-point arithmetic;
- locale `ru-RU`, currency `RUB`, versioned published timestamps;
- exactly 13 feature rows per tenant;
- enabled order-type codes match manifest and exactly one enabled type is default;
- 3 declared asset roles with exact hashes/metadata;
- destination fields contain only approved `required-secret:*` markers.

## Immutable plan

Parser produces records, not persistence entities or JSON nodes:

```text
LegacyImportPlan
  manifest identity and canonical hashes
  environment alias
  ordered TenantPlan[2]
    tenant/config/profile/features/order types
    default RestaurantPlan
    ordered CategoryPlan / ItemPlan
    AssetPlan references
    DestinationRequirement marker
  exact promotion requirements
```

Internal tenant UUID, asset ID and destination ID are absent before inspection. Every
plan collection is immutable and deterministically ordered. Plan exposes versioned
hashes for config, menu, features, destinations, assets and complete semantics.

## Secret readiness

Before database inspection, each manifest marker must map through explicit runtime
configuration to an opaque reference with an allowlisted scheme such as `secret://`.
Notification-owned readiness port checks existence/enabled version/channel/environment
using metadata only. It must not resolve destination bytes or return the reference.

Report stores only marker name, readiness `PRESENT|MISSING|INCOMPATIBLE` and an optional
non-reversible fingerprint defined by the notification module. Missing readiness yields
`PROMOTION_BLOCKED`; malformed/raw phone/token input yields validation error. Dry-run
still performs zero writes.

## Inspection and classification

For each normalized slug, migration calls public inspection ports only:

- tenancy: lifecycle, run provenance, canonical config/features hash;
- restaurant: default restaurant identity/hash;
- menu: current/versioned ordered snapshot hash and counts;
- notification: destination semantic/reference fingerprint readiness;
- asset provisioning inspection: tenant/kind/hash/size/registry state without object key.

Inspection ports must not use `REQUIRES_NEW`, cache-fill writes or external side effects.
They return typed canonical snapshots/hashes and explicitly distinguish absent,
suspended and active state.

Classifier returns exactly one decision per batch and per tenant:

- `CREATE` — target/provenance absent and plan is valid;
- `RESUME` — matching run-owned suspended state can continue from a known stage;
- `UNCHANGED` — both active tenants match the complete approved semantic plan;
- `CONFLICT` — slug/provenance/version/hash/ownership differs or state is ambiguous;
- `PROMOTION_BLOCKED` — semantics may match, but readiness/confirmation is missing.

One matching tenant plus one conflicting/foreign tenant is batch `CONFLICT`, never
partial create. Partial rows without accepted V5 provenance are not adopted.

## Redacted report and exit codes

Allowed report fields: manifest/schema versions, environment alias, source/semantic
hashes, tenant slug, lifecycle/action, counts, public-field diff paths, asset kind/hash/
size, promotion marker and readiness status.

Forbidden: raw JSON/JS, environment dump, phone values, opaque reference URI, secret,
credentials, signed URL, canonical object key, DB URL, internal tenant/asset IDs and
arbitrary provider/driver exception messages.

Success (`CREATE|RESUME|UNCHANGED` with no blocker) exits `0`. Validation, conflict,
readiness failure, dependency failure or forbidden mode exits non-zero with stable
redacted error code. `PROMOTION_BLOCKED` is reported distinctly and cannot be bypassed.

## Required tests

- Golden manifest/fixture produces exactly 2 tenants, 8 categories, 42 items and the
  same hashes/facts as `verify-preflight.mjs`.
- Every source hash/size/set-hash/path/encoding/PNG metadata mutation fails closed.
- Traversal, backslash, symlink escape, duplicate resolved file and undeclared file fail.
- Unknown JSON field/version, fractional/overflow price, duplicate ID, wrong membership,
  wrong feature/default/asset/secret marker fail.
- Canonical hashes are stable across map insertion order, locale and timezone.
- Fake inspection states cover the full decision matrix and mixed-batch conflicts.
- PostgreSQL/S3 spies prove dry-run creates zero rows, transactions, PUT/DELETE/HEAD calls.
- Notification fake proves readiness metadata only and no secret/reference reaches report.
- CLI tests cover exact arguments, non-web profile, exit codes and production/apply denial.
- Context/route test proves no importer bean on normal startup and no HTTP mapping.
- Modulith verification proves outbound-only API dependencies and no cycles.
- Log capture rejects all forbidden values even on nested provider/parser exceptions.

## Failure and edge cases

- File changes between checksum and semantic read are prevented by parsing verified bytes,
  not reopening the file.
- Inspection changing concurrently is represented as a dry-run snapshot; apply must
  re-inspect under ADR locks and never trusts dry-run as authorization.
- Unknown active tenant, foreign suspended provenance and one-of-two applied state conflict.
- Readiness outage is not treated as missing secret and never falls back to raw marker.
- Large/recursive JSON input is bounded before mapping.
- Report write failure does not trigger any mutation and returns non-zero.

## Acceptance commands

```text
node docs/migration/import/verify-preflight.mjs
./gradlew --stop
./gradlew :apps:api:test --tests "*LegacyImport*" --tests "*Migration*"
./gradlew clean :apps:api:check :apps:api:bootJar
git diff --check
```

## Definition of Done

- Strict parser and canonical plan reproduce the checked-in preflight exactly.
- Dry-run uses only public inspection/readiness ports and performs provably zero writes.
- CLI is non-web, profile-gated, implements only `--dry-run` and rejects production/apply.
- Decisions and errors are deterministic, batch-aware and redacted.
- All unit, Modulith, PostgreSQL/S3 no-write, CLI and full backend checks pass.
- `MIG-001C` remains blocked until `MIG-001A` and this task are integrated and reviewed.

## Handoff report

```text
Task ID: MIG-001B
Changed files:
Parser/canonicalization decisions:
Inspection/readiness ports used:
Zero-write evidence:
Commands and exact results:
Security/redaction checks:
Unresolved issues:
Suggested next unlock: MIG-001C
```

Агент не коммитит и не пушит. Integration commit выполняет оркестратор.
