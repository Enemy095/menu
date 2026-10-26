# VAL-001/CUTOVER-001 — system validation, canary and legacy retirement

Status: `superseded`
Wave: 8  
Dependencies: all implementation tasks

## Objective

Доказать parity, isolation, reliability и operability новой платформы, затем безопасно переключить tenants с проверенным rollback.

## Full validation matrix

- Platform path, subdomain и verified custom domain.
- Unknown/pending/disabled domain and inactive tenant.
- Config/menu contract, ETag/cache isolation and targeted invalidation.
- Exact Stambul 23 / Adamas 19 item parity and visual snapshots.
- Responsive menu/cart/checkout and accessibility.
- Storage corruption, schema migration, tenant switch and stale menu.
- All order types/features/limits.
- Wrong total, unavailable item, validation/rate/network/internal errors.
- Concurrent double submit and conflicting idempotency key.
- Notification failure, worker restart and stale lease.
- DB unavailable readiness behavior.
- Cross-tenant read/write/cache/asset/job attacks.
- PII/security header/secret scans.

## Initial staging performance targets

- Cached public GET p95 < 150 ms at application edge.
- Uncached DB-backed menu p95 < 300 ms.
- Create order p95 < 500 ms excluding external notification.
- Zero duplicates in concurrency test.
- Zero cross-tenant access in isolation suite.

Targets are staging release gates, not permanent universal SLA; measurement setup and load profile must be recorded.

## Canary

1. Rehearse database backup/restore and frontend/backend rollback.
2. Deploy staging with imported tenants and synthetic destinations.
3. Enable one tenant canary with mutually exclusive traffic; legacy and new UI never both submit real order for one action.
4. Compare order counts/errors, conversion, Web Vitals, API latency, idempotent replays and outbox backlog.
5. Observe agreed rollback window.
6. Promote second tenant.
7. Freeze legacy, retain rollback artifact, then remove in a separate change.

## Production checklist

- Confirm timezone, public contacts, canonical/custom domains and notification destinations for both tenants.
- Verify domain ownership/TLS/CSP/CORS/cookies/CDN.
- Verify monitoring dashboards/alerts/runbooks and on-call ownership.
- Verify secrets rotation and least privilege.
- Sign off parity, accessibility, security and data retention.

## Acceptance

- All automated gates green.
- Canary has no duplicate/lost orders or cross-tenant incident.
- Notification failures remain recoverable.
- Rollback completed successfully in rehearsal.
- Legacy deletion occurs only after explicit sign-off and rollback window.
