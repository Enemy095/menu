# MIG-001 deterministic mapping inventory

## Общие правила

- Импортируется ровно два tenant: `stambul` и `adamas`, финальный status `active`, по одному active default restaurant.
- `tenantId` и internal UUID не берутся из OpenAPI examples. Rerun находит существующие rows по tenant-scoped natural keys.
- Locale/currency: `ru-RU`/`RUB`.
- `Europe/Moscow` — временная assumption, а не факт legacy; production activation требует подтверждения.
- `priceMinor = legacy integer RUB × 100`. Floating point не используется.
- `sourceOrder` — zero-based позиция в исходном JS array. Category `sortOrder` равен category source order; item `sortOrder` zero-based внутри категории.
- Все legacy items считаются `available=true`: legacy не содержит availability field и отображает каждый item.
- Пустой `image` становится `imageAssetId=null`; строковый пустой URL не сохраняется.
- Координаты `(0, 0)` вместе с пустым label становятся отсутствующей location, не географической точкой.

## Public restaurant mapping

| Target | Stambul | Adamas | Источник/преобразование |
|---|---|---|---|
| `slug` | `stambul` | `adamas` | `config.slug`, сверяется с registry и directory |
| restaurant display name | Ресторан-кафе у Ризвана | Адамас | `brand.displayName` |
| `branding.businessType` | Ресторан-кафе | Служба доставки еды | `brand.businessTypeLabel` |
| `logoAssetId` | asset role `LOGO` | asset role `LOGO` | AST tenant-scoped import; Adamas использует те же bytes, что HERO |
| `faviconAssetId` | absent | absent | legacy files отсутствуют; OpenAPI example URL не является source |
| localized display/short/type/cuisine/slogan/intro | exact legacy values | exact legacy values | `brand.*`; punctuation/whitespace сохраняются |
| `menuSectionTitle` | Выберите блюда | Выберите блюда | `brand.menuSection.title` |
| `cartTitle` | Ваш заказ | Ваш заказ | `brand.cartTexts.title` |
| `orderTypeLegend` | Как хотите получить заказ? | Как хотите получить заказ? | `brand.orderTexts.typeLegend` |
| hero image | absent | asset role `HERO` | Fixture хранит role; importer записывает internal asset ID, public URL batch-resolved |
| hero title | slogan | `titleLine1 + " — " + titleLine2` | deterministic composition; пробелы нормализуются только вокруг dash |
| hero eyebrow | Наше меню | Доставка еды | Stambul `menuSection.eyebrow`; Adamas contract copy, требует content confirmation |
| hero subtitle | Турецкая кухня | Пицца • Роллы • Бургеры • Гарниры | cuisine/hero subtitle |
| hero CTA | Смотреть меню | Смотреть меню | Adamas direct; Stambul contract-required shared CTA default |
| hero benefits | `[]` | 3 exact legacy benefits | `hero.benefits` |
| SEO title/description | exact `brand.pageTitle/metaDescription` | exact `brand.pageTitle/metaDescription` | OpenAPI shortened variants не используются |
| canonical URL | required environment config | required environment config | legacy не содержит trusted domain; custom domain не активируется |
| OG asset | absent | absent | OpenAPI example `og.jpg` отсутствует в legacy |
| public phone | explicit `profile.phone` | explicit `profile.phone` | это public contact, не manager destination |
| public phone label/hours | exact legacy | exact legacy | `profile.publicPhoneLabel/workingHoursText` |
| address | absent | absent | legacy empty string -> null |
| location | absent | absent | `(0,0,"" )` -> null |
| social links | `[]` | `[]` | exact legacy |
| limits | 7 required values | 7 required values | `limits`; optional thresholds не выводятся из marketing copy |
| menu version | `1` | `1` | config/menu equality validated |

### Design tokens

Tokens берутся из реально подключаемых legacy theme CSS, а не из illustrative OpenAPI examples.

| Token | Stambul | Adamas | Legacy source |
|---|---:|---:|---|
| primary | `#721F2A` | `#D4A843` | `--color-primary` |
| secondary | `#4B1018` | `#242424` | `--burgundy-dark` / `--color-card-hover` |
| accent | `#C8A467` | `#D4A843` | `--color-accent` |
| background | `#F7F0E3` | `#0A0A0A` | `--color-background` |
| surface | `#FFFDF8` | `#1A1A1A` | `--color-surface` |
| text | `#2E2020` | `#FFFFFF` | `--color-text` |
| muted text | `#7E6C66` | `#888888` | `--color-muted` |
| border radius | `21px` | `12px` | `--radius-card` |
| font family | `Georgia, "Times New Roman", serif` | `"Segoe UI", "Helvetica Neue", Arial, sans-serif` | root/theme body font |

`secondaryColor` не имеет одинакового legacy semantic token и является явным adapter mapping. Изменение требует fixture update и migration review.

## Feature rows и order types

Создаются ровно 13 tenant-scoped rows. Missing `deliveryBlocks` у Stambul трактуется как false по legacy defaults/feature registry.

| `feature_key` | Stambul | Adamas | Config |
|---|---:|---:|---|
| `orders` | true | true | `{limits:{...}}` |
| `contacts` | true | true | `{}` |
| `geo_location` | true | false | `{}`; Stambul conflict с absent location |
| `social_links` | false | false | `{}` |
| `delivery` | true | true | `{label:"Доставка",default:false}` |
| `pickup` | true | true | `{label:"Самовывоз",default:false}` |
| `preorder` | true | true | `{label:"Предзаказ",default:true}` |
| `custom_theme` | true | true | `{}` |
| `whatsapp_fallback` | true | true | `{}` |
| `promotions` | false | false | `{}` |
| `loyalty` | false | false | `{}` |
| `reservations` | false | false | `{}` |
| `delivery_blocks` | false | true | `{}` |

`orderTypes` строится только из enabled `preorder|delivery|pickup` rows. Ровно один enabled row имеет `default=true`; порядок public array: preorder, delivery, pickup. `legacyValue` не является API field.

`orders.config.limits` для обоих tenant:

```json
{
  "maxItemQuantity": 99,
  "maxTotalItems": 200,
  "maxNameLength": 80,
  "maxPhoneLength": 32,
  "maxAddressLength": 240,
  "maxCommentLength": 500,
  "delayedOrderConfirmationMinutes": 15
}
```

`maxFinalMessageLength` относится к legacy WhatsApp renderer и не входит в public limits. `minOrderMinor=50000` из OpenAPI Adamas example не имеет legacy source. Текст «Доставка от 1000 ₽ бесплатно» не превращается автоматически в operational `freeDeliveryThresholdMinor`; оба значения требуют подтверждённой business configuration.

## Category mapping

| Tenant | Source order | Legacy name | Stable `categoryId` |
|---|---:|---|---|
| Stambul | 0 | Роллы | `rolls` |
| Stambul | 1 | Пицца | `pizza` |
| Stambul | 2 | Гарниры | `sides` |
| Stambul | 3 | Бургеры | `burgers` |
| Adamas | 0 | Пицца | `pizza` |
| Adamas | 1 | Роллы | `rolls` |
| Adamas | 2 | Бургеры | `burgers` |
| Adamas | 3 | Гарниры | `sides` |

Category images/descriptions отсутствуют. IDs стабильны и tenant/menu-version scoped.

## Все 42 menu items

### Stambul — 23

| Source | In category | Stable legacy ID | Category ID | Name | `priceMinor` | Available | Image |
|---:|---:|---|---|---|---:|---:|---|
| 0 | 0 | `cezar-roll-chicken` | `rolls` | Ролл Цезарь с курицей | 25000 | true | absent |
| 1 | 1 | `cezar-roll-shrimp` | `rolls` | Ролл Цезарь с креветкой | 30000 | true | absent |
| 2 | 2 | `sake-maki` | `rolls` | Ролл Горячий шик | 30000 | true | absent |
| 3 | 3 | `hot-roll-salmon` | `rolls` | Горячий ролл с лососем | 35000 | true | absent |
| 4 | 4 | `cheese-baked-roll` | `rolls` | Ролл Сырный запечённые | 30000 | true | absent |
| 5 | 5 | `bonito-roll` | `rolls` | Ролл Ойси | 40000 | true | absent |
| 6 | 6 | `philadelphia-chicken-roll` | `rolls` | Ролл Ойси с курицей | 35000 | true | absent |
| 7 | 7 | `philadelphia-roll` | `rolls` | Ролл Филадельфия | 40000 | true | absent |
| 8 | 0 | `pizza-caesar` | `pizza` | Пицца Цезарь | 35000 | true | absent |
| 9 | 1 | `pizza-pepperoni` | `pizza` | Пицца Пепперони | 35000 | true | absent |
| 10 | 2 | `pizza-mushroom` | `pizza` | Пицца Грибная | 35000 | true | absent |
| 11 | 3 | `pizza-cheese` | `pizza` | Пицца Сырная | 35000 | true | absent |
| 12 | 4 | `pizza-julienne` | `pizza` | Пицца Жульен | 35000 | true | absent |
| 13 | 5 | `pizza-assorti` | `pizza` | Пицца Ассорти | 35000 | true | absent |
| 14 | 6 | `pizza-margherita` | `pizza` | Пицца Маргарита | 35000 | true | absent |
| 15 | 7 | `pizza-chicken` | `pizza` | Пицца Куриная | 35000 | true | absent |
| 16 | 8 | `pizza-four-cheese` | `pizza` | Пицца    4 сыра | 35000 | true | absent |
| 17 | 9 | `pizza-hunter` | `pizza` | Пицца Охотничья | 35000 | true | absent |
| 18 | 0 | `french-fries` | `sides` | Картофель фри | 12000 | true | absent |
| 19 | 1 | `village-potato` | `sides` | Картофель по-деревенски | 12000 | true | absent |
| 20 | 2 | `nuggets` | `sides` | Наггетсы | 20000 | true | absent |
| 21 | 0 | `burger-beef` | `burgers` | Бургер говяжий | 27000 | true | absent |
| 22 | 1 | `burger-chicken` | `burgers` | Бургер куриный | 27000 | true | absent |

### Adamas — 19

| Source | In category | Stable legacy ID | Category ID | Name | `priceMinor` | Available | Image |
|---:|---:|---|---|---|---:|---:|---|
| 0 | 0 | `pizza-pepperoni` | `pizza` | Пицца Пепперони | 59000 | true | absent |
| 1 | 1 | `pizza-margherita` | `pizza` | Пицца Маргарита | 49000 | true | absent |
| 2 | 2 | `pizza-four-cheese` | `pizza` | Пицца 4 сыра | 62000 | true | absent |
| 3 | 3 | `pizza-chicken` | `pizza` | Пицца Куриная | 55000 | true | absent |
| 4 | 4 | `pizza-hawaiian` | `pizza` | Пицца Гавайская | 54000 | true | absent |
| 5 | 5 | `pizza-mushroom` | `pizza` | Пицца Грибная | 57000 | true | absent |
| 6 | 0 | `roll-philadelphia` | `rolls` | Сет Филадельфия | 84900 | true | absent |
| 7 | 1 | `roll-california` | `rolls` | Калифорния | 69000 | true | absent |
| 8 | 2 | `roll-hot-salmon` | `rolls` | Горячий ролл с лососем | 72000 | true | absent |
| 9 | 3 | `roll-dragon` | `rolls` | Дрэгон ролл | 79000 | true | absent |
| 10 | 4 | `roll-spicy-tuna` | `rolls` | Спайси тунец | 75000 | true | absent |
| 11 | 0 | `burger-classic` | `burgers` | Чизбургер | 35000 | true | absent |
| 12 | 1 | `burger-double` | `burgers` | Дабл чизбургер | 49000 | true | absent |
| 13 | 2 | `burger-chicken` | `burgers` | Куриный бургер | 37000 | true | absent |
| 14 | 3 | `burger-bbq` | `burgers` | Барбекю бургер | 42000 | true | absent |
| 15 | 0 | `fries-standard` | `sides` | Картофель фри | 14000 | true | absent |
| 16 | 1 | `fries-wedges` | `sides` | Картофель по-деревенски | 16000 | true | absent |
| 17 | 2 | `nuggets-8` | `sides` | Наггетсы (8 шт) | 22000 | true | absent |
| 18 | 3 | `onion-rings` | `sides` | Кольца лука | 18000 | true | absent |

Descriptions сохранены дословно в sanitized fixture. Одинаковые item IDs между tenant (`pizza-*`, `burger-chicken`) допустимы и обязаны быть tenant-scoped.

## Asset mapping

| Tenant | Role | Source | SHA-256 | Result |
|---|---|---|---|---|
| Stambul | `LOGO` | `restaurants/stambul/branding/logo.png` | `90374c…ee9b` | AST import |
| Adamas | `LOGO` | `restaurants/adamas/branding/hero-burger.png` | `68eed8…aaab` | AST import with LOGO kind |
| Adamas | `HERO` | тот же source | `68eed8…aaab` | AST import with HERO kind |
| оба | `FAVICON` | отсутствует | — | null |
| оба | `OPEN_GRAPH` | отсутствует | — | null |
| все categories/items | image | empty string | — | null |

AST key и DB uniqueness включают tenant и kind. Одинаковые Adamas bytes в ролях LOGO/HERO не смешивают semantic role.

## Legacy fields, которые не становятся новым source of truth

| Legacy field | Решение |
|---|---|
| `managerPhone` | не читается в manifest; заменяется required secret-reference marker |
| `submitMode` | не управляет client direct send; order создаётся через backend, `whatsapp_fallback` остаётся feature |
| `apiBaseUrl` | deployment config, не tenant data |
| `allowedImageOrigins` | единая server/CDN policy, не client allowlist |
| `theme` | только selector источника design tokens; slug-specific CSS branch не переносится |
| `clientMenuVersion` | обязан совпасть с `menu.version`, отдельно не хранится |
| `legacyValue` order type | не public API field |
| `maxFinalMessageLength` | notification formatter policy, не public order limit |
| generic form/cart/submit copy | shared typed frontend copy, если field отсутствует в OpenAPI; tenant-specific branches не создаются |
| registry default `adamas` | запрещён production fallback; unknown host/path остаётся 404 |
