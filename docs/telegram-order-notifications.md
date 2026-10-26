# Telegram-уведомления о заказах

Интеграция отправляет созданные заказы в один приватный Telegram-чат или закрытую
группу. Браузер не знает token или `chat_id`; сообщение уходит после фиксации заказа
через transactional outbox worker.

## 1. Создание бота

1. Откройте в Telegram официальный аккаунт `@BotFather`.
2. Отправьте `/newbot`.
3. Укажите отображаемое имя, например `У Ризвана — заказы`.
4. Укажите свободный username, заканчивающийся на `bot`.
5. Скопируйте выданный token. Никому его не отправляйте.
6. Откройте созданного бота и нажмите `Start` или отправьте `/start`.

Официальная инструкция: <https://core.telegram.org/bots/tutorial>.

## 2. Локальный secret-файл

Из корня проекта:

```powershell
Copy-Item .env.telegram.local.example .env.telegram.local
```

Откройте `.env.telegram.local` и замените только:

```dotenv
TELEGRAM_BOT_TOKEN=PASTE_BOTFATHER_TOKEN_HERE
```

На реальный token. Файл `.env.telegram.local` игнорируется Git.

## 3. Автоматическая настройка

После того как в чате с ботом отправлен `/start`:

```powershell
.\scripts\setup-telegram-local.ps1
```

Чтобы явно выбрать конкретный личный аккаунт:

```powershell
.\scripts\setup-telegram-local.ps1 -RecipientUsername ferz_095
```

Перед этим выбранный аккаунт обязан отправить боту `/start`.

Скрипт:

1. проверяет token через `getMe`;
2. находит единственный приватный `/start` через `getUpdates`;
3. сохраняет `chat_id` в игнорируемый secret-файл;
4. отправляет контрольное сообщение;
5. отключает другие локальные active notification destinations;
6. создаёт/активирует Telegram destination в PostgreSQL.

Если PostgreSQL не запущен:

```powershell
docker compose up -d --wait postgres
.\scripts\setup-telegram-local.ps1
```

## 4. Запуск API

```powershell
.\scripts\start-api-with-telegram.ps1
```

Скрипт загружает secret-файл, включает Telegram provider и outbox worker, затем
запускает Spring Boot API.

Frontend запускается отдельно:

```powershell
$env:MENU_API_BASE_URL = "http://localhost:8080"
pnpm --filter web dev
```

После оформления заказа API сначала сохраняет order. Ошибка Telegram не отменяет
заказ: worker повторяет временно неуспешную доставку.

## 5. Закрытая группа вместо личного чата

1. Создайте закрытую группу.
2. Добавьте бота.
3. Отправьте в группе `/start@username_бота`.
4. Получите отрицательный group chat ID через `getUpdates`.
5. Вручную задайте его в `.env.telegram.local`:

```dotenv
TELEGRAM_CHAT_ID=-1001234567890
```

После изменения повторно запустите setup-скрипт. Для forum topic дополнительно
задайте:

```dotenv
TELEGRAM_MESSAGE_THREAD_ID=123
```

## 6. Production

В production не копируйте `.env.telegram.local`. Передайте секреты процессу API
через secret manager или защищённые environment variables:

```text
TELEGRAM_NOTIFICATIONS_ENABLED=true
TELEGRAM_BOT_TOKEN=<secret>
TELEGRAM_CHAT_ID=<secret>
TELEGRAM_MESSAGE_THREAD_ID=<optional secret>
TELEGRAM_DESTINATION_SECRET_REFERENCE=secret://application/telegram/orders
APP_WORKER_ENABLED=true
```

В production PostgreSQL должна существовать active destination с channel
`telegram` и той же opaque secret reference. Token и chat ID в таблицу не
записываются.

## 7. Безопасность и восстановление

- Token не добавляется в Git, frontend, URL сайта, logs или screenshots.
- При утечке откройте `@BotFather`, отзовите token и создайте новый.
- Bot API token предоставляет полный контроль над ботом.
- Бот не может первым начать личный диалог: получатель обязан нажать `Start`.
- Telegram `sendMessage` не предоставляет idempotency key, поэтому после редкого
  сбоя в crash-window возможен дубликат. Номер заказа позволяет его распознать.
