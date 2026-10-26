# MENU-001 — published menu public API

Status: `superseded`
Wave: 4  
Dependencies: `API-001`, `TEN-001`, `DB-001`, `MOD-001`; final asset URLs require `AST-001`

## Objective

Реализовать `GET /api/v1/public/{tenantSlug}/menu`, возвращающий только текущую published version в детерминированном порядке и с bounded SQL query count.

## Deliverables

- Query projection/read repository, не entity graph serialization.
- OpenAPI-compatible controller/mapper.
- Restaurant ID, version, publishedAt, currency, ordered categories/items.
- Stable public IDs и `priceMinor`.
- Image URL через asset resolver.
- Strong ETag `menu-{tenantSlug}-v{version}-{sha256}` from every ordered public field and resolved asset URL.
- Browser cache 30s, shared 300s, stale-while-revalidate 60s.

## Tests

- Только current PUBLISHED version возвращается.
- No published menu → controlled 404.
- Exact Stambul 23 / Adamas 19 items.
- Exact category/item ordering.
- Minor-unit prices равны legacy price × 100.
- Query count не растёт с количеством items.
- Cross-tenant public IDs не приводят к data leakage.

## Acceptance

Contract/isolation/query-count tests и Gradle check проходят; ETag стабилен для одинакового public response и меняется при любой published data/asset representation revision. Exact legacy 23/19 acceptance закрывается import E2E в `MIG-001`.

## Review state

Published read API, fail-closed validation, tenant isolation, fixed query count and representation ETag are implemented. Exact Stambul 23 / Adamas 19 imported-response acceptance remains coupled to the `MIG-001` apply/E2E gate; the checked-in preflight fixture is enforced in CI meanwhile.
