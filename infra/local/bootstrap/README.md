# Versioned local application content

This is the Git-distributed starting content for the single-client local Docker
deployment. `start-docker.cmd` loads the frozen **v1** snapshot into a fresh database,
then applies **v2** branding, **v3** pickup-address and **v4** fulfillment updates automatically.
Existing databases receive only pending content releases, once each.
No separate import command is needed on a new Windows machine.

## Files

| Path | Purpose |
|---|---|
| `v1/content.sql` | Data-only snapshot of 11 explicitly selected public-content tables |
| `v1/assets/` | Original object-storage images at their content-addressed object keys |
| `v1/manifest.json` | Table counts, image sizes/hashes and frontend asset inventory |
| `v1/checksums.sha256` | Integrity checks for the versioned snapshot and object files |
| `v1/public-config.json`, `v1/public-menu.json` | Expected public API responses; local asset origin is a placeholder |
| `v2/update.sql` | Idempotent branding update, tracked in `site_content_releases` |
| `v2/manifest.json`, `v2/checksums.sha256` | Frozen photography inventory (40 frontend assets) and integrity checks |
| `v2/public-config.json`, `v2/public-menu.json` | Frozen v2 expected public API responses |
| `v3/update.sql` | Owner-provided pickup address and verified map coordinates |
| `v3/manifest.json`, `v3/checksums.sha256` | Frozen pickup release inventory and integrity checks |
| `v3/public-config.json`, `v3/public-menu.json` | Frozen v3 expected public API responses |
| `v4/update.sql` | Delivery and pickup only; preorder disabled |
| `v4/manifest.json`, `v4/checksums.sha256`, `v4/public-*.json` | Current asset inventory, integrity checks and expected API responses |
| `bootstrap.sh`, `is-empty.sql`, `apply.sql` | Local initialization, empty-database guard and transaction |

The snapshot was exported from the existing application on 2026-10-26. It preserves
4 categories, 23 items, prices, names/descriptions, IDs, publication version, public
restaurant contacts/hours, theme, features and logo. Public display text is preserved
exactly as it existed, including spelling and spacing.

Menu pictures, illustrations and fonts are already in `apps/web/public`. They remain
there without duplicate copies. The v1 manifest records the original 17 frontend files so tests
can verify their downloaded bytes on a clean installation.

Only these PostgreSQL tables are exported:

```text
assets, restaurants, menu_versions, menu_categories, menu_items,
menu_modifier_groups, menu_modifier_options,
site_configuration, site_branding, site_profile, site_features
```

Orders, buyer details, order counters/history, outbox events, notification delivery
audit, notification destinations, credentials and Flyway history are not exported.
Public restaurant contact information is intentionally included. The existing
notification setup must be configured separately on another machine.

## Startup and persistence

1. Flyway migrates PostgreSQL through the normal API startup.
2. The bootstrap service verifies snapshot checksums.
3. Existing application rows cause the original snapshot to be skipped safely.
4. For an empty application database, object images are copied into the local bucket.
   Existing hash keys are verified; different bytes cause a failure.
5. A database transaction acquires an advisory lock, rechecks emptiness and verifies
   Flyway V10 before applying the data-only snapshot.
6. The same transaction applies `vam-pizza-v2` once: display name, localized short
   names, logo alt text and SEO become “Вам пицца”. Prices, orders, menu IDs, contacts
   and calendar choices are preserved. Unknown restaurant datasets are not changed.
7. The transaction applies `vam-pizza-pickup-v3` once, setting the pickup address,
   latitude/longitude and geo-location feature. No orders, prices or closures change.
8. The transaction applies `vam-pizza-order-types-v4` once: delivery becomes the
   default, pickup remains available, preorder is disabled for both UI and API.
9. The frontend starts after successful bootstrap completion. Its 23 approved dish
   photographs are versioned files in `apps/web/public/assets/menu/photography-v1`.

An interrupted upload can be retried. A failed SQL apply rolls back all rows. A
concurrent second initializer skips after the first transaction commits. Re-running
the launcher does not overwrite menu edits or duplicate rows. Explicit content
releases can update selected fields in existing installations; a new snapshot alone
does not replace an existing database. Once v2 is marked applied, later local edits
to branding are preserved on subsequent starts.

Set `LOCAL_BOOTSTRAP_ENABLED=false` in `.env` to skip initialization deliberately.
The app may be unavailable until content is provisioned when this is disabled.
This service is for local development only; it is not a production import workflow
and is not part of production Flyway migrations.

## Updating the initial content

With the local application and MinIO running, export a **new** version from the
repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/export-local-bootstrap.ps1 -Version v5
```

The exporter refuses to overwrite an existing version. It uses the explicit table
allowlist, exports assets referenced by the asset catalog, verifies SHA-256/size,
captures public API expectations and inventories frontend files. It currently
supports schema V10. Review the SQL, public contact/configuration fields and images
before adding the new version to Git. Public content must be stable during export.
If export fails, inspect the incomplete new directory before removing it or retrying.

After review, deliberately update the active version paths in `bootstrap.sh`,
`apply.sql` and `test-local-bootstrap.ps1`; adjust schema expectations if migrations
changed. Keep previously published snapshots immutable. Rebuild and run the test below.
Do not reset another developer's existing database to force a new default onto it.

The new dataset files and `apps/web/public` belong in Git along with the Docker and
launcher changes. `.env`, `.env.telegram.local` and Docker volumes remain local.

## Verification

First run `start-docker.cmd` to build the images. Then:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-local-bootstrap.ps1
```

The acceptance check creates a uniquely named isolated Compose project and empty
volumes on temporary ports, with all external notification delivery disabled. It
verifies exact config/menu responses, every content table's row count, HTTP 200,
object-storage image bytes, all frontend image/font hashes and absence of operational
data. It rejects tampered files, conflicting object bytes and an incompatible schema
before publishing database content. It changes menu prices only in the disposable database, reruns initialization
and proves those edits and calendar dates survive while the branding release applies.
Its `finally` block removes only its verified test
containers and volumes; the normal `emenu-local` data stays intact.
