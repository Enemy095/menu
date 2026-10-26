# Production dataset decision checklist

Status: `STAMBUL_APPROVED; local/test executable; production overrides required`
Task: [SC-DATA-001](../tasks/SC-DATA-001.md)
Decision date: `2026-07-14`
Decision artifact: `owner-directive:codex-thread:2026-07-14`

Этот record разделяет уже принятое решение о content dataset и ещё не выданное
разрешение на production publication. Local/test baseline можно воспроизводимо
валидировать и публиковать в disposable environment. Production apply остаётся
fail-closed, пока оператор не передаст все значения и approvals из последнего раздела.

## 1. Dataset selection — resolved

- [x] Выбран ровно один outcome: `STAMBUL_APPROVED`.
- [x] Adamas должен отсутствовать в normalized bundle, БД и object storage.
- [x] Decision owner: product/repository owner в текущем Codex thread.
- [x] Canonical selected-source digest:
  `aec158b37b08ed9c1166d0023c5c22171fd48fdad5770b3c8583d99b8044173a`.
- [x] Approved normalized bundle:
  `docs/migration/data/stambul-approved-v1.json`.
- [x] Bundle SHA-256:
  `7b04313f8907b44820d9eccf2490cea118504e68be2ea20ffef6186df6955b40`.

Source digest is SHA-256 of the canonical ordered JSON source inventory. The five text
sources use canonical Git LF bytes; existing CRLF clones are normalized in memory.
The PNG is always hashed as raw binary bytes.

## 2. Approved content and normalizations

- [x] Public identity/contact from the Stambul legacy source is approved for local/test.
- [x] Locale/currency are `ru-RU` / `RUB`.
- [x] Exactly one restaurant and one published menu version are selected.
- [x] Category order is `rolls`, `pizza`, `sides`, `burgers`.
- [x] All 23 item IDs, names, descriptions, order and minor-unit prices are frozen.
- [x] Exact `Пицца    4 сыра` spacing is preserved.
- [x] All items are available; modifiers and item/category images are absent.
- [x] Order types are preorder/default, delivery and pickup.
- [x] Limits are 99/item, 200 total, name 80, phone 32, address 240, comment 500,
  delayed confirmation 15 minutes.
- [x] Fee policy is `none`; minimum and free-delivery threshold are absent.
- [x] `(0,0)` coordinates are discarded and `geo_location` is disabled.
- [x] Stambul logo usage is approved for local/test; no hero image is invented.
- [x] Logo alt is persisted separately as `Логотип ресторан-кафе «У Ризвана»`.
- [x] Muted text is explicitly normalized from `#7E6C66` to `#7C6A64` for the public
  design-token contrast rule.
- [x] Font token is explicitly normalized to `Georgia, Times New Roman, serif` because
  the current contract rejects embedded CSS quotes.

## 3. Local/test baseline — resolved

```text
timezone: Europe/Moscow
canonical URL: https://stambul.localhost
schedule: Monday–Sunday 10:00–23:00
public contact: selected legacy public phone
notification destination: absent
worker: disabled
delivery fee: none
minimum/free threshold: absent
```

The enabled legacy `whatsapp_fallback` feature is preserved as config parity, but it
does not create a destination and cannot send anything while the worker is disabled.
No raw manager number or provider credential enters the bundle, output or logs.

## 4. Deterministic identity — resolved for immutable v1

UUIDv5 namespace: `e9b2ab7a-0a0a-5309-9403-39ed9ec91d4b`.

| Resource | UUID name | UUID |
|---|---|---|
| logo asset | `asset:logo` | `41eec9b7-b68e-5c82-ac50-a988df90b415` |
| restaurant | `restaurant:stambul` | `d4579042-8239-57df-b716-c27d3a6c213a` |
| menu v1 | `menu:stambul:1` | `db0d6a46-9145-55c7-af56-2795b510fa19` |

Category UUID names are `category:<publicId>` and item UUID names are
`item:<publicId>` for this one-time immutable v1 graph. This is not a reusable naming
rule for future versions. A future menu v2 must include the menu version in category
and item UUID names; asset names include kind + SHA-256; the restaurant UUID remains
stable.

## 5. Publication readiness — demonstrated locally

- [x] Pinned source/bundle/fixture verification is executable and exact.
- [x] Dry-run has zero schema, row and S3 writes.
- [x] Apply writes the complete graph in one serializable DB transaction.
- [x] Identical rerun returns `UNCHANGED`; changed target graph returns conflict.
- [x] Concurrent identical apply is serialized by a PostgreSQL transaction advisory
  lock and produces one graph/object.
- [x] Late database failure rolls back the whole public graph; an unreferenced immutable
  S3 object is an allowed cleanup candidate.
- [x] No orders, outbox events, deliveries or notification destinations are created.
- [x] `/api/v1/public/config` and `/api/v1/public/menu` are compared with the approved
  fixture and their ETags/representation hashes.
- [x] Adamas path, asset hash, content and runtime selector are absent.

The menu representation hash is
`24071fa64801b7e8d2695955c543839e603c9fd45633de242bd79e34b30e710b`.
Config hash changes when the environment-owned CDN base changes; importer result and API
ETag must match within the same run.

## 6. Production gate — intentionally unresolved

Before `--target=production`, an authorized operator must provide all of the following;
the importer rejects the command if any item is absent:

- [ ] real IANA business timezone via `--timezone`;
- [ ] real canonical non-local HTTPS origin via `--canonical-url`;
- [ ] traceable production approval artifact via `--approval-artifact`;
- [ ] approval of the public contact via `--approve-public-contact`;
- [ ] approval of the seven-day schedule via `--approve-schedule`;
- [ ] approval of production asset rights via `--approve-asset-rights`;
- [ ] explicit acknowledgement that notification worker/destination remain disabled via
  `--acknowledge-worker-disabled`;
- [ ] pre-migrated target schema at current Flyway version and empty/matching content
  graph;
- [ ] pre-created private S3/MinIO bucket and protected credentials.

A production notification channel is not invented here. If delivery is required later,
its provider and opaque secret reference need a separate approved configuration slice;
the content importer never accepts a raw phone/token/credential.

Production publication must run dry-run first, archive the redacted result, and only
then run apply with the identical bundle and overrides. The production URL, timezone,
secret or notification destination must never be inferred from the local baseline.
