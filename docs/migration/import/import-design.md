# MIG-001 Phase B design

## Placement

Importer должен быть Spring Boot CLI entry point в `apps/api`, а не Flyway data migration и не utility в `shared`.

Рекомендуемое размещение:

```text
com.salamu.menu.migration
  api/                         # отсутствует, пока нет межмодульного потребителя
  internal/
    application/LegacyImportUseCase.java
    application/LegacyImportPlan.java
    infrastructure/ManifestReader.java
    infrastructure/LegacyImportCommand.java
```

Модуль `migration` является временным orchestration module. Он может зависеть только от публичных API `tenancy`, `restaurant`, `menu` и от tenant-scoped asset API. Он не импортирует `internal` пакеты и не выполняет прямой SQL в чужие таблицы.

До реализации нужен ADR `docs/adr/00xx-temporary-migration-orchestrator.md`, потому что модуль временный и пересекает несколько bounded contexts. ADR фиксирует:

- разрешённые публичные зависимости;
- запрет runtime HTTP endpoint для импорта;
- удаление модуля после production validation и rollback window;
- невозможность использовать `shared` как обход module boundaries.

## Stable interfaces, необходимые от Phase B

Минимальные application API:

```text
tenancy: provision suspended tenant skeleton; atomically activate completed snapshot
restaurant: replace/compare public config snapshot for TenantId + default restaurant
menu: replace/compare menu version snapshot for TenantId + restaurant
assets: privileged provisioning import for active/suspended TenantId; never exposed over HTTP
notification: create/compare destination by TenantId + channel + display name + secret reference
```

Каждый API принимает `TenantId`; importer не передаёт client-provided tenant ID и не обращается к repository другого модуля.

## Утверждённый CFG input shape

На tenant создаются ровно 13 строк `tenant_features`:

```text
orders, contacts, geo_location, social_links, delivery, pickup, preorder,
custom_theme, whatsapp_fallback, promotions, loyalty, reservations,
delivery_blocks
```

- `preorder`, `delivery`, `pickup`: `config={"label":"...","default":boolean}`. Ровно одна enabled-запись имеет `default=true`.
- `orders`: `config.limits` содержит все required limits контракта и только подтверждённые optional thresholds.
- Остальные config по умолчанию `{}`.
- Public mapper преобразует snake_case keys в typed camelCase contract.
- `tenant_branding.hero` хранит internal `imageAssetId`, а `seo_metadata` — internal `openGraphAssetId`. Public mapper batch-resolves URL через tenant-scoped AST и не возвращает internal IDs.

## STAGED lifecycle и transaction boundary

1. CLI читает versioned JSON manifest и sanitized fixture.
2. До любого write проверяет schema version, allowlisted repository-relative paths и все raw-byte SHA-256.
3. Parser строит immutable import plan и валидирует его полностью: slugs, category membership, IDs, prices, 13 feature rows, один default order type, secret markers и promotion flags.
4. `--dry-run` сравнивает plan с текущим состоянием через provisioning/application API. Он не загружает assets, не создаёт tenant и не пишет БД.
5. Первая короткая transaction создаёт skeleton обоих tenant в `suspended`. Suspended tenant не разрешается production `TenantResolver` и не доступен через public API.
6. Privileged AST provisioning API импортирует content-addressed assets для `active|suspended` tenant. Он недоступен через HTTP. Текущий обычный `TenantAssetService` и public URL resolution остаются active-only.
7. После успешной asset stage одна transaction записывает/сверяет для обоих tenant default restaurant, branding/profile/features, menu version/categories/items и destination references, а последним действием атомарно переводит оба tenant в `active`. Вызываемые write APIs присоединяются к transaction orchestrator и не используют `REQUIRES_NEW`; skeleton rows блокируются в стабильном slug order.
8. Ошибка main transaction оставляет только non-public suspended skeleton и staged assets. Rerun узнаёт их по natural keys/hashes; explicit cleanup удаляет только suspended import state, которое не имеет active references.
9. Post-commit verification повторно читает config/menu и сравнивает с plan.

Нельзя делать по tenant отдельные activation commits: оба snapshot становятся public одной transaction или оба остаются suspended. External object storage и текущие AST `REQUIRES_NEW` transactions не дают настоящей distributed all-or-none atomicity, поэтому это STAGED provisioning, а не «одна transaction на всё».

До Phase B ADR должен утвердить privileged provisioning API и lifecycle. Без него текущий AST `importAsset` неприменим: он требует уже committed ACTIVE tenant. Альтернатива — active tenant + compensation/orphan cleanup — не гарантирует отсутствие частично публичного state и для этого migration slice не рекомендуется.

## Idempotency

Минимальный business key run для tenant:

```text
(tenantSlug, menuVersion, assetSetSha256)
```

Дополнительно importer сравнивает `sourceSetSha256` и semantic manifest data. Сценарии:

- state absent + checksums exact → create suspended skeleton, stage, activate;
- matching suspended state → continue deterministic staging or explicit cleanup;
- state semantic-equal → `UNCHANGED`, zero writes;
- тот же key, но другой source/config/menu content → fail `IMPORT_CONTENT_CONFLICT`;
- menu version уже существует с отличающимся содержимым → fail, не mutate published history;
- partial/inconsistent state → fail с redacted diff; repair выполняется отдельным подтверждённым режимом, не обычным rerun.

UUID не берутся из OpenAPI examples. Natural keys сохраняют существующие internal IDs на rerun. Для fresh environments acceptance сравнивает публичные stable item/category IDs и business facts, а не случайные internal UUID.

## Dry-run и redaction

Планируемая команда:

```text
./gradlew :apps:api:bootRun --args="legacy-import --manifest docs/migration/import/manifest-v1.json --dry-run"
```

Dry-run report содержит:

- manifest/schema/source-set hashes;
- tenant slug, version, counts и `CREATE|UNCHANGED|CONFLICT`;
- asset kind/hash/size, но не bytes и не signed URL;
- secret reference status `PRESENT|MISSING`, но не env value;
- diff только для allowlisted public/config fields;
- promotion blockers.

Logger получает уже redacted report DTO. Он не получает raw config object, environment dump, source JS text, phone из private destination или exception message провайдера.

## Secret injection

Manifest хранит только markers:

```text
required-secret:LEGACY_IMPORT_STAMBUL_WHATSAPP_DESTINATION_REF
required-secret:LEGACY_IMPORT_ADAMAS_WHATSAPP_DESTINATION_REF
```

Staging secret store заранее содержит реальные destinations. Environment variables передают importer только opaque reference, например `secret://staging/tenants/stambul/whatsapp-destination`, а не сам номер. Apply без обеих references завершается до skeleton/asset/DB writes. `notification_destinations.secret_reference` хранит opaque reference; resolver worker читает secret material только при доставке.

PowerShell staging example без значений:

```powershell
$env:LEGACY_IMPORT_STAMBUL_WHATSAPP_DESTINATION_REF = '<injected-by-secret-manager>'
$env:LEGACY_IMPORT_ADAMAS_WHATSAPP_DESTINATION_REF = '<injected-by-secret-manager>'
./gradlew :apps:api:bootRun --args="legacy-import --manifest docs/migration/import/manifest-v1.json --apply --environment staging"
```

CLI запрещает `--apply --environment production`, пока все promotion requirements не переданы как independently audited confirmations.

## Promotion blocker contract

`manifest-v1.json#promotionRequirements` является единственным machine-readable набором обязательных подтверждений. CLI не выводит blockers из human-readable warnings и требует точного подтверждения каждого marker. В частности, production promotion также требует:

- `confirm-adamas-delivery-threshold-policy` — владелец явно подтверждает operational thresholds либо их осознанное отсутствие; marketing copy не становится policy автоматически;
- `approve-visual-assets:adamas-logo-hero-and-missing-optional-assets` — visual review принимает повторное использование Adamas hero как logo и отсутствие favicon, Open Graph, category и menu-item images.

`acceptance.md` и `verification-report.md` описывают тот же набор. Добавление или удаление blocker требует синхронного изменения manifest, verifier, acceptance plan и verification report.
