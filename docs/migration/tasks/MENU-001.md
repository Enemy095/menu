# MENU-001 — published menu public API

Status: `ready`
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
- ETag `menu-{tenantSlug}-v{version}`.
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

Contract/isolation/query-count tests и Gradle check проходят; ETag меняется только с published version/data revision согласно policy.
