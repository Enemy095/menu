# MIG-001A — provisioning foundations

Status: `superseded`
Wave: 4B
Dependencies: `DB-001`, `TEN-001`, `AST-001`, `CFG-001`, `MENU-001`

## Objective

Создать безопасные application API и persistence foundation для последующего
детерминированного импорта. Foundation должен позволить:

1. создать или переиспользовать непубличный `suspended` tenant skeleton;
2. проинспектировать текущее tenant-owned состояние без direct SQL из importer;
3. импортировать assets для `active|suspended` tenant через отдельный privileged API;
4. записать tenant configuration, default restaurant, menu и notification destination
   в одной внешней PostgreSQL transaction;
5. последним действием атомарно активировать весь согласованный набор tenant;
6. доказуемо сопоставить все staged/applied данные с immutable import run fingerprint;
7. безопасно очистить только неактивный state, созданный конкретным import run.

Результат задачи — инфраструктурные границы. Parser, CLI, dry-run classifier и
production import данных Stambul/Adamas реализуются следующими задачами.

## Non-goals

- Не реализовывать manifest parser, CLI, `--dry-run`/`--apply` и не импортировать legacy.
- Не изменять `Электронное меню/` и не создавать HTTP/admin/scheduled provisioning.
- Не ослаблять production `TenantResolver` и обычный `TenantAssetService`.
- Не активировать custom domains и не добавлять default tenant fallback.
- Не хранить реальные notification secrets/private destinations и не вводить второй
  `DataSource`, transaction manager, outbox, broker или Redis.
- Не обещать distributed transaction между PostgreSQL и object storage.

## Immutable decisions

- `tenantId` генерируется backend и никогда не берётся из manifest/fixture/OpenAPI example.
- Skeleton имеет status `suspended`; public resolution/read API видят только `active`.
- `tenants`, `tenant_branding`, `tenant_profiles` и `tenant_features` записывает
  tenancy-owned provisioning API.
- Restaurant, menu и notification пишутся только API соответствующего модуля.
- Importer зависит только от `*/api`; импорт `internal` и direct SQL в чужие таблицы запрещены.
- Все final writers используют propagation `MANDATORY` и одну orchestration transaction.
- Final writers не используют `REQUIRES_NEW`, не делают network/object-storage calls и
  не активируют tenant самостоятельно.
- Inspection/verification ports также не используют `REQUIRES_NEW`: verification
  prepared state выполняется внутри outer transaction и видит её uncommitted writes.
- Активация вызывается последней и переводит весь batch `suspended -> active` атомарно.
- Asset staging предшествует final DB transaction и поэтому является compensatable,
  а не distributed-atomic частью операции.
- Canonical S3 key и public URL policy из `AST-001`/ADR-0001 не меняются.
- Run fingerprint — lowercase SHA-256 immutable semantic plan; это provenance, а не secret.
- Реальные secret values не проходят через эти API. Допустима только opaque
  `secretReference`, причём отчёты получают лишь redacted status/fingerprint.
- Phase B предполагает ровно один PostgreSQL `DataSource` и один transaction manager.
  Изменение этого допущения блокирует реализацию до отдельного ADR.

## Exclusive ownership

Агент MIG-001A владеет только следующими областями:

- новая Flyway migration `V5__legacy_import_provisioning.sql`;
- migration ledger/provenance persistence и advisory-lock API в
  `com.salamu.menu.migration`, без parser/CLI;
- provisioning/inspection types в `tenancy/api`, `restaurant/api`, `menu/api`,
  `notification/api` и их module-owned implementations;
- privileged provisioning asset API и implementation внутри `tenancy`;
- необходимые `package-info.java` dependency declarations;
- module/unit/PostgreSQL/MinIO tests этих API.

Не менять public controllers, OpenAPI, generated client, CFG/MENU response mapping,
ordinary tenant resolution либо legacy/import Phase A artifacts. Если существующий
uncommitted diff пересекается с ownership, остановиться и сообщить оркестратору.

## V5 ledger and provenance

Migration должна добавить migration-owned таблицы без редактирования V1–V4:

```text
legacy_import_runs
  run_id UUID PK; importer_key; manifest_id; schema_version; run_fingerprint CHAR(64)
  manifest_sha256; fixture_sha256; source_set_sha256
  status staging|applying|applied|failed|cleaned
  created_at; updated_at; applied_at nullable; failure_code nullable

legacy_import_run_tenants
  run_id UUID FK; tenant_id_snapshot UUID immutable; tenant_id UUID nullable live FK
  tenant_slug; menu_version BIGINT
  tenant_source_set_sha256; asset_set_sha256; semantic_fingerprint
  status skeleton|staged|applied|cleaned; created_at; updated_at

legacy_import_run_assets
  run_id UUID FK; tenant_id_snapshot UUID immutable; tenant_id UUID nullable live FK
  asset_id_snapshot UUID nullable immutable; asset_id UUID nullable live FK
  asset_kind; sha256; size_bytes; canonical_object_key (internal cleanup only)
  status planned|created|reused|cleaned
  provenance VARCHAR; created_at; reused BOOLEAN
```

Обязательны составные FK, timestamp/status/hash checks, tenant-scoped indexes и
uniqueness для run fingerprint, одного open run на importer, asset identity и
`(run_id, canonical_object_key)`.
`asset_id` после staging ссылается на tenant-scoped asset; `created/reused` различает
новый object и существующий immutable content.
Same-run object, восстановленный после uncertain PUT либо process restart, имеет runtime
disposition `RECOVERED`, но в ledger остаётся принадлежащим этому run объектом `created`,
а не чужим `reused`. Snapshot UUID сохраняются после DB cleanup, nullable live FK
обнуляются до post-commit object cleanup.
Provenance не содержит manifest JSON, source text, asset bytes, secret reference,
телефон, address либо exception message. Canonical object key хранится только внутри
asset ledger для cleanup и никогда не попадает в public API/report/log.

Ledger API предоставляет acquire/inspect/CAS-transition/finalize semantics. Acquisition,
final apply и cleanup transaction отдельно берут `pg_advisory_xact_lock` для стабильного
canonical run fingerprint; network asset stage не удерживает connection/lock. Между transactions
конкуренцию блокируют ledger status, partial uniqueness одного open run и CAS transition.
При `23505` текущая transaction откатывается полностью; новая transaction снова берёт
xact lock и перечитывает winner. Продолжать работу в aborted transaction запрещено.

## Required public application interfaces

Конкретные Java names можно улучшить в пределах модуля, но семантика неизменна.

### Tenancy

- `provisionSuspendedSkeleton(command, runFingerprint)` — короткая transaction;
  создаёт server-generated UUID или возвращает semantic-equal suspended skeleton.
- `inspectProvisioningState(slug)` — read-only typed state: ID, slug, lifecycle,
  locale/currency/timezone и sanitized configuration fingerprint/data.
- `writeTenantConfiguration(snapshot, runFingerprint)` — `MANDATORY`; записывает или
  сравнивает branding/profile и ровно 13 известных feature rows.
- `activateCompletedBatch(candidates, runFingerprint)` — `MANDATORY`; блокирует строки
  в стабильном slug order, требует весь batch `suspended`, затем активирует весь batch.
- `cleanupSuspendedRun(runId, runFingerprint)` — отдельная explicit transaction;
  удаляет только run-owned suspended skeleton/config, не имеющие active references.

Skeleton creation не создаёт active/canonical domain. Existing `active`, `deleted`,
semantic-different `suspended` или provenance другого run дают conflict; active tenant
никогда не понижается до suspended.

### Tenant assets

- Отдельный non-HTTP `TenantAssetProvisioningService` принимает `TenantId`, asset
  request и `runFingerprint`.
- Разрешены только tenant status `active|suspended`; `deleted`/unknown отклоняются.
- Повтор того же tenant/kind/hash идемпотентен; metadata/content conflict fail-fast.
- Inspection возвращает IDs, kind, content type, size и hash, но не object key,
  credentials, signed URL или bytes.
- Созданный S3 object получает безопасную metadata/tag с `runFingerprint`; это только
  provenance для cleanup/audit, не public URL и не authorization boundary.
- Ordinary `TenantAssetService`, public URL lookup и resolver остаются active-only.
- Staged object/record привязывается к provenance run; cleanup не удаляет объект,
  имеющий active reference, и безопасен при повторе.

### Restaurant

- Read-only inspection port возвращает typed default restaurant snapshot по `TenantId`.
- `writeDefaultRestaurant(snapshot, runFingerprint)` имеет propagation `MANDATORY`.
- Writer возвращает canonical semantic hash или отдельный `verifyPrepared` port с
  propagation `MANDATORY` сверяет prepared snapshot внутри outer transaction.
- Natural key — tenant + stable restaurant slug; internal UUID переиспользуется.
- Writer не создаёт tenant, branding, menu, domain или asset и не выполняет commit.

### Menu

- Read-only inspection port возвращает version, status, currency, ordered categories,
  ordered items и asset references/fingerprints по `TenantId + restaurantId`.
- `writeMenuSnapshot(snapshot, runFingerprint)` имеет propagation `MANDATORY`.
- Writer возвращает canonical semantic hash или предоставляет `verifyPrepared`
  (`MANDATORY`) для сравнения uncommitted prepared snapshot.
- Stable category/item public IDs и source order сохраняются; money — integer minor units.
- Existing semantic-equal version — no-op; тот же version с другим content — conflict.
- Writer не публикует tenant, не архивирует чужую version и не выполняет commit.

### Notification

- Module получает разрешённую зависимость только на `tenancy::api`.
- Inspection возвращает channel/display name/status и SHA-256 opaque reference,
  но не исходный `secretReference`.
- `writeDestination(snapshot, runFingerprint)` имеет propagation `MANDATORY`, допускает
  `active|suspended` tenant и принимает только уже разрешённую opaque reference.
- Canonical result hash/`verifyPrepared` (`MANDATORY`) подтверждает prepared destination
  до activation, не раскрывая secret reference.
- Marker `required-secret:*`, blank/raw phone и unsupported scheme отклоняются.
- Natural key `(tenant_id, channel, display_name)`; differing reference — conflict,
  а не silent overwrite.

## Transaction and call sequence contract

```text
acquisition tx: xact lock → insert/reread ledger + CAS → skeletons → commit
→ privileged asset stage
→ final tx: xact lock → reread + CAS staging→applying
   → all MANDATORY writers → verifyPrepared/canonical hashes
   → provenance, включая legacy_import_run_assets
   → activateCompletedBatch last → run applied → commit
→ отдельный future post-commit public smoke
cleanup tx: xact lock → guarded cleanup/CAS → commit
```

Любая ошибка final writer откатывает configuration/restaurant/menu/destination,
activation и ledger transition вместе. После rollback могут остаться только suspended
skeleton, staged asset/provenance и immutable object; public API остаётся недоступным.

## Implementation sequence

1. Добавить V5 schema, ledger types/repository, advisory lock и migration tests.
2. Реализовать tenancy skeleton/inspection/config/activation/cleanup и privileged assets.
3. Добавить restaurant, menu и notification inspection/`MANDATORY` writers.
4. Проверить Modulith boundaries, отсутствие HTTP exposure и transaction rollback.
5. Выполнить PostgreSQL/MinIO integration suite и полный backend check.

## Required tests

- Fresh V1→V5 и upgrade V4→V5 проходят; applied migrations неизменны.
- Два concurrent acquisition сериализуются; `23505` приводит к rollback и новой
  lock+reread transaction, а не продолжению aborted transaction.
- Xact lock не удерживается во время S3/network stage; CAS/open-run uniqueness не
  разрешают competing run продолжить staging/apply.
- Equal suspended skeleton переиспользуется; active/deleted/different/run mismatch conflict.
- Suspended tenant по-прежнему даёт public `404`; unknown host не получает fallback.
- Final writer вне transaction падает до write; ошибка каждого writer откатывает
  остальные final writes и activation.
- Prepared verification видит uncommitted writes; mismatch откатывает batch до activation.
- Batch activation не допускает mixed active/suspended и не делает partial update.
- Ordinary AST service запрещает suspended; privileged service разрешает suspended/active.
- Asset rerun идемпотентен; cross-tenant/run и deleted запрещены; cleanup не затрагивает
  active tenant, foreign run или referenced active asset.
- Asset ledger различает created/reused; S3 metadata/tag содержит только run fingerprint.
- Restaurant/menu inspection и writer всегда tenant-scoped; same public IDs у двух
  tenants не конфликтуют.
- Menu equal snapshot no-op; same version/different fingerprint conflict.
- Notification marker/raw value rejected; logs/errors не раскрывают reference.
- Modulith verification подтверждает только объявленные `api` dependencies и no cycles.
- Ни один provisioning bean не опубликован как MVC endpoint.

## Failure and edge cases

- Malformed/non-lowercase fingerprint, duplicate slug в batch и empty batch отклоняются.
- Acquisition/final/cleanup используют transaction advisory lock; session lock запрещён.
- Process crash после asset stage оставляет recoverable suspended state, не public tenant.
- Process crash после commit определяется ledger `applied` + semantic inspection.
- Ledger/provenance mismatch считается conflict; автоматический repair запрещён.
- Timeout/deadlock получает стабильный redacted code; retry решает будущий orchestrator.
- Existing partial rows без matching provenance не усыновляются автоматически.
- Cleanup является explicit command foundation, повторяем и fail-closed при ambiguity.
- Asset cleanup учитывает non-atomic object delete; orphan report допустим, утрата
  active object недопустима.
- Internal UUID может передаваться между application API, но не попадает в public DTO.

## Acceptance commands

Агент запускает последовательно, без параллельных Gradle daemon/test runs:

```text
./gradlew --stop
./gradlew :apps:api:compileJava :apps:api:compileTestJava
./gradlew :apps:api:test --tests "*Provisioning*" --tests "*LegacyImport*"
./gradlew clean :apps:api:check :apps:api:bootJar
git diff --check
```

Также приложить доказательство Testcontainers PostgreSQL/MinIO tests, Flyway clean/upgrade
validation, Modulith verification и поиск, что provisioning interfaces не имеют
`@RequestMapping`/`@Controller` exposure.

## Acceptance criteria

- Ledger/lock детерминированы без PII/secrets; все ports tenant-scoped.
- Writers реально `MANDATORY`; failed transaction не активирует ни один tenant batch.
- Suspended tenant/assets не видны public boundary; ordinary AST contract не расширен.
- Cleanup ограничен run fingerprint + suspended lifecycle.
- Tests, backend check, bootJar и `git diff --check` зелёные.

## Handoff report

Реализация интегрирована оркестратором 2026-07-14:

- добавлена append-only Flyway V5 schema для import ledger, tenant ownership и
  recoverable asset stage;
- privileged provisioning/inspection/cleanup API изолированы профилем
  `legacy-import` и не регистрируются в обычном runtime;
- tenant configuration, default restaurant, published menu и notification destination
  записываются module-owned `MANDATORY` writers;
- tenant assets имеют условную S3-запись, provenance reconciliation и fail-closed
  cleanup proof;
- transaction-boundary test доказывает единый `DataSource`/transaction manager,
  общий rollback и видимость tenant только после commit/activation;
- PostgreSQL, MinIO, Flyway, Modulith и полный backend check прошли на Docker engine.

Проверено:

```text
./gradlew clean :apps:api:check --no-daemon    BUILD SUCCESSFUL
./gradlew :apps:api:bootJar --no-daemon        BUILD SUCCESSFUL
git diff --check                               clean
```

```text
Task ID: MIG-001A
Changed files:
Schema/API decisions made within allowed scope:
Module dependency changes:
Transaction propagation evidence:
Commands run and exact results:
PostgreSQL/MinIO/Flyway/Modulith evidence:
Security/redaction checks:
Unresolved issues:
Suggested next dependency unlocks: MIG-001B parser/dry-run classifier
```

Агент не коммитит и не пушит. Integration commit выполняет оркестратор после review.
