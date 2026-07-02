# Электронное меню «Ресторан-кафе Стамбул»

Статический frontend на HTML, CSS и чистом JavaScript. Клиент выбирает блюда, заполняет форму и отправляет заказ через настраиваемый адаптер. Backend, оплата и панель ресторана пока не входят в проект.

Frontend уже подготовлен к `backend` mode, но сам backend ещё не реализован. Точный контракт
`POST /api/public/orders` описан в [`docs/api-contract.md`](docs/api-contract.md).
Следующий этап — Go backend skeleton, реализующий этот контракт.

Настройка названия, слогана, логотипа, темы, контактов и feature flags описана в
[`docs/white-label.md`](docs/white-label.md).

## Запуск

Можно открыть `index.html` напрямую или запустить локальный сервер:

```powershell
python -m http.server 8000
```

Затем открыть `http://localhost:8000/`.

## Структура

- `index.html` — разметка;
- `style.css` — дизайн и responsive-стили;
- `config.js` — приложение, бренд, feature flags и публичный профиль;
- `validation.js` — нормализация, ограничения и проверка URL;
- `order-submit.js` — адаптеры WhatsApp/backend/mock;
- `app.js` — меню, корзина и связывание UI;
- `images/` — локальные изображения.

Скрипты подключаются именно в указанном порядке. Внешних библиотек и сборки нет.

## Конфигурация

В `config.js` находятся:

- `APP_CONFIG` — режим отправки, API, business slug и лимиты;
- `BRAND_CONFIG` — название, логотип и тема;
- `FEATURE_CONFIG` — orders, contacts, geoLocation, socialLinks, delivery, pickup, preorder и WhatsApp fallback;
- `BUSINESS_PROFILE_CONFIG` — публичный телефон, адрес, часы, координаты и соцсети.

Критические настройки не читаются из URL. Параметры `phone`, `manager`, `to`, цены, скидки, режим отправки, `apiBaseUrl` и `businessSlug` не могут быть переопределены ссылкой.

## Режимы отправки

Измените `APP_CONFIG.orderSubmitMode`:

- `"whatsapp"` — текущий рабочий режим, открывает номер `APP_CONFIG.managerPhone`;
- `"backend"` — POST на `${apiBaseUrl}/api/public/orders`;
- `"mock"` — создаёт локальный ответ `MOCK-001`, не открывая внешние сервисы.

Backend-запрос отправляет JSON payload и заголовок `Idempotency-Key`. При повторной отправке неизменённого заказа ключ сохраняется.

Payload содержит `businessSlug`, `orderType`, `salesChannel`, customer, ID и количество позиций, comment, предварительный `clientTotal`, ISO-время, idempotency key и версию меню.

`clientTotal` не является источником истины. Backend обязан получить актуальные позиции и цены самостоятельно.

## Optional-функции

В `FEATURE_CONFIG`:

- `contacts` показывает/скрывает контактный блок;
- `geoLocation` добавляет лёгкую ссылку на карту при валидных ненулевых координатах;
- `socialLinks` показывает только ссылки с протоколом `https:`;
- `delivery`, `pickup`, `preorder` управляют доступными типами заказа;
- `orders` скрывает отправку заказа;
- `customTheme` включает значения темы из `BRAND_CONFIG`.

Внешние ссылки получают `target="_blank"` и `rel="noopener noreferrer"`. `javascript:`, `data:` и `blob:` для соцсетей и изображений запрещены.

## Ограничения и персональные данные

По умолчанию:

- блюдо: до 99 штук;
- весь заказ: до 200 единиц;
- имя: 80 символов, обязательно;
- телефон: 32 символа, обязательно;
- адрес: 240 символов, обязателен для доставки;
- комментарий: 500 символов;
- итоговое WhatsApp-сообщение: 3500 символов.

Ввод очищается от управляющих, bidi и zero-width символов; пробелы и переводы строк нормализуются.

В `localStorage` сохраняются только корзина и тип заказа. Имя, телефон, адрес и комментарий не сохраняются между перезагрузками. Повреждённое сохранение удаляется, а приложение продолжает работу. «Очистить» удаляет корзину, все поля и pending draft.

## Безопасность

- Данные меню и корзины создаются через `createElement`/`textContent`.
- URL изображений проходят allowlist-проверку.
- Номер WhatsApp не подменяется query-параметрами.
- Количество ограничено во всех клиентских путях.
- Повторное нажатие блокируется на время отправки.
- Добавлена статическая CSP без inline-кода.

При production-хостинге CSP лучше отправлять HTTP-заголовком:

```text
default-src 'self';
img-src 'self';
style-src 'self';
script-src 'self';
connect-src 'self';
object-src 'none';
base-uri 'none';
frame-ancestors 'none';
form-action 'self';
```

`frame-ancestors` не поддерживается в CSP meta и должен задаваться серверным заголовком.

### CSP для local backend

Текущая production-политика `connect-src 'self'` не разрешает прямой запрос со страницы
`http://localhost:8000` к API `http://localhost:8080`, потому что это разные origins.
Безопасные варианты разработки:

1. Рекомендуемый: проксировать `/api` на порт 8080 через локальный frontend-сервер и оставить
   `APP_CONFIG.apiBaseUrl = ""`. Тогда браузер обращается к своему origin, а production CSP не меняется.
2. Только в локальной копии HTML временно использовать:

   ```text
   connect-src 'self' http://localhost:8080 http://127.0.0.1:8080;
   ```

Local origins нельзя добавлять в production CSP: опубликованный сайт не должен получать разрешение
обращаться к сервисам на компьютере посетителя.

## Backend security requirements

Будущий Go backend обязан:

- не доверять `clientTotal`, названиям и другим производным данным клиента;
- повторно загружать и считать актуальные цены;
- проверять доступность каждого `menuItemId`;
- проверять `orderType` против включённых серверных features;
- валидировать длину, формат и допустимый Unicode всех входных данных;
- использовать только parameterized SQL queries;
- ограничивать rate limit по IP, business и другим подходящим признакам;
- проверять и сохранять `Idempotency-Key`, возвращая прежний результат повтора;
- хранить price snapshot в каждой записи `order_items`;
- создавать order и order_items в одной транзакции;
- защищать административные endpoints аутентификацией и авторизацией;
- никогда не принимать URL-параметры frontend как доверенные business-настройки.

Frontend-проверки улучшают UX, но не заменяют эти требования.

## Android UX

Запрет выделения и tap highlight применяется только к декоративному brand/header, заголовку hero, категориям и элементам управления. `input`, `textarea` и `select` остаются выделяемыми и доступными с клавиатуры.

## Тесты

Откройте:

```text
http://localhost:8000/?test=1
```

Результаты появятся в консоли и на странице. Сейчас проходит **128/128** проверок. Самопроверка покрывает старые сценарии телефона/корзины/WhatsApp, точный формат сообщения, payload, idempotency, выбор адаптера, backend request, non-2xx и network errors, mock mode, double-submit lock, лимиты количества, malicious URL parameters, XSS-like данные, безопасные URL, localStorage cleanup, DOM-render, optional-модули, Android selection, полный white-label brand/theme/UI, config-driven order types и feature flags.

История счётчика: исходные 66 тестов были заменены при архитектурном рефакторинге более компактным набором 52, затем расширены до 62 во время hardening. Контрольный review восстановил потерянные детальные проверки телефона, WhatsApp-формата, pickup и базового изменения количества, не удаляя новые security/backend-тесты.

Перед MVP следует перенести эти проверки в автоматический browser/CI runner, сохранив встроенную самопроверку как smoke test.

Ручная регрессионная проверка описана в [`docs/manual-checklist.md`](docs/manual-checklist.md).

## Изображения

Блюда находятся в `images/dishes/`, логотип — `images/logo-stambul.png`. Для коммерческого запуска используйте фотографии с подтверждёнными правами, желательно WebP шириной 800–1000 px и размером до 150–250 КБ.
