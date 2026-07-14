# SC-SEC-001 — single-client request and security boundary

Status: `ready`
Wave: P2
Dependencies: `SC-000`, `SC-API-001`
Integration order: execute after the public contract is frozen; integrate this diff before
`SC-BE-001` deletes `com.salamu.menu.tenancy`.

## Objective

Сохранить строгую security boundary после удаления host/slug business resolution:

- only exact fixed public routes are anonymous;
- Host is validated only as HTTP authority/cache-poisoning defense and never selects data;
- trusted proxy handling lives entirely in `identity`;
- order rate limiting uses client-IP plus one application-wide bucket;
- tenant/platform admin concepts and tenant error branches are removed;
- public, future client-admin and management boundaries stay separate and default-deny.

This task must compile while the old tenancy module still exists, but `identity` must have
zero import/dependency on it when handed off. That makes it safe for `SC-BE-001` to delete
the module in the next integration step.

## Unblock checklist

1. `SC-API-001` is accepted and its exact paths, methods, headers and error codes are
   frozen.
2. Generated contract has no tenant selector; public reads do not expose `429`, while
   order creation does.
3. Orchestrator supplies accepted application/management port topology and exact allowed
   development/test hosts/origins already represented by repository configuration.
4. If a real admin API/authentication requirement appears, stop; it is a new task/ADR and
   must not be improvised here.

## Exclusive ownership

Allowed write scope:

```text
apps/api/src/main/java/com/salamu/menu/identity/**
apps/api/src/test/java/com/salamu/menu/identity/**
apps/api/src/main/resources/application.yml
apps/api/src/main/resources/application-local.yml
apps/api/src/main/resources/application-production.yml
apps/api/src/test/resources/application-test.yml
```

Within these YAML files the task may remove obsolete platform/tenant keys, add validated
single-client request-boundary keys and rename local/test asset bucket/base URL defaults
from `tenant-assets-*` to `menu-assets-*`. It must not alter DB/Flyway, observability
export, worker semantics or business feature values.

Forbidden scope:

- `site`, `asset`, `restaurant`, `menu`, `ordering`, `notification`, `tenancy` and
  `migration` Java code/tests — `SC-BE-001` owns them;
- root `ApiErrorHandler`, root filters, Flyway, OpenAPI/generated client, infra, frontend,
  legacy and task registry;
- adding actual admin endpoints/authentication or a distributed rate limiter.

Do not commit.

## Exact dependency and integration order

```text
SC-API-001 accepted
→ implement SC-SEC-001 against current backend
→ compile/test with tenancy module still present but unused by identity
→ orchestrator integrates SC-SEC-001
→ SC-BE-001 rebases and deletes tenancy/migration runtime
→ run combined security + Modulith + backend suite
```

`SC-SEC-001` must not import planned `site`/`asset` code, so its development is disjoint
and can run in parallel with backend refactoring after the OpenAPI freeze.

## Required package design

Keep `com.salamu.menu.identity` a closed Spring Modulith module with:

```java
@ApplicationModule(
    displayName = "Identity",
    type = ApplicationModule.Type.CLOSED,
    allowedDependencies = {}
)
```

`identity::api` may retain a marker for future client-admin auth, but no tenant-aware type,
role or selector is exported. All current implementations live under
`identity/internal/security`.

Required neutral components:

```text
SecurityProperties
SecurityRequestMatchers
SecurityConfiguration
RequestAuthorityFilter
TrustedProxyPolicy              # identity-owned neutral CIDR policy
TrustedClientIpResolver
OrderRateLimiter
PublicOrderRateLimitFilter
SecurityErrorWriter             # typed common envelope for servlet filters
```

Names may vary slightly, but each responsibility must have one owner. Do not keep a
deprecated `TenantRateLimiter`, wrapper `TenantContext`, fake singleton tenant or
dependency on `tenancy::api`.

## Configuration contract

Replace old platform/tenant properties with this shape:

```yaml
app:
  security:
    cors:
      allowed-origins: []
    request-authority:
      allowed-hosts: []
    trusted-proxies:
      enabled: false
      cidrs: []
    rate-limit:
      client:
        tokens-per-period: 5
        period: 1m
        burst-capacity: 10
      application:
        tokens-per-period: 120
        period: 1m
        burst-capacity: 240
      cache:
        maximum-client-buckets: 50000
        expire-after-access: 10m
```

Rules:

- delete `app.platform.hosts`, `base-domain`, `local-aliases`,
  `app.platform.trusted-forwarding`, `rate-limit.tenant` and
  `maximum-tenant-buckets`;
- production `allowed-hosts` and CORS origins must be explicit; empty production host
  allowlist fails startup rather than allowing every Host;
- local/test may list `localhost`, `127.0.0.1`, `[::1]` and explicit test aliases;
- host entries are host literals only: no scheme, userinfo, path, query, fragment,
  wildcard or uncontrolled suffix rule;
- CORS entries are exact HTTP(S) origins only, no wildcard/path and credentials false;
- proxy CIDRs are validated at startup; forwarding is disabled by default;
- set/retain `server.forward-headers-strategy: none` so application never rewrites request
  authority from untrusted forwarding headers; client IP parsing is handled explicitly;
- `PUBLIC_BASE_URL` and `ASSET_BASE_URL` remain validated absolute configuration and are
  never derived from request Host;
- local/test neutral asset values become `menu-assets-local`/`menu-assets-test`; this task
  changes values only, not asset Java behavior.

`SecurityProperties` must use `@Validated` and immutable copied collections. Invalid
origin/host/CIDR, non-positive duration/tokens, burst below refill, or non-positive cache
size/expiry fails application startup with no partial permissive fallback.

## Exact request matchers and chains

`SecurityRequestMatchers` defines only:

```text
GET  /api/v1/public/config
GET  /api/v1/public/menu
POST /api/v1/public/orders
GET  /livez
GET  /readyz
```

No wildcard below `/api/v1/public/**`. Old slug routes, `/api/public/orders`, unknown
methods, `/api/v1/admin/**`, `/tenant-admin/**` and `/platform-admin/**` remain denied.

### Chain 1 — management

- `@Order(1)`, `EndpointRequest.toAnyEndpoint()`;
- keep health/info/prometheus exposure consistent with the existing private management
  port configuration; all other actuator endpoints denied;
- stateless, no request cache, basic/form/logout disabled;
- production management port remains private; do not make actuator public to solve tests.

### Chain 2 — application

- `@Order(2)`, stateless anonymous public boundary;
- exact matchers above only; `DispatcherType.ERROR` permitted only for framework error
  dispatch; all remaining requests `denyAll`;
- CORS registered separately on config, menu and order exact paths;
- GET allowed headers: `Accept`, correlation/tracing headers;
- POST allowed headers: GET set plus `Content-Type`, `Idempotency-Key`;
- exposed response headers include correlation/trace ID, `ETag`, `Cache-Control`,
  cache/surrogate tags if emitted by contract, and `Retry-After`;
- credentials false; allowed methods exact; max age bounded;
- CSRF ignore only exact `POST /api/v1/public/orders` while it is stateless and does not
  use cookie authentication;
- never ignore CSRF for `/api/v1/admin/**` or a wildcard path.

## Host/authority boundary

Create `RequestAuthorityFilter` before rate limiting and before any cacheable business
response.

Algorithm:

1. Apply once to `DispatcherType.REQUEST`; reject missing/multiple/malformed `Host`.
2. Bound header length; reject whitespace/control chars, userinfo, path/query/fragment,
   invalid port and ambiguous IPv6 syntax.
3. Normalize DNS host using lower-case ASCII/IDN and one optional trailing dot; normalize
   IP literals and strip only a syntactically valid port.
4. Compare the normalized host to exact configured allowlist.
5. Unknown authority returns a generic non-cacheable client error before controller/rate
   DB work. Use an error code/status already permitted by accepted OpenAPI/common policy;
   if no such policy exists, use generic `400 VALIDATION_ERROR`, message
   `Invalid request authority`, empty details.
6. Do not echo Host, IP or normalized candidate in response/log.

Never read `Forwarded` or `X-Forwarded-Host`; they do not select site, authorization,
rate bucket, cache tag, destination or canonical URL. Two allowed host aliases must reach
the same downstream request and yield the same config/menu representation and ETag.

This application filter is defense in depth. The deployment/CDN/ingress must still have
its own exact host allowlist; document the limitation in handoff without editing infra.

## Trusted proxy and client IP contract

Move the neutral CIDR policy into `identity`; do not reuse/import
`tenancy.api.TrustedProxyPolicy`.

`TrustedClientIpResolver` rules:

- parse only IP literals; never cause DNS lookup for attacker input;
- use socket `remoteAddr` when the direct peer is not a configured trusted proxy;
- inspect exactly one bounded `X-Forwarded-For` header only when direct peer is trusted;
- maximum 512 characters and 16 hops; malformed/multiple/empty header falls back to the
  canonical direct-peer address;
- scan right-to-left and return first untrusted hop; if all hops are trusted, use direct
  peer;
- IPv4 leading-zero/invalid-octet forms and malformed IPv6 fail closed;
- never log or return forwarding header values in an error.

`Forwarded` and forwarded-host headers remain ignored. The resolved IP is an internal
rate key, not a metric label, response field or log field.

## Rate limiting contract

Rename `TenantRateLimiter` to `OrderRateLimiter`. Required scopes:

```text
client bucket:       canonical trusted client IP
application bucket: one singleton bucket for this process
```

Implementation:

- bounded Caffeine cache exists only for client buckets; maximum size and
  expire-after-access come from validated properties;
- application bucket is a single `Bucket4j` instance, not a cache keyed by Host/slug;
- consume client then application token for exact order POST before body parsing,
  validation and DB access;
- keys must not contain Host, origin, phone, order ID, idempotency key or arbitrary
  header/body values;
- denial returns accepted `429 RATE_LIMITED` envelope, `Retry-After >= 1`, trace header,
  `Cache-Control: no-store`; filter chain is not invoked;
- `SecurityErrorWriter` serializes the same four-field envelope
  `code/message/details/traceId`; no hand-built JSON concatenation;
- configure response character encoding/content type and prevent response splitting;
- Caffeine/Bucket4j is explicitly per-instance. Do not add Redis; handoff notes that a
  horizontal deployment multiplies aggregate capacity.

If the accepted OpenAPI keeps `429` for config/menu, this task must either implement an
explicit bounded read limiter with separate measured policy or stop and return the
contract inconsistency. Do not claim edge behavior that is absent from repository config.

## Admin and role semantics

- Remove all `TENANT_ADMIN`, `PLATFORM_ADMIN`, tenant ownership and host-selection
  semantics from identity/security source and tests.
- Do not replace them with an unauthenticated generic admin.
- Until a dedicated client-admin auth task, `/api/v1/admin/**` is denied by the application
  chain and has no controller.
- Future client-admin requires separate chain, authentication, a dedicated client-admin
  role, audit trail and explicit CORS/CSRF policy. This task only preserves the boundary.

## Failure and redaction matrix

| Case | Required behavior |
|---|---|
| Missing/multiple/malformed/unknown Host | generic non-cacheable error; no reflected authority |
| Forged forwarding headers from untrusted peer | ignored for client IP; direct peer key |
| Malformed trusted-proxy header | direct peer fallback; no parsing exception leakage |
| Client bucket empty | `429`, common envelope, `Retry-After`, no controller/DB call |
| Application bucket empty | same `429`; different IP does not bypass aggregate limit |
| Old slug route or wrong HTTP method | default deny/not found; never public |
| Admin/tenant-admin/platform-admin route | denied; no host-derived role/scope |
| Invalid CORS preflight | no permissive wildcard/credentials response |
| Error writer/trace lookup failure | safe generated diagnostic ID; valid JSON; no input echo |

Responses, logs, traces and metric labels must not contain Host/Forwarded/XFF values,
resolved IP, phone, address, comment, idempotency key, order payload, credentials, secret
reference or tenant identifiers. Metrics may count outcomes by bounded reason code only.

## Required test ownership and cases

Rewrite old identity suites under their existing package; rename tenant-oriented test
classes to neutral names.

### Properties

- exact origins/hosts accepted; wildcard, path, userinfo, invalid port/IDN/control chars
  rejected;
- production empty host allowlist fails binding/validation;
- proxy CIDRs and durations/tokens/cache bounds validated;
- no property named `tenant`, `platform.hosts` or `trusted-forwarding` binds;
- local/test neutral asset bucket/base URL values load.

### Matchers/chains

- only three exact method/path pairs and explicit probes are public;
- old slug/non-versioned paths and method substitutions denied;
- unknown/admin/tenant-admin/platform-admin denied;
- management chain precedence and existing private endpoint policy preserved;
- no session/basic/form/logout and no cookie created;
- CSRF ignore cannot match an admin path.

### Authority/CORS

- two allowed aliases both pass; unknown/malformed/multiple Host rejected before handler;
- `Forwarded`/`X-Forwarded-Host` never affect allowlist or downstream data marker;
- no header value is echoed in body/log capture;
- exact origin/method/header preflight succeeds; wildcard/unlisted origin and credentials
  attempt fail;
- exposed headers match accepted contract.

### Proxy/IP

- untrusted peer ignores XFF; trusted peer resolves first untrusted hop right-to-left;
- multiple/oversized/too-many-hop/malformed headers fall back to direct peer;
- IPv4/IPv6 canonicalization, leading-zero rejection and no hostname/DNS input;
- resolved address is not logged or placed in metrics.

### Rate limiter/filter

- same IP shares one client bucket across allowed Host aliases and forged headers;
- different IPs share one application bucket;
- client cache is bounded/expiring; application has exactly one bucket;
- filter applies only exact order POST and runs before test controller/body/DB marker;
- `429` envelope is valid JSON with four fields, `Retry-After`, trace ID and `no-store`;
- allowed request continues once; denied request never invokes chain;
- deterministic ticker tests cover refill/retry rounding without sleeps.

### Combined handoff gate

After `SC-BE-001` is applied, rerun security integration tests proving:

- no `TenantResolver`, `TenantRequestAccess`, tenant filter or argument-resolver bean;
- allowed host aliases yield identical config/menu body and ETag;
- authority/header/query/body tenant-like input cannot change data or destination;
- `ApplicationModules.verify()` reports `identity` with no dependencies.

## Acceptance commands

Before SC-BE integration:

```text
./gradlew :apps:api:compileJava :apps:api:compileTestJava
./gradlew :apps:api:test --tests "*SecurityProperties*"
./gradlew :apps:api:test --tests "*SecurityRequestMatchers*" --tests "*SecurityIntegration*"
./gradlew :apps:api:test --tests "*RequestAuthority*" --tests "*TrustedClientIpResolver*"
./gradlew :apps:api:test --tests "*OrderRateLimiter*" --tests "*PublicOrderRateLimitFilter*"
rg -n "com\.salamu\.menu\.tenancy|Tenant(Id|Context|Request|RateLimiter)|TENANT_NOT_FOUND|tenantSlug" apps/api/src/main/java/com/salamu/menu/identity apps/api/src/test/java/com/salamu/menu/identity
rg -n "platform:|trusted-forwarding|tenant:|maximum-tenant-buckets|tenant-assets" apps/api/src/main/resources apps/api/src/test/resources
git diff --check
```

After SC-BE integration:

```text
pnpm --filter @e-menu/api-contract validate
./gradlew :apps:api:test --tests "*Security*" --tests "*RateLimit*" --tests "*RequestAuthority*"
./gradlew :apps:api:test --tests "*ApplicationModule*" --tests "*Modularity*"
./gradlew :apps:api:test
./gradlew :apps:api:check
git diff --check
```

Also inspect the four owned YAML files directly. All searches must be reported, including
expected immutable/history hits outside owned scope.

## Out of scope

- client-admin authentication implementation;
- changing business controllers, DTOs, repositories or `ApiErrorHandler`;
- implementing order creation/idempotency/outbox;
- distributed rate limiting/Redis;
- WAF/CDN/ingress mutation;
- changing OpenAPI to hide an implementation mismatch;
- WebFlux/R2DBC, Kafka, microservices or Kubernetes.

## Handoff template

Executor sends this exact structure and does not commit:

```text
SC-SEC-001 handoff
SC-API base: <commit/SHA>
Owned files changed: <exact list>
Identity module dependencies: <actual allowedDependencies>
Public matcher matrix: <method/path/result>
Authority policy: <normalization, aliases, unknown-host status/code>
Trusted proxy policy: <properties and parsing behavior>
Rate scopes/config: <client + application; cache bounds; per-instance limitation>
CORS/CSRF/management decisions: <actual behavior>
Deleted tenant/admin semantics: <classes/properties/tests>
Redaction evidence: <response/log/metric assertions>
Tests run: <command + exit code>
Unrun tests/blockers: <reason; never claim green>
Integration instruction for SC-BE: <apply order and any conflicts>
Search results/exceptions: <NONE or exact justified hit>
Git status: <short output; no commit>
```

## Definition of Done

- Identity has no tenancy import, role, selector or module dependency.
- Fixed public method/path allowlist, stateless/default-deny chains and exact CORS are
  tested.
- Host is a validated transport boundary only and forwarding host is ignored.
- Trusted proxy/IP parsing is neutral, bounded and fail-closed.
- Rate limiting is IP + one application bucket, with common redacted `429` response.
- Admin routes remain denied; management boundary remains private/default-deny.
- Owned YAML has no platform/tenant keys or tenant asset defaults.
- All owned tests and combined post-SC-BE gates pass.
- Executor has not committed.
