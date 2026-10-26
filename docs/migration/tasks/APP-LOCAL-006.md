# APP-LOCAL-006: readable Telegram orders and public work calendar

The user requested a blank line between fulfillment and customer details, bold
fulfillment value and a readable timestamp, plus a third navigation link opening
a compact calendar below contacts with closures highlighted red.

The Telegram HTML formatter now separates the sections, bolds the fulfillment
value and formats the received instant as `dd.MM.yyyy HH:mm:ss` in the configured
business timezone. Existing HTML escaping and message limits remain in effect.
There are no live Telegram test sends.

`#work-schedule` follows contacts and shares the existing availability provider:
explicit closures and weekly closures come from the same public endpoint as the
checkout guard, refreshed every 30 seconds/on focus. The calendar shows the current
month and upcoming months, red closed days, today's gold outline and accessible
date/status labels. Past dates and dates outside the one-year availability horizon
are dimmed instead of inventing historical/future availability. Header navigation
includes the work-schedule link on desktop and mobile. No API/schema changes or
stored calendar edits are needed.

Validation: frontend lint/typecheck/unit tests, mocked refresh/month-navigation
tests, Telegram formatting/provider/HTTP tests and module verification, production
Docker build/start and desktop/mobile browser navigation/layout checks. Browser
fixtures do not change real closure dates or create orders. Publish through the
shared branch; other installations pull and run `start-docker.cmd`.

Results: 71 frontend tests passed with lint/typecheck; Telegram formatter/provider/
HTTP and module tests passed. Production Docker startup and desktop/mobile browser
checks passed, including visible header link, scroll target, explicit/weekly red days,
month navigation and no horizontal overflow. Preview calendar dates are browser
fixtures; real stored closures were not edited.
