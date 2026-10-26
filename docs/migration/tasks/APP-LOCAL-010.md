# APP-LOCAL-010: researched roll photography and active navigation

The owner requested online visual research for all four roll names and photos
that represent the dishes more accurately, plus navigation highlighting that follows
the selected/current section and compact links at tablet widths.

Research and full built-in image-generation prompts are recorded in
`docs/migration/data/roll-photo-v6-references.md`. Hot Shik is now a crispy fried
salmon roll rather than plain salmon maki. Caesar chicken visibly contains chicken,
lettuce and tomato; Caesar shrimp includes tempura shrimp, lettuce/tomato and a
light breadcrumb exterior. The approved baked-cheese photo already shows a melted
golden cap, so it remains. No raw cheese heaps or decorative garnish are added.
Three immutable WebP photos live in `photography-v3` and replace only their mappings.

The v6 content release publishes menu version 3 with three matching descriptions,
retaining all 19 names/prices and archiving prior rows for order history. Earlier
snapshots stay immutable; custom publications fail closed rather than being replaced.

Client navigation uses `aria-current=location` for exactly one selected section,
updates on anchor clicks, hash history and manual scrolling, and preserves an
explicit anchor selection until the next manual scroll. Tablet links are centered
with bounded gaps; underline positioning is relative to the active link instead
of a hardcoded first-child offset.

Validate frontend lint/typecheck/unit tests, production Docker build, isolated
bootstrap exact API/asset/replay checks, existing-order preservation and browser
navigation/scroll/layout at phone/tablet/desktop widths. No live order or Telegram
test messages are sent.

Results: 76 frontend tests, lint/typecheck and production Docker startup passed.
Isolated bootstrap accepted exact config/menu and 45 asset hashes, preserved edits
and archived menu rows on replay. Real local order/outbox/order-line counts remain
25/25/55. Browser checks passed all 19 printed names/prices, three revised photo
mappings, one active link, hash/click/manual-scroll tracking, underline alignment
and compact gaps at widths 390, 768, 1000 and 1440 px.
