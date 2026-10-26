# APP-LOCAL-003: provision Telegram order destination on Windows startup

Implemented. A copied credentials file enabled the provider and worker, but a fresh
bootstrap deliberately excluded notification destinations. Orders consequently
failed with `DESTINATION_NOT_CONFIGURED` before any Telegram call.

The launcher now validates enabled Telegram credentials and logical secret reference,
then upserts the canonical `telegram` / `restaurant-orders` destination after healthy
startup. Repeated launches preserve the row without duplicate destinations; disabled
canonical destinations are reactivated. Credentials remain in container environment,
never SQL or output. Other channels/destinations and failed events remain unchanged.
No schema, public contract or application architecture changes are required.

Validation: isolated temporary-table transaction checks fresh registration, repeated
registration and reactivation. Actual Docker launcher verifies the existing configured
installation without sending test messages. Existing failed orders need explicit
recovery after inspecting their outbox state; they are not silently replayed.
