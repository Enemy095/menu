# Legacy to final single-client mapping

Status: `implemented for STAMBUL_APPROVED; production values gated`
Task: [SC-DATA-001](../tasks/SC-DATA-001.md)
Contract: `packages/api-contract/openapi/openapi.yaml`
Schema: Flyway V6 shadow model activated/finalized by V7

## Mapping rules

- Import exactly the owner-selected Stambul dataset; Adamas rows/assets/runtime branches are absent.
- Raw source text is preserved unless this document marks an owner-approved
  normalization.
- Legacy whole-ruble integer `price` becomes `price_minor = price * 100` using integer
  arithmetic.
- Legacy item ID becomes `menu_items.public_id`; Russian category name receives a
  frozen lowercase public ID listed below.
- Internal UUIDs must be deterministic for an identical approved bundle. The importer
  must publish its UUID namespace/algorithm; random IDs on retry are not acceptable.
- Empty strings representing missing optional data become SQL `NULL`, not public empty
  placeholders.
- `(latitude, longitude) = (0, 0)` with empty label becomes absent location.
- Raw manager phone is not imported. No notification destination is created by this
  slice and the worker remains disabled. A later delivery slice may supply a valid
  opaque secret-manager locator.

## Relational graph

| Source | Final destination | Transformation / invariant |
|---|---|---|
| selected dataset | `site_configuration` | singleton `1`, `active`, confirmed locale/currency/timezone |
| `brand`, theme CSS | `site_branding` | typed JSON objects below; required active logo reference |
| `profile` | `site_profile` | public contact + structured schedule; empty location/address removed |
| `features`, `limits`, order types | `site_features` | 13 known rows; exact config shape |
| selected config | `restaurants` | one `active`, `is_default=true` row |
| menu metadata | `menu_versions` | one `published` row, source version/time, `RUB` |
| categories | `menu_categories` | ordered, available, no images |
| items | `menu_items` | ordered within category, `price*100`, available, no images |
| no legacy modifiers | `menu_modifier_groups/options` | zero rows |
| branding PNG(s) | `assets` | unique bytes only, active, canonical hash key |
| WhatsApp assumption | `notification_destinations` | zero rows; destination intentionally deferred |
| none | order/outbox tables | zero rows; content import never creates runtime history |

### `site_configuration`

| Column | Stambul | Adamas | Gate |
|---|---|---|---|
| `singleton_id` | `1` | `1` | fixed schema invariant |
| `status` | `active` | `active` | owner confirms publication |
| `locale` | `ru-RU` | `ru-RU` | confirm normalized assumption |
| `currency` | `RUB` | `RUB` | confirm normalized assumption |
| `timezone` | `Europe/Moscow` for local/test | not selected | production requires explicit IANA override |

### `site_branding`

Common:

- `favicon_asset_id = NULL`;
- `logo_alt` is persisted separately from display name and is required for an active
  public projection;
- no Open Graph asset unless separately approved;
- `localized_texts` contains one `ru-RU` object;
- target JSON uses exact camelCase fields accepted by the current backend.

Stambul:

```json
{
  "display_name": "Ресторан-кафе у Ризвана",
  "logo_alt": "Логотип ресторан-кафе «У Ризвана»",
  "business_type": "Ресторан-кафе",
  "design_tokens": {
    "primaryColor": "#721F2A",
    "secondaryColor": "#4B1018",
    "accentColor": "#C8A467",
    "backgroundColor": "#F7F0E3",
    "surfaceColor": "#FFFDF8",
    "textColor": "#2E2020",
    "mutedTextColor": "#7C6A64",
    "borderRadius": "21px",
    "fontFamily": "Georgia, Times New Roman, serif"
  },
  "localized_texts": [{
    "locale": "ru-RU",
    "displayName": "Ресторан-кафе у Ризвана",
    "shortName": "у Ризвана",
    "businessTypeLabel": "Ресторан-кафе",
    "cuisineText": "Турецкая кухня",
    "slogan": "Вкус Стамбула рядом с вами",
    "introText": "Выбирайте любимые блюда — мы соберём заказ и передадим его менеджеру в WhatsApp.",
    "menuSectionTitle": "Выберите блюда",
    "cartTitle": "Ваш заказ",
    "orderTypeLegend": "Как хотите получить заказ?"
  }],
  "hero": {
    "title": "Вкус Стамбула рядом с вами",
    "subtitle": "Турецкая кухня",
    "ctaLabel": "Смотреть меню",
    "benefits": []
  },
  "seo_metadata": {
    "title": "У ризвана — электронное меню",
    "description": "Электронное меню ресторан-кафе «У Ризвана»",
    "canonicalUrl": "https://stambul.localhost"
  }
}
```

`logo_asset_id` references the Stambul logo. Stambul has no hero image.

Adamas:

```json
{
  "display_name": "Адамас",
  "business_type": "Служба доставки еды",
  "design_tokens": {
    "primaryColor": "#D4A843",
    "secondaryColor": "#242424",
    "accentColor": "#D4A843",
    "backgroundColor": "#0A0A0A",
    "surfaceColor": "#1A1A1A",
    "textColor": "#FFFFFF",
    "mutedTextColor": "#888888",
    "borderRadius": "12px",
    "fontFamily": "\"Segoe UI\", \"Helvetica Neue\", Arial, sans-serif"
  },
  "localized_texts": [{
    "locale": "ru-RU",
    "displayName": "Адамас",
    "shortName": "Адамас",
    "businessTypeLabel": "Служба доставки еды",
    "cuisineText": "Пицца • Роллы • Бургеры • Гарниры",
    "slogan": "ВКУСНАЯ ЕДА БЫСТРАЯ ДОСТАВКА",
    "introText": "Закажите любимые блюда — мы доставим быстро и горячо.",
    "menuSectionTitle": "Выберите блюда",
    "cartTitle": "Ваш заказ",
    "orderTypeLegend": "Как хотите получить заказ?"
  }],
  "hero": {
    "imageAssetId": "<Adamas content asset UUID>",
    "imageAlt": "Аппетитный бургер с сыром и овощами",
    "title": "ВКУСНАЯ ЕДА — БЫСТРАЯ ДОСТАВКА",
    "subtitle": "Пицца • Роллы • Бургеры • Гарниры",
    "ctaLabel": "Смотреть меню",
    "benefits": [
      {"icon": "🔥", "text": "Горячая еда"},
      {"icon": "⏱", "text": "От 40 минут"},
      {"icon": "🚗", "text": "Доставка от 1000 ₽ бесплатно"}
    ]
  },
  "seo_metadata": {
    "title": "Адамас — доставка еды",
    "description": "Доставка еды Адамас — пицца, роллы, бургеры, гарниры. Быстрая доставка.",
    "canonicalUrl": "<owner-approved HTTPS URL>"
  }
}
```

Adamas `logo_asset_id` and `hero.imageAssetId` reference the same `assets.id`. The
1000-RUB text may remain only after owner confirms it as publishable copy; it does not
configure fee calculation.

### `site_profile` and schedule

| Column | Stambul | Adamas |
|---|---|---|
| `public_phone` | `+7 (989) 442-81-81` | `+7 (999) 123-45-67` |
| `public_phone_label` | `Позвонить ресторану` | same |
| `public_email` | `NULL` | `NULL` |
| `address_text` | `NULL` | `NULL` |
| `latitude/longitude` | `NULL/NULL` | `NULL/NULL` |
| `social_links` | `[]` | `[]` |
| `working_hours_text` | `Ежедневно с 10:00 до 23:00` | same |

If owner confirms the text literally, `working_hours` becomes seven rows:

```json
[
  {"day":"monday","opensAt":"10:00","closesAt":"23:00"},
  {"day":"tuesday","opensAt":"10:00","closesAt":"23:00"},
  {"day":"wednesday","opensAt":"10:00","closesAt":"23:00"},
  {"day":"thursday","opensAt":"10:00","closesAt":"23:00"},
  {"day":"friday","opensAt":"10:00","closesAt":"23:00"},
  {"day":"saturday","opensAt":"10:00","closesAt":"23:00"},
  {"day":"sunday","opensAt":"10:00","closesAt":"23:00"}
]
```

This projection is approved for local/test with `Europe/Moscow`. Production requires
an explicit timezone and schedule approval; the local value is never a production
default.

### `site_features`

Create exactly these rows. `orders.config` is identical for both:

```json
{
  "limits": {
    "maxItemQuantity": 99,
    "maxTotalItems": 200,
    "maxNameLength": 80,
    "maxPhoneLength": 32,
    "maxAddressLength": 240,
    "maxCommentLength": 500,
    "delayedOrderConfirmationMinutes": 15
  }
}
```

Order-type configs:

```text
preorder: {"label":"Предзаказ","default":true}
delivery: {"label":"Доставка","default":false}
pickup:   {"label":"Самовывоз","default":false}
```

Every other feature has `{}` config.

| `feature_key` | Stambul | Adamas |
|---|---:|---:|
| `orders` | true | true |
| `contacts` | true | true |
| `geo_location` | decision: false unless coordinates supplied | false |
| `social_links` | false | false |
| `delivery` | true | true |
| `pickup` | true | true |
| `preorder` | true | true |
| `custom_theme` | true | true |
| `whatsapp_fallback` | true | true |
| `promotions` | false | false |
| `loyalty` | false | false |
| `reservations` | false | false |
| `delivery_blocks` | false | true |

Current backend derives `deliveryConfiguration` as `enabled=delivery feature`,
`addressRequired=true`, `feePolicy=none`.

### `restaurants`

One row only:

| Column | Stambul selection | Adamas selection |
|---|---|---|
| `id` | deterministic approved UUID | deterministic approved UUID |
| `external_key` | `stambul` | `adamas` |
| `slug` | `stambul` | `adamas` |
| `display_name` | `Ресторан-кафе у Ризвана` | `Адамас` |
| `status` | `active` | `active` |
| `is_default` | `true` | `true` |

Slug/external key remain restaurant business identifiers inside one deployment; they
are not request selectors and are not exposed in canonical public routes.

### Menu tables

- `menu_versions.version/published_at` preserve source metadata; `status=published`,
  `currency=RUB`.
- `menu_categories.public_id` mapping:

| Russian source | Public ID |
|---|---|
| Пицца | `pizza` |
| Роллы | `rolls` |
| Гарниры | `sides` |
| Бургеры | `burgers` |

- Category `sort_order` follows the selected source category array.
- Item `public_id`, name and description preserve source; `sort_order` is zero-based
  within category; `available=true`; `image_asset_id=NULL`; `currency=RUB`.
- Stambul prices map from 120–400 RUB to 12,000–40,000 minor units.
- Adamas prices map from 140–849 RUB to 14,000–84,900 minor units.
- Both modifier tables remain empty.

The complete ordered item inventory is in [source-inventory.md](source-inventory.md).

### Assets

| Dataset | `asset_type` | SHA-256 | Object key | References |
|---|---|---|---|---|
| Stambul | `logo` | `90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b` | `assets/logo/90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b.png` | branding logo |
| Adamas | implementation decision: one kind accepted by both references, or explicit normalized asset roles without accidental overwrite | `68eed81f6b021efd58113f4ab6edc8feef68d91336f600729c776a07ab6caaab` | canonical hash key for each approved kind | branding logo + hero |

The current schema uniqueness is `(sha256, asset_type)` and canonical key includes
`asset_type`. Before importing Adamas, asset ownership must decide how one physical
image legally satisfies both `LOGO` and `HERO` kind validation without copying bytes or
creating two conflicting semantic records. This is a real model compatibility gate.

All rows use verified byte length/content type/hash and `status=active`. Public API gets
only CDN URLs; object keys never enter the client response.

### Notification destination

The approved graph contains zero `notification_destinations`. The legacy manager phone
is only evidence that a WhatsApp destination once existed; it is not an approved
destination value. Worker remains disabled. Enabling delivery later requires a separate
approved slice with an opaque `secret://...` or `vault://...` reference.

## OpenAPI projection

| API field | Source/final owner |
|---|---|
| `restaurantId` | `restaurants.id` |
| `branding` | site branding JSON + CDN-resolved assets |
| `seo` | SEO JSON + optional Open Graph CDN URL |
| `profile` | site profile; schedule is separate |
| `features` | booleans from known feature rows |
| `orderTypes` | enabled preorder/delivery/pickup rows, exactly one default |
| `limits` | `orders.config.limits` public subset |
| `schedule` | timezone + display text + exactly seven structured days |
| `deliveryConfiguration` | delivery enabled, address required, fee policy none |
| `locale/currency/timezone` | singleton configuration |
| menu `version/publishedAt/currency` | published menu version |
| menu categories/items | available ordered rows; modifier groups nested per item |

Canonical order request differences from legacy:

- no `businessSlug`, body idempotency key, `salesChannel`, `clientCreatedAt` or trusted
  total;
- `Idempotency-Key` is required HTTP header;
- `guestCount` lives under fulfillment;
- delivery has required address; preorder/pickup use non-delivery fulfillment;
- each item contains a `modifiers` array, empty for these source menus;
- server resolves restaurant/menu, recalculates all totals and creates order/outbox.

## Remaining production gates

Dataset, local/test values, UUID namespace, exact repeated-space text, Stambul logo and
fee policy are resolved in the approved bundle. Production still requires an explicit
real IANA timezone, canonical HTTPS origin, contact/schedule/asset-rights approvals and
acknowledgement that notification remains disabled. No importer may convert local/test
values into production defaults.

UUIDv5 namespace is `e9b2ab7a-0a0a-5309-9403-39ed9ec91d4b`. The current
`category:<publicId>` and `item:<publicId>` names are frozen only for immutable menu v1;
future menu versions include version in those names, asset names include kind + SHA-256,
and the restaurant name remains stable.
