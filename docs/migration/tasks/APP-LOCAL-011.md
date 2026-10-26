# APP-LOCAL-011: Caesar pizza and visible topping audit

The owner requested online image research for Caesar pizza, a new photograph, and
an audit of all other pizzas for recognizable toppings. All ten current photos were
reviewed against their names/descriptions and delivery-menu references. The complete
audit and image-generation prompts are in `docs/migration/data/pizza-photo-v7-audit.md`.

Replace Caesar (previously visually indistinguishable from plain chicken pizza),
Julienne (higher view clarifies chicken/mushrooms), and Four Cheeses (higher view
and readable small blue-cheese patches). Retain seven already legible, appropriate
photos, including the recent Meat/Sicilian images. All new assets are immutable WebP
files in `photography-v4`, generated with the built-in image tool, using white plates
and restaurant backgrounds. Toppings are visible above the cheese; Caesar lettuce
is a recipe ingredient, not invented decorative garnish.

The v7 content release publishes menu version 4 with matching Caesar/four-cheese
descriptions, retaining all 19 printed names/prices and prior menu/order rows. No
schema or API contract changes. Existing publication guards and release markers
preserve later local edits and stop unexpected custom-menu replacement.

Validate frontend checks, production Docker startup, exact fresh/upgrade/replay
bootstrap with asset hashes, preserved existing-order counts, and browser photo
decoding, pizza dialogs and mobile framing. No live order/Telegram test sends.

Results: 76 frontend tests plus lint/typecheck passed. Production Docker startup
published version 4; existing order/outbox/order-line counts remain 25/25/55.
Isolated bootstrap passed exact current config/menu, 48 asset hashes, failure guards
and replay preserving edits and historical menu rows. Browser checks matched all
19 printed names/prices, decoded ten distinct pizza photos, verified the three
replacement dialogs/prices and checked widths 320, 390, 768, 1000 and 1440 px.
