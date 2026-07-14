# MIG-001 — legacy import preflight

Этот каталог фиксирует Phase A для детерминированного импорта Stambul и Adamas. Он не является production seed и не изменяет legacy. Исходные файлы читаются как неизменяемые raw bytes, а private notification destinations намеренно отсутствуют в Git.

## Артефакты

- [`manifest-v1.json`](manifest-v1.json) — versioned source manifest, raw-byte checksums, asset roles и минимальные ожидаемые факты.
- [`fixtures/expected-tenants-v1.json`](fixtures/expected-tenants-v1.json) — sanitized machine-readable mapping fixture для тестов Phase B; это не runtime config.
- [`mapping-inventory.md`](mapping-inventory.md) — полная таблица config/menu/features/order types/assets и преобразований.
- [`import-design.md`](import-design.md) — placement backend command, границы транзакций, dry-run, idempotency и secret injection.
- [`acceptance.md`](acceptance.md) — планируемые команды, SQL/API checks и критерии rerun zero diff.
- [`verification-report.md`](verification-report.md) — фактический human-readable результат Phase A и promotion blockers.

## Граница Phase A

Документы описывают вход и ожидаемый результат. Production importer, новые migrations, CFG/MENU/AST adapters и реальные secrets здесь не создаются. Phase B начинается только после стабилизации публичных API модулей `restaurant`, `menu` и `tenancy`.

## Политика чувствительных данных

- Legacy `managerPhone` не копируется ни в manifest, ни в fixture, ни в отчёты.
- Manifest содержит только `required-secret:<ENV_KEY>`.
- Публичный телефон заведения разрешён в fixture, потому что он явно взят из `profile.phone`; каждое такое поле содержит source marker.
- Dry-run и failure report выводят только наличие/отсутствие secret reference, но не его значение.
