# Legacy parity baseline

## Назначение

Этот документ определяет поведение, которое должно быть сохранено, и дефекты, которые нельзя закреплять как желаемую parity. Источник — каталог `Электронное меню/` на ветке `feature/adamas-redesign`.

## Runtime

```text
index.html script order
→ DOMContentLoaded
→ app.js:init()
→ EngineBootstrap.init()
→ HashStrategy #/slug
→ registry/provider
→ EngineState
→ theme/branding/features
→ restore cart
→ render UI
→ bind events
```

При `hashchange` legacy очищает локальное UI state, загружает config/menu, применяет theme/branding/features и восстанавливает `<slug>-menu-cart-v1`. Все tenant scripts при этом уже загружены в browser; lazy tenant loading отсутствует.

## Tenant inventory

| Поле | Stambul | Adamas |
|---|---|---|
| slug | `stambul` | `adamas` |
| theme | `stambul` | `delivery` |
| submit mode | WhatsApp | WhatsApp |
| order types | preorder/delivery/pickup | preorder/delivery/pickup |
| optional UI | нет | `deliveryBlocks` |
| menu items | 23 | 19 |
| category order | Роллы, Пицца, Гарниры, Бургеры | Пицца, Роллы, Бургеры, Гарниры |
| menu version | 1 / 2026-07-01 | 1 / 2026-07-13 |
| prices | 120–400 ₽ | 140–849 ₽ |

Source files:

- `Электронное меню/restaurants/stambul/`;
- `Электронное меню/restaurants/adamas/`;
- legacy duplicates `config.js` и `menu.js`.

## Required user parity

- Tenant branding: title, meta, name, slogan, logo, theme, schedule и contacts.
- Menu: category ordering, filter «Все», visible count, cards, fallback image и price formatting.
- Quantity: add/remove, maximum 99 per item и 200 total.
- Cart: mobile bottom bar, dialog, desktop panel, empty state, lines, totals и clear.
- Order types: preorder, delivery и pickup controlled by features.
- Checkout: phone, name, optional guest count, conditional address и comment.
- Phone normalization: Russian и international formats.
- Submission lock: повторный click не создаёт второй request.
- Storage: cart/order type persist; PII does not persist; corrupt JSON is discarded.
- Contacts: safe `tel:`, map и HTTPS social links.
- Adamas: configurable popular items, category cards и delivery benefits.
- Accessibility: keyboard, Escape/backdrop close, focus, `aria-pressed`, reduced motion.

## Legacy order payload

Legacy создаёт:

```text
businessSlug
orderType
salesChannel=public_menu
customer{name,phone,address}
items[{menuItemId,quantity}]
comment
clientTotal
clientCreatedAt
idempotencyKey
clientMenuVersion
```

`guestCount` существует в UI, но legacy не включает его в payload. Новая версия намеренно включает optional `guestCount`, чтобы не терять введённые пользователем данные.

## Defects that must not migrate

### P0 — wrong tenant during checkout

UI читает tenant из `EngineState`, но `validation.js` и `order-submit.js` продолжают читать global Stambul `APP_CONFIG`/`FEATURE_CONFIG`. Adamas может валидироваться или отправляться по настройкам Stambul. В новой системе tenant context проходит до backend use case, а notification destination никогда не приходит из browser.

### P0 — broken feature unmount

`delivery-blocks.js` пересоздаёт hero через `innerHTML`, оставляя `app.js` со ссылками на detached nodes. Tenant switch может оставить пустой или старый hero. Новая версия использует React lifecycle и declarative widgets.

### P1 — inconsistent asset policy

Tenant paths могут отвергаться legacy validator, delivery widgets обходят общую проверку, а CSP не согласован с external origins. Новая версия использует tenant-scoped object storage, immutable keys и единый image/CDN policy.

### P1 — duplicate sources of truth

Legacy дублирует config, menu, order types, feature data, menu version и cart state. Новая версия имеет один API contract, один server state owner и один cart owner.

### P1 — fake dynamic tenancy

Registry hardcoded, оба tenant загружаются клиенту, API mode перезаписывается local features, unknown slug открывает Adamas. Новая production-система возвращает 404 и не имеет default tenant fallback.

### P1 — incomplete order lifecycle

Backend/mock success может оставить submit disabled, cart и pending draft не очищаются согласованно, route errors не имеют rollback UI. Новая версия тестирует success/error/retry/reload state machine.

### P2 — hardcoded Adamas widgets

Benefits, images, categories и DOM selectors зашиты в feature module. Новая версия выражает это typed tenant data и shared React components без slug branches.

## Characterization gates

До удаления legacy должны существовать automated evidence для:

- 23/19 menu item counts и ordering;
- price conversion в minor units;
- обоих themes/brandings;
- mobile и desktop cart;
- quantity limits;
- storage corruption и tenant isolation;
- phone validation corpus;
- всех трёх order types;
- double submit;
- Adamas delivery widgets;
- keyboard/accessibility flows;
- tenant switch и unknown tenant;
- payload/error snapshots.

README legacy заявляет 128 self-tests, тогда как текущий `runSelfTests()` содержит 115 проверок. Счётчик README не является acceptance criterion; поведение фиксируется новой automated suite.
