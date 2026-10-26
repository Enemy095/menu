# APP-LOCAL-004: restore hero and show today's closure only

The user clarified that only the free-delivery-from-1000 promotional benefit should
be removed. Restore the existing food hero, burger image and menu CTA; retain the
other benefits and current branding. The public calendar now displays a bordered,
padded notice only for `closedToday=true`, without listing future/recurring closures.
Owner bot calendar and stored dates remain unchanged.

Validation: frontend unit tests cover the restored heading/CTA, absent delivery
promotion, hidden future-only announcements and today's notice. Lint/typecheck,
production Docker build and desktop/mobile browser layout checks passed. All 68
frontend tests passed. Browser checks confirmed the visible menu CTA at both widths,
hidden future-only notices, today's notice with padding/full border and no horizontal
overflow. Calendar responses were mocked only in the browser, leaving real dates unchanged.
