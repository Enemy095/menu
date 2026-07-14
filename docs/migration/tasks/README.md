# Agent task registry

Статусы: `planned`, `ready`, `in_progress`, `review`, `done`, `blocked`.

| ID | Задача | Dependencies | Wave | Status |
|---|---|---|---:|---|
| PLAN-001 | Architecture rules и master migration plan | — | 0 | done |
| LEG-001 | Legacy inventory и parity baseline | — | 0 | done |
| BE-001/MOD-001 | Spring Boot scaffold и Modulith boundaries | PLAN-001 | 1 | done |
| API-001 | OpenAPI-first package и TS generation | PLAN-001, LEG-001 | 1 | done |
| INF-001 | Local PostgreSQL/assets/observability infrastructure | PLAN-001 | 1 | done |
| DB-001 | Flyway schema и JPA persistence baseline | BE-001, INF-001 | 2 | done |
| OBS-001 | Logs, traces, health и baseline metrics | BE-001 | 2 | done |
| CI-001 | Contract/build/migration/security gates | BE-001, API-001, INF-001 | 2 | done |
| TEN-001 | Trusted tenant resolver и context | DB-001, MOD-001 | 3 | done |
| AST-001 | S3-compatible tenant asset adapter | DB-001, TEN-001, INF-001 | 3 | done |
| SEC-001 | Security boundaries и rate limiting | BE-001, TEN-001 | 3 | done |
| CFG-001 | Restaurant/config public read API | API-001, TEN-001, DB-001 | 4 | review |
| MENU-001 | Published menu public read API | API-001, TEN-001, DB-001 | 4 | review |
| MIG-001 | Idempotent legacy tenant import | CFG-001, MENU-001, AST-001 | 4 | in_progress |
| WEB-001 | Next.js SSR tenant shell | API-001 | 5 | planned |
| WEB-002 | Branding/menu/contact/delivery UI | WEB-001, CFG-001, MENU-001 | 5 | planned |
| CART-001 | Tenant-scoped persisted cart | WEB-002 | 6 | planned |
| CHECKOUT-001 | Checkout form и generated API client | CART-001, ORD-001 | 6/7 | planned |
| ORD-001 | Transactional order API/idempotency | CFG-001, MENU-001, SEC-001 | 7 | planned |
| OUT-001 | Transactional outbox worker | ORD-001 | 7 | planned |
| VAL-001 | Full system validation | all implementation tasks | 8 | planned |
| CUTOVER-001 | Canary, rollback и legacy retirement | VAL-001 | 8 | planned |

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
