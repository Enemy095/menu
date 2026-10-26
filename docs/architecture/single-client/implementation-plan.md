# Single-client implementation and migration plan

Status: `in_progress`
Decision: [ADR-0003](../../adr/0003-single-client-deployment.md)

## 1. Outcome

Перевести текущий tenant-oriented backend и планируемый frontend в честное приложение
для одного клиента на deployment, сохранив:

- branding, contacts, schedule, SEO и theme;
- published versioned menu;
- preorder, delivery и pickup;
- cart и checkout;
- backend price calculation;
- idempotent order creation;
- transactional outbox и notification delivery;
- S3-compatible assets/CDN;
- OpenAPI-first и modular monolith.

Финальный runtime и schema не должны содержать tenant abstraction.

## 2. Current-state checkpoint

До pivot были реализованы Spring Boot scaffold, PostgreSQL/Flyway V1–V5, observability,
CI, tenant resolver/security/assets, config/menu reads и staged two-tenant import
foundation. Эти изменения не откатываются массово: полезные domain/application parts
переносятся, а tenant lifecycle/resolution/provenance удаляются контролируемо.

Two-tenant tasks `MIG-001*` и tenant-scoped `TEN/AST/CFG/MENU/SEC` помечены
`superseded`. Незавершённые parser/CLI файлы удалены до нового implementation wave.

## 3. Non-negotiable invariants

- Никакого constant/default tenant compatibility layer.
- Никакого механического `tenant` → `client` rename.
- Один deployment содержит один site configuration и один production dataset.
- Host не выбирает business data.
- Public API routes не содержат slug/tenant ID.
- PostgreSQL остаётся источником истины; Flyway append-only.
- Orders всегда пересчитываются backend.
- External notification не выполняется в order transaction.
- Existing public/business behavior покрывается characterization tests перед удалением.
- Каждый checkpoint проходит clean DB и upgrade V1–V5 tests.

## 4. Dependency graph

```text
SC-000 architecture pivot
       │
       ├───────────────┐
       ▼               ▼
 SC-DB-001        SC-API-001
       └───────┬───────┘
               ▼
           SC-BE-001
               │
          ┌────┴────┐
          ▼         ▼
      SC-SEC-001  client dataset selection
          │         │
          └────┬────┘
               ▼
          SC-DATA-001
          ┌────┴────────────┐
          ▼                 ▼
      SC-WEB-001        SC-ORD-001
          │                 │
          ▼                 ▼
     SC-CART-001        SC-OUT-001
          └────────┬────────┘
                   ▼
              SC-VAL-001
                   ▼
            SC-CUTOVER-001
```

## 5. Execution waves

### P0 — architecture reset (`SC-000`)

Deliverables:

- ADR-0003 accepted; ADR-0001/0002 superseded.
- Root `AGENTS.md` expresses only single-client target.
- Impact audits for database/backend, API/security and frontend/delivery.
- Old task statuses reconciled.
- This implementation plan and bounded task files.
- No unfinished two-tenant code remains in worktree.

Gate:

- repository search distinguishes historical docs/migrations from active target;
- no agent follows obsolete tenant rules;
- `git diff --check` clean;
- architecture-only checkpoint commit.

### P1A — append-only schema pivot (`SC-DB-001`)

Objective: выполнить expand phase single-client relational model без потери reusable
data и без поломки текущего runtime до переключения Java-кода.

Required work:

1. Inventory V1–V5 tables, indexes, constraints and FKs.
2. Design append-only migration(s) for:
   - site configuration/branding/features;
   - restaurant and published menu;
   - assets and notification destinations;
   - orders/order items/outbox/deliveries;
   - removal of import ledger and tenant/domain lifecycle.
3. Define deterministic upgrade rule:
   - zero existing tenants: create empty single-client schema;
   - exactly one selected source dataset: migrate it;
   - more than one populated tenant without explicit selection: fail closed before
     destructive migration or use a separate pre-migration selection operation.
4. Создать `sc_*` shadow target без `tenant_id` в PK/UK/FK/indexes; legacy tables
   временно остаются для старого runtime.
5. Make order idempotency global.
6. Replace asset namespace with `assets/{kind}/{sha256}.{ext}`.
7. Validate no orphan references and exact current menu relationship.
8. Contract/drop/rename migration не входит в изолированный P1 commit: она добавляется
   вместе с SC-BE switch, чтобы каждый checkpoint оставался runnable.

Tests:

- clean V1→current migration;
- upgrade V1–V5→current on empty database;
- upgrade on one selected data set;
- fail-closed ambiguous two-data-set state;
- constraints for menu/assets/orders/outbox;
- current Flyway version assertion;
- schema metadata assertion that `sc_*` target tables contain no `tenant_id`;
- existing backend still boots against legacy tables until P2.

Restrictions:

- do not edit V1–V5;
- no business fixture in Flyway;
- do not delete legacy Java code in this task;
- migration must be restart-safe according to Flyway transaction semantics.

### P1B — public contract pivot (`SC-API-001`)

Objective: publish canonical single-client OpenAPI and regenerated TypeScript client.

Routes:

```text
GET  /api/v1/public/config
GET  /api/v1/public/menu
POST /api/v1/public/orders
```

Required work:

- remove tenant slug path parameters and tenant error codes;
- preserve restaurant/config/menu DTO business fields;
- define order request, idempotency header and response/error envelopes;
- keep internal identifiers opaque and expose only required resource IDs;
- regenerate client deterministically;
- add compatibility/lint/generated-diff tests;
- update contract fixtures, not server implementation yet.

Gate: OpenAPI lint/generation/check succeeds and no public schema contains tenant fields.

### P2 — backend single-client boundary (`SC-BE-001`)

Objective: switch application code to the new schema/contracts and remove tenancy module.

Substeps:

1. Create `site` module owning branding/SEO/contacts/locale/timezone/features.
2. Create/refactor `asset` module owning asset metadata and S3 operations.
3. Refactor `restaurant`, `menu` and `notification` APIs to remove `TenantId`.
4. Replace tenant-scoped repository signatures with business identifiers.
5. Implement `/config` and `/menu` controllers against single-client read models.
6. Remove resolver/filter/context/domain-registry and suspended lifecycle.
7. Remove migration/provisioning APIs and V5 runtime code after data/schema callers switch.
8. Update Modulith allowed dependencies and delete obsolete named interfaces.
9. Remove tenant fields from logs, cache keys, asset paths and notification metadata.

Deletion order:

```text
new schema/read models
→ new site/asset APIs
→ switch restaurant/menu/notification callers
→ switch controllers
→ switch tests
→ remove tenant resolver/context
→ remove provisioning/migration module
→ remove tenancy module
→ prove zero active tenant imports/usages
```

Tests:

- Modulith verification;
- config/menu PostgreSQL integration tests;
- bounded SQL query counts and N+1 checks;
- asset MinIO roundtrip/new canonical key;
- normal context contains no migration/provisioning beans;
- repository/code search gate for tenant runtime symbols;
- clean/upgrade Flyway tests.

### P2B — single-client security (`SC-SEC-001`)

Objective: preserve security without host-based business resolution.

Required work:

- public and client-admin filter chains;
- trusted proxy/effective host validation only for transport security;
- canonical domain config, CORS/CSP/CSRF/cookie policy;
- order/auth rate limits with non-tenant keys;
- delete platform-admin/tenant-admin semantics;
- redact PII/secrets/provider errors;
- ensure feature configuration cannot grant privileges.

Tests include forged Host, forwarded headers, public→admin denial, secret/log redaction and
rate limit isolation by endpoint/client signal rather than tenant.

### P3 — one dataset import (`SC-DATA-001`)

Prerequisite: owner explicitly selects the production dataset/content. Structural work
may proceed before this choice; production import may not.

Required work:

- select exactly one of the approved legacy datasets or provide a new approved fixture;
- characterize visible menu/config/theme behavior;
- map it to site/restaurant/menu/asset/notification models;
- validate raw source/asset checksums;
- import via one non-HTTP, idempotent, single-client operation;
- never include raw destination secret; inject opaque reference at runtime;
- emit redacted result and run public read verification;
- remove temporary importer after migration window if it has no ongoing value.

There is no suspended tenant, batch activation or two-tenant atomicity. Publication is a
single-dataset transaction; S3/database boundary remains explicit and recoverable.

### P4 — frontend (`SC-WEB-001`)

Required work:

- scaffold pnpm/Next.js App Router if absent;
- route `/`, error/not-found/loading boundaries;
- server-side config/menu fetch via generated client or typed server adapter;
- CSS variables/design tokens;
- header/hero/contacts/schedule/menu/categories/items;
- responsive behavior and accessible navigation;
- metadata, canonical, sitemap and Open Graph;
- no tenant provider/slug route/client branches;
- SSR/cache tests and Playwright visual/a11y baseline.

### P5A — cart and checkout (`SC-CART-001`)

Required work:

- one Zustand store;
- versioned persisted cart without tenant ID/PII;
- stale menu reconciliation;
- order-type and quantity rules;
- RHF/Zod checkout form;
- pending/idempotency workflow;
- double-submit protection and recoverable errors;
- unit, persistence, accessibility and Playwright tests.

### P5B — ordering (`SC-ORD-001`)

Required work:

- implement generated OpenAPI endpoint;
- validate current restaurant/menu/order type;
- calculate prices/totals server-side;
- store item snapshots;
- global idempotency key + payload hash semantics;
- one transaction for order/items/outbox;
- concurrency tests on PostgreSQL;
- rate limit and public security integration.

### P6 — outbox worker (`SC-OUT-001`)

Required work:

- claim/lease poller using PostgreSQL;
- retry/backoff/attempt/dead-letter state;
- configured notification destination without tenant lookup;
- provider idempotency where supported;
- no notification network call in order transaction;
- recovery, crash, duplicate delivery and redaction tests;
- backlog/failure business metrics.

### P7 — validation (`SC-VAL-001`)

Required suites:

- backend unit/module/PostgreSQL/MinIO/contract/security;
- frontend lint/typecheck/unit/component/a11y/visual;
- Playwright happy/error/stale/double-submit flows;
- Flyway clean and V1–V5 upgrade;
- no tenant runtime symbol/schema/API assertions;
- load test public menu and concurrent order idempotency;
- secret scan, dependency scan and production builds.

Exit criteria:

- no duplicate orders under retry/concurrency;
- menu/config response matches approved dataset;
- notification outage never loses order;
- no PII/secrets in logs/bundle;
- tenant abstraction absent outside historical ADR/migration evidence.

### P8 — cutover (`SC-CUTOVER-001`)

Required work:

- backup and restore rehearsal;
- staging sign-off and selected dataset approval;
- canonical domain/TLS/CORS/CSP verification;
- production deployment and smoke;
- observation window with rollback thresholds;
- prevent old/new frontends from creating orders simultaneously;
- remove temporary importer and legacy only after rollback window;
- final architecture/task documentation reconciliation.

## 6. Parallel-agent ownership

At most three worker agents operate concurrently. Each receives a disjoint write scope.

Recommended batches:

1. P0 audits: database/backend; API/security; frontend/delivery.
2. P1: schema migration and OpenAPI contract in parallel; third agent writes independent
   characterization/search gates.
3. P2: site module, asset module and security tests only after shared contracts fixed.
4. P4/P5/P6: frontend shell, ordering domain and outbox design can overlap only after
   OpenAPI/schema contracts are frozen.

Agents never commit. Orchestrator reviews diffs, resolves shared contracts, runs gates
sequentially and creates one meaningful integration commit per checkpoint.

## 7. Commit checkpoints

```text
1. docs: adopt single-client architecture
2. feat(db): add single-client schema pivot
3. feat(api): expose single-client public contract
4. refactor(api): remove tenancy runtime and add site/asset modules
5. feat(data): import selected client dataset
6. feat(web): add single-client SSR menu
7. feat(ordering): add cart, checkout and idempotent orders
8. feat(notification): deliver transactional outbox
9. test: validate single-client cutover
10. chore: retire legacy after rollback window
```

## 8. Decision gates requiring owner input

Only two product decisions must not be inferred silently:

1. Which content dataset is the production client: Stambul, Adamas or a new approved
   dataset.
2. Which real notification provider/destination reference is configured at deployment.

Code, schema, API and frontend framework work continue independently. Production data
import and external delivery remain blocked until the corresponding choice/secret setup.
