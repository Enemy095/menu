# White-label настройка меню

White-label слой работает через `config.js`. Developer/admin frontend пока отсутствует:
настройки меняются вручную в коде и применяются после перезагрузки страницы.

## Бренд

В `BRAND_CONFIG`:

- `displayName` — видимое название в header, accessibility-тексте и заголовке вкладки;
- `shortName` — короткое имя для будущих компактных представлений;
- `businessTypeLabel` — тип заведения над названием;
- `cuisineText` — кухня/eyebrow hero;
- `slogan` — главный hero-заголовок; пустая строка скрывает его;
- `introText` — вводный текст под слоганом;
- `pageTitle` — `<title>`;
- `metaDescription` — `meta[name="description"]`;
- `logoUrl` — основной логотип;
- `avatarUrl` — fallback-источник, если `logoUrl` не задан.
- `logoAlt` — доступное описание логотипа.

`meta[name="theme-color"]` автоматически получает `theme.primaryColor`.

Вложенные группы:

- `menuSection` — eyebrow и заголовок меню;
- `cartTexts` — cart bar, drawer, очистка и aria-label закрытия;
- `orderTexts` — legend, submit и пояснение для whatsapp/backend/mock;
- `formTexts` — labels, placeholders, hints, errors и подпись optional.

Логотип принимает локальный путь из `images/` либо HTTPS-origin, заранее добавленный в
`APP_CONFIG.allowedImageOrigins`. `javascript:`, `data:`, `blob:` и другие схемы запрещены.
При пустом или невалидном URL используется локальный логотип «Стамбул».

Пример:

```js
window.BRAND_CONFIG = Object.freeze({
  displayName: "Тестовое кафе",
  slogan: "Тестовый слоган",
  logoUrl: "./images/logo-test.png",
  avatarUrl: "./images/logo-test.png",
  theme: Object.freeze({
    // ...
  })
});
```

## Тема

`BRAND_CONFIG.theme` подключён к CSS variables:

| Config | CSS variable |
|---|---|
| `primaryColor` | `--color-primary` |
| `primaryTextColor` | `--color-primary-text` |
| `accentColor` | `--color-accent` |
| `backgroundColor` | `--color-background` |
| `surfaceColor` | `--color-surface` |
| `textColor` | `--color-text` |
| `mutedTextColor` | `--color-muted` |
| `borderColor` | `--color-border` |
| `cardRadius` | `--radius-card` |
| `buttonRadius` | `--radius-button` |

Тема применяется, когда `FEATURE_CONFIG.customTheme = true`. Технические цвета error,
success и focus намеренно не являются частью white-label темы.

## Контакты и публичный профиль

В `BUSINESS_PROFILE_CONFIG` меняются:

- `phone`;
- `publicPhoneLabel`;
- `address`;
- `workingHoursText`;
- `location.latitude`, `location.longitude`, `location.label`;
- `socialLinks`.

Координаты должны находиться в диапазонах latitude −90…90 и longitude −180…180.
Социальные ссылки принимают только `https://`.

## Feature flags

В `FEATURE_CONFIG`:

- `delivery`, `pickup`, `preorder` — доступные типы заказа;
- `contacts` — контактный блок;
- `geoLocation` — ссылка на карту при валидных координатах;
- `socialLinks` — прошедшие проверку социальные ссылки;
- `customTheme` — применение темы;
- `orders` — возможность оформления заказа.

Submit modes `whatsapp`, `backend`, `mock` настраиваются отдельно через
`APP_CONFIG.orderSubmitMode`.

## Типы заказа

`ORDER_TYPE_CONFIG` задаёт стабильный API-код, видимую подпись, старое значение для
миграции и связанный feature flag:

```js
{ code: "delivery", label: "Доставка", legacyValue: "Доставка", feature: "delivery" }
```

UI строится из этого массива. В payload всегда уходят технические коды `preorder`,
`delivery`, `pickup`, даже если видимые labels переведены или переименованы. Если default
тип выключен, выбирается первый доступный.

## Меню и блюда

Список блюд пока не относится к white-label config. Он вручную меняется в массиве `MENU`
в `app.js`. В будущем его заменит опубликованный backend menu snapshot.

## Проверка изменений

1. Изменить `displayName`, `businessTypeLabel`, `slogan`, `cuisineText` и `introText`.
2. При необходимости изменить `pageTitle`, `metaDescription` и logo.
3. Изменить `primaryColor`, фон или радиусы.
4. Обновить страницу через Ctrl+F5.
5. Открыть `?test=1`.
6. Убедиться, что все тесты проходят.

После проверки вернуть production-значения перед публикацией.
