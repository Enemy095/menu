# Local infrastructure

This Compose stack is for local development only. It deliberately contains no
production credentials, Redis, message broker, external telemetry exporter or
production business data.

## Services and ports

| Service | Profile | Host ports | Purpose |
|---|---|---|---|
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
