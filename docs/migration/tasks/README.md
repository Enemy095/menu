# Agent task registry

Статусы: `planned`, `ready`, `in_progress`, `review`, `done`, `blocked`, `superseded`.

| ID | Задача | Dependencies | Wave | Status |
|---|---|---|---:|---|
| PLAN-001 | Architecture rules и master migration plan | — | 0 | done |
| LEG-001 | Legacy inventory и parity baseline | — | 0 | done |
| BE-001/MOD-001 | Spring Boot scaffold и Modulith boundaries | PLAN-001 | 1 | done |
| API-001 | OpenAPI-first package и TS generation | PLAN-001, LEG-001 | 1 | superseded |
| INF-001 | Local PostgreSQL/assets/observability infrastructure | PLAN-001 | 1 | done |
| DB-001 | Flyway schema и JPA persistence baseline | BE-001, INF-001 | 2 | superseded |
| OBS-001 | Logs, traces, health и baseline metrics | BE-001 | 2 | done |
| CI-001 | Contract/build/migration/security gates | BE-001, API-001, INF-001 | 2 | done |
| TEN-001 | Trusted tenant resolver и context | DB-001, MOD-001 | 3 | superseded |
| AST-001 | S3-compatible tenant asset adapter | DB-001, TEN-001, INF-001 | 3 | superseded |
| SEC-001 | Security boundaries и rate limiting | BE-001, TEN-001 | 3 | superseded |
| CFG-001 | Restaurant/config public read API | API-001, TEN-001, DB-001 | 4 | superseded |
| MENU-001 | Published menu public read API | API-001, TEN-001, DB-001 | 4 | superseded |
| MIG-001 | Idempotent legacy tenant import | CFG-001, MENU-001, AST-001 | 4 | superseded |
| MIG-001A | Provisioning foundations и V5 provenance | MIG-001 Phase A, DB-001, TEN-001, AST-001 | 4B | superseded |
| MIG-001B | Strict parser и zero-write dry-run | MIG-001A | 4B | superseded |
| MIG-001C | Staged apply и gated verification | MIG-001A, MIG-001B | 4C | superseded |
| SC-000 | Single-client ADR, AGENTS и replan | — | P0 | done |
| SC-DB-001 | Append-only single-client schema pivot | SC-000, INF-001 | P1 | done |
| SC-API-001 | Single-client OpenAPI и generated client | SC-000 | P1 | done |
| SC-BE-001 | Site/asset modules и удаление tenancy runtime | SC-DB-001, SC-API-001 | P2 | blocked |
| SC-SEC-001 | Single-client public/admin security boundary | SC-API-001 | P2 | ready |
| SC-DATA-001 | Импорт одного выбранного legacy dataset | SC-BE-001, client selection | P3 | blocked |
| SC-WEB-001 | Next.js SSR config/menu frontend | SC-API-001, SC-DATA-001 | P4 | blocked |
| SC-CART-001 | Single-client persisted cart и checkout | SC-WEB-001 | P5 | blocked |
| SC-ORD-001 | Orders, global idempotency и totals | SC-BE-001, SC-API-001, SC-SEC-001 | P5 | blocked |
| SC-OUT-001 | Transactional outbox worker | SC-ORD-001 | P6 | blocked |
| SC-VAL-001 | Full-system validation | all SC implementation tasks | P7 | blocked |
| SC-CUTOVER-001 | Production cutover и legacy retirement | SC-VAL-001 | P8 | blocked |
| WEB-001 | Next.js SSR tenant shell | API-001 | 5 | superseded |
| WEB-002 | Branding/menu/contact/delivery UI | WEB-001, CFG-001, MENU-001 | 5 | superseded |
| CART-001 | Tenant-scoped persisted cart | WEB-002 | 6 | superseded |
| CHECKOUT-001 | Checkout form и generated API client | CART-001, ORD-001 | 6/7 | superseded |
| ORD-001 | Transactional order API/idempotency | CFG-001, MENU-001, SEC-001 | 7 | superseded |
| OUT-001 | Transactional outbox worker | ORD-001 | 7 | superseded |
| VAL-001 | Full system validation | all implementation tasks | 8 | superseded |
| CUTOVER-001 | Canary, rollback и legacy retirement | VAL-001 | 8 | superseded |

## Agent contract

Каждый task-файл обязан содержать:

- objective и non-goals;
- dependencies и immutable decisions;
- exclusive file ownership;
- deliverables и interfaces;
- implementation sequence;
- required tests;
- acceptance commands;
- failure/edge cases;
- handoff report format.

Агент перед завершением сообщает:

```text
Task ID
Changed files
Decisions made within allowed scope
Commands run and results
Unresolved issues
Suggested next dependency unlocks
```

Агент не коммитит и не пушит изменения, если task явно этого не требует. Integration commit выполняет оркестратор после review.
