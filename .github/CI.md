# CI baseline

The workflows are intentionally incremental and match the repository as it exists now.

- `ci.yml` validates the OpenAPI contract, deterministic TypeScript generation, the frozen root pnpm workspace, frontend lint/typecheck/unit/accessibility/build gates, the production HTTP 503 outage path, Java 25/Gradle checks, Spring Modulith tests, `bootJar`, an SPDX JSON SBOM, Compose configuration, and an ephemeral PostgreSQL health check.
- `security.yml` reviews pull-request dependency changes and scans the repository for high/critical vulnerabilities, secrets, and configuration errors.
- Every external action is pinned to a full commit SHA. The nearby comment records the corresponding release tag.
- The OpenAPI compatibility step reports an explicit notice until the first released baseline is added under `openapi-baseline/`; it does not claim compatibility without history.

The frontend job installs the single root lockfile and runs the production-shaped web gates. The status integration test starts the built Next server against a controllable API, proves normal healthy ISR reuse, warms the caches, then fails the API and verifies that fresh no-store readiness still makes `/` return HTML with HTTP 503, `no-store` and `noindex`. It guards against both a visually correct error page returning HTTP 200 and a warm data cache masking a current outage.

There is also no production application Dockerfile yet. CI validates the existing Compose model and scans repository/IaC content, but an application container build and image vulnerability scan are deferred until a real Dockerfile exists. Do not build a synthetic image only to satisfy the pipeline shape.
