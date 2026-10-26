# SC-OUT-001 — transactional outbox worker

Status: `done`
Wave: P6
Dependencies: `SC-ORD-001`

## Objective

Deliver committed `order.created` events from the single-client transactional outbox
with durable at-least-once semantics. Order creation remains successful and contains no
provider call; the worker resolves all destinations from server-side configuration after
the order transaction commits.

## Non-goals

- No provider credentials, raw WhatsApp/Telegram/email/POS destinations or production
  provider adapter are added.
- No direct browser redirect, synchronous notification inside order creation or rollback
  of a committed order after delivery failure.
- No Kafka, Redis, WebFlux, R2DBC, unbounded `@Async`, separate worker database or
  exactly-once claim.
- No notification access to `ordering.internal` and no second SQL implementation over
  `outbox_events` outside the ordering module.
- No client-selected destination/channel and no destination locator inside event payload.

## Immutable decisions

- `ordering` owns `outbox_events`, transactional event insertion and the public
  claim/complete/retry/fail port exported through `ordering::api`.
- `notification` owns polling orchestration, server-side destination resolution,
  provider boundary, delivery audit, retry policy and worker metrics.
- Claims use a bounded stable batch with PostgreSQL `FOR UPDATE SKIP LOCKED`; pending
  available work and expired processing leases are eligible.
- Claim atomically assigns `lease_owner`/`leased_until`, changes state to `processing`
  and increments `attempt_count`.
- Mutations after a provider call compare event ID, lease owner and monotonically
  incremented attempt number. A stale attempt cannot complete, retry or dead-letter a
  reclaimed lease even when replicas accidentally share the same worker ID.
- Delivery is at-least-once. A crash after external success and before durable success
  recording can cause a duplicate; a stable provider idempotency key mitigates it.
- The existing terminal outbox state `failed` is the dead-letter state. It is stable and
  operator-visible; no automatic worker path returns it to pending.
- External calls execute outside database transactions. Short transactions are used for
  claim, delivery audit and outbox state transitions.
- Every active destination is selected server-side. Disabled destinations are ignored;
  malformed or ambiguous active configuration fails closed instead of guessing.
- Retry uses capped exponential backoff with bounded jitter. The attempt limit includes
  the current claimed attempt.
- Logs never contain payload, phone, address, comment, order/event/delivery ID, provider
  body, provider message ID, secret locator or exception message.

## Exclusive file ownership

Primary scope:

```text
apps/api/src/main/java/com/salamu/menu/notification/**
apps/api/src/test/java/com/salamu/menu/notification/**
docs/migration/tasks/SC-OUT-001.md
docs/migration/tasks/README.md       # SC-OUT status only
```

Integration scope:

```text
apps/api/src/main/resources/application.yml
apps/api/src/test/resources/application-test.yml
```

Review hardening jointly owned with `SC-ORD-001`:

```text
apps/api/src/main/java/com/salamu/menu/ordering/api/Outbox{Completion,Retry,Failure}.java
apps/api/src/main/java/com/salamu/menu/ordering/internal/infrastructure/persistence/JdbcOrderOutboxPort.java
apps/api/src/test/java/com/salamu/menu/ordering/internal/application/PublicOrderCreationPostgreSqlIntegrationTests.java
```

This narrow integration adds the monotonic attempt fence and bounded exhausted-event
claim; notification still never imports ordering internals or reads `outbox_events`.

The task must not otherwise edit ordering internals, `outbox_events` schema, OpenAPI,
generated client, frontend, selected dataset or legacy code.

## Interfaces and delivery flow

The notification module depends only on the public `ordering::api` outbox contract. A
notification-internal gateway maps public ordering types to redacted worker types so unit
tests do not know persistence details.

```text
scheduled bounded poll
→ reconcile bounded stale started audits owned by notification
→ ordering::api claim(worker, capacity, lease)
→ validate supported event/schema
→ resolve active destinations from notification configuration
→ skip destinations already durably succeeded
→ create started delivery audit in a short transaction
→ call provider outside transaction with stable idempotency key
→ persist succeeded/failed delivery audit
→ all succeeded: ordering::api complete with lease guard
→ transient failure: ordering::api retry at calculated availableAt
→ permanent/max-attempt/config failure: ordering::api fail/dead-letter
```

The provider request contains a redacted opaque event payload and opaque secret-manager
reference. Its textual representations are redacted. A deterministic fake provider lives
only in test code; it can script success/transient/permanent outcomes and performs no
network I/O.

## Runtime configuration

`app.worker.enabled=false` remains the safe default. Worker configuration has bounded
defaults and environment overrides for:

- stable deployment/process worker ID;
- poll interval, batch size, concurrency and maximum active destinations per event;
- lease duration and provider call timeout (`timeout < lease`);
- initial/max backoff, jitter ratio and max attempts;
- graceful shutdown timeout.

Invalid zero/negative values, unbounded batch/concurrency, invalid jitter or a provider
timeout not shorter than the lease fail startup validation.

`APP_WORKER_ID` may provide an operationally meaningful process identity. Without it,
Spring generates a per-process random UUID; correctness relies on the attempt fence rather
than worker ID uniqueness.

## Retry and failure semantics

- Delay before attempt `n+1` is based on
  `min(initialBackoff * 2^(n-1), maxBackoff)`, receives symmetric bounded jitter and
  is capped again at `maxBackoff`.
- Jitter is injected behind a small source so tests are deterministic.
- A transient provider result retries while the current attempt is below the maximum.
- Permanent provider result, unsupported event/schema, invalid/no destination or exhausted
  attempts moves the outbox event to `failed`.
- Provider exceptions are treated as transient with a sanitized stable code; raw messages
  are neither persisted nor logged.
- Expired `started` audit rows are closed with
  `LEASE_EXPIRED_UNKNOWN_OUTCOME` before the next attempt, making the duplicate-risk window
  explicit without claiming exactly-once behavior.
- A bounded notification-owned `SKIP LOCKED` reconciliation also closes `started` audits
  older than one lease duration. This covers a crash on the final attempt, when ordering
  terminalizes the exhausted event and no next delivery attempt exists.
- A notification failure never updates order business status and never deletes the order.

## Observability and redaction

Low-cardinality metrics cover:

- claimed events and lease-lost transitions;
- delivery attempts/outcomes by fixed channel and fixed outcome;
- retries and dead-letter transitions by fixed reason category;
- pending/processing backlog count and oldest age;
- polling and provider latency.

Structured logs contain only fixed event type/schema, attempt number, channel and safe
outcome/error codes. Correlation context is inherited from the worker observation; event
payload and all identifiers listed above are excluded.

The executor uses Micrometer context propagation and each event runs inside a
`menu.notification.outbox.process` observation with bounded low-cardinality tags.

## Implementation sequence

1. Freeze this task contract and registry status.
2. Agree the lease-guarded public outbox port with `SC-ORD-001`.
3. Add redacted internal gateway/event/payload types and ordering API adapter.
4. Add destination selection and transactional delivery audit repository.
5. Add provider boundary and deterministic test fake.
6. Add pure capped exponential retry policy with injectable jitter.
7. Add processor state machine and lease-guarded terminal/retry paths.
8. Add bounded scheduler/executor, shutdown behavior and opt-in configuration.
9. Add safe logs, metrics and backlog sampling.
10. Run unit, PostgreSQL concurrency/recovery, module and complete backend checks.

## Required tests

### Unit

- deterministic base/capped delays and lower/upper jitter bounds;
- supported/unsupported event and schema handling;
- all destinations resolved only from server configuration;
- disabled ignored; malformed, duplicate active and empty configuration fail closed;
- success completes, transient retries, permanent/max-attempt dead-letters;
- provider exception is sanitized and does not expose its message;
- payload/secret/provider request/result textual forms are redacted;
- worker never logs payload, PII, secret reference or identifiers;
- capacity never exceeds configured concurrency and polling stops during shutdown.

### PostgreSQL/Testcontainers

- pending events are claimed once across concurrent workers with `SKIP LOCKED`;
- stale processing lease is recovered after simulated crash;
- non-expired lease is not stolen;
- stale attempt complete/retry/fail is rejected after same-worker-ID lease reclaim;
- retry stores bounded `available_at`, increments attempts once per claim and is not
  immediately reclaimed;
- max-attempt event reaches terminal `failed` and stays unclaimable;
- final-attempt crash is terminalized and its stale `started` delivery is reconciled;
- exhausted terminalization and stale-audit reconciliation are bounded per poll;
- started/succeeded/failed audits obey uniqueness and terminal timestamps;
- previously succeeded destination is not called again on event retry;
- order/outbox commit survives worker/provider failure;
- no provider call occurs in the order transaction.

### Module/config/observability

- Modulith allows only `notification -> ordering::api` and rejects internal imports;
- worker beans are absent/idle by default and enabled only explicitly;
- invalid worker properties fail validation;
- metrics use bounded labels and redacted logs contain no forbidden data;
- executor context propagation and worker observations preserve trace correlation.

## Acceptance commands

```text
./gradlew :apps:api:compileJava :apps:api:compileTestJava
./gradlew :apps:api:test --tests "*Notification*" --tests "*Outbox*"
./gradlew :apps:api:test --tests "*Ordering*PostgreSql*" --tests "*Flyway*"
./gradlew :apps:api:test --tests "*ApplicationModule*" --tests "*Modularity*"
./gradlew :apps:api:test
./gradlew :apps:api:check
git diff --check
```

## Failure and edge cases

- Database unavailable during claim: no provider call; next bounded poll retries.
- Executor rejects after claim: event remains leased and becomes recoverable after expiry.
- Process dies after claim: stale lease becomes eligible after `leased_until`.
- Process dies after provider success but before success audit: provider idempotency key is
  reused and duplicate remains possible by the documented at-least-once contract.
- Process dies after success audit but before outbox completion: recovery skips the
  succeeded destination and completes after reconciling remaining destinations.
- Destination is disabled/changed between attempts: each attempt re-resolves server
  configuration; existing succeeded audits are preserved.
- Destination disappears while an event is pending: fail closed/dead-letter; never use a
  client payload or historical raw destination as fallback.
- Clock moves: database claim rules use explicit instants; negative delays are clamped and
  validation rejects invalid durations.
- Graceful shutdown stops new claims first, waits for bounded in-flight work and leaves any
  unfinished leases recoverable.
- Exhausted terminalization is ordered, bounded and uses `FOR UPDATE SKIP LOCKED`; a poll
  never updates an unbounded exhausted backlog.

## Handoff report format

```text
Task ID: SC-OUT-001
Changed files: <exact grouped list>
Ordering public API: <types and lease semantics>
Claim/recovery: <SQL ownership, SKIP LOCKED and crash behavior>
Provider/destination: <server-side routing and fake evidence>
Retry/dead-letter: <formula, bounds and terminal behavior>
Audit/redaction/metrics: <implementation and evidence>
Commands run: <command + exit code>
Unresolved issues: <NONE or exact blocker>
Suggested next unlock: SC-VAL-001 if all other dependencies are done
Git status: <short output; no commit>
```

## Review evidence

- Notification unit/config/provider/gateway/audit tests: green.
- PostgreSQL delivery audit including transactional rollback and crash-window recovery:
  green.
- Ordering-owned PostgreSQL outbox gate covers concurrent `SKIP LOCKED`, active lease
  non-steal, expired lease recovery, lease-guarded transitions, delayed retry, terminal
  exhaustion and backlog: green.
- Spring Modulith boundary verification: green; notification depends only on
  `ordering::api`.
- Full `./gradlew :apps:api:check`: green.
- `git diff --check`: green; line-ending conversion warnings are informational.
