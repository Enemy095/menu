# MIG-001C — deterministic legacy apply orchestration

Status: `superseded`
Wave: 4C
Dependencies: `MIG-001A`, `MIG-001B`, `DB-001`, `TEN-001`, `AST-001`, `CFG-001`, `MENU-001`

## Objective

Реализовать staging-only orchestration, которая применяет проверенный legacy manifest для Stambul и Adamas к новой multi-tenant модели без частично опубликованного состояния, без tenant-specific runtime logic и без secrets в репозитории.

Apply должен:

- создать или переиспользовать ровно два tenant в состоянии `SUSPENDED`;
- импортировать content-addressed assets до database publication;
- в одной финальной database transaction через owning module ports записать tenancy profile/branding/features, restaurants, опубликованные menu snapshots и notification destinations для обоих tenant;
- активировать оба tenant только последними операциями этой же transaction;
- быть идемпотентным при повторном запуске с тем же manifest и детерминированно отклонять конфликтующие данные;
- после commit доказать gated public HTTP smoke точный результат: Stambul — 23 позиции, Adamas — 19 позиций;
- не раскрывать PII, destination values, secret references, внутренние object keys или внутренний `tenantId` в reports, logs и public responses.

Это implementation task. Оно не является разрешением на production import или production activation.

## Scope

В scope входят:

- application-level import command/use case и orchestration state machine;
- privileged non-HTTP provisioning APIs для модулей `tenancy`, `restaurant`, `menu`, `notification` и `asset`;
- writers согласно ownership: tenancy владеет tenant/profile/branding/features, restaurant — restaurants, menu — menu snapshots, notification — destinations;
- zero-write inspection ports, читающие `ACTIVE` и import-owned `SUSPENDED` state;
- staged import двух tenant в одном orchestration run;
- загрузка и проверка assets по manifest hashes;
- destination references, переданные через защищённую runtime configuration;
- dry-run, apply, idempotent rerun, conflict detection и deterministic cleanup;
- failure injection, concurrency, tenant isolation, public API и log-redaction tests;
- staging runbook и машинно-проверяемый redacted result report.

Не входят:

- изменение legacy-каталога;
- production promotion без отдельных owner confirmations;
- создание или активация custom domains: этот slice публикует tenants только по platform slug;
- перенос реальных notification secrets в Git, manifest, fixtures, database migrations или CLI history;
- отправка реальных WhatsApp, Telegram, email или POS сообщений;
- создание универсального ETL framework;
- изменение public OpenAPI только ради import tooling;
- Flyway seed migrations с business fixtures;
- отдельные frontend/backend forks для Stambul или Adamas.

## Current blockers

### Implementation blockers

Разработка apply orchestration не начинается, пока не выполнены технические prerequisites:

- `MIG-001A` предоставил provisioning foundation: lifecycle `SUSPENDED → ACTIVE`, Flyway V5 migration ledger, privileged module APIs, zero-write inspection ports, schema constraints и transaction-compatible writers;
- `MIG-001B` завершил parser/dry-run: versioned manifest, fixture, source checksums, mapping inventory, canonical hashes, redacted report и validation rules согласованы и проходят повторную проверку;
- AST предоставляет privileged provisioning operation для assets tenant в `SUSPENDED`, не ослабляя обычный active-tenant public/admin boundary;
- tenancy, restaurant, menu и notification модули предоставляют tenant-scoped provisioning writers с propagation `MANDATORY`, пригодные для вызова внутри transaction владельца orchestration;
- tenancy API позволяет создавать/находить staged tenant, блокировать его для update и активировать только после полной записи snapshot;
- все provisioning writers и inspection ports используют один configured datasource и один transaction manager; смешение JPA/JDBC допустимо только при доказанном участии в одной transaction;
- V5 содержит migration-owned ledger для runs, tenants и assets, а S3 objects получают проверяемый import run fingerprint;
- inspection ports умеют читать `ACTIVE` и import-owned `SUSPENDED` state, не выполняют writes и не используют `REQUIRES_NEW`;
- определён защищённый staging source destination references/secrets, который возвращает только opaque references, а не secret values;
- staging database и object storage доступны, изолированы от production и допускают destructive cleanup только для import-owned staged records/assets.

Если prerequisite требует изменения module boundary, schema, inspection port или security model, сначала обновляется provisioning foundation `MIG-001A` и при необходимости создаётся ADR. Изменения parser/manifest/dry-run относятся к `MIG-001B`. `MIG-001C` не обходит отсутствующий API прямым импортом чужих `internal` packages.

### Production promotion blockers

Эти подтверждения не блокируют разработку, synthetic staging apply и automated verification, но блокируют production apply и activation:

- владелец каждого заведения подтвердил business timezone;
- подтверждены public display name, business type, contacts, schedule, address и location либо явно согласовано отсутствие каждого поля;
- подтверждены canonical platform slug и intended platform URLs;
- custom domains вынесены в отдельный ownership/TLS/cutover slice и не создаются либо активируются этим import независимо от наличия confirmation;
- подтверждены order types, default order type, operational limits, delivery configuration и feature flags;
- владелец подтвердил состав, названия, порядок, цены и доступность legacy menu;
- destination references заведены через secret management, проверены владельцем и не содержат fixture/synthetic values;
- подтверждены logo, hero и иные assets, включая права на публикацию;
- назначены approver, staging sign-off и production execution window;
- согласованы backup, rollback, observation window и ответственный за go/no-go.

Отсутствие любого production confirmation должно давать явный `PROMOTION_BLOCKED` result. Нельзя автоматически подставлять production defaults из legacy, local environment или другого tenant.

## Required provisioning contracts

Все контракты ниже являются application/module APIs. Они не публикуются как public HTTP endpoints и не принимают клиентский `tenantId`.

### Tenancy provisioning

Контракт должен поддерживать:

- поиск staged tenant по стабильному slug;
- создание tenant только в `SUSPENDED`;
- проверку immutable identity и import ownership marker;
- pessimistic/advisory locking в стабильном slug order;
- применение tenant profile, branding, SEO metadata, operational feature config и полного feature set без активации;
- проверку единственного enabled default order type в tenancy-owned feature/config model;
- активацию только после успешной полной записи обоих tenant;
- отказ от изменения существующего active tenant через initial-import command;
- раздельные cleanup ports для tenancy-owned profile/branding/features и для tenant skeleton;
- cleanup только import-owned `SUSPENDED` tenant, никогда произвольного active tenant.

Slug является внешним идентификатором, а не заменой внутреннего `tenantId`. Полученный сервером `TenantId` явно передаётся во все последующие provisioning commands.

### Restaurant provisioning

Restaurant writer должен:

- принимать `TenantId` и stable restaurant source key;
- upsert-ить ровно один default restaurant на tenant;
- валидировать только restaurant-owned fields и принадлежность restaurant tenant;
- возвращать stable restaurant identity для menu writer;
- не записывать branding, tenant profile, feature rows или notification destinations;
- не активировать tenant и не открывать public reads самостоятельно.

### Menu provisioning

Menu writer должен:

- принимать `TenantId`, restaurant identity, deterministic menu version и source manifest hash;
- создать immutable menu snapshot с categories/items в исходном legacy order;
- сохранять stable legacy item IDs как external/public IDs только внутри tenant scope;
- преобразовывать цены в integer minor units и отклонять overflow, negative или fractional ambiguity;
- проверять category membership, uniqueness, availability, currency и asset ownership;
- публиковать ровно одну current version на tenant в рамках финальной transaction;
- при совпадающем source hash возвращать idempotent result;
- при том же slug/version и другом source hash возвращать typed conflict, не перезаписывая snapshot.

### Notification provisioning

Notification writer должен:

- принимать `TenantId`, environment-scoped opaque destination reference и import fingerprint;
- создавать или переиспользовать только destination metadata/reference, принадлежащую этому tenant и environment;
- проверять readiness reference через secret provider до первой mutation, не читать и не сохранять secret value в command/report/log;
- отклонять raw phone, token, URL credential или иной destination secret в manifest и provisioning command;
- не записывать tenant profile/features, restaurant или menu rows;
- предоставлять отдельный cleanup port для import-owned staged destination references;
- не выполнять network notification/POS side effects во время import.

### Asset provisioning

Asset provisioning API должен:

- быть privileged non-HTTP API, доступным только import tooling;
- принимать asset bytes, declared kind, expected SHA-256 и staged `TenantId`;
- разрешать `ACTIVE` и import-owned `SUSPENDED` tenant, не меняя обычный active-only behavior `TenantAssetService`;
- вычислять hash сервером и сравнивать с manifest до сохранения metadata;
- использовать canonical content-addressed key `tenants/{tenantId}/{kind}/{sha256}.{ext}`;
- проверять media type, magic bytes, size и kind;
- дедуплицировать одинаковый asset внутри tenant по content hash;
- возвращать opaque asset identity и public URL, не raw object key;
- предоставлять проверку существования/целостности для rerun;
- записывать migration run fingerprint в S3 object metadata/tag и проверять его при reuse/cleanup;
- поддерживать удаление только orphan assets, созданных данным failed import run и не referenced database records.

Object storage operation не считается частью PostgreSQL transaction. Orchestration обязана учитывать этот разрыв явно.

### Zero-write inspection ports

Каждый owning module предоставляет import inspection port:

- tenancy inspection читает tenant lifecycle, profile, branding и полный feature set;
- restaurant inspection читает default restaurant и только restaurant-owned fields;
- menu inspection читает immutable snapshot, current version, categories/items и ordering;
- notification inspection подтверждает наличие, tenant/environment ownership и сохранённое readiness evidence opaque destination reference без обращения к secret provider и без раскрытия значения;
- asset inspection подтверждает database metadata, tenant ownership, kind, content hash, public URL и ledger S3 run fingerprint; S3 existence/hash/tag проверяются до Stage 5, а не сетевым вызовом внутри transaction.

Inspection ports:

- допускают `ACTIVE` и import-owned `SUSPENDED` tenants;
- являются zero-write: не выполняют lazy repair, cache fill, access timestamp update, audit write или event publication;
- при вызове из Stage 5 используют тот же datasource, connection-bound transaction context и transaction manager, поэтому видят uncommitted writes outer transaction;
- не открывают новую transaction, не используют `REQUIRES_NEW` и не вызывают public HTTP/controllers;
- возвращают typed immutable inspection models, достаточные для semantic hash/count validation;
- не заменяют обычные public reads и недоступны frontend/client input.

## Flyway V5 import ledger and ownership

`MIG-001A` должна добавить append-only production-safe Flyway V5 schema change без business fixtures. Минимальный migration-owned ledger:

- `legacy_import_runs` — run ID, exact mode, environment fingerprint, importer/schema versions, manifest/source-set hashes, status, stage, timestamps и redacted error code;
- `legacy_import_tenants` — run ID, canonical platform slug, resolved internal tenant identity, expected config/features/menu hashes, lifecycle result и ownership state;
- `legacy_import_assets` — run ID, tenant entry, asset kind/hash, opaque asset identity, created/reused state, S3 run fingerprint и cleanup state.

Точные table names могут быть скорректированы только в `MIG-001A`/ADR до реализации, но сохраняются три разные ответственности: run coordination, tenant ownership и non-transactional asset recovery. Ledger не хранит PII, raw destination values, secrets, raw source payload или S3 credentials.

Import fingerprint является migration-owned составным identity, вычисленным минимум из importer schema/version, manifest hash, source-set hash, environment fingerprint и target slug set. Он хранится в ledger и передаётся в privileged provisioning commands, но не добавляется в public DTO. S3 object получает тот же fingerprint в server-controlled metadata/tag; клиентский fingerprint не считается доверенным.

Ledger constraints должны обеспечивать:

- uniqueness exact import fingerprint для target environment;
- один незавершённый owning run на пересекающийся target slug set;
- tenant/run и asset/run referential integrity;
- typed terminal statuses `VERIFIED`, `FAILED`, `ABANDONED`, `ROLLED_BACK` и recoverable intermediate statuses;
- безопасный `23505` race path: failed transaction полностью откатывается; bounded reconciliation transaction заново получает advisory/ledger locks, перечитывает winning ledger/tenant row и классифицирует `ALREADY_APPLIED`, `IN_PROGRESS` или `CONFLICT`.

До проверки либо создания отсутствующего tenant skeleton orchestration обязана получить transaction-scoped PostgreSQL advisory lock по canonical target set fingerprint, затем lock существующей ledger run/tenant ownership row через `SELECT ... FOR UPDATE`, если она есть. Нельзя сначала проверять отсутствие skeleton и затем создавать его без advisory/ledger coordination. Row locks tenant применяются дополнительно после появления tenant rows и всегда берутся в stable slug order.

## Import identity and deterministic inputs

Один apply run идентифицируется неизменяемыми значениями:

- schema/version manifest;
- manifest SHA-256;
- source-set SHA-256;
- fixture/schema version;
- importer implementation version;
- target environment identity;
- run ID, сгенерированный сервером и безопасный для logs.

Для каждого tenant фиксируются:

- canonical slug;
- source tenant key;
- expected menu version;
- expected category/item counts;
- expected tenant configuration hash;
- expected feature-set hash;
- expected menu snapshot hash;
- ожидаемые asset hashes и kinds;
- opaque destination reference names без значений secrets.

Все collections перед hashing canonicalize-ятся по явно определённым правилам. Locale-dependent sorting, filesystem enumeration order, timestamps текущего запуска и random identifiers не входят в business snapshot hash.

## Apply modes and environment guard

Команда имеет как минимум режимы:

- `--dry-run`: читает и валидирует всё, но не изменяет PostgreSQL/object storage;
- `--apply`: выполняет staged import только при выполнении всех guards;
- `--cleanup-failed-run <runId>`: удаляет только доказанные import-owned orphan resources после dry-run cleanup report;
- `--verify <runId|manifestHash>`: повторно проверяет database/assets и, только при наличии explicit probe route, gated public HTTP без mutation.

CLI contract строгий:

- процесс запускается только при exact active profile set `legacy-import`; environment-specific значения передаются properties/secrets, а не дополнительными profile aliases;
- `spring.main.web-application-type=none`; importer не поднимает HTTP server, public/admin controllers или background workers;
- указан ровно один mutually exclusive mode из `--dry-run`, `--apply`, `--verify` или `--cleanup-failed-run`;
- неизвестный argument, отсутствие mode, несколько modes, incomplete option value или positional input дают usage error и non-zero exit;
- `--apply` принимает exact approved manifest/source-set hash, а не mutable directory/latest alias;
- validation error, guard rejection, conflict, failed apply, failed cleanup и failed verification возвращают distinct non-zero exit codes;
- `ALREADY_APPLIED` возвращает success только после zero-write integrity verification;
- runtime property не может незаметно включить web mode, дополнительный profile или production endpoint.

`--apply` запрещён по умолчанию и требует одновременно:

- explicit flag `--apply`;
- exact active profile set `legacy-import`; любые дополнительные profiles, включая `prod`/`production` aliases, дают hard deny;
- `spring.main.web-application-type=none`, проверенный runtime guard;
- environment marker, однозначно указывающий staging;
- отдельное allow flag, например `LEGACY_IMPORT_APPLY_ENABLED=true`;
- target database/object-storage fingerprint, точно совпадающий со staging allowlist и не совпадающий с production denylist;
- manifest hash allowlist либо явное повторное подтверждение exact hash;
- synthetic или staging destination references;
- secret-provider readiness и существование всех required opaque destination references без получения/логирования secret values;
- absence of active target tenants, кроме строго idempotent already-applied result;
- operator confirmation через безопасный deployment mechanism, не интерактивный secret-bearing CLI prompt.

Все profile/mode/target fingerprint/manifest hash/secret readiness guards выполняются до первой PostgreSQL или S3 mutation, включая создание ledger run. Любое несоответствие завершает процесс с non-zero exit. Production profile, production database/object-storage fingerprint, неизвестная environment identity или отсутствие marker всегда hard deny и fail closed. Возможность production apply должна появиться отдельным change после owner confirmations и release approval; staging guard нельзя отключать скрытым параметром.

## Orchestration flow

### Stage 0 — immutable input load

1. Вызвать approved `MIG-001B` parser для чтения versioned manifest и fixture как bytes.
2. Проверить UTF-8, schema version, file set и каждый declared SHA-256.
3. Проверить source-set hash и canonical business hashes.
4. Запретить undeclared files, duplicate tenant/restaurant/category/item keys и неизвестные fields, если schema не допускает их явно.
5. Разрешить destination placeholders только как opaque required references; secret values в input должны дать fail-fast.
6. Разрешить адресацию только approved platform slugs `adamas` и `stambul`; custom domain mapping в apply input отклоняется.
7. Сформировать redacted dry-run plan и expected result для обоих tenant.

Ошибки Stage 0 не создают run records, tenants, database rows или objects.

### Stage 1 — preflight and guard

1. Проверить exact CLI mode, `legacy-import` profile, web-none guard, staging marker и production deny rules.
2. Проверить exact database/object-storage environment fingerprints до подключения writer paths.
3. Проверить Flyway V5 ledger/schema и availability обязательных dependencies.
4. Проверить, что manifest/source hashes и dry-run result совпадают с approved `MIG-001B` report.
5. Проверить secret-provider readiness и resolve existence/ownership всех staging destination references, не материализуя values в report/logging context.
6. Проверить, что input содержит только platform slugs; custom domain rows не создаются и существующие custom domains не активируются.
7. Вычислить migration-owned import fingerprint и S3 run fingerprint.
8. Только после успешного выполнения всех zero-write guards открыть mutation path и зарегистрировать ledger run как `PLANNED`, сохранив hashes/counts/status, но не PII/secrets.

### Stage 2 — two suspended tenants

1. Отсортировать target slugs лексикографически; для текущего набора порядок фиксирован: `adamas`, затем `stambul`.
2. В одной короткой transaction сначала получить transaction-scoped advisory lock по canonical target set/import fingerprint.
3. Под advisory lock перечитать и `SELECT ... FOR UPDATE` существующие run/tenant ownership ledger rows; только затем проверять наличие tenant skeletons.
4. Существующие tenant rows блокировать в порядке `adamas`, `stambul`; отсутствующие skeletons создавать в том же порядке.
5. Оба tenant создаются только `SUSPENDED`; ownership фиксируется V5 ledger/import fingerprint, а не произвольным полем публичной модели.
6. Если insert получает `23505`, failed transaction полностью откатывается; новая bounded reconciliation transaction заново получает advisory/ledger locks, перечитывает winning ledger/tenant row и классифицирует результат. Blind retry insert или продолжение aborted transaction запрещены.
7. Existing target обрабатывается так:
   - тот же ledger import fingerprint и `SUSPENDED` — безопасно переиспользовать;
   - тот же completed manifest и `ACTIVE` — вернуть `ALREADY_APPLIED` после verify;
   - `ACTIVE` с другим ledger import fingerprint/hash — conflict, без mutation;
   - `SUSPENDED` другого run/hash — conflict до явного cleanup/recovery;
   - произвольный tenant с совпадающим slug — conflict, никогда не takeover.
8. Записать/обновить `legacy_import_tenants` ownership state для обоих slugs.
9. Не создавать active custom-domain mappings; допустимы только platform slug mappings.
10. Commit skeleton transaction до asset upload.

Пока оба tenant `SUSPENDED`, public resolver должен возвращать `404` и никакие частично импортированные config/menu данные не видны.

### Stage 3 — content-addressed assets

1. Для каждого tenant отсортировать assets по kind и expected SHA-256.
2. Повторно прочитать bytes и проверить SHA-256 непосредственно перед upload.
3. Вызвать privileged AST provisioning API с staged `TenantId`, import run ID и server-derived S3 run fingerprint.
4. Проверить canonical metadata, ownership, kind, computed hash, public URL и server-controlled S3 metadata/tag fingerprint каждого результата.
5. Записать результат в `legacy_import_assets`: opaque asset ID, content hash, created/reused marker, S3 fingerprint и cleanup state.
6. Rerun переиспользует существующий корректный content-addressed object/metadata только после совпадения bytes hash, tenant/kind и разрешённого run fingerprint/ledger ownership.
7. Same logical asset slot с другим hash считается новой version до final transaction; конфликт policy определяется manifest, а не заменой объекта на месте.
8. Upload/metadata failure останавливает run до финальной transaction; оба tenant остаются `SUSPENDED`.

Нельзя считать rollback PostgreSQL достаточным rollback object storage. Assets, созданные failed run, помечаются в V5 ledger как cleanup candidates и удаляются только после проверки отсутствия references и совпадения S3 run fingerprint. Object без доказанного fingerprint ownership не удаляется автоматически.

### Stage 4 — build and validate final snapshot

До write transaction полностью собрать in-memory commands для обоих tenant:

- tenancy-owned profile/branding/SEO, asset references, полный feature set, order types/limits и единственный default;
- restaurant-owned default restaurant records;
- notification-owned opaque destination references;
- menu categories/items, ordering, availability, currency и prices;
- menu version/published timestamp из approved source, а не current clock;
- hashes и expected counts.

Затем повторно выполнить все domain validations. Любая ошибка оставляет database business snapshot неизменённым и tenant suspended.

### Stage 5 — one final transaction and activation last

До открытия transaction ingress/cutover gate для platform slugs `adamas` и `stambul` должен быть явно `CLOSED`: обычный пользовательский traffic и order writes не достигают новых tenants, а разрешён только идентифицированный smoke probe после commit. Gate является deployment/cutover control, а не feature flag tenant.

Одна outer application transaction на единственном datasource и transaction manager охватывает оба tenant. Все module writers имеют propagation `MANDATORY` и не могут работать вне этой transaction. Порядок операций фиксирован:

1. Получить row/advisory locks обоих tenant в порядке `adamas`, `stambul`.
2. Перепроверить `SUSPENDED`, import ownership marker, manifest hash и отсутствие конкурентного apply.
3. Через tenancy writer записать profile, branding, SEO, asset references, operational feature config и полный feature set для обоих tenant.
4. Через restaurant writer записать default restaurants для обоих tenant.
5. Через notification writer записать только opaque destination references для обоих tenant.
6. Через menu writer записать immutable snapshots и выбрать current published menu для обоих tenant.
7. Через zero-write inspection ports того же transaction manager семантически проверить uncommitted state обоих tenant: ownership, exact config/features/order types/default, restaurants, сохранённое destination readiness evidence, asset metadata/hashes/kinds, menu version/order/prices, Stambul 23 items и Adamas 19 items.
8. Сопоставить inspection models с canonical `MIG-001B` hashes, проверить referential integrity и ровно один default restaurant/order type per tenant.
9. Проверить, что custom-domain mappings отсутствуют либо неактивны; импорт доступен только по platform slug после gate opening.
10. Пометить ledger run/tenant snapshot как `PREPARED`.
11. Только после успешной semantic inspection активировать сначала `adamas`, затем `stambul` в этой же transaction.
12. Записать completed manifest hash/import audit data.
13. Commit, оставляя ingress/cutover gate закрытым.

Запрещены `REQUIRES_NEW`, самостоятельные commits, secondary transaction manager и async writers внутри Stage 5. Tenancy/restaurant/menu/notification writers используют propagation `MANDATORY`. Inspection ports zero-write и также не открывают transaction. Final transaction не вызывает public/read API, если тот использует `REQUIRES_NEW`, другую connection или выполняет скрытый write/cache fill. Ошибка на любой операции, включая semantic inspection, вторую activation или import audit row, откатывает все business writes и обе activation: оба tenant остаются в предшествующем `SUSPENDED` состоянии.

После commit выполняется gated HTTP smoke. Post-commit verification failure не открывает ingress/cutover gate и не выполняет автоматическую частичную деактивацию одного tenant; run получает `VERIFICATION_FAILED`, write fence остаётся включённым, затем используется согласованный rollback/recovery procedure.

### Stage 6 — post-commit gated HTTP smoke

HTTP smoke выполняется через те же public boundaries, которыми пользуется frontend, но только авторизованным synthetic probe за явно закрытым ingress/cutover gate. Наличие `ACTIVE` database status само по себе не разрешает внешний traffic. Gate bypass ограничен source identity/test route и не является публичным secret query parameter.

- resolve Stambul и Adamas по platform path/host mapping;
- получить restaurant config каждого tenant;
- получить current published menu каждого tenant;
- проверить exact contract shape и отсутствие внутренних полей;
- проверить Stambul: ровно 23 items и утверждённое число/порядок categories;
- проверить Adamas: ровно 19 items и утверждённое число/порядок categories;
- проверить каждое имя, external ID, цену в minor units, availability, ordering и menu version по fixture;
- проверить logo/hero/public asset URLs и отсутствие raw object keys;
- проверить feature flags, order types, default, limits, locale, currency и timezone;
- проверить ETag/conditional GET и tenant-scoped cache headers;
- проверить unknown host `404` и отсутствие default tenant fallback;
- проверить отсутствие active custom domains и работу только platform slug routing;
- проверить, что public responses не содержат `tenantId`, destination references, phones помимо подтверждённых public contacts, secret names/values или import metadata.

Result report содержит expected/actual hashes и counts, но не полный payload с PII. Успешный run переводится в `VERIFIED`; расхождение — в `VERIFICATION_FAILED` с безопасными typed differences. В staging gate открывается отдельно только после `VERIFIED` и explicit cutover command. В production этот command дополнительно заблокирован всеми owner confirmations и release approval; import apply сам gate не открывает.

## Idempotency, rerun and conflict rules

### Idempotent rerun

Повтор с теми же manifest/source/config/menu/asset hashes должен:

- не создавать новые tenants, restaurants, menu versions, feature rows или destination rows;
- не загружать повторно корректные content-addressed assets;
- не менять published timestamps и ETag без изменения business input;
- вернуть исходные stable identities и `ALREADY_APPLIED`/`VERIFIED` result;
- повторить zero-write integrity inspection и, когда explicit ingress gate предоставляет probe route, gated public HTTP smoke;
- оставить database zero-diff, кроме отдельной bounded audit записи попытки, если она предусмотрена schema.

### Conflicts

Apply завершается до final mutation с typed conflict, если:

- target slug уже принадлежит не этому import;
- active tenant имеет другой manifest/config/menu hash;
- source menu version совпадает, но snapshot hash отличается;
- stable external category/item ID сопоставляется другому semantic object;
- asset metadata/hash/kind/tenant ownership не совпадает;
- destination reference отсутствует, относится к другому tenant/environment или содержит forbidden raw value;
- один tenant уже применён, а второй находится в несовместимом состоянии;
- обнаружен незавершённый concurrent run с тем же target set;
- owner-confirmed production data расходится с approved manifest при production promotion.

Apply не выполняет silent merge, best-effort overwrite или partial repair active data.

### Cleanup and recovery

Cleanup разрешён только для run со статусом failed/abandoned и должен:

- сначала сформировать dry-run list ресурсов;
- включить ingress/application write fence для обоих platform slugs, дождаться drain in-flight commands и проверить отсутствие active/in-flight orders;
- проверить V5 ledger ownership, run ID, import fingerprint, manifest hash и S3 fingerprints;
- отказаться от удаления `ACTIVE` tenant или referenced asset;
- быть идемпотентным;
- сохранять redacted audit результата;
- выдерживать повтор после crash между object delete и metadata cleanup.

Database cleanup выполняется одной transaction на том же datasource/transaction manager, под advisory/ledger locks и tenant locks в порядке `adamas`, `stambul`. Module cleanup ports имеют propagation `MANDATORY` и вызываются строго в таком порядке:

1. `menu` удаляет import-owned staged current pointers, items, categories и menu versions.
2. `restaurant` удаляет import-owned staged restaurants.
3. `notification` удаляет import-owned staged destination metadata/references, не secret values во внешнем provider.
4. `tenancy` удаляет import-owned features, branding/profile/SEO, platform slug mappings и asset references, но пока сохраняет tenant skeleton.
5. `asset` после повторного cross-module reference check удаляет import-owned asset metadata и возвращает immutable список S3 object deletion candidates; reused/pre-existing objects не входят в него.
6. `tenancy` удаляет import-owned `SUSPENDED` tenant skeletons.
7. Ledger фиксирует committed database cleanup и object candidates, сохраняя redacted audit rows для recovery.

Только после commit database cleanup начинается отдельная object-storage phase. Она удаляет S3 objects строго по ledger candidates при совпадении tenant scope, content hash и server-controlled S3 run fingerprint. Каждый успешный delete отмечается в ledger отдельной bounded cleanup transaction; failure остаётся `ORPHAN_OBJECT_PENDING` и безопасно ретраится. Нельзя удалять S3 object до commit database metadata cleanup, иначе rollback может оставить живую ссылку на отсутствующий object.

Если failed run оставил корректные assets, recovery может безопасно переиспользовать их. Cleanup не является обязательным условием rerun, если весь staged state совпадает по hashes и проходит integrity check.

## Transaction and concurrency requirements

- Stage 5 имеет единственную outer transaction boundary на одном datasource и transaction manager.
- Все module writers имеют propagation `MANDATORY`; ни один writer не использует `REQUIRES_NEW`, ручной commit, independent transaction template, secondary transaction manager или асинхронную запись.
- Inspection ports zero-write и участвуют в transaction caller без открытия собственной transaction.
- External object storage и secret-provider reads выполняются до финальной transaction.
- До проверки отсутствующего skeleton всегда берутся advisory target-set lock и ledger ownership lock; tenant row locks дополняют их после появления rows.
- Lock acquisition всегда использует один стабильный slug order во всех apply/cleanup/recovery paths.
- Database constraints остаются окончательным арбитром uniqueness.
- `23505` обрабатывается rollback-and-reread winning ledger/entity state, а не продолжением failed transaction или слепым insert retry.
- Concurrent одинаковые runs: один владеет locks, второй после ожидания возвращает `ALREADY_APPLIED` либо безопасный in-progress result.
- Concurrent разные manifests для тех же slugs: ровно один может продолжить; второй получает conflict без mutation.
- Timeout/deadlock не ретраится бесконечно; retry bounded и повторяет всю transaction с неизменным input hash.
- Application reads до commit видят `404` для suspended tenants; после commit authorized probe за закрытым ingress gate видит целиком новый snapshot, а обычный внешний traffic остаётся заблокирован до explicit cutover.

## Failure injection matrix

Automated tests должны принудительно вызвать сбой как минимум:

- после Stage 0 validation;
- на каждом CLI/profile/web-none/environment/production-deny/secret-readiness guard до первой mutation;
- до и после advisory/ledger lock acquisition;
- на concurrent skeleton insert с `23505` и последующем reread;
- после создания первого и второго suspended tenant;
- до первого asset upload;
- после каждого asset upload/metadata write;
- при hash mismatch и unsupported asset type;
- при отсутствии destination reference;
- после записи config первого tenant;
- после записи features первого tenant;
- после записи menu первого tenant;
- после полной записи первого tenant перед записью второго;
- внутри каждого zero-write inspection port и на semantic hash/count mismatch;
- непосредственно перед activation;
- после activation первого tenant перед activation второго;
- после activation второго перед audit update/commit;
- во время commit/deadlock/connection loss;
- сразу после commit до gated HTTP smoke;
- на gated config/menu HTTP smoke одного и второго tenant;
- при закрытом/ошибочно открытом ingress gate и failed gated smoke;
- во время cleanup object deletion и metadata deletion.

Для каждого pre-commit сбоя проверяется:

- оба tenant не публичны;
- нет частичного active state;
- Stage 5 database writes полностью откатились;
- существующие active tenants других организаций не изменены;
- orphan assets перечислены безопасно и пригодны для idempotent reuse/cleanup;
- rerun либо завершается успешно, либо даёт детерминированный typed conflict.

Для post-commit smoke failure проверяется, что ingress/cutover gate остаётся закрытым, order writes fenced, оба tenant обрабатываются как единый failed promotion set и автоматический partial rollback не выполняется.

## Test plan

### Unit tests

- manifest canonicalization и hashes;
- exact mode/profile/web-none/environment/production-deny/secret-readiness guard fail-closed behavior и exit codes;
- target-set advisory key, ledger ownership и slug lock ordering;
- `23505` rollback/reread classification;
- mapping rubles to minor units и overflow;
- config/features/order-type invariants;
- menu ordering, uniqueness и category membership;
- idempotency decision matrix;
- conflict classification;
- cleanup ownership/reference policy;
- zero-write inspection model semantic comparison;
- redaction of exception/report/log fields.

### PostgreSQL and object-storage integration tests

- fresh apply создаёт ровно два suspended skeletons, затем атомарно активирует оба;
- финальное состояние содержит два tenants, два default restaurants и ровно 42 menu items: 23 + 19;
- V5 run/tenant/asset ledger обеспечивает ownership, statuses и recovery без PII/secrets;
- tenancy feature/profile/branding, restaurant, notification и menu rows имеют tenant scope и ожидаемые hashes;
- asset keys content-addressed и tenant-scoped, а S3 metadata/tag содержит правильный server-derived run fingerprint;
- каждый module writer отказывает без outer transaction (`MANDATORY`) и участвует в одном datasource/transaction manager;
- inspection ports читают active/suspended state и не создают writes/audit/cache rows;
- повторный apply создаёт zero business diff;
- changed manifest при той же version отклоняется;
- forced failure в каждой точке Stage 5 откатывает оба tenant;
- cleanup не удаляет referenced/shared-by-rerun assets;
- concurrent same/different manifest runs, missing skeleton race и `23505` reread соответствуют advisory/ledger/row locking rules;
- two tenants могут иметь одинаковые external item/category IDs без collision;
- cross-tenant asset/config/menu/destination references отклоняются constraints/application checks;
- Flyway validation проходит на clean и pre-existing supported schema.

### Tenant isolation and security tests

- import command нельзя вызвать через public HTTP;
- обычный AST service не получает доступ к suspended tenant;
- importer не может записать tenant, отсутствующий в approved manifest;
- tenant A config/menu/assets/destinations не связываются с tenant B;
- unknown host и suspended tenant возвращают `404`;
- query/header/body `tenantId` не влияет на provisioning target;
- production environment guard нельзя обойти profile alias, отсутствующим marker или неизвестным fingerprint;
- custom domain не создаётся/активируется и smoke использует только platform slug;
- закрытый ingress/cutover gate не пропускает обычный traffic/order writes, но разрешает только explicit smoke probe;
- reports/logs/traces не содержат raw phone, destination value, secret, auth token, source bytes, full request payload или database URL credentials.

### Public API and E2E acceptance

После commit automated E2E выполняет авторизованный smoke за закрытым ingress/cutover gate:

- Stambul platform route/config/menu: exact 23 items;
- Adamas platform route/config/menu: exact 19 items;
- порядок categories/items совпадает с approved fixture;
- все prices совпадают после conversion в minor units;
- branding/assets/features/order types tenant-specific;
- одинаковые external IDs не вызывают cross-tenant response;
- conditional GET возвращает корректный `304` только для ETag того же tenant/version;
- cache headers/keys не смешивают tenants;
- смена host/path не переносит config/menu другого tenant;
- custom domain остаётся отсутствующим/неактивным;
- public JSON проходит OpenAPI validation и не содержит internal IDs/secrets;
- synthetic notification destinations не вызывают external side effects.

## Logging, reporting and observability

Разрешённые structured fields:

- run ID;
- environment identity/fingerprint alias;
- manifest/source/config/menu hashes в сокращённом или полном безопасном виде;
- tenant slug;
- stage/status/error code;
- expected/actual counts;
- asset kind/hash и created/reused marker;
- duration и bounded retry count.

Запрещённые fields:

- public/private phone values в import operational logs;
- notification destination value;
- secret reference resolution result;
- access keys, tokens, passwords, connection strings;
- raw fixture/menu/contact payload;
- arbitrary exception message от secret provider/storage/driver без sanitization;
- internal object key в public report;
- high-cardinality values как metric labels, включая run ID и asset hash.

Metrics должны иметь bounded cardinality: runs by status/stage, duration, conflicts, idempotent replays, orphan cleanup count и verification failures. Tenant slug допускается в trace/log context согласно общей policy, но не добавляется бездумно как unbounded metric label.

## Rollback and recovery

До staging apply должен существовать проверенный runbook:

1. Остановить новый apply, оставить/перевести ingress gate в `CLOSED` и включить application write fence сразу для обоих tenant.
2. Дождаться drain уже начатых commands/workers и получить advisory/ledger/tenant locks в установленном порядке.
3. Через owning inspection ports проверить orders/outbox/side effects. Автоматический rollback разрешён только при отсутствии active/in-flight orders; destructive cleanup newly imported data требует нулевого общего числа orders. Если найден хотя бы один active order или external side effect, rollback останавливается и требует отдельного incident/data-retention plan.
4. Определить состояние run по V5 ledger, tenant lifecycle, manifest/import fingerprint и S3 fingerprint.
5. Если финальная transaction не committed, оставить tenants suspended и выполнить verify/cleanup или idempotent rerun.
6. Если commit успешен, но gated HTTP smoke failed, сохранить gate закрытым и evidence; не выполнять partial ручную правку.
7. При выполненном no-orders precondition перевод обоих newly imported tenants из `ACTIVE` в `SUSPENDED` выполняется одной fenced transaction. Нельзя деактивировать только один и объявить import атомарно откаченным.
8. После suspension применяется exact module cleanup order и отдельная post-commit S3 object phase из раздела Cleanup.
9. Assets удаляются только после database rollback/deactivation, expiry rollback window, reference scan и S3 fingerprint check.
10. Повторная activation и открытие gate разрешены только после полной повторной semantic inspection и gated HTTP verification.

Для production promotion дополнительно обязательны backup/restore rehearsal, release artifact pinning, monitoring и explicit go/no-go. Production rollback не должен зависеть от legacy frontend, параллельно создающего реальные заказы.

## Deliverables

- Application orchestration command/use case с dry-run/apply/verify/cleanup modes.
- Privileged, module-safe provisioning и inspection contracts для tenancy, restaurant, menu, notification и assets.
- Tenant-scoped `MANDATORY` writers согласно module ownership на одном datasource/transaction manager.
- Flyway V5 run/tenant/asset ledger и migration-owned import fingerprint без PII/secrets.
- Server-controlled S3 run fingerprint и recoverable object cleanup state.
- Staging-only environment guard и documented target fingerprint policy.
- Strict `legacy-import` web-none CLI contract, exit codes и production hard deny.
- Deterministic redacted apply/verify reports.
- Failure injection and concurrency test harness.
- PostgreSQL + object-storage integration suite.
- Gated public HTTP/E2E smoke exact Stambul 23 / Adamas 19.
- Staging execution, recovery и cleanup runbooks.
- Ingress/cutover gate и rollback write-fencing runbook с no-active-orders gate.
- Отдельный production promotion checklist с owner confirmations и approvals.

## Acceptance criteria

Implementation считается завершённой, когда:

- provisioning/schema/inspection foundation `MIG-001A` используется без обхода module boundaries, а approved parser/dry-run outputs `MIG-001B` проверяются повторно;
- Flyway V5 ledger координирует runs/tenants/assets, S3 objects имеют проверяемый server-derived run fingerprint;
- `--apply` невозможно запустить вне exact `legacy-import` web-none CLI и явно разрешённого staging environment;
- production fingerprint/profile, неправильный mode или неготовые destination secrets завершают процесс non-zero до первой write;
- два tenant сначала существуют только как `SUSPENDED`;
- assets импортируются content-addressed с server-computed hash и tenant ownership checks;
- tenancy profile/branding/features, restaurants, menus и notification destinations для обоих tenant записываются owning module ports одной transaction;
- все writers имеют propagation `MANDATORY`, используют один datasource/transaction manager и не используют `REQUIRES_NEW`;
- zero-write inspection ports семантически проверяют uncommitted active/suspended state до activation;
- activation обоих tenant выполняется последними database operations и атомарно откатывается при любом pre-commit failure;
- custom domains не создаются/активируются, routing ограничен platform slug;
- успешный gated post-commit HTTP smoke показывает Stambul 23 и Adamas 19 items с exact approved values/order;
- rerun того же manifest даёт zero business diff;
- conflicting manifest/state отклоняется без partial mutation;
- cleanup удаляет только доказанные orphan resources failed run;
- cleanup использует exact owning-module order, отдельную S3 object phase и write fence/no-active-orders guard;
- failure injection, concurrency, tenant isolation, OpenAPI/public response и log-redaction tests проходят;
- relevant Gradle checks, Testcontainers integration tests и `git diff --check` проходят при реализации задачи;
- staging apply report и rollback rehearsal reviewed;
- production promotion остаётся blocked, пока не закрыты все owner confirmations отдельным sign-off.

## Definition of Done distinction

`MIG-001C implementation done` означает, что deterministic apply безопасно реализован и проверен в staging с synthetic destinations.

`Production promotion approved` является отдельным состоянием и требует всех owner confirmations, security/infrastructure readiness, backup/rollback rehearsal и explicit release sign-off. Завершение кода или зелёные tests не переводят задачу автоматически в production-ready и не разрешают запуск против production.
