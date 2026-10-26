# SC-TG-001 — Telegram order notification provider

Status: `done`
Wave: P6
Dependencies: `SC-OUT-001`

## Objective

Deliver committed `order.created` events to one server-configured Telegram private
chat or group through the official HTTPS Bot API. The order transaction remains
independent from Telegram availability.

## Decisions

- `notification` owns formatting, Bot API transport and error classification.
- The provider is opt-in through `TELEGRAM_NOTIFICATIONS_ENABLED=true`.
- Token and chat ID exist only in runtime secrets/environment.
- `notification_destinations` stores only
  `secret://application/telegram/orders`.
- Telegram HTML is escaped and message length is kept below the Bot API limit.
- `429`, timeout, network and `5xx` failures are transient; invalid token, forbidden
  chat and invalid request failures are permanent.
- Delivery remains at-least-once because Telegram `sendMessage` has no provider
  idempotency key.
- No webhook, inbound command handler, Telegram login or frontend token is added.

## Runtime flow

```text
POST /api/v1/public/orders
→ PostgreSQL order + order.created outbox event
→ notification worker
→ TelegramOrderMessageFactory
→ TelegramBotApiClient.sendMessage
→ notification delivery audit
→ complete / retry / dead-letter
```

## Configuration

```text
TELEGRAM_NOTIFICATIONS_ENABLED
TELEGRAM_BOT_TOKEN
TELEGRAM_CHAT_ID
TELEGRAM_MESSAGE_THREAD_ID
TELEGRAM_DESTINATION_SECRET_REFERENCE
APP_WORKER_ENABLED
```

Local secrets live in ignored `.env.telegram.local`. Production secrets are
injected by the deployment secret manager.

## Verification

- Formatter tests cover HTML escaping, money, items, modifiers and malformed events.
- Provider tests cover success, invalid payload and unknown destination.
- Transport tests cover request JSON, message ID and rate-limit classification.
- Existing notification/outbox tests continue to cover retry, audit and crash recovery.

## Acceptance commands

```text
./gradlew :apps:api:test --tests <Telegram test classes>
./gradlew :apps:api:test --tests <Notification and Outbox test classes>
./gradlew :apps:api:check
git diff --check
```
