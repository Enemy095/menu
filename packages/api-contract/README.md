# Public API contract

Единственный source of truth для публичных config, menu и order API одного deployment.
Routing identifiers, notification destinations, storage keys и secrets в контракт не входят.

## Команды

Из `packages/api-contract`:

```powershell
pnpm install
pnpm lint
pnpm test
pnpm generate
pnpm check-generated
pnpm typecheck
pnpm validate
```

- `lint` проверяет OpenAPI через Redocly.
- `test` проверяет canonical endpoints, examples, schema boundaries и forbidden public fields.
- `generate` полностью пересоздаёт `generated/typescript-fetch` посредством OpenAPI Generator и
  детерминированно удаляет шаблонный trailing whitespace.
- `check-generated` генерирует client во временный каталог и побайтово сравнивает его с сохранённым результатом.
- `typecheck` компилирует сгенерированный client в strict TypeScript без emit.

## Правила изменения

1. Сначала изменить `openapi/openapi.yaml`.
2. Выполнить `pnpm lint` и `pnpm test`.
3. Выполнить `pnpm generate`.
4. Выполнить `pnpm check-generated` и `pnpm typecheck`.
5. Затем синхронизировать Spring implementation и frontend usage.

Файлы в `generated/typescript-fetch` не редактируются вручную. Генерация детерминирована,
timestamp banner отключён. Java server stubs намеренно не генерируются.

Архитектурные решения пакета описаны в [DECISIONS.md](./DECISIONS.md).
