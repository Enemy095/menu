# APP-LOCAL-009: printed-menu parity and В.А.М. branding

Owner source: supplied В.А.М. ПИЦЦА menu photograph. Exact names and prices are
transcribed in `infra/local/bootstrap/v5/menu-source.json`: four rolls, ten pizzas,
three sides and two burgers (19 total). Цезарь and Жульен pizzas become 400 RUB;
all other printed prices agree with their retained counterparts. Remove the hot
salmon roll, Ойси, Ойси с курицей, Филадельфия, Маргарита and Охотничья from the
published menu. Add Мясная and Сицилийская at 350 RUB each.

The owner explicitly authorized typical delivery recipes for the new descriptions
and photographs. Their sources and complete built-in image-generation prompts are
in `docs/migration/data/vam-pizza-menu-photo-v5-prompts.md`. Photographs use white
plates and softly blurred restaurant backgrounds; final WebP assets are immutable,
content-addressed files in `apps/web/public/assets/menu/photography-v2`.

The v5 content release archives version 1 and publishes a new immutable version 2
with separate category/item IDs and retained public dish IDs. No old item/order
rows are deleted. Unknown manually published menus fail closed for operator review.
The release runs once on fresh and existing installations; its marker preserves
later local edits. Frozen v1–v4 snapshots/assets remain intact. Branding becomes
“В.А.М. Пицца” in configuration/SEO/footer/header; bot calendar text reads the
configured brand rather than keeping a hardcoded old name.

Validation covers frontend lint/typecheck/unit, Telegram/module tests, production
Docker, exact fresh public config/menu and all asset hashes, replay preserving price
and calendar edits, archived-menu retention and existing-order counts after upgrade.
Browser checks must compare all 19 names/prices with the source transcription,
decode all active photos, check new item dialogs and mobile branding/layout.

The owner also reported a tablet breakpoint where the hero image became an in-flow
block below its text. Restrict the correction to 560–1023 px: keep the photograph
absolutely positioned inside the hero with the text above it. Check both boundaries
and intermediate widths, alongside phone/desktop, after production build.

Results: 74 frontend tests and lint/typecheck passed; Telegram calendar/module tests
passed. Production Docker startup upgraded the real local version-1 installation:
25 orders, 25 outbox records and 55 order lines remained unchanged. Version 1 retains
23 archived items; published version 2 exposes exactly 19. Isolated bootstrap passed
fresh exact API/42 asset checks, integrity failures and replay preserving edits and
calendar rows. Browser checks matched all 19 source names/prices, decoded 19 unique
active photos, opened both new pizza dialogs and checked branding/hero geometry
at 320, 390, 559, 560, 640, 768, 900, 1000, 1023, 1024, 1280 and 1440 px.
No live orders or Telegram test messages were sent.
