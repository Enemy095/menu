# APP-LOCAL-005: closed-day ordering guard and reopening date

The user requested that closed days disable order creation while keeping the menu
browsable, show the first working date after consecutive closures, and stop duplicate
Telegram command replies.

## Decisions and interfaces

- The existing calendar remains owned by restaurant. Ordering now depends on its
  public API and rejects new orders on the current closed business date, including
  empty weekly schedule days, before creating order/outbox records. Existing identical
  idempotent replays retain their original response.
- OpenAPI adds `RESTAURANT_CLOSED` under HTTP 409 and optional nullable
  `nextOpenDate` to public availability. The TypeScript client is regenerated.
- Reopening scans the next 365 days, combining explicit dates and weekly rules;
  null means no known opening in that horizon. No guessed date is displayed.
- One browser provider refreshes availability every 30 seconds/on focus. While
  initially unknown, ordering is disabled. On closure, add/quantity/checkout actions
  are disabled, existing cart contents remain, and dish details and cart still open
  with the explanation and reopening date. The server also rejects direct POSTs.
- Telegram polling commits one command per transaction, so a later command failure
  does not replay earlier successful replies. External delivery remains at-least-once.
  Independent databases must never poll one bot token simultaneously. The local test
  installation's calendar was disabled, leaving order notifications enabled; the
  installation serving the owner's website must be the sole calendar consumer.

## Validation and handoff

Frontend lint/typecheck and tests, OpenAPI lint/semantic/deterministic-generation
checks, targeted module/order/controller/calendar/poller PostgreSQL tests, Docker
build/start and browser checks cover the change. Test Telegram gateways are mocks;
browser closure responses do not change real dates or send orders. Existing database
content and bot credentials are not committed. No schema migration is needed.

The shared branch is updated through Git; the serving installation runs the launcher
after pulling. Its calendar flag must remain enabled, with the token disabled for
calendar polling on every other independent installation.

Results: all 69 frontend tests and lint/typecheck passed; all 20 contract semantic
tests, lint, deterministic generation and contract typecheck passed. Targeted backend
order/module/controller/calendar/poller tests passed, including PostgreSQL tests for
no order/outbox on rejection, replay of accepted orders and no repeat of an earlier
reply after a later command fails. The additional schedule-closure unit test passed.
Actual Docker services became healthy. Browser acceptance passed open→closed→open
refresh, disabled actions, preserved cart, detail/cart explanation and reopening
date with no order POSTs or live Telegram test sends.
