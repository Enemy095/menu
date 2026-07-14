# ADR-0002: Staged provisioning для legacy import

- Status: accepted
- Date: 2026-07-14
- Scope: MIG-001 Phase B, одноразовый перенос legacy tenants

## Context

Legacy import должен перенести Stambul и Adamas в общую multi-tenant модель, не делая частично заполненный tenant доступным через production `TenantResolver`. Импорт пересекает bounded contexts `tenancy`, `restaurant`, `menu` и `notification`, но не должен обходить их ownership прямыми SQL-запросами или импортом `internal` packages.

Текущий production asset use case принимает только `active` tenant. Поэтому схема «сначала активировать tenant, затем загрузить данные и компенсировать ошибки» создаёт окно, в котором неполный tenant доступен публично. Одновременно PostgreSQL и S3-compatible object storage не поддерживают общую distributed transaction: обещать атомарность database state и object bytes невозможно.

Нужен временный orchestration boundary, который обеспечивает атомарную публикацию обоих tenant на уровне PostgreSQL, детерминированно переживает сбой между database и object storage, оставляет audit trail и после migration window полностью удаляется из runtime codebase.

## Decision

В `apps/api` вводится временный top-level Spring Modulith module:

```text
com.salamu.menu.migration
  internal/
    application/
    infrastructure/
```

Модуль предназначен только для CLI migration command и не предоставляет runtime API package. Его зависимости направлены только наружу, на публичные application/provisioning API модулей:

```text
migration
  -> tenancy::api
  -> restaurant::api
  -> menu::api
  -> notification::api

notification
  -> tenancy::api
```

Зависимость `notification -> tenancy::api` разрешена только для typed `TenantId`, tenant lifecycle/ownership checks и tenant-scoped destination operations. Ни один production module не зависит от `migration`, а `migration` не импортирует чужие `internal` packages. Spring Modulith verification обязана проверять эти границы, объявленную зависимость notification и отсутствие циклов.

Импорт запускается только как явная non-HTTP CLI command. Public, tenant-admin и platform-admin controller для него не создаётся. Обычный web startup не запускает import автоматически.

## Ownership и module-owned ports

Ownership существующей schema для MIG-001 фиксируется так:

- `tenancy` владеет tenant lifecycle/domain registry, `tenant_branding`, `tenant_profiles`, `tenant_features` и tenant assets;
- `restaurant` владеет только `restaurants`;
- `menu` владеет menu versions, categories и items;
- `notification` владеет notification destinations и их opaque secret references;
- `migration` владеет только временным import ledger из Flyway V5.

Branding, profile и feature rows не переносятся в ownership `restaurant` ради importer. Изменение ownership потребует отдельного ADR и schema/application migration.

Каждый bounded context сам владеет Java ports и реализацией операций над своими данными. Узкие provisioning, inspection и cleanup interfaces размещаются в `api` package соответствующего модуля и принимают typed `TenantId`; migration module только компонует их:

- `tenancy` создаёт или сравнивает suspended skeleton, записывает branding/profile/features, управляет lifecycle и tenant assets;
- `restaurant` создаёт или сравнивает default restaurant;
- `menu` создаёт или сравнивает immutable menu snapshot;
- `notification` создаёт или сравнивает destination metadata с opaque secret reference.

Provisioning port не является generic repository и не позволяет вызывающей стороне передать произвольный SQL, entity или table-shaped DTO. Он выражает module invariants, semantic comparison и conflict result. Модуль-владелец выполняет tenant isolation, validation и persistence; migration не пишет напрямую в принадлежащие ему таблицы.

Каждый модуль предоставляет отдельный non-HTTP inspection port. Он:

- явно допускает чтение `active|suspended` tenant для importer;
- выполняет zero writes, не создаёт lazy entities и не меняет timestamps;
- возвращает typed snapshot и canonical semantic hash;
- нормализует порядок коллекций, money minor units, locale/timezone и JSON keys;
- не возвращает secret material, persistence entity или случайные audit fields;
- присоединяется к текущей transaction, чтобы pre-activation verification видела ещё не committed writes.

Canonical hash algorithm и schema version являются частью port contract. Смена алгоритма требует новой версии hash schema, а не молчаливого сравнения несовместимых hashes. Production public read API остаётся active-only; privileged inspection port не публикуется через HTTP.

Tenant asset provisioning является отдельным tenancy-owned non-HTTP use case. Его общий контракт допускает `active|suspended`, но режим MIG-001 требует ровно `suspended` при staging и recovery. Обычный `TenantAssetService`, используемый production flows, остаётся active-only. Нельзя ослаблять его проверку или передавать boolean `skipActiveCheck` ради importer.

## Migration-owned ledger и Flyway V5

Flyway V5 создаёт три migration-owned таблицы:

```text
legacy_import_runs
legacy_import_run_tenants
legacy_import_run_assets
```

`legacy_import_runs` хранит как минимум `run_id`, `manifest_id`, manifest schema version, environment, global/complete source-set SHA-256, canonical semantic-plan SHA-256, current stage, status, redacted failure code и `created_at`, `updated_at`, `started_at`, `completed_at`. Уникальный ключ `(manifest_id, environment, complete_source_set_sha256, semantic_plan_sha256)` определяет один logical run.

`legacy_import_run_tenants` хранит `run_id`, normalized tenant slug, nullable `tenant_id` до создания skeleton, menu version, tenant source-set/asset-set SHA-256, expected config/menu/destination semantic hashes, stage, status и timestamps. Минимальные ограничения: primary/unique `(run_id, tenant_slug)` и business uniqueness `(tenant_slug, menu_version, asset_set_sha256, source_set_sha256)`.

`legacy_import_run_assets` хранит `run_id`, tenant slug/ID, asset kind, source/content SHA-256, canonical object key, nullable registered asset ID, byte size, stage/status и timestamps. Минимальные ограничения: unique `(run_id, tenant_slug, asset_kind, content_sha256)` и unique `(run_id, canonical_object_key)`. Ledger не содержит source bytes, public/private contacts, signed URLs, credentials или secret values.

Stage/status используют ограниченный versioned набор значений, достаточный для `PLANNED`, `SKELETONS_SUSPENDED`, `ASSETS_PENDING`, `ASSETS_STAGED`, `FINAL_TX_PENDING`, `DB_PUBLISHED`, `SMOKE_VERIFIED`, `CUTOVER_APPROVED`, `CLEANUP_OBJECTS_PENDING`, `COMPLETED`, `CONFLICT` и `FAILED`. Переход выполняется compare-and-set из ожидаемого предыдущего состояния; произвольный skip stage запрещён.

Run fingerprint вычисляется из `manifestId`, environment, complete source-set hash и semantic-plan hash. Его non-secret SHA-256 записывается в ledger и в metadata/tag каждого загруженного S3 object, например `legacy-import-run-fingerprint`. S3 tag помогает найти orphan candidate, но не является authorization или единственным источником истины: tenant scope, canonical key, ledger и asset registry сверяются вместе.

V5 после применения не редактируется. Ledger сохраняется на audit/rollback retention window. После удаления migration module отдельная новая Flyway migration удаляет ledger только после экспорта audit artifacts, завершения orphan cleanup и формального подтверждения retention; production lifecycle tables этой migration не затрагиваются.

## Strict CLI contract

Importer запускается только с profile `legacy-import` и `spring.main.web-application-type=none`. Profile не регистрирует MVC server, scheduler, notification worker или ordinary application runners. Команда требует ровно один взаимоисключающий mode: `--dry-run`, `--apply`, `--verify`, `--cleanup` или `--rollback`.

Отсутствующий/лишний mode, неизвестный argument, неверный profile, checksum mismatch, unresolved conflict, missing secret readiness, failed smoke test или promotion blocker завершают process с non-zero exit code. Успешные `UNCHANGED` и completed verification возвращают zero. `--force`, implicit default mode и интерактивное подтверждение не поддерживаются.

`--apply --environment production` является default-deny. До любого write CLI сверяет точный набор machine-readable promotion confirmations; отсутствие хотя бы одного blocker завершает process non-zero. Команда не получает способ обойти проверку аргументом или environment wildcard.

## Secret readiness до write boundary

До создания ledger row, skeleton или S3 object importer проверяет только metadata readiness обеих opaque secret references через notification-owned port: marker разрешён в allowlisted reference scheme, reference существует, version enabled, importer имеет право проверить metadata, а channel/provider type совместим с destination plan. Secret value не читается и не возвращается.

Если readiness нельзя подтвердить без material access, import останавливается до любого write. Результат содержит только marker и `PRESENT|MISSING|INCOMPATIBLE|UNAVAILABLE`. Secret readiness повторно проверяется перед final transaction, но provider material не разрешается внутри transaction.

## Staged lifecycle

Apply выполняется в следующем порядке:

1. CLI читает versioned manifest и sanitized fixture, проверяет allowlisted repository-relative paths, raw-byte SHA-256, source-set hashes, schema version, secret-reference markers и semantic invariants. Строится immutable plan и canonical hashes.
2. Выполняется secret readiness metadata check. До его успеха database и object storage остаются без изменений.
3. Первая короткая PostgreSQL transaction получает transaction-level advisory lock по canonical run fingerprint до проверки отсутствующего skeleton. Затем она создаёт/блокирует ledger row и через tenancy port создаёт либо сравнивает suspended skeleton сразу для обоих tenant. Ledger row существует до первой попытки создать отсутствующий skeleton.
4. После commit skeleton transaction privileged asset provisioning stage загружает и регистрирует content-addressed assets. Повторная загрузка тех же bytes с теми же natural keys возвращает `UNCHANGED`.
5. Одна final PostgreSQL transaction блокирует ledger и skeleton обоих tenant, записывает либо сравнивает snapshots через module-owned ports и вызывает все inspection ports внутри той же transaction. Config, restaurant, menu, destination и asset canonical hashes должны точно совпасть с plan до активации.
6. Только после успешной in-transaction semantic verification final transaction последним business action атомарно переводит оба tenant в `active` и фиксирует ledger stage `DB_PUBLISHED`.
7. Общий production ingress/cutover gate остаётся закрыт. Через отдельный access-controlled smoke path проверяются только canonical platform slug routes Stambul и Adamas, config/menu/assets и cross-tenant isolation. Importer не создаёт, не подтверждает и не активирует custom domains.
8. Успешный smoke переводит ledger в `SMOKE_VERIFIED`; открытие ingress является отдельным audited cutover action и переводит run в `CUTOVER_APPROVED`. При smoke failure gate остаётся закрыт, а suspend runbook одной transaction возвращает оба tenant в `suspended` и помечает run `FAILED`.

Нельзя активировать tenants отдельными transactions. До открытия cutover gate публичное состояние недоступно общему traffic, даже если database lifecycle уже `active`. Platform slug smoke является единственным ingress verification для MIG-001. Custom domain ownership и activation выполняются позже отдельным production workflow после проверки владения.

## Transaction boundaries, locks и concurrency

Skeleton transaction и final publication transaction являются разными короткими transactions. Asset upload выполняется между ними и не удерживает database locks на время network I/O.

MIG-001 поддерживается только при одном Spring `DataSource` и одном `PlatformTransactionManager` для ledger и всех tenant/restaurant/menu/notification writes. Startup/apply обязан отказать, если module writer использует другой datasource/transaction manager. В final transaction orchestration boundary находится в migration application service, а все module-owned final writers имеют propagation `MANDATORY`. `REQUIRED`, `REQUIRES_NEW`, programmatic independent commit и early flush/commit как отдельная transaction для этих writers запрещены. Inspection ports выполняются в той же transaction.

Перед проверкой отсутствующего ledger/skeleton acquisition transaction получает `pg_advisory_xact_lock` по стабильному 64-bit ключу, выведенному из canonical run fingerprint. После этого ledger row блокируется `FOR UPDATE`. Оба tenant блокируются в стабильном порядке по normalized slug, затем по immutable `TenantId` как tie-breaker. Все module writers соблюдают тот же порядок.

Unique constraints остаются последней защитой от гонки. Если сторонний concurrent provisioner всё же выигрывает insert и PostgreSQL возвращает `23505`, текущая transaction считается aborted и полностью откатывается. Importer начинает новую acquisition transaction, повторно берёт advisory lock и перечитывает winner row/skeleton; он не продолжает работу в failed transaction и не выполняет blind retry insert. Semantic-equal winner даёт `UNCHANGED`/resume, отличный winner даёт conflict.

Domain/application events, необходимые после публикации, записываются внутри final transaction и доставляются только после commit. Importer не выполняет внешнее уведомление, POS call или secret resolution внутри transaction.

## Asset state rechecks и pending recovery

Для MIG-001 privileged asset port повторно читает lifecycle tenant и требует ровно `suspended` на каждой database transaction: перед reserve/dedup decision, при регистрации uploaded object, при resume pending asset и при подготовке cleanup. Проверка, выполненная в skeleton transaction или в памяти importer, не переиспользуется как authorization для следующей transaction.

Непосредственно перед S3 `PUT` importer выполняет fresh suspended inspection. После `PUT` database registration transaction снова блокирует tenant/ledger rows и повторяет suspended check. Если tenant уже не suspended, object не регистрируется как active asset, ledger asset остаётся `ASSETS_PENDING`, а object обрабатывается как fingerprinted orphan candidate. Recovery никогда не продолжает asset mutation для active tenant; сначала требуется audited suspend/rollback decision.

Каждая asset operation compare-and-set обновляет `legacy_import_run_assets`. Неопределённый network result остаётся pending до `HEAD`/metadata/hash reconciliation; importer не делает немедленный compensating delete. Ordinary `TenantAssetService` продолжает проверять строго `active` и не используется recovery command.

## Отсутствие distributed atomicity и recovery

STAGED provisioning не является distributed transaction между PostgreSQL и S3. Возможны следующие состояния:

- upload не состоялся: tenant skeleton остаётся suspended, snapshot не публикуется;
- object upload состоялся, а asset registration не состоялась: fingerprinted object является orphan candidate, ledger остаётся pending;
- assets зарегистрированы, а final transaction откатилась: suspended skeleton и staged assets остаются для deterministic rerun или cleanup;
- final transaction committed, а gated smoke не прошёл: ingress остаётся закрыт, suspend runbook возвращает оба tenant в suspended;
- smoke прошёл: cutover открывается только отдельным audited action.

Content-addressed key, tenant scope, manifest/run fingerprints и semantic natural keys позволяют rerun распознать staged bytes без повторной публикации. Natural keys сохраняют существующий internal ID; случайный новый UUID на rerun не создаётся.

## Cleanup contract

Cleanup является отдельным CLI mode и доступен только для run, который не прошёл cutover и чьи tenants находятся в `suspended`. Каждый модуль предоставляет migration-specific cleanup port для принадлежащих ему данных; migration не выполняет direct JDBC cleanup чужих tables.

Порядок cleanup фиксирован:

1. Закрыть ingress gate, взять advisory lock, заблокировать ledger/tenant rows и подтвердить suspended lifecycle, run fingerprint, отсутствие active references и допустимый cleanup stage.
2. В одной database transaction вызвать module cleanup ports в dependency-safe порядке: notification destinations, menu snapshot, restaurant, tenancy-owned branding/profile/features и asset registry, затем tenant skeleton. Canonical object keys сохраняются в migration ledger, а run переводится в `CLEANUP_OBJECTS_PENDING`.
3. После commit database cleanup удалить только перечисленные fingerprinted objects. S3 delete идемпотентен: отсутствующий object считается success; hash/metadata mismatch не удаляется и создаёт conflict.
4. Маленькими ledger transactions отмечать результат каждого object delete. После полного успеха run становится `COMPLETED`; временный сбой оставляет `CLEANUP_OBJECTS_PENDING` для безопасного rerun.

Object cleanup не выполняется до commit database cleanup. Object, на который ссылается любой active или suspended asset record вне очищаемого run, не удаляется. Grace period и storage inventory используются для дополнительных orphan candidates, но не заменяют ledger/reference checks.

## Idempotency, conflicts и dry-run

Минимальный business key import plan:

```text
(tenantSlug, menuVersion, assetSetSha256)
```

Дополнительно сравниваются `manifestId`, `sourceSetSha256`, complete source-set hash, semantic-plan hash и canonical module hashes.

- Отсутствующий state с совпадающими checksums создаётся через staged lifecycle.
- Совпадающий suspended state продолжается с последнего ledger stage.
- Полностью semantic-equal state возвращает `UNCHANGED` и выполняет zero domain writes.
- Тот же business key с другим content возвращает `IMPORT_CONTENT_CONFLICT`.
- Существующая menu version с отличающимся snapshot не перезаписывается.
- Partial state без доказуемой связи с ledger/run fingerprint не чинится автоматически.

`--dry-run` выполняет parse, checksum, secret metadata readiness, validation и semantic comparison через zero-write inspection ports, но не создаёт ledger/skeleton, не загружает assets и не пишет в PostgreSQL или S3. Report перечисляет planned actions, counts, allowlisted diffs, hashes и promotion blockers.

## Redaction

Manifest и fixture не содержат private destination value. Они содержат только `required-secret:*` marker, а notification module получает opaque reference из утверждённого secret manager.

Dry-run, apply report, exceptions и structured logs проходят redaction до logging boundary. Разрешены tenant slug, manifest/schema version, counts, content hashes, asset kind/size, action/result и readiness status. Запрещены raw fixture/JS, environment dump, secret value, signed URL, private destination, customer data и provider exception body. Conflict report показывает только allowlisted public/config fields и redacted hashes.

## Production promotion blockers

Production apply и cutover допускаются только после выполнения всех условий:

- реализованы V5 ledger и module-owned provisioning/inspection/cleanup ports;
- Spring Modulith verification, tenant isolation, canonical-hash, concurrency, idempotency и recovery tests проходят;
- staging dry-run, apply, in-transaction verification и gated smoke завершены без conflict;
- exact `promotionRequirements` из manifest подтверждены и совпадают с verifier/acceptance documentation;
- timezone/location/order policies, public contacts и visual assets явно одобрены;
- обе notification secret references metadata-ready без раскрытия material;
- canonical platform slug routes, CDN prefix rewrite и object-storage/orphan runbook проверены;
- custom domains не активируются importer; их последующая activation требует отдельной ownership verification;
- observability, database backup, ingress drain, rollback procedure и rollback window согласованы;
- legacy и новый frontend не создают параллельные реальные заказы.

Human-readable warning не заменяет machine-readable blocker и не может быть пропущен флагом. Изменение набора blockers требует синхронного изменения manifest, verifier, acceptance plan и verification report.

## Testing

До promotion обязательны:

- Spring Modulith test для declared outbound dependencies, `notification -> tenancy::api` и отсутствия входящих зависимостей на `migration`;
- Flyway V5 clean/existing-schema validation, ledger constraints и future retention-removal migration test;
- unit tests manifest parser, canonical hashes, secret readiness, redaction и import-plan invariants;
- PostgreSQL tests suspended visibility, MANDATORY writers, single transaction manager и двух-tenant rollback/activation;
- concurrency tests advisory lock, stable row order, ledger CAS, `23505` rollback/winner reread и concurrent identical import;
- idempotency tests `CREATE`, resumed pending state, `UNCHANGED` и content conflict;
- Testcontainers PostgreSQL/S3 tests upload failure, uncertain PUT, registration failure, lifecycle recheck, final rollback, rerun и orphan reconciliation;
- cleanup tests, доказывающие database-first order, reference protection и idempotent object deletion;
- security tests отсутствия HTTP route, strict CLI profile и active-only ordinary `TenantAssetService`;
- CLI tests exactly-one-mode, non-zero failures и default-deny production;
- dry-run test, доказывающий zero writes в database и object storage;
- pre-activation canonical-hash verification и post-commit gated platform-slug smoke tests;
- log/report tests, запрещающие secret/private values;
- rollback tests ingress drain, write-boundary recheck и отказ после cutover/orders.

Fault-injection tests проверяют каждую границу между skeleton transaction, asset operations, final transaction, gated smoke и cleanup.

## Consequences

Положительные последствия:

- частично заполненный tenant не становится доступным общему traffic;
- оба legacy tenant публикуются атомарно на уровне PostgreSQL;
- semantic snapshot проверяется до activation commit и после него через gated ingress;
- bounded-context ownership сохраняется во время одноразовой миграции;
- ledger, S3 fingerprint и module hashes дают детерминированные rerun/cleanup;
- production asset service не ослабляет lifecycle checks;
- временный код и данные имеют явные условия удаления.

Стоимость решения:

- требуется V5 ledger и несколько узких временных module APIs;
- S3 failures требуют pending recovery и orphan runbook;
- final transaction охватывает несколько модулей и допустима только при одном datasource/transaction manager;
- cutover требует отдельного ingress gate, controlled smoke и drain procedure;
- до окончания rollback/audit window поддерживаются CLI profile и дополнительные integration tests.

## Rejected alternatives

### Direct JDBC writes в таблицы других модулей

Отклонено: importer обходит domain invariants, tenant checks и module ownership, связывается с внутренней schema и делает Spring Modulith boundaries фиктивными.

### Active-first import с compensation

Отклонено: неполный tenant становится доступным resolver, а compensation после неопределённого storage/database результата небезопасна и не восстанавливает наблюдаемую атомарность.

### Отдельная activation transaction на tenant

Отклонено: между commits один tenant уже active, а другой ещё suspended. MIG-001 требует публикацию обоих или ни одного.

### `REQUIRES_NEW` или самостоятельный `REQUIRED` final writer

Отклонено: отдельный commit переживёт rollback orchestrator transaction и оставит несогласованный snapshot. Все final writers используют `MANDATORY` и один transaction manager.

### Public platform-admin import endpoint

Отклонено: одноразовый privileged workflow увеличивает attack surface и позволяет случайный production повтор. Строгий non-web CLI соответствует назначению.

### Flyway data import

Отклонено: Flyway не подходит для network asset upload, secret readiness, dry-run, semantic conflicts и staged recovery. Flyway используется только для V5 schema ledger и других необходимых schema changes.

### Custom domain activation внутри importer

Отклонено: ownership verification и DNS/certificate rollout имеют отдельный lifecycle. MIG-001 проверяет platform slug за закрытым ingress gate.

## Rollback

До final publication rollback означает оставить tenant suspended, затем выполнить deterministic rerun или cleanup.

При gated smoke failure ingress уже закрыт. Suspend runbook получает advisory lock, блокирует оба tenant в stable order, повторно проверяет ledger/cutover state на write boundary и одной transaction переводит оба tenant в `suspended`. Данные и assets сохраняются для анализа до явного cleanup.

До любого rollback после publication operator обязан закрыть ingress gate и дождаться drain всех in-flight reads/writes. На самой database write boundary importer повторно проверяет, что gate остаётся закрыт, custom domains не active, cutover для orders не открывался и run не перешёл в состояние, допускающее реальные orders. Проверка только перед drain недостаточна.

После открытия order ingress или появления хотя бы одного реального order автоматический MIG-001 rollback запрещён. Для такого rollback нужен отдельный ADR с участием ordering/outbox, политикой уже принятых заказов и versioned restore strategy. Migration module поэтому не получает обычную зависимость на ordering и не пытается удалять либо откатывать orders.

Если импорт обновляет существующий tenant, а не создаёт новый, сценарий также требует отдельного ADR и не входит в MIG-001.

## Removal criteria

Migration module удаляется полностью, когда одновременно выполнены условия:

- оба tenant прошли production acceptance и observability review;
- завершён rollback window, а rollback больше не зависит от importer;
- legacy traffic переведён и не осталось approved import consumers;
- audit artifacts экспортированы, retention подтверждён;
- pending asset recovery, orphan scan и cleanup завершены;
- custom-domain/cutover ownership передан обычным production workflows.

При удалении удаляются CLI profile, migration module и wiring. Узкие provisioning/inspection/cleanup APIs удаляются, если у них нет подтверждённого production consumer; превращать их в постоянный admin API без нового ADR запрещено. Затем новая Flyway migration после audit retention удаляет `legacy_import_run_assets`, `legacy_import_run_tenants` и `legacy_import_runs` в dependency order. V5 не изменяется. Production lifecycle schema сохраняется.
