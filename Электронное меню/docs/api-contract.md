# Контракт публичного создания заказа

Статус документа: контракт текущего frontend для будущего Go backend. Backend в проекте пока не реализован.

## Источник payload

Функция `buildOrderPayload(order = readOrderData())` находится в `app.js`.

Она принимает необязательный объект `order`. Если аргумент не передан, функция вызывает
`readOrderData()` и читает:

- выбранный UI-тип заказа;
- имя, телефон, адрес и комментарий из формы;
- количество гостей из формы — оно валидируется UI, но в текущий payload не входит;
- текущее состояние корзины.

Функция нормализует телефон и текстовые поля и возвращает:

- `businessSlug` из `APP_CONFIG.businessSlug`;
- `orderType` после преобразования UI-значения;
- `salesChannel` со значением `public_menu`;
- `customer.name`, `customer.phone`, `customer.address`;
- `items`, содержащие только `menuItemId` и `quantity`;
- `comment`;
- предварительный `clientTotal`;
- `clientCreatedAt`;
- `idempotencyKey`;
- `clientMenuVersion` из `APP_CONFIG.clientMenuVersion`.

Поддерживаемые значения `orderType`:

| UI | API |
|---|---|
| Предзаказ | `preorder` |
| Доставка | `delivery` |
| Самовывоз | `pickup` |

`clientTotal` рассчитывается frontend по локальному menu snapshot как сумма
`item.price × quantity`. Это только предварительное значение для UI и контроля расхождений.

### Idempotency key

Ключ создаётся через `crypto.randomUUID()`. Если API недоступен, используется fallback
`order-<timestamp>-<random>`.

Frontend строит fingerprint нормализованного payload без `clientCreatedAt` и
`idempotencyKey`. Пока содержимое заказа не меняется, повторный вызов переиспользует и ключ,
и исходное `clientCreatedAt`. Изменение customer, items, comment, total или другого поля
fingerprint создаёт новый ключ. После перезагрузки страницы pending order в текущей версии
не восстанавливается, поэтому создаётся новый ключ.

## POST /api/public/orders

### Headers

```http
Content-Type: application/json
Idempotency-Key: <то же значение, что body.idempotencyKey>
```

Backend обязан отклонить запрос, если ключ в заголовке отсутствует или не совпадает с body.

### CreateOrderRequest

Фактический текущий payload:

```json
{
  "businessSlug": "stambul",
  "orderType": "pickup",
  "salesChannel": "public_menu",
  "customer": {
    "name": "Али",
    "phone": "+79292979937",
    "address": ""
  },
  "items": [
    {
      "menuItemId": "mercimek",
      "quantity": 2
    }
  ],
  "comment": "Позвонить заранее",
  "clientTotal": 640,
  "clientCreatedAt": "2026-07-02T16:20:00.000Z",
  "idempotencyKey": "uuid-or-random-key",
  "clientMenuVersion": 1
}
```

Схема полей:

| Поле | Тип | Текущее frontend-ограничение |
|---|---|---|
| `businessSlug` | string | config-only |
| `orderType` | enum | `delivery`, `pickup`, `preorder` |
| `salesChannel` | string | `public_menu` |
| `customer.name` | string | обязательно, максимум 80 |
| `customer.phone` | string | нормализованный E.164-подобный формат, 8–15 цифр |
| `customer.address` | string | максимум 240; обязательно для delivery |
| `items` | array | непустой |
| `items[].menuItemId` | string | ID текущего menu snapshot |
| `items[].quantity` | integer | 1–99 |
| `comment` | string | максимум 500 |
| `clientTotal` | number | недоверенное предварительное значение |
| `clientCreatedAt` | ISO 8601 string | время формирования заказа клиентом |
| `idempotencyKey` | string | UUID или fallback-ключ |
| `clientMenuVersion` | number | сейчас `1` |

Общее количество единиц в текущем frontend ограничено 200.

## Недоверенные данные

Backend не должен доверять:

- `clientTotal`;
- названиям или ценам блюд, если они когда-либо появятся в payload;
- `orderType` без проверки server-side feature flags;
- customer data без повторной server-side валидации;
- `clientMenuVersion` без проверки актуальной публикации меню;
- любым значениям из URL;
- `managerPhone`;
- скидкам или `discount`, если они появятся на клиенте;
- существованию, цене и доступности переданных `menuItemId`;
- клиентским ограничениям количества.

Backend обязан:

- найти business по `businessSlug`;
- проверить, что feature `orders` включена;
- проверить `orderType` против `delivery`, `pickup`, `preorder`;
- загрузить актуальное опубликованное меню;
- проверить каждый item, quantity и availability;
- пересчитать `serverTotal`;
- сравнить `clientTotal` и `serverTotal`;
- создать immutable snapshots позиций в `order_items`;
- создавать заказ и связанные записи транзакционно;
- использовать idempotency key и стабильный canonical request hash;
- вернуть прежний response для того же key и того же request hash;
- вернуть HTTP 409 для того же key с другим request hash.

## CreateOrderResponse

Успешный ответ:

```json
{
  "orderId": 104,
  "orderNumber": 104,
  "status": "new",
  "serverTotal": 640,
  "serverReceivedAt": "2026-07-02T16:20:05.000Z",
  "needsConfirmation": false
}
```

- `orderId` — внутренний идентификатор заказа.
- `orderNumber` — публичный номер для клиента и кафе. Тип следует сохранить стабильным;
  в примере это number.
- `status` — текущий статус, при создании `new`.
- `serverTotal` — сумма, рассчитанная backend.
- `serverReceivedAt` — серверное время приёма.
- `needsConfirmation` — `true`, если разница между server time и валидным
  `clientCreatedAt` превышает `delayedOrderConfirmationMinutes`.

## ErrorResponse

Стандартный ответ ошибки:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Некорректные данные заказа",
    "details": []
  }
}
```

| Code | HTTP | Значение |
|---|---:|---|
| `VALIDATION_ERROR` | 400 | Некорректные поля запроса |
| `MENU_CHANGED` | 409 | Версия или состав меню изменились |
| `ITEM_UNAVAILABLE` | 409 | Позиция недоступна |
| `TOTAL_MISMATCH` | 409 | Клиентский и серверный итоги различаются |
| `FEATURE_DISABLED` | 403 | Orders или выбранный order type отключён |
| `RATE_LIMITED` | 429 | Превышен лимит запросов |
| `IDEMPOTENCY_CONFLICT` | 409 | Ключ повторён с другим request hash |
| `SERVER_ERROR` | 500 | Непредвиденная серверная ошибка |

`NETWORK_ERROR` — только frontend-side код для отсутствия ответа, CORS/CSP или сетевого
сбоя. Backend его не возвращает.

Для `ITEM_UNAVAILABLE` backend при необходимости может выбрать HTTP 409 или 422, но после
начала реализации значение следует зафиксировать единообразно.

## CreateOrder backend flow

1. Read and validate JSON.
2. Read `Idempotency-Key` header.
3. Compare header key with body `idempotencyKey`.
4. Validate `businessSlug`.
5. Validate features.
6. Validate customer fields.
7. Validate items.
8. Check idempotency table.
9. Load current published menu.
10. Validate item availability.
11. Recalculate `serverTotal`.
12. Compare with `clientTotal`.
13. Begin transaction.
14. Insert `orders`.
15. Insert `order_items` snapshots.
16. Insert `order_status_history`.
17. Insert `notification_outbox`.
18. Insert `idempotency_keys` response.
19. Commit.
20. Return `CreateOrderResponse`.

Проверка idempotency до транзакции должна быть повторена или защищена уникальным constraint
внутри транзакции, чтобы параллельные запросы не создали два заказа.
