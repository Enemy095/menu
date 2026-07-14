# Локальный запуск приложения

Этот гайд запускает полный локальный single-client стек:

```text
Next.js:   http://localhost:3000
Spring API http://localhost:8080
PostgreSQL localhost:5432
MinIO API  http://localhost:9000
MinIO UI   http://localhost:9001
```

Обычный повторный запуск не удаляет и не импортирует данные заново. PostgreSQL и
MinIO используют Docker volumes, в которых уже сохранён набор Stambul.

## 1. Что должно быть установлено

- Docker Desktop с запущенным Docker Engine;
- Java 25;
- Node.js 24 или новее;
- pnpm 11;
- PowerShell.

Проверка:

```powershell
docker version
java --version
node --version
pnpm --version
```

Все команды ниже выполняются из корня репозитория:

```powershell
cd D:\Projects\Temporary\Salamu\e-menu-rezvan
```

## 2. Обычный повторный запуск

### Шаг 1. PostgreSQL и MinIO

Откройте первое окно PowerShell:

```powershell
docker compose --profile assets up -d
docker compose --profile assets ps
```

Дождитесь состояния `healthy` у `postgres` и `minio`. `minio-init` может завершиться
со статусом `Exited (0)` — это нормальный одноразовый initializer.

Volumes не пересоздаются, поэтому ранее импортированное меню останется в базе.

### Шаг 2. Spring Boot API

Откройте второе окно PowerShell:

```powershell
cd D:\Projects\Temporary\Salamu\e-menu-rezvan
$env:SPRING_PROFILES_ACTIVE = "local"
$env:APP_WORKER_ENABLED = "false"
.\gradlew.bat :apps:api:bootRun
```

API готов после сообщения:

```text
Started MenuPlatformApplication
```

Проверка в другом окне:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/liveness
Invoke-RestMethod http://localhost:8080/api/v1/public/config
Invoke-RestMethod http://localhost:8080/api/v1/public/menu
```

Worker намеренно выключен, потому что локально не настроен реальный Telegram,
WhatsApp или другой notification provider. Заказы сохраняются в PostgreSQL, но
внешние сообщения не отправляются.

### Шаг 3. Next.js frontend

Откройте третье окно PowerShell:

```powershell
cd D:\Projects\Temporary\Salamu\e-menu-rezvan
$env:MENU_API_BASE_URL = "http://localhost:8080"
pnpm --filter web dev
```

Откройте в браузере:

```text
http://localhost:3000
```

Должна отображаться страница «У Ризвана», четыре категории и 23 позиции меню.

## 3. Остановка

В окнах API и frontend нажмите `Ctrl+C`. После этого выполните:

```powershell
docker compose --profile assets down
```

Команда удаляет только контейнеры и сети. Данные сохраняются в Docker volumes.

Проверка остановки:

```powershell
docker compose --profile assets ps
```

## 4. Первый запуск после удаления volumes

Этот раздел нужен только после полного удаления Docker volumes или на новом компьютере.

Сначала запустите infrastructure и API по шагам выше. API применит Flyway migrations
до текущей версии схемы. Затем в четвёртом PowerShell задайте параметры importer:

```powershell
cd D:\Projects\Temporary\Salamu\e-menu-rezvan

$env:DATA_IMPORT_JDBC_URL = "jdbc:postgresql://localhost:5432/emenu"
$env:DATA_IMPORT_DB_USER = "emenu"
$env:DATA_IMPORT_DB_PASSWORD = "emenu_local_password"
$env:DATA_IMPORT_S3_ENDPOINT = "http://localhost:9000"
$env:DATA_IMPORT_S3_REGION = "us-east-1"
$env:DATA_IMPORT_S3_BUCKET = "menu-assets-local"
$env:DATA_IMPORT_S3_ACCESS_KEY = "emenu-local"
$env:DATA_IMPORT_S3_SECRET_KEY = "emenu-local-password"
$env:DATA_IMPORT_CDN_BASE_URL = "http://localhost:9000/menu-assets-local"
$env:DATA_IMPORT_S3_PATH_STYLE = "true"
```

Сначала безопасный dry-run:

```powershell
.\gradlew.bat :apps:api:runDataImport --args="--bundle=docs/migration/data/stambul-approved-v1.json --asset-root=. --mode=dry-run --target=local-test" --no-daemon
```

Затем применение:

```powershell
.\gradlew.bat :apps:api:runDataImport --args="--bundle=docs/migration/data/stambul-approved-v1.json --asset-root=. --mode=apply --target=local-test" --no-daemon
```

Успешный импорт сообщает `DATA_IMPORT_RESULT` с четырьмя категориями и 23 позициями.
Повторное применение того же утверждённого bundle является идемпотентным.

## 5. Полная проверка проекта

Быстрая проверка frontend/backend/contracts:

```powershell
pnpm validate:full
```

Полная одноразовая Docker acceptance-проверка Stambul:

```powershell
pnpm validate:acceptance
```

Acceptance использует отдельные временные PostgreSQL/MinIO и не изменяет постоянную
локальную базу.

## 6. Полный сброс локальных данных

Следующая команда необратимо удаляет локальные PostgreSQL и MinIO volumes этого
проекта:

```powershell
docker compose --profile assets down -v
```

Используйте её только если действительно нужен чистый импорт. После неё выполните
раздел «Первый запуск после удаления volumes».

## 7. Диагностика

### Занят порт

```powershell
Get-NetTCPConnection -State Listen |
  Where-Object LocalPort -In 3000,8080,9000,9001,5432 |
  Select-Object LocalPort,OwningProcess
```

Не завершайте неизвестный процесс, пока не проверите его команду:

```powershell
Get-CimInstance Win32_Process -Filter "ProcessId = <PID>" |
  Select-Object ProcessId,CommandLine
```

### Состояние и логи Docker

```powershell
docker compose --profile assets ps
docker compose --profile assets logs --tail 100 postgres minio minio-init
```

### Frontend показывает страницу недоступности

Убедитесь, что API отвечает:

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/v1/public/config
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/v1/public/menu
```

Затем перезапустите frontend с правильным `MENU_API_BASE_URL`.

### Не загружается логотип

Проверьте MinIO initializer и сам объект:

```powershell
docker compose --profile assets logs minio-init
Invoke-WebRequest -UseBasicParsing "http://localhost:9000/menu-assets-local/assets/logo/90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b.png"
```

MinIO Console использует локальные credentials:

```text
login:    emenu-local
password: emenu-local-password
```
