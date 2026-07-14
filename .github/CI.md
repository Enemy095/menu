# CI baseline

The workflows are intentionally incremental and match the repository as it exists now.

- `ci.yml` validates the OpenAPI contract, deterministic TypeScript generation, Java 25/Gradle checks, Spring Modulith tests, `bootJar`, an SPDX JSON SBOM, Compose configuration, and an ephemeral PostgreSQL health check.
- `security.yml` reviews pull-request dependency changes and scans the repository for high/critical vulnerabilities, secrets, and configuration errors.
- Every external action is pinned to a full commit SHA. The nearby comment records the corresponding release tag.
- The OpenAPI compatibility step reports an explicit notice until the first released baseline is added under `openapi-baseline/`; it does not claim compatibility without history.

There is no `apps/web` yet, so no placeholder frontend job is present. Add its real frozen-lockfile, lint, typecheck, unit, build, and Playwright commands when the application scaffold lands.

There is also no production application Dockerfile yet. CI validates the existing Compose model and scans repository/IaC content, but an application container build and image vulnerability scan are deferred until a real Dockerfile exists. Do not build a synthetic image only to satisfy the pipeline shape.
