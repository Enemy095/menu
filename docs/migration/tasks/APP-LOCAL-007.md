# APP-LOCAL-007: shared pickup address and Yandex Maps previews

Owner-provided source: https://yandex.com/maps/-/CXV~EHM0 . Verified redirected
house page and placemark: longitude 46.566266, latitude 43.225478, Republic of
Daghestan, Khasavyurt, Sadovy, 20th Street, 12. Public display address is Russian.

Use existing typed `profile.address`/`profile.location` fields; no new contract or
schema is needed. Frozen v1/v2 bundles remain immutable. The explicit v3 content
release sets the address/coordinates and enables geo-location once for the selected
dataset, preserving operational and menu data. Current API expectations and checksums
are versioned alongside it.

A shared entity component renders a lazy embedded Yandex Maps preview with an
accessible external link and visible address. Contacts places it on the right;
mobile stacks it below contact details. Checkout shows the same component between
fulfillment selection and customer fields only for pickup. Clicking the preview or
address opens Yandex Maps in a new tab. No frontend address/coordinates are hardcoded.

Validate frontend lint/typecheck/unit tests, conditional checkout map, production
Docker/start, real map preview/browser layout and isolated fresh/upgrade/replay
bootstrap acceptance including expected config and preserved prices/closures.

Results: all 74 frontend tests, lint and typecheck passed. Production Docker startup
provisioned the exact Russian address and coordinates. The isolated bootstrap test
passed exact fresh API expectations, asset integrity, pending branding/address upgrade
and price/calendar preservation. Browser checks passed desktop/mobile contacts and
pickup-only checkout, widget position before the name field and correct Yandex link
coordinates, without creating an order. The live embedded map loaded its street
tiles and marker. No original snapshots or operational data were reset.
