# SC-DATA-001 — selected single-client dataset

Status: `review`
Decision: `STAMBUL_APPROVED`
Wave: P3
Dependencies: `SC-BE-001`, owner dataset decision
Production publication: `blocked by explicit deployment overrides/approvals`

## Objective and result

Реализован deterministic, idempotent, non-HTTP importer ровно одного owner-approved
Stambul dataset в single-client schema. Approved normalized bundle содержит один
restaurant/config/menu, 4 категории и 23 позиции в точном legacy order с точными
текстами и minor-unit prices. Adamas не является runtime mode и отсутствует в bundle,
database graph и object publication.

Local/test import полностью исполним на disposable PostgreSQL + S3-compatible storage.
Production apply намеренно требует отдельные реальные timezone/canonical URL и
traceable approvals; local placeholders не могут стать production defaults.

Основные артефакты:

- [approved bundle](../data/stambul-approved-v1.json);
- [independent expected graph](../../fixtures/stambul/expected-normalized-v1.json);
- [fixture verifier](../../fixtures/stambul/verify-expected-normalized-v1.mjs);
- [operator guide](../data/README.md);
- [decision record](../data/dataset-decision-checklist.md);
- [source inventory](../data/source-inventory.md);
- [target mapping](../data/final-schema-mapping.md).

## Immutable decisions

- Selection is `STAMBUL_APPROVED`; bundle SHA-256 is
  `7b04313f8907b44820d9eccf2490cea118504e68be2ea20ffef6186df6955b40`.
- Canonical source inventory digest is
  `aec158b37b08ed9c1166d0023c5c22171fd48fdad5770b3c8583d99b8044173a`.
- Text source authority is the canonical Git LF representation. Existing pre-attribute
  CRLF/CR worktrees normalize in memory before comparison. Logo bytes remain raw.
- UUIDv5 namespace is `e9b2ab7a-0a0a-5309-9403-39ed9ec91d4b`.
- Exact `Пицца    4 сыра` spacing is preserved.
- Approved public-contract normalizations are muted `#7C6A64` and font
  `Georgia, Times New Roman, serif`.
- Logo alt text is preserved separately as `Логотип ресторан-кафе «У Ризвана»`; it is
  never substituted with display name.
- Append-only Flyway V9 adds nullable `site_branding.logo_alt`, backfills existing rows
  from branding display name and rejects blank values; active public configuration
  requires a nonblank value.
- Local/test baseline is `ru-RU`, `RUB`, `Europe/Moscow`,
  `https://stambul.localhost`, daily 10:00–23:00, no fee/minimum/threshold, no
  notification destination and worker disabled.
- PostgreSQL is the source of truth; logo is immutable at
  `assets/logo/{sha256}.png` in private S3-compatible storage.
- Import never creates orders, outbox events, notification deliveries or destination.
- No production URL, timezone, secret or delivery destination is inferred.

## Non-goals

- Do not import or branch on Adamas.
- Do not store both legacy datasets, a tenant/client selector or a default compatibility
  row.
- Do not put business fixtures in Flyway or edit V1–V8 migrations.
- Do not invoke an HTTP import endpoint or enable the notification worker.
- Do not accept a raw WhatsApp phone, token or provider credential.
- Do not merge into an arbitrary non-empty content graph.
- Do not promise atomic S3 + PostgreSQL commit: an immutable unreferenced object after a
  late DB failure is explicitly tolerated and can be garbage-collected later.

## Implemented interfaces and ownership

```text
apps/api/src/main/java/com/salamu/menu/dataimport/**
apps/api/src/main/java/com/salamu/menu/asset/api/AssetImport*.java
apps/api/src/main/java/com/salamu/menu/asset/internal/application/AssetImportService.java
apps/api/src/test/java/com/salamu/menu/dataimport/**
docs/migration/data/**
docs/fixtures/stambul/**
.gitattributes
```

`ApprovedDatasetImporter` exposes `dryRun(request)` and `apply(request)`. The command
uses a dedicated minimal non-web Spring context: datasource, JdbcTemplate/JdbcClient,
transaction manager, Jackson, validation, asset storage and the restaurant/site/menu
JDBC read projections needed for post-commit hashes. It does not component-scan servlet
security, controllers, workers, JPA repositories, Flyway or observability exporters.

The migration module uses direct SQL across the final content graph as a bounded
one-time publication tool. Runtime bounded contexts retain their normal module
ownership; no runtime module imports `dataimport.internal`.

## Deterministic graph

| Resource | Count | Identity rule for immutable v1 |
|---|---:|---|
| site configuration | 1 | singleton 1 |
| asset/logo | 1 | UUIDv5 `asset:logo` + content-addressed key |
| branding/profile | 1 / 1 | singleton rows |
| features | 13 | fixed known feature keys |
| restaurant | 1 | UUIDv5 `restaurant:stambul` |
| menu version | 1 | UUIDv5 `menu:stambul:1` |
| categories | 4 | UUIDv5 `category:<publicId>` |
| items | 23 | UUIDv5 `item:<publicId>` |
| modifiers | 0 | source has none |
| destination/orders/outbox/deliveries | 0 | publication invariant |

Stable anchors:

```text
asset       41eec9b7-b68e-5c82-ac50-a988df90b415
restaurant  d4579042-8239-57df-b716-c27d3a6c213a
menu v1     db0d6a46-9145-55c7-af56-2795b510fa19
```

The v1 category/item names above are a one-time immutable compatibility rule. Future
menu v2 category/item UUID names must include menu version; asset names must include
kind + SHA-256; restaurant identity stays stable.

## Execution chain

```text
parse strict CLI
→ reject inherited Spring/app/JVM configuration contamination
→ start dedicated non-web importer context
→ resolve real bundle/root paths and reject symlink escape
→ verify pinned bundle digest and exact source inventory
→ canonicalize text EOL in memory, verify sizes/SHA-256
→ validate normalized semantics and deterministic UUIDs
→ validate logo through the production asset content policy
→ inspect target graph in SERIALIZABLE read transaction
→ dry-run: return READY/UNCHANGED with zero writes
→ apply: immutable S3 put-if-absent
→ SERIALIZABLE REQUIRES_NEW transaction + advisory xact lock
→ re-inspect and insert entire graph or return UNCHANGED
→ query canonical config/menu representation hashes
→ emit one redacted JSON result
```

Any mismatched bundle/source, partial/non-matching DB graph, repeated ID with different
semantics, production placeholder or unsafe path fails closed.

## CLI contract

From the repository root:

```powershell
.\gradlew.bat :apps:api:runDataImport --args="--bundle=docs/migration/data/stambul-approved-v1.json --asset-root=. --mode=dry-run --target=local-test"
.\gradlew.bat :apps:api:runDataImport --args="--bundle=docs/migration/data/stambul-approved-v1.json --asset-root=. --mode=apply --target=local-test"
```

Required environment variables are `DATA_IMPORT_JDBC_URL`, `DATA_IMPORT_DB_USER`,
`DATA_IMPORT_DB_PASSWORD`, `DATA_IMPORT_S3_ENDPOINT`, `DATA_IMPORT_S3_REGION`,
`DATA_IMPORT_S3_BUCKET`, `DATA_IMPORT_S3_ACCESS_KEY`, `DATA_IMPORT_S3_SECRET_KEY` and
`DATA_IMPORT_CDN_BASE_URL`. `DATA_IMPORT_S3_PATH_STYLE` defaults to `true`. The target
schema must already be migrated and the bucket must already exist: importer disables
Flyway and SQL initialization.

Production adds:

```text
--target=production
--timezone=<IANA>
--canonical-url=<real HTTPS origin>
--approval-artifact=<traceable id>
--approve-public-contact
--approve-schedule
--approve-asset-rights
--acknowledge-worker-disabled
```

Exit codes: `0` success (`READY`, `CREATED` or `UNCHANGED`), `2` invalid input,
`3` content conflict, `1` infrastructure/unexpected failure. Output never echoes
credentials.

## Required tests and evidence

- independent Node fixture checks exact sources, graph, 4/23 order/text/prices,
  approved normalizations, deterministic UUIDs and Adamas absence;
- portability check compares five canonical `HEAD:<path>` blobs, current checkout
  normalized bytes, SHA-256 and `.gitattributes` under Windows line-ending behavior;
- loader tests reject a changed bundle, changed namespace, Adamas source substitution,
  lexical escape and symlink traversal before asset access;
- command tests reject hostile inherited environment/system properties without printing
  their values;
- dedicated context test proves JdbcTemplate/JdbcClient/transaction/import beans exist
  and servlet security/controllers/worker/JPA/Flyway do not;
- disposable PostgreSQL + MinIO tests prove zero-write dry-run, create + no-op replay,
  concurrent apply, changed-graph conflict and late-failure DB rollback;
- integration reads public config/menu, exact ETags, 4/23 content, local values and
  legacy repeated spaces;
- S3 evidence proves one 43,728-byte PNG with exact content type/hash metadata;
- runtime table counts and Adamas source/asset searches remain zero.

The menu representation hash is
`24071fa64801b7e8d2695955c543839e603c9fd45633de242bd79e34b30e710b`.
The config representation includes the environment-owned CDN URL, so parity compares
the importer result to the API ETag in the same environment.

## Acceptance commands

```text
node --test docs/fixtures/stambul/verify-expected-normalized-v1.mjs
pnpm validate:acceptance
pnpm --filter @e-menu/api-contract validate
./gradlew :apps:api:test --tests "*DataImport*" --tests "*Site*" --tests "*Menu*" --tests "*Asset*"
./gradlew :apps:api:check
git diff --check
```

## Remaining production gate

Implementation and disposable evidence are ready for review. Production publication is
not authorized until every unchecked item in the
[decision record](../data/dataset-decision-checklist.md) production section is supplied.
`SC-CUTOVER-001` remains blocked independently. No code change is required merely to
keep notification disabled; enabling a real channel is a separate approved slice.

## Handoff report

```text
Task ID: SC-DATA-001
Decision: STAMBUL_APPROVED
Bundle SHA-256: 7b04313f8907b44820d9eccf2490cea118504e68be2ea20ffef6186df6955b40
Graph: 1 restaurant, 1 menu, 4 categories, 23 items, 1 logo
Validation: fixture + unit + PostgreSQL/MinIO + actual CLI acceptance
Public parity: config/menu body + ETag representation hashes
Unselected dataset: Adamas absent
Production unresolved: real timezone/domain/approvals; destination intentionally absent
```
