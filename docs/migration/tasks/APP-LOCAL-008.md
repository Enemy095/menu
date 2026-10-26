# APP-LOCAL-008: delivery and pickup only

Owner requested removing preorder from checkout. The explicit v4 content release
disables the preorder feature and its default flag, enables delivery/pickup and
selects delivery as default. The existing typed configuration drives checkout and
server validation, so direct preorder requests are also rejected without deleting
existing orders or changing the historical contract. No schema or frontend code
changes are required. Frozen v1–v3 content stays unchanged.

Validate exact fresh public configuration, upgrade from the previous preorder
default, preserved menu prices/calendar settings and browser checkout with only
delivery/pickup radios. Preserve configured pickup map and existing cart contents.

Results: Docker startup passed; public API returns delivery(default)/pickup only
with preorder disabled. Isolated bootstrap acceptance passed fresh exact API checks
and upgrade from a restored preorder default without changing prices or closures.
Browser acceptance passed two fulfillment radios, default delivery, absence of
preorder and the working pickup map, without submitting an order.
