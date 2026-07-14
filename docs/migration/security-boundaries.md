# Security boundaries for the migration platform

## Public and reserved routes

The application security chain permits anonymous access only to these exact method and path combinations:

| Method | Path |
| --- | --- |
| `GET` | `/api/v1/public/{tenantSlug}/restaurant` |
| `GET` | `/api/v1/public/{tenantSlug}/menu` |
| `POST` | `/api/v1/public/{tenantSlug}/orders` |

`GET /livez` and `GET /readyz` are explicit unauthenticated probe routes. Every other application route is denied by default. Tenant-admin and platform-admin namespaces remain reserved and denied until their authentication and audit design is implemented.

Actuator endpoints are served on the management boundary. Production binds that boundary to loopback by default; a routable management address is allowed only inside a private network policy. HTTP exposure remains limited to health, info, and Prometheus.

## CORS, CSRF, and cookies

Same-origin access is the default. Cross-origin access is enabled only for exact `http` or `https` origins in `app.security.cors.allowed-origins`; wildcards, origin patterns, credentials, paths, queries, and fragments are rejected. CORS configurations are registered only for the three public routes and their contracted methods.

Custom domains should serve the Next.js frontend and proxy its API calls through the same origin. A controlled cross-origin deployment must add each canonical frontend origin explicitly. The public API does not accept browser credentials.

CSRF protection stays enabled. Only the exact stateless public order `POST` route is excluded. Future cookie-authenticated admin routes require a separate authenticated chain, explicit cookie domain and `SameSite` policy, an exact credentialed origin allowlist, and CSRF tokens; the public exemption must never be widened to an admin namespace.

## Trusted client address

Forwarded client addresses use the tenancy module's shared `TrustedProxyPolicy`, which is backed by `app.platform.trusted-forwarding`. `X-Forwarded-For` is ignored unless the direct `remoteAddr` is a configured trusted proxy. For a trusted ingress, the chain is evaluated from right to left and the first untrusted address is used. Invalid or ambiguous forwarded data falls back to the direct peer, so a client-supplied header cannot create arbitrary limiter keys.

Client addresses and tenant IDs exist only in bounded in-memory rate-limit keys. They are not written to logs, traces, response bodies, or metric labels.

## Per-instance rate limiting

Public order creation consumes one token from both local limits:

- `(tenantId, trustedClientIp)`: 5 tokens per minute, burst capacity 10;
- `tenantId`: 120 tokens per minute, burst capacity 240.

Limits, cache bounds, and expiry are configurable under `app.security.rate-limit`. Bucket state is local to one application instance and stored in bounded, expiring Caffeine caches. Redis is not used and requires a separate ADR.

For every multi-instance production deployment, an edge or ingress distributed rate limit is mandatory. The local limiter remains defense in depth; it cannot provide a global quota across replicas. Edge configuration must preserve the same tenant-aware semantics and must not trust client-controlled forwarded headers.
