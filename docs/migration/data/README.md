# Stambul approved data publication

This directory contains the only executable SC-DATA-001 content artifact:
`stambul-approved-v1.json`. It is a pinned, owner-approved, single-client Stambul
bundle. `source-inventory.md` and `final-schema-mapping.md` remain evidence and mapping
documentation; historical `docs/migration/import/**` is not an executable input.

## Safety model

- Run from the repository root through the Gradle `runDataImport` task.
- The command accepts only the pinned bundle digest and six exact source anchors.
- Five text sources are hashed as canonical Git LF; current CRLF/CR clones normalize in
  memory. The logo is raw binary.
- Real paths must remain below `--asset-root`; symlink and lexical escapes are rejected.
- Dry-run validates source bytes, asset policy, target approvals and DB state without
  DB/schema/S3 writes.
- Apply uploads one immutable content-addressed logo, then publishes the entire DB graph
  in a serializable transaction protected by an advisory transaction lock.
- Exact replay is `UNCHANGED`; any different or partial content graph is `CONFLICT`.
- Flyway and SQL initialization are disabled. Migrate the database separately first.
- Notification worker is disabled and no notification destination is created.

## Prerequisites

1. PostgreSQL schema is already at the repository's current Flyway version (V9).
2. Content/runtime tables are empty, or the content graph exactly equals this bundle.
3. A private S3-compatible bucket already exists. The importer never creates a bucket.
4. Object credentials can read/head/write the configured bucket but need no bucket-list
   exposure to the public application.
5. The configured CDN base is the public immutable asset base; origin credentials and
   signed URLs are not exposed.

Set these environment variables in the operator shell:

```text
DATA_IMPORT_JDBC_URL
DATA_IMPORT_DB_USER
DATA_IMPORT_DB_PASSWORD
DATA_IMPORT_S3_ENDPOINT
DATA_IMPORT_S3_REGION
DATA_IMPORT_S3_BUCKET
DATA_IMPORT_S3_ACCESS_KEY
DATA_IMPORT_S3_SECRET_KEY
DATA_IMPORT_CDN_BASE_URL
DATA_IMPORT_S3_PATH_STYLE=true   # optional; default true
```

The command rejects inherited `SPRING_*`, `APP_*`, `SERVER_*`, `MANAGEMENT_*`,
`DATABASE_*`, `ASSET_*`, `OTEL_*`, `LOGGING_*` and JVM option overrides. Use a clean
operator shell and only the `DATA_IMPORT_*` interface above.

## Local/test procedure

```powershell
.\gradlew.bat :apps:api:runDataImport --args="--bundle=docs/migration/data/stambul-approved-v1.json --asset-root=. --mode=dry-run --target=local-test"
.\gradlew.bat :apps:api:runDataImport --args="--bundle=docs/migration/data/stambul-approved-v1.json --asset-root=. --mode=apply --target=local-test"
.\gradlew.bat :apps:api:runDataImport --args="--bundle=docs/migration/data/stambul-approved-v1.json --asset-root=. --mode=apply --target=local-test"
```

Expected outcomes are `READY`, then `CREATED`, then `UNCHANGED`. Each successful line
starts with `DATA_IMPORT_RESULT` and contains redacted JSON with selection, bundle and
effective digests, deterministic IDs, 4/23 counts, asset digest and—after publication—
config/menu representation hashes.

Exit codes:

| Code | Meaning |
|---:|---|
| 0 | valid `READY`, `CREATED` or `UNCHANGED` result |
| 2 | invalid CLI, approval, path, bundle or source |
| 3 | target content graph conflict |
| 1 | infrastructure or unexpected failure |

## Production procedure

Do not reuse local/test timezone or URL. First complete the production section of the
[decision checklist](dataset-decision-checklist.md), then run dry-run and apply with the
same exact overrides:

```powershell
$productionArgs = "--bundle=docs/migration/data/stambul-approved-v1.json --asset-root=. --target=production --timezone=<IANA> --canonical-url=<HTTPS_ORIGIN> --approval-artifact=<ID> --approve-public-contact --approve-schedule --approve-asset-rights --acknowledge-worker-disabled"
.\gradlew.bat :apps:api:runDataImport --args="$productionArgs --mode=dry-run"
.\gradlew.bat :apps:api:runDataImport --args="$productionArgs --mode=apply"
```

Archive the redacted dry-run/apply results and public `/api/v1/public/config` and
`/api/v1/public/menu` ETags. Never archive environment values containing credentials.

## Failure recovery

- Source or bundle mismatch: stop; do not edit the pinned artifact in place. Produce a
  separately approved versioned bundle.
- DB conflict: inspect the target out of band. Do not truncate or merge production data
  merely to make the importer pass.
- S3 failure: no DB graph is published.
- DB failure after S3 upload: DB transaction rolls back. The content-addressed object is
  harmless while unreferenced and may be removed only after an ownership/reference
  audit.
- Interrupted exact apply: rerun the identical command. Immutable S3 put and DB graph
  comparison are idempotent.

## Versioning guardrail

The current UUID names are frozen only for immutable v1 compatibility. A future menu
v2 must include the menu version in category/item UUID names. Future asset UUID names
include asset kind and SHA-256. The restaurant UUID stays stable. Never mutate this
bundle and reuse its digest/IDs for different content.
