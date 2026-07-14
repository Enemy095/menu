# Stambul local acceptance fixture

This directory is an independent, executable acceptance oracle for the approved
`STAMBUL_APPROVED` local/test selection. It does not authorize production publication
and it is not importer runtime code.

## Artifacts

- `expected-normalized-v1.json` freezes the normalized graph, source hashes, explicit
  absences, UUIDv5 policy and publication safety invariants.
- `verify-expected-normalized-v1.mjs` reads the legacy files in an isolated VM, verifies
  their bytes, and compares both the fixture and
  `docs/migration/data/stambul-approved-v1.json` to those sources.

The approved importer bundle SHA-256 is
`7b04313f8907b44820d9eccf2490cea118504e68be2ea20ffef6186df6955b40`; an importer must
reject any other bytes even if an edited bundle is internally self-consistent.
Repository `.gitattributes` pins the bundle and five approved legacy text sources to LF
and the PNG as binary. The verifier/importer canonicalizes CRLF/CR to LF in memory as a
compatibility measure for existing Windows worktrees, so the approved hashes are
identical across operating systems without editing the read-only legacy files.

Run:

```text
node --test docs/fixtures/stambul/verify-expected-normalized-v1.mjs
```

The gate expects exactly:

- one Stambul dataset and no Adamas source/asset reference;
- one 43,728-byte Stambul logo with the recorded SHA-256, and no other asset;
- four categories in order: `rolls`, `pizza`, `sides`, `burgers`;
- 23 items in exact source/category order with exact names/descriptions and integer
  RUB converted to minor units by `price * 100`;
- all items available, with no item/category image and no modifiers;
- the exact public Stambul identity/contact and seven daily 10:00–23:00 intervals;
- preorder (default), delivery and pickup in that order;
- limits 99/item, 200 total, name 80, phone 32, address 240, comment 500 and delayed
  confirmation 15 minutes;
- delivery enabled with an address required and `feePolicy=none`, without a minimum or
  free-delivery threshold;
- no favicon, hero image, Open Graph image, email, address, location, social links,
  promotions, loyalty, reservations, delivery blocks or notification destination;
- no order, order item, order status, outbox or notification delivery row created by
  publication.

The repeated spaces in `Пицца    4 сыра` are deliberately preserved.

## Local-only values and approved source deltas

`Europe/Moscow`, `https://stambul.localhost` and the legacy public phone are local/test
baseline values. Production must replace or re-confirm them; the fixture does not claim
they are current business truth. The notification worker remains disabled until a
production environment supplies an approved opaque destination reference.

Only these technical content changes are approved:

1. `mutedTextColor` changes from source-derived `#7E6C66` to `#7C6A64`. The source value
   has 4.386:1 contrast against `#F7F0E3` and the current frontend rejects it; the
   approved value has 4.516:1.
2. `Georgia, "Times New Roman", serif` becomes
   `Georgia, Times New Roman, serif` because the public safe-font grammar forbids CSS
   quote syntax.
3. Legacy `geoLocation=true` becomes false because `(0,0)` with an empty label means
   absent location. It must never publish Gulf of Guinea coordinates.

No other branding or menu text is silently normalized.

## Deterministic identity

The importer bundle freezes UUIDv5 namespace
`e9b2ab7a-0a0a-5309-9403-39ed9ec91d4b` and these names:

```text
asset:logo
restaurant:stambul
menu:stambul:1
category:<publicId>
item:<publicId>
```

The executable fixture checks replay stability, UUID version/variant, uniqueness across
all 30 IDs and three anchor UUIDs. A retry must therefore address the same rows and
object, not generate a second graph.

These names are frozen for the immutable v1 import only. A future publication that
retains multiple menu/asset versions must include the menu version in category/item UUID
names and the asset kind plus content SHA in an asset UUID name; otherwise the global
primary keys would collide. The restaurant name remains stable across content versions.

## Importer safety review

The planned operator interface is:

```text
./gradlew :apps:api:runDataImport --args="--bundle=docs/migration/data/stambul-approved-v1.json --asset-root=. --mode=dry-run --target=local-test"
./gradlew :apps:api:runDataImport --args="--bundle=docs/migration/data/stambul-approved-v1.json --asset-root=. --mode=apply --target=local-test"
```

Database/object-storage connection material must come only from this environment
boundary:

```text
DATA_IMPORT_JDBC_URL
DATA_IMPORT_DB_USER
DATA_IMPORT_DB_PASSWORD
DATA_IMPORT_S3_ENDPOINT
DATA_IMPORT_S3_REGION
DATA_IMPORT_S3_BUCKET
DATA_IMPORT_S3_ACCESS_KEY
DATA_IMPORT_S3_SECRET_KEY
DATA_IMPORT_S3_PATH_STYLE        # optional; defaults to true
DATA_IMPORT_CDN_BASE_URL
```

The command must make these values authoritative after removing inherited Spring/JVM
configuration that could redirect the database or object store, enable Flyway/worker,
or activate another profile. The bundle must never contain a password, raw manager
destination or provider credential.

Stable process outcomes are:

```text
exit 0  DATA_IMPORT_RESULT <redacted JSON result>
exit 2  DATA_IMPORT_INVALID <safe validation message>
exit 3  DATA_IMPORT_CONFLICT <safe conflict message>
exit 1  DATA_IMPORT_FAILED <exception class only>
```

Implementation acceptance remains fail-closed:

1. Resolve the repository root and every selected source with real-path checks. Only
   the six allowlisted Stambul paths may be read. Reject symlinks, path traversal,
   checksum/size mismatch, extra assets and any Adamas path/hash before external writes.
2. `dry-run` validates source bytes, bundle semantics, UUIDs, target readiness and
   resulting counts, but performs zero database/object-store writes. Flyway is disabled
   for this command; schema migration is a separate operator gate, and an outdated or
   missing schema fails the dry-run.
3. `apply` acquires a publication lock, revalidates the same immutable bundle, uploads
   the one content-addressed logo with put-if-absent semantics and verifies the stored
   size/hash.
4. Site, profile, features, restaurant and the published menu graph are written in one
   PostgreSQL transaction. Public readers see either the old/empty graph or the complete
   graph, never a partial publication.
5. An identical bundle replay reports `UNCHANGED` and performs zero writes. A different
   bundle against an existing graph reports `CONFLICT`; it must not merge, overwrite or
   select content by host/slug.
6. A database failure after immutable upload leaves no public database graph. The only
   tolerated residue is an unreferenced content-addressed object eligible for separately
   verified garbage collection.
7. Import never creates orders, order history, outbox events, notification deliveries or
   provider calls. No destination row is created for this worker-disabled baseline.

The runtime implementation must cover interrupted/concurrent apply, identical replay,
changed-digest conflict and injected failures before asset upload, after asset upload and
inside the database transaction. This fixture proves the input/output oracle; those
external-state properties belong in the importer PostgreSQL/object-storage integration
suite.
