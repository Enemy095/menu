# SC-DATA-001 — selected single-client dataset

Status: `blocked`
Decision: `decision-required`
Wave: P3
Dependencies: `SC-BE-001`, explicit production dataset selection

## Objective

Загрузить в single-client deployment ровно один одобренный content dataset после
явного решения владельца: `Stambul`, `Adamas` либо новый проверенный bundle.

Текущий документ закрывает только аналитическую часть задачи. Он фиксирует фактические
legacy-источники, различия двух datasets, mapping в final schema/OpenAPI, потери,
неподтверждённые значения и критерии выбора. Боевые данные не импортированы, dataset не
выбран.

Подробные артефакты:

- [source inventory](../data/source-inventory.md);
- [target mapping](../data/final-schema-mapping.md);
- [owner decision checklist](../data/dataset-decision-checklist.md).

## Non-goals

- Не выбирать Stambul или Adamas от имени владельца.
- Не хранить оба datasets в одном production deployment.
- Не добавлять runtime slug/host switch, tenant/client context или compatibility row.
- Не менять Java, OpenAPI, Flyway, frontend или legacy source.
- Не копировать raw WhatsApp destination в manifest, SQL, logs или документацию.
- Не считать marketing copy operational delivery policy.
- Не запускать importer и не записывать business fixtures в production Flyway.

## Immutable decisions

- Один deployment обслуживает одного клиента и один production dataset.
- Host не выбирает business data; canonical public routes — `/`,
  `/api/v1/public/config`, `/api/v1/public/menu`, `/api/v1/public/orders`.
- PostgreSQL — источник истины; assets до публикации становятся immutable
  content-addressed objects в S3-compatible storage.
- Цена импортируется в integer minor units; backend пересчитывает order total.
- Notification destination задаётся server-side opaque reference формата
  `secret://...` или `vault://...`; raw legacy manager phone не импортируется.
- Publication должна быть non-HTTP, idempotent и single-dataset. Частичная публикация
  public config/menu недопустима.
- Historical `docs/migration/import/**` относится к отменённому two-tenant design и не
  является executable specification для этой задачи.

## Exclusive file ownership

До разблокировки этот task владеет только:

```text
docs/migration/tasks/SC-DATA-001.md
docs/migration/data/**
docs/migration/tasks/README.md  # только status/decision note
```

Runtime code, migrations, OpenAPI, generated client и `Электронное меню/` вне scope.
После выбора dataset отдельный implementation slice обязан объявить собственный
disjoint file scope до создания importer.

## Evidence baseline

Аудит выполнен по raw legacy-файлам, а не по названиям ресторанов в старом manifest:

- `Электронное меню/restaurants/{stambul,adamas}/config.js`;
- `features.js`, `order-types.js`, `menu.js` каждого dataset;
- `themes/stambul/theme.css`, `themes/delivery/theme.css`;
- `index.html`, `app.js`, `validation.js`, `order-submit.js`;
- `features/delivery-blocks.js`;
- PNG assets обоих datasets.

SHA-256, byte length и image dimensions зафиксированы в
[source inventory](../data/source-inventory.md). Любое изменение source bytes после
approval инвалидирует решение и требует повторной инвентаризации.

## Dataset summary

| Dimension | Stambul | Adamas |
|---|---|---|
| Product identity | Ресторан-кафе у Ризвана | Адамас, служба доставки еды |
| Theme | светлый burgundy/gold, Georgia | тёмный gold/black, Segoe UI |
| Menu | 4 categories, 23 items, 120–400 RUB | 4 categories, 19 items, 140–849 RUB |
| Menu publication | v1, 2026-07-01 | v1, 2026-07-13 |
| Default order type | preorder | preorder |
| Enabled order types | preorder, delivery, pickup | preorder, delivery, pickup |
| Public contacts | phone + text schedule | phone + text schedule |
| Location | zero coordinates with feature enabled; unusable | zero coordinates; feature disabled |
| Dataset-specific UI | standard hero/menu/cart | delivery hero, popular items, category cards, benefits |
| Asset bytes | one 197×179 logo PNG | one 658×434 burger PNG reused as logo and hero |
| Submit assumption | direct WhatsApp redirect | direct WhatsApp redirect |

Оба legacy datasets используют общий cart/checkout shell и одинаковые limits:

- 99 units per item, 200 total units;
- name 80, phone 32, address 240, comment 500 characters;
- guest count UI range 1–99;
- preorder/delivery/pickup, delivery address conditional;
- cart и selected order type сохраняются, PII не сохраняется;
- repeat submit использует client idempotency draft, но legacy WhatsApp redirect не
  создаёт durable order.

## Target representation

После выбора будет создан только один graph:

```text
site_configuration (singleton)
├── site_branding (singleton JSON projections + asset references)
├── site_profile (singleton schedule/contacts)
├── site_features (typed feature rows/config)
└── restaurants (one active default restaurant)
    └── menu_versions (one published version)
        ├── menu_categories
        ├── menu_items
        ├── menu_modifier_groups (empty for both legacy datasets)
        └── menu_modifier_options (empty for both legacy datasets)

assets (one content-addressed row per unique object)
notification_destinations (opaque reference only)
```

No order, order item, order history, outbox event or delivery is created by content
import.

### Required normalized constants

Оба текущих datasets требуют owner confirmation для:

- `locale = ru-RU`;
- `currency = RUB`;
- IANA business timezone (historical draft assumes `Europe/Moscow`, source does not
  prove it);
- canonical HTTPS URL;
- whether the public phone is real production contact;
- opaque WhatsApp/provider reference;
- structured weekly schedule derived from text `Ежедневно с 10:00 до 23:00`;
- delivery fee/minimum-order policy.

`canonicalUrl`, timezone и destination reference must be supplied at deployment; source
placeholders are never persisted as active production values.

## Known incompatibilities and intentional losses

### Shared legacy incompatibilities

1. Legacy payload fields `businessSlug`, `salesChannel`, `clientTotal`,
   `clientCreatedAt` and body `idempotencyKey` do not map one-to-one to canonical
   OpenAPI. The route has no slug, `Idempotency-Key` is a header, server total is
   authoritative, and `salesChannel` is server-owned.
2. `guestCount` is present in UI and validated but omitted from the legacy payload.
   Canonical OpenAPI intentionally carries it under `fulfillment`.
3. Legacy order validation and WhatsApp submission read global Stambul config in parts
   of the flow. That bug is not parity and cannot determine Adamas rules or destination.
4. Legacy schedule is only display text. Target API requires exactly seven structured
   days plus timezone; conversion is an explicit business assertion.
5. Both locations are `(0, 0)` with empty label. They map to absent location, never to
   Gulf of Guinea coordinates.
6. No menu item has an image and neither dataset defines modifiers. Target arrays remain
   empty/omitted according to contract; fake images/modifiers are not synthesized.
7. No source proves favicon, Open Graph image, public email, social links, address,
   delivery fee or minimum order.
8. Legacy `maxFinalMessageLength=3500` belongs to the browser WhatsApp adapter and has no
   target `OrderLimits` field.
9. Legacy direct WhatsApp navigation is replaced by durable order + transactional
   outbox; browser no longer receives the destination.
10. Legacy slug-scoped cart key is not data-import input. New cart is deployment-local,
    versioned and reconciled against `restaurantId`/menu version.

### Stambul-specific decisions

- `geoLocation=true` conflicts with zero coordinates; target feature may stay true only
  if real coordinates and label are supplied, otherwise it must be false.
- `pizza-four-cheese` contains four spaces in the visible name. Exact preservation is
  safest for source parity, but owner may approve normalization to one space; this is a
  content decision, not automatic cleanup.
- There is no source hero image. Target Hero still requires title and CTA; text can be
  mapped from slogan/menu copy without inventing an image.
- `Турецкая кухня` branding and the actual menu (rolls, pizza, sides, burgers) may be a
  product inconsistency requiring owner review, not mapper correction.

### Adamas-specific decisions

- `deliveryBlocks` is real visible behavior, not just a boolean. It covers a two-column
  hero, up to four derived popular items, category navigation cards and delivery
  benefits. New frontend must implement these as shared data-driven widgets.
- Popular items are algorithmically derived from the first Pizza, Burger and Roll, then
  filled from menu order. Source has no editorial `popular` flag.
- The same burger PNG is used as both logo and hero, but current asset-kind validation
  expects separate `LOGO` and `HERO` kinds while object keys include kind. Owner of the
  asset model must explicitly approve two semantic rows/keys or a safe shared-kind
  design; importer must not guess or silently upload conflicting bytes.
- `Доставка от 1000 ₽ бесплатно`/`Бесплатно от 1000 ₽` is marketing text only. Current
  OpenAPI supports `feePolicy=none`; it cannot express a 1000 RUB threshold. Do not
  enforce or advertise it as operational policy without a separate contract decision.
- Delivery blocks hardcode fallback image/content and DOM behavior. Only approved
  visible copy/structure migrates; detached-node and slug-specific implementation does
  not.

## Dataset selection criteria

Owner selection must be evidence-based. Required questions:

1. Which legal/operational business receives real orders?
2. Which public phone, name and menu are current and approved for publication?
3. Is a restaurant-style or delivery-style public experience required?
4. Are all item names, descriptions, prices and availability current?
5. Are the three order types actually supported operationally?
6. What timezone and exact seven-day opening schedule apply?
7. Is delivery free, paid or threshold-based, and may the Adamas `1000 ₽` claim be
   published?
8. Which canonical domain and SEO copy are approved?
9. Which opaque notification destination will receive orders?
10. Are source asset rights and public use approved?

Selection outcomes:

- `STAMBUL_APPROVED` — import only Stambul; Adamas absent from DB/object publication.
- `ADAMAS_APPROVED` — import only Adamas; Stambul absent from DB/object publication.
- `NEW_BUNDLE_REQUIRED` — neither source is current; owner supplies a new versioned
  bundle satisfying the same checklist.
- `REJECTED` — no production import and downstream content-dependent tasks remain
  blocked.

Silence, repository branch name, first registry entry, host, path or existing historical
manifest is not a selection.

## Implementation sequence after unblock

1. Record signed/traceable owner decision and immutable selected source digest.
2. Resolve all checklist items; reject placeholders and raw notification secrets.
3. Produce a single-dataset normalized bundle matching final OpenAPI constraints.
4. Validate UTF-8, IDs, cardinality, money conversion, schedule, asset bytes/hash/type
   and opaque reference syntax without writes.
5. Upload/verify unique immutable assets under `assets/{kind}/{sha256}.{ext}`.
6. Run one non-HTTP idempotent publication operation against an empty content graph.
7. In one PostgreSQL transaction write site, profile, features, restaurant, published
   menu and destination metadata; never create orders/outbox.
8. Re-run with identical digest and prove no-op/same result; different digest must fail
   unless an explicit content-version operation is approved.
9. Read `/api/v1/public/config` and `/api/v1/public/menu`, compare normalized body and
   representation hash with the approved bundle.
10. Prove the unselected dataset has no rows, asset references, secrets or runtime mode.
11. Store redacted import evidence and keep rollback inputs until cutover window closes.

## Required tests

- source checksum and UTF-8 verification;
- exact 23/19 item counts and category order for the selected dataset;
- decimal RUB → minor-unit conversion (`price * 100`) without floating point;
- stable public IDs and deterministic UUID policy chosen before implementation;
- exactly one active default restaurant and one published menu;
- config/menu typed projection equals approved fixture;
- all public asset references resolve to active verified objects;
- Adamas logo/hero dual-kind decision is tested without accidental overwrite; Stambul
  has no invented hero;
- no raw manager phone or destination locator appears in output/logs/client bundle;
- identical import is idempotent; different digest is conflict/fail-closed;
- failed asset/database stage leaves no partially public graph;
- neither import creates orders, outbox events or notification deliveries;
- unselected dataset search is empty;
- OpenAPI contract validation and config/menu integration tests pass.

## Acceptance commands after implementation

Commands are intentionally deferred until a dataset and importer shape are approved.
At minimum the implementation handoff must include:

```text
pnpm --filter @e-menu/api-contract validate
./gradlew :apps:api:test --tests "*DataImport*" --tests "*Site*" --tests "*Menu*" --tests "*Asset*"
./gradlew :apps:api:check
git diff --check
```

The current documentation-only checkpoint requires Markdown link review,
source/checksum spot checks and `git diff --check`; it does not authorize Gradle,
database writes or object uploads.

## Failure and edge cases

- Any source checksum mismatch after approval: stop and regenerate inventory.
- Both datasets selected, no dataset selected or selection inferred indirectly: stop.
- Unknown timezone, schedule, canonical URL or destination: remain blocked.
- Invalid/missing image bytes, hash or media type: no publication.
- Same public ID with changed semantics: explicit menu version/content decision.
- Raw provider destination in bundle: reject and redact evidence.
- Existing content rows in target DB: compare provenance/digest; do not merge silently.
- Marketing delivery threshold without executable policy: omit claim or add approved
  contract/ADR before publication.
- Partial S3 upload: unused immutable object may be garbage-collected only after proven
  unreferenced; database publication remains rolled back.

## Current blocker

`SC-DATA-001` remains `blocked / decision-required` until the owner supplies the completed
[decision checklist](../data/dataset-decision-checklist.md). Consequently
`SC-WEB-001` remains content-blocked. Structural frontend work may be separately planned,
but no production dataset or client-specific UI mode may be inferred.

## Handoff report format

```text
Task ID: SC-DATA-001
Decision artifact: <id/link>
Selected dataset: STAMBUL_APPROVED | ADAMAS_APPROVED | NEW_BUNDLE_REQUIRED
Source digest: <sha256>
Resolved assumptions: <timezone/domain/schedule/delivery/contact/assets>
Opaque destination readiness: <ready/not ready, fingerprint only>
Changed files: <paths>
Validation commands: <command + result>
Database/object-store result: <redacted counts/digests>
Public config/menu parity: <hashes>
Unselected dataset absence proof: <result>
Unresolved issues: <none/list>
```
