# Stambul disposable full-stack acceptance

## Purpose

`pnpm validate:acceptance` is the reproducible local acceptance gate for the pinned,
owner-approved Stambul dataset. It supplements `pnpm validate:full`; it does not establish
production readiness, validate cart UI/browser persistence, or authorize cutover.

The command requires Docker, Java 25, Node.js and pnpm. It must run only against the
containers it creates. It does not accept a database URL, object-store endpoint,
notification destination or provider credential from the operator.

## Isolation model

- PostgreSQL `postgres:17.5-alpine3.21` and MinIO
  `minio/minio:RELEASE.2025-04-22T22-12-26Z` bind random ports to `127.0.0.1`.
- Database and object bytes live only on Docker `tmpfs`; bind, named and anonymous
  volumes are rejected by inspection.
- Container names and labels include a unique run ID. Cleanup verifies ownership before
  removal and finally queries Docker to prove no owned container remains.
- Inherited Spring, application, datasource, importer, object-storage, telemetry, JVM,
  Next.js and server option variables are scrubbed before child processes start.
- The approved bundle and independent normalized oracle are checked before infrastructure
  is created. The oracle freezes selected source bytes, aggregate digest, normalized
  graph, exact item text/prices/order and deterministic UUIDv5 anchors.
- The production JAR and a local acceptance JAR are built separately. A verifier opens
  both archives and proves that the deterministic acceptance provider is absent from the
  production artifact and present only in the acceptance artifact.
- The provider is acceptance-source code, is enabled only by the `acceptance` profile and
  property, and has no network, file or subprocess capability. It returns one configured
  transient failure followed by success without a provider message ID.

## Execution and assertions

```text
verify normalized oracle and production/acceptance artifact isolation
→ build production Next.js output
→ start disposable PostgreSQL and MinIO; create a private empty bucket
→ boot production API with worker disabled to apply Flyway
→ stop bootstrap API
→ importer dry-run; assert zero database/object writes
→ importer apply; assert exact row counts, deterministic IDs and logo object
→ assert no orders, outbox, deliveries, destinations or Adamas markers
→ insert one synthetic no-network destination into the disposable database
→ boot acceptance API and production Next.js server
→ assert exact public config, menu, ETags, normalized text, prices and SSR signatures
→ submit one pickup order through Next.js and repeat the identical idempotency key
→ assert authoritative server total, one order/item/history/outbox and exact snapshots
→ observe first failed delivery while the committed order remains
→ observe bounded retry, successful second delivery and published outbox event
→ assert no real destination, provider ID, failed terminal outbox or duplicate order
→ stop child processes; remove and prove absence of owned containers
```

The synthetic destination is inserted only after the importer baseline is proven to have
no destination, worker side effect or runtime business row. It is never stored in the
approved bundle and cannot enter the production JAR.

## Failure interpretation

- An oracle/bundle mismatch is a content freeze failure; reconcile the approved artifact
  and independent oracle instead of weakening the assertion.
- An importer dry-run write is a blocker even if apply succeeds.
- Any Adamas marker, real destination, provider message ID or unexpected external access
  is a blocker.
- A transient provider failure must leave the order committed and auditable. Missing
  retry, duplicate rows or a terminal outbox state different from the expected bounded
  success is a blocker.
- Any failed assertion still triggers cleanup. A cleanup failure is reported together
  with the original error and must be resolved before evidence can be accepted.

## Evidence

Record the command, exit code, elapsed time, assessed revision/dirty paths, pinned bundle
and oracle hashes, artifact-isolation result, safe database count signatures and cleanup
proof in a revision-specific copy of `evidence-template.md`. Do not retain container data
or log secrets/PII/raw order payloads.
