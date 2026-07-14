# Legacy dataset source inventory

Status: `evidence-only`
Captured: `2026-07-14`
Task: [SC-DATA-001](../tasks/SC-DATA-001.md)

## Purpose and trust boundary

Этот inventory описывает raw legacy bytes и наблюдаемое browser behavior двух
кандидатов. Он не выбирает dataset и не является готовым import payload.

Authoritative sources are the files under `Электронное меню/`. Historical
`docs/migration/import/**` was used only as a cross-check; it belongs to the superseded
two-tenant design. A checksum mismatch invalidates this snapshot.

SHA-256 is calculated over raw file bytes and rendered lowercase.

## Shared browser behavior

| Source | Bytes | SHA-256 |
|---|---:|---|
| `Электронное меню/index.html` | 9,577 | `0a82fd180e4f71f9301471028290b9db51cd26b9f9bb10c75549bdb194acdfe4` |
| `Электронное меню/app.js` | 63,036 | `9ba4a390529c58ccee42ac2e5f4a2f2bbd662beeb7858e3bc20ec4613c97b86e` |
| `Электронное меню/validation.js` | 4,615 | `40e7a653a44fd2ae13585ff63537089d086acfdf86e450d9c7660212bc4ce662` |
| `Электронное меню/order-submit.js` | 5,567 | `4c69db70923a1b0b16c5fdea382f0ac9c98d48275635dd157d4a1d14316e0d1b` |

### Runtime chain

```text
DOMContentLoaded
→ EngineBootstrap.init()
→ load registry config/menu/features
→ apply theme and branding
→ restore cart/order type
→ render contacts/categories/menu/cart
→ bind form/cart/router events
```

The legacy router can switch between datasets in one browser and loads scripts for both
up front. This mechanic is explicitly not a target feature.

### Menu and cart mechanics

- Category filter prepends `Все`, preserves menu category order and updates
  `aria-pressed`.
- Cards show category, name, description, integer-ruble price and a fallback visual.
- Quantity controls support add/decrement, max 99 per item and max 200 total units.
- Mobile has header badge + bottom cart bar + modal drawer; desktop keeps the cart panel
  visible.
- Drawer supports close button, backdrop and `Escape`; opening moves focus to close.
- Cart rows show `quantity × unit price`, line total and aggregate total.
- Clear removes cart, pending draft and all personal fields.
- Storage contains only `{cart, selectedOrderType}` under `<slug>-menu-cart-v1`.
  Unknown items, invalid quantities and corrupt JSON are discarded.
- PII fields are blank after load and are never persisted.

### Checkout fields

| Field | Required behavior | Legacy bound | Canonical destination |
|---|---|---:|---|
| order type | one enabled value; default preorder | enum | `orderType` |
| phone | required, Russian/international normalization | 32 | `customer.phone` |
| name | required, whitespace/control normalization | 80 | `customer.name` |
| guest count | optional integer | 1–99 | `fulfillment.guestCount` |
| address | required only for delivery | 240 | `fulfillment.address` |
| comment | optional, multiline normalization | 500 | `comment` |

The phone normalizer removes punctuation, converts Russian `8xxxxxxxxxx` and
`9xxxxxxxxx` to `+7...`, accepts `00` international prefix and finally requires 8–15
digits after `+`.

### Legacy order/notification behavior

Legacy builds:

```text
businessSlug
orderType
salesChannel = public_menu
customer { name, phone, address }
items [{ menuItemId, quantity }]
comment
clientTotal (whole RUB)
clientCreatedAt
idempotencyKey (also request header in backend mode)
clientMenuVersion
```

`guestCount` is validated but absent from this payload. WhatsApp mode formats the cart
and navigates to `wa.me/<managerPhone>`. Backend mode targets obsolete
`POST /api/public/orders`. Parts of validation/submission read global Stambul config,
even after a dataset switch. Therefore manager destination, limits and availability from
that path are not trustworthy migration data for Adamas.

## Stambul

### Source files

| Kind | Source | Bytes | SHA-256 |
|---|---|---:|---|
| config | `Электронное меню/restaurants/stambul/config.js` | 4,696 | `3110348a95b27ec008fc46910ca205606090a54f4641b5abb613a277f1f5d7c3` |
| features | `Электронное меню/restaurants/stambul/features.js` | 713 | `8d9282273f257803e0b901cf7a233943ea874fdd0a0f71964cb6ed3bd719aa1c` |
| order types | `Электронное меню/restaurants/stambul/order-types.js` | 744 | `bb2489ca8416792192ed755b0c0490df1a3136e4b76bf3eea25a6072e4a15503` |
| menu | `Электронное меню/restaurants/stambul/menu.js` | 7,333 | `242954101114d367af2fb863a216785f8b35ffd45faa11cdb7d3d521e9562495` |
| theme | `Электронное меню/themes/stambul/theme.css` | 538 | `619a529a3a24a8e3617972c72e6dccb08a59ee97540157b3fa28d29ab9f2c217` |
| logo | `Электронное меню/restaurants/stambul/branding/logo.png` | 43,728 | `90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b` |

Logo: PNG, 197×179, 24-bit RGB. `Электронное меню/images/logo-stambul.png`
contains identical bytes and is a duplicate, not a second asset.

### Branding/profile

| Field | Legacy value |
|---|---|
| display/short name | `Ресторан-кафе у Ризвана` / `у Ризвана` |
| business/cuisine | `Ресторан-кафе` / `Турецкая кухня` |
| slogan | `Вкус Стамбула рядом с вами` |
| intro | `Выбирайте любимые блюда — мы соберём заказ и передадим его менеджеру в WhatsApp.` |
| page title | `У ризвана — электронное меню` |
| meta description | `Электронное меню ресторан-кафе «У Ризвана»` |
| public phone | `+7 (989) 442-81-81` |
| address/email/social | absent |
| schedule text | `Ежедневно с 10:00 до 23:00` |
| location | `(0,0)`, empty label; unusable |
| theme | burgundy/gold light theme, Georgia/Times |

Source also contains a separate WhatsApp manager destination. Its raw value is not
reproduced here and must not become public config.

Target design-token candidates derived from CSS are `#721F2A` primary, `#4B1018`
secondary, `#C8A467` accent, `#F7F0E3` background, `#FFFDF8` surface, `#2E2020` text,
`#7E6C66` muted, `21px` radius and `Georgia, "Times New Roman", serif`.

### Features and order types

Enabled: `orders`, `contacts`, `geoLocation`, `delivery`, `pickup`, `preorder`,
`customTheme`, `whatsappFallback`.

Disabled: `socialLinks`, `promotions`, `loyalty`, `reservations`. `deliveryBlocks` is
absent and therefore normalizes to false.

Order types, in source order:

1. `preorder` / `Предзаказ` / default;
2. `delivery` / `Доставка`;
3. `pickup` / `Самовывоз`.

### Menu

Version `1`, published `2026-07-01T00:00:00.000Z`, inferred currency `RUB`.
All 23 items are available, have no item image and no modifiers.

| # | Category | Public item ID | Name | RUB | Description |
|---:|---|---|---|---:|---|
| 1 | Роллы | `cezar-roll-chicken` | Ролл Цезарь с курицей | 250 | Ролл с курицей, свежими овощами и соусом Цезарь |
| 2 | Роллы | `cezar-roll-shrimp` | Ролл Цезарь с креветкой | 300 | Ролл с креветкой, овощами и фирменным соусом |
| 3 | Роллы | `sake-maki` | Ролл Горячий шик | 300 | Классический ролл с лососем |
| 4 | Роллы | `hot-roll-salmon` | Горячий ролл с лососем | 350 | Запечённый ролл с лососем и нежной начинкой |
| 5 | Роллы | `cheese-baked-roll` | Ролл Сырный запечённые | 300 | Запечённые роллы с сырной начинкой |
| 6 | Роллы | `bonito-roll` | Ролл Ойси | 400 | Ролл с традиционной японской начинкой и стружкой тунца |
| 7 | Роллы | `philadelphia-chicken-roll` | Ролл Ойси с курицей | 350 | Ролл с курицей и сливочным сыром |
| 8 | Роллы | `philadelphia-roll` | Ролл Филадельфия | 400 | Классический ролл с лососем и сливочным сыром |
| 9 | Пицца | `pizza-caesar` | Пицца Цезарь | 350 | Пицца с курицей, сыром и соусом Цезарь |
| 10 | Пицца | `pizza-pepperoni` | Пицца Пепперони | 350 | Классическая пицца с колбасками пепперони |
| 11 | Пицца | `pizza-mushroom` | Пицца Грибная | 350 | Пицца с грибами и сыром |
| 12 | Пицца | `pizza-cheese` | Пицца Сырная | 350 | Пицца с несколькими видами сыра |
| 13 | Пицца | `pizza-julienne` | Пицца Жульен | 350 | Пицца с курицей, грибами и сливочным соусом |
| 14 | Пицца | `pizza-assorti` | Пицца Ассорти | 350 | Пицца с разнообразной начинкой |
| 15 | Пицца | `pizza-margherita` | Пицца Маргарита | 350 | Классическая пицца с томатами, сыром и соусом |
| 16 | Пицца | `pizza-chicken` | Пицца Куриная | 350 | Пицца с курицей и сыром |
| 17 | Пицца | `pizza-four-cheese` | `Пицца    4 сыра` | 350 | Пицца с четырьмя видами сыра |
| 18 | Пицца | `pizza-hunter` | Пицца Охотничья | 350 | Пицца с мясными ингредиентами и сыром |
| 19 | Гарниры | `french-fries` | Картофель фри | 120 | Хрустящий картофель фри |
| 20 | Гарниры | `village-potato` | Картофель по-деревенски | 120 | Картофельные дольки со специями |
| 21 | Гарниры | `nuggets` | Наггетсы | 200 | Куриные наггетсы с хрустящей корочкой |
| 22 | Бургеры | `burger-beef` | Бургер говяжий | 270 | Сочный бургер с говяжьей котлетой и фирменным соусом |
| 23 | Бургеры | `burger-chicken` | Бургер куриный | 270 | Бургер с куриной котлетой и свежими овощами |

Category public IDs proposed by semantic transliteration are `rolls`, `pizza`, `sides`,
`burgers`, with sort order `0..3`. These IDs are normalized target choices and must be
frozen in the approved bundle; legacy stores only Russian category names.

## Adamas

### Source files

| Kind | Source | Bytes | SHA-256 |
|---|---|---:|---|
| config | `Электронное меню/restaurants/adamas/config.js` | 4,917 | `0e11098276ef1236430c9d11bdee6971d5e62cb304f53daa0e45ac6a042663e9` |
| features | `Электронное меню/restaurants/adamas/features.js` | 447 | `fa2dcf6b1a815fc7cf915cb35343b15171bfeb0cedcc472241653ac36094ecf5` |
| order types | `Электронное меню/restaurants/adamas/order-types.js` | 519 | `3fe160b52fcf99a8bcd6ebccf1cf0a675d3463e78f88cd3d42735c95543319fe` |
| menu | `Электронное меню/restaurants/adamas/menu.js` | 5,769 | `5555508565d47bced7875e6752870d6d29514bade043109f593b33116e2ae721` |
| theme | `Электронное меню/themes/delivery/theme.css` | 15,397 | `8b37509cae5baf8c30d9b89c2caa657ed3812eaa9be7fe977e210db660f16b22` |
| hero/logo | `Электронное меню/restaurants/adamas/branding/hero-burger.png` | 447,184 | `68eed81f6b021efd58113f4ab6edc8feef68d91336f600729c776a07ab6caaab` |
| delivery UI | `Электронное меню/features/delivery-blocks.js` | 16,154 | `d40ed817362561017081cd8ee33e5f449b45d8e8654c2f476e454e40d3fe6a02` |

Hero/logo: PNG, 658×434, 24-bit RGB. The same bytes serve both roles.

### Branding/profile

| Field | Legacy value |
|---|---|
| display/short name | `Адамас` / `Адамас` |
| business/cuisine | `Служба доставки еды` / `Пицца • Роллы • Бургеры • Гарниры` |
| slogan | `ВКУСНАЯ ЕДА БЫСТРАЯ ДОСТАВКА` |
| intro | `Закажите любимые блюда — мы доставим быстро и горячо.` |
| page title | `Адамас — доставка еды` |
| meta description | `Доставка еды Адамас — пицца, роллы, бургеры, гарниры. Быстрая доставка.` |
| public phone | `+7 (999) 123-45-67` |
| address/email/social | absent |
| schedule text | `Ежедневно с 10:00 до 23:00` |
| location | `(0,0)`, empty label; feature disabled |
| theme | black/gold delivery theme, Segoe UI/Helvetica/Arial |

Source contains a separate WhatsApp manager destination; it is deliberately redacted
from this inventory.

Target design-token candidates: `#D4A843` primary/accent, `#242424` secondary,
`#0A0A0A` background, `#1A1A1A` surface, `#FFFFFF` text, `#888888` muted, `12px`
radius, `"Segoe UI", "Helvetica Neue", Arial, sans-serif`.

### Hero and delivery blocks

Hero:

- title lines `ВКУСНАЯ ЕДА` / `БЫСТРАЯ ДОСТАВКА`;
- subtitle `Пицца • Роллы • Бургеры • Гарниры`;
- CTA `Смотреть меню` scrolls to menu;
- benefits `🔥 Горячая еда`, `⏱ От 40 минут`,
  `🚗 Доставка от 1000 ₽ бесплатно`.

`deliveryBlocks=true` additionally renders:

- `Популярное`: first item from Pizza, Burgers and Rolls, then fills to at most four
  from menu order; add button delegates to the main cart;
- `Категории`: keyboard-focusable cards with item counts which activate the main filter;
- three benefit cards, including hardcoded `Бесплатно от 1000 ₽`;
- custom two-column hero that replaces the shared hero DOM.

The deterministic current popular list is therefore:

1. `pizza-pepperoni`;
2. `burger-classic`;
3. `roll-philadelphia`;
4. `pizza-margherita`.

This list is observed behavior, not source editorial metadata.

### Features and order types

Enabled: `orders`, `contacts`, `delivery`, `pickup`, `preorder`, `customTheme`,
`whatsappFallback`, `deliveryBlocks`.

Disabled: `geoLocation`, `socialLinks`, `promotions`, `loyalty`, `reservations`.

Order types are identical to Stambul and ordered preorder/default, delivery, pickup.

### Menu

Version `1`, published `2026-07-13T00:00:00.000Z`, inferred currency `RUB`.
All 19 items are available, have no item image and no modifiers.

| # | Category | Public item ID | Name | RUB | Description |
|---:|---|---|---|---:|---|
| 1 | Пицца | `pizza-pepperoni` | Пицца Пепперони | 590 | Классическая пицца с пикантной пепперони и моцареллой |
| 2 | Пицца | `pizza-margherita` | Пицца Маргарита | 490 | Томаты, моцарелла, базилик на тонком тесте |
| 3 | Пицца | `pizza-four-cheese` | Пицца 4 сыра | 620 | Моцарелла, пармезан, дор-блю, чеддер |
| 4 | Пицца | `pizza-chicken` | Пицца Куриная | 550 | Курица, грибы, моцарелла, соус альфредо |
| 5 | Пицца | `pizza-hawaiian` | Пицца Гавайская | 540 | Ветчина, ананас, моцарелла |
| 6 | Пицца | `pizza-mushroom` | Пицца Грибная | 570 | Шампиньоны, белые грибы, трюфельное масло |
| 7 | Роллы | `roll-philadelphia` | Сет Филадельфия | 849 | Классический ролл с лососем и сливочным сыром |
| 8 | Роллы | `roll-california` | Калифорния | 690 | Краб, авокадо, огурец, икра тобико |
| 9 | Роллы | `roll-hot-salmon` | Горячий ролл с лососем | 720 | Запечённый ролл с лососем и сливочным сыром |
| 10 | Роллы | `roll-dragon` | Дрэгон ролл | 790 | Угорь, авокадо, огурец, соус унаги |
| 11 | Роллы | `roll-spicy-tuna` | Спайси тунец | 750 | Тунец, спайси соус, кунжут, авокадо |
| 12 | Бургеры | `burger-classic` | Чизбургер | 350 | Говяжья котлета, сыр чеддер, томаты, соус |
| 13 | Бургеры | `burger-double` | Дабл чизбургер | 490 | Две говяжьи котлеты, двойной сыр, соус |
| 14 | Бургеры | `burger-chicken` | Куриный бургер | 370 | Хрустящая курица, салат, томаты, соус цезарь |
| 15 | Бургеры | `burger-bbq` | Барбекю бургер | 420 | Говяжья котлета, бекон, лук кольцами, BBQ соус |
| 16 | Гарниры | `fries-standard` | Картофель фри | 140 | Хрустящий картофель фри |
| 17 | Гарниры | `fries-wedges` | Картофель по-деревенски | 160 | Картофельные дольки со специями |
| 18 | Гарниры | `nuggets-8` | Наггетсы (8 шт) | 220 | Куриные наггетсы с хрустящей корочкой |
| 19 | Гарниры | `onion-rings` | Кольца лука | 180 | Хрустящие кольца лука в панировке |

Category public IDs proposed by normalization are `pizza`, `rolls`, `burgers`, `sides`,
with sort order `0..3`.

## Cross-dataset collisions

Several public item IDs occur in both datasets but represent different copy/prices,
including `pizza-pepperoni`, `pizza-margherita`, `pizza-four-cheese`, `pizza-chicken`,
`pizza-mushroom` and `burger-chicken`. This is safe only because a deployment imports
one dataset. Merging both would silently choose incompatible semantics and is prohibited.

## Evidence limitations

- Repository bytes do not prove that names, prices, phones or schedule are current.
- Published timestamps are source constants, not proof of business approval.
- Currency and locale are strongly implied by Russian copy and `₽`, but remain normalized
  target decisions.
- No source proves timezone, canonical domain, delivery fee rules or asset usage rights.
- No real provider call or backend order exists in the legacy repository.
