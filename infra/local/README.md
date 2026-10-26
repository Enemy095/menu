# Local infrastructure

This Compose stack is for local development only. It deliberately contains no
production credentials, Redis, message broker, external telemetry exporter or
production business data.

## One-click Windows application startup

From Windows Explorer, double-click **`start-docker.cmd`** in the repository root.
From PowerShell, run:

```powershell
.\start-docker.cmd
# Include the optional collector and Prometheus:
.\start-docker.cmd -Observability
```

Install Docker Desktop with the WSL 2 backend and Linux containers once. Accept
Docker Desktop's initial setup before the first run. Host Java, Node.js, pnpm and
Gradle installations are not needed: both application builds run inside Docker.
The first build requires internet access to download images and dependencies.

The launcher starts Docker Desktop if its engine is unavailable, waits up to
180 seconds, creates `.env` from `.env.example` only when missing, validates
Compose, builds application images, and starts the `app` and `assets` profiles.
Each run checks the current local sources with Docker's build cache. Unchanged
layers are reused; changed inputs rebuild the affected layers. It does not fetch
Git changes, change pinned dependency versions or force base-image pulls. Existing
containers stay running until the image build has succeeded.
For this local workflow, the launcher disables default build provenance only for
its build subprocess. This prevents timestamped attestations from changing an
otherwise cached image and unnecessarily recreating containers. It restores the
previous environment setting immediately after the build.
This also avoids the newer Compose `build.provenance` field, which is rejected
by Compose 2.35.1 shipped with some Docker Desktop installations.

The stack contains Next.js, the Spring Boot API/notification worker, PostgreSQL,
MinIO and its bucket initializer. It waits for the database, bucket initialization,
API readiness and frontend HTTP health before reporting success. A failed build
or health check returns a nonzero exit code; the double-click window stays open
on failure. Containers keep running after the launcher exits.

| Address | Service |
|---|---|
| `http://localhost:3000` | Application |
| `http://localhost:8080/actuator/health/readiness` | API readiness |
| `http://localhost:9001` | MinIO console |

Change `WEB_PORT` / `API_PORT` in `.env` for port conflicts. Update
`PUBLIC_BASE_URL` when changing the public origin. The launcher prints the
configured web/API ports. Internal API/database/object-storage addresses always
use Compose service names, independently of host port overrides. Web/API ports
bind to `127.0.0.1` for this local workflow.

Existing `.env.telegram.local`, if present, is passed as a second Compose env file
and overrides `.env`; shell environment variables take precedence over both.
Only explicitly mapped API settings reach the API container. Telegram secrets
are runtime settings and are excluded from Docker build contexts and frontend
images. If notifications and the worker are enabled in that existing file,
normal order delivery uses them. The launcher does not send test orders.

PostgreSQL and MinIO reuse their named volumes. On a fresh database, the `bootstrap`
service loads the versioned public content from `infra/local/bootstrap/v1` after
Flyway migrations and before the frontend starts. It includes the current 4 categories,
23 menu items, restaurant configuration, branding and the MinIO logo. Menu photographs,
illustrations and fonts are already tracked in `apps/web/public` and are included in
the frontend image. Their hashes are recorded in the bootstrap manifest.

Existing application data is detected and skipped, including local menu edits. No
reset occurs. The snapshot excludes orders, customer data, notification destinations,
delivery history and credentials. Telegram needs separate local configuration on
each machine. Set `LOCAL_BOOTSTRAP_ENABLED=false` in `.env` to opt out of content
initialization. See [the bootstrap guide](bootstrap/README.md) for export/update and
clean-install verification. This is a local-development dataset, not production provisioning.

For slower machines, run
`.\start-docker.cmd -DockerTimeoutSeconds 300 -WaitTimeoutSeconds 300`.
For troubleshooting and shutdown (preserving all named volumes):

```powershell
docker compose --profile app --profile assets ps --all
docker compose --profile app --profile assets logs --tail 100 api web
docker compose --profile app --profile assets --profile observability down
```

When reproducing settings manually, include
`--env-file .env --env-file .env.telegram.local` before the profiles if the
Telegram file exists. Do not print resolved Compose config when sharing logs:
it contains runtime credentials.

The default Compose workflow below still starts infrastructure only; application
containers are opt-in through the `app` profile.

## Services and ports

| Service | Profile | Host ports | Purpose |
|---|---|---|---|
| `api`, `web` | `app` | `8080`, `3000` | Container-built Spring API and Next.js |
| `bootstrap` | `app` | none | One-time public content initialization for an empty database |
| `postgres` | default | `5432` | PostgreSQL 17 database `emenu` |
| `minio` | `assets` | `9000`, `9001` | S3-compatible single-client assets and console |
| `minio-init` | `assets` | none | One-shot creation of `menu-assets-local` |
| `otel-collector` | `observability` | `4317`, `4318`, `13133` | Local OTLP and health endpoints |
| `prometheus` | `observability` | `9090` | Local metrics storage and UI |

The ports do not overlap with the legacy frontend on `8000` or the planned API
on `8080`. Override ports in `.env` if a local service already uses one.

## Initial setup

From the repository root in PowerShell:

```powershell
Copy-Item .env.example .env
docker compose config
docker compose up -d --wait postgres
docker compose ps
```

The documented local database values are:

```text
host: localhost
port: 5432
database: emenu
user: emenu
password: emenu_local_password
JDBC URL: jdbc:postgresql://localhost:5432/emenu
```

They are intentionally non-secret and must never be reused outside local
development. The Spring `local` profile has the same safe defaults, so the API
can use them without copying `.env`. If you override them, use the exact
`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `PLATFORM_*`,
`PUBLIC_BASE_URL` and `ASSET_BASE_URL` names listed in `.env.example`.

## Health and database connection

Check PostgreSQL with tools installed inside its container:

```powershell
docker compose exec postgres pg_isready -U emenu -d emenu
docker compose exec postgres psql -U emenu -d emenu -c "select current_database(), current_user;"
```

Inspect service state and logs:

```powershell
docker compose ps
docker compose logs --tail 100 postgres
docker compose logs -f postgres
```

## Optional single-client assets

Start MinIO and run the idempotent bucket initializer:

```powershell
docker compose --profile assets config
docker compose --profile assets up -d minio
docker compose --profile assets run --rm minio-init
docker compose --profile assets ps
```

MinIO API is at `http://localhost:9000`; its console is at
`http://localhost:9001`. The bucket is private by default. The local access
values live in `.env.example` and are not production credentials.

### Existing local MinIO volume

The canonical local bucket is `menu-assets-local` in Compose, `.env.example` and the
Spring `local` profile. Changing this default does not rename a bucket already stored in
the named `minio-data` volume. An existing `.env` also continues to override the new
default until it is updated.

For an empty/disposable local asset store, use the destructive reset documented below and
then run `minio-init`. To preserve objects, start MinIO, copy each legacy bucket into the
new private bucket, verify the copy, and retain the source bucket for rollback:

```powershell
docker compose --profile assets up -d --wait minio
$env:SOURCE_BUCKET = "emenu-assets-local" # repeat with tenant-assets-local if it exists
$env:TARGET_BUCKET = "menu-assets-local"
docker compose --profile assets run --rm `
  -e SOURCE_BUCKET -e TARGET_BUCKET `
  --entrypoint /bin/sh minio-init -ec '
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc mb --ignore-existing "local/$TARGET_BUCKET"
    mc anonymous set none "local/$TARGET_BUCKET"
    mc stat "local/$SOURCE_BUCKET"
    mc mirror --overwrite "local/$SOURCE_BUCKET" "local/$TARGET_BUCKET"
    mc du "local/$SOURCE_BUCKET"
    mc du "local/$TARGET_BUCKET"
  '
```

After verifying object counts and hashes required by the selected dataset, update the
existing `.env` values `MINIO_BUCKET`, `ASSET_STORAGE_BUCKET` and `ASSET_BASE_URL` to
`menu-assets-local`, restart the API, and keep the source bucket through the rollback
window. The procedure never deletes the source bucket automatically.

## Optional observability

Start the collector and Prometheus:

```powershell
docker compose --profile observability config
docker compose --profile observability up -d --build --wait otel-collector prometheus
docker compose --profile observability ps
```

The collector accepts OTLP/gRPC on `4317` and OTLP/HTTP on `4318`. It exports
only to its local debug output; no telemetry leaves the machine. Prometheus is
available at `http://localhost:9090` and is prepared to scrape the future
Actuator endpoint at `host.docker.internal:8080/actuator/prometheus`. That
target remains down until the API exposes it and does not make Prometheus
unhealthy.

```powershell
docker compose --profile observability logs --tail 100 otel-collector prometheus
```

## Stop, restart and persistence

The following commands stop containers but retain named volumes:

```powershell
docker compose --profile assets --profile observability down
docker compose up -d --wait postgres
```

To verify PostgreSQL persistence explicitly:

```powershell
docker compose exec postgres psql -U emenu -d emenu -c "create table if not exists local_volume_probe (id integer primary key); insert into local_volume_probe values (1) on conflict do nothing;"
docker compose down
docker compose up -d --wait postgres
docker compose exec postgres psql -U emenu -d emenu -c "select * from local_volume_probe;"
```

Remove the probe after the check if desired:

```powershell
docker compose exec postgres psql -U emenu -d emenu -c "drop table local_volume_probe;"
```

## Destructive reset

> **WARNING:** The following command permanently deletes the local PostgreSQL,
> MinIO and Prometheus named volumes for this Compose project. Confirm that
> `COMPOSE_PROJECT_NAME` points to the intended local project before running it.

```powershell
docker compose --profile assets --profile observability down --volumes --remove-orphans
```

Recreate only the default database afterward:

```powershell
docker compose up -d --wait postgres
```
