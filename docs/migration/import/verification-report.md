# MIG-001 Phase A verification report

Date: 2026-07-14

Scope: read-only legacy preflight for Stambul and Adamas

Result: source data is structurally importable; production promotion remains blocked by business/configuration decisions.

## Verified source integrity

SHA-256 был рассчитан по exact raw file bytes до декодирования. Text files дополнительно прошли strict UTF-8 validation и не имеют BOM. Path separators в manifest нормализованы в `/`; сами paths не входят в file hash.

| Scope | Files | Source-set SHA-256 |
|---|---:|---|
| global defaults/registry/base theme | 3 | `b0ac237c4ed49a8e2d6c4cf0fb7094d63077956dc9d4ada8af26f316457d4ec1` |
| Stambul config/features/menu/order types/theme/asset | 6 | `35cd63f27a6a533298a6eda7e75cdf05927538fbee8adeed63743c5d3ef3d013` |
| Adamas config/features/menu/order types/theme/asset | 6 | `2b20446a0e9fd8b7f68d64198f222516402980bf8890b3d412cdb171a337cc53` |
| complete Phase A set | 15 | `4412cf89ebf5a6664064afb9f7f745682be126e3bd1482ce0dff20175f31f064` |

Per-file sizes/checksums находятся в `manifest-v1.json`.

Assets:

- Stambul logo: valid PNG, 43,728 bytes, 197×179, SHA-256 `90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b`.
- Adamas hero/logo source: valid PNG, 447,184 bytes, 658×434, SHA-256 `68eed81f6b021efd58113f4ab6edc8feef68d91336f600729c776a07ab6caaab`.
- Все referenced non-empty legacy asset paths существуют; все 42 menu image fields пусты и детерминированно становятся null.

## Structural validation

| Check | Stambul | Adamas | Result |
|---|---:|---:|---|
| menu version | 1 | 1 | pass |
| categories | 4 | 4 | pass |
| items | 23 | 19 | pass |
| unique item IDs within tenant | 23 | 19 | pass |
| category membership | 23/23 | 19/19 | pass |
| integer nonnegative RUB prices | 23/23 | 19/19 | pass |
| item ID pattern | 23/23 | 19/19 | pass |
| source ordering contiguous | 0–22 | 0–18 | pass |
| order types | 3 | 3 | pass |
| enabled default order type | preorder | preorder | pass |
| feature rows after mapping | 13 | 13 | pass |
| available items after mapping | 23 | 19 | pass |

Cross-tenant duplicate IDs существуют и ожидаемы (`pizza-*`, `burger-chicken`); они не конфликтуют при обязательном tenant scope. Duplicate IDs внутри tenant не обнаружены.

`config.orderTypes` и отдельные `order-types.js` совпадают. `clientMenuVersion` совпадает с `menu.version`. Registry содержит ровно оба slugs; его unsafe default Adamas не переносится.

## Sanitization result

- Private manager destinations не скопированы.
- Fixture содержит только два explicit public restaurant phones с source markers.
- Destination fields содержат только `required-secret:*` markers.
- Secret values, notification phone digits, credentials и signed URLs отсутствуют.
- Public API fixture не содержит internal tenant ID, internal asset ID или OpenAPI example UUID как authoritative value.

## Conflicts and incomplete data

### Promotion blockers

1. `Europe/Moscow` отсутствует в legacy и требует owner confirmation.
2. Production canonical platform URLs не определены. Custom domains в legacy отсутствуют; ни один custom domain нельзя активировать без ownership verification.
3. Public phones существуют, но оба contact records должны быть подтверждены владельцами перед production promotion. Address отсутствует у обоих.
4. Private WhatsApp destinations должны быть предварительно помещены в secret manager и переданы только как opaque references.
5. Stambul имеет `geoLocation=true`, но `(0,0,empty label)` преобразуется в absent location. Нужно либо предоставить координаты/label, либо выключить feature.
6. Текущий AST import требует committed ACTIVE tenant. Для безопасного импорта утверждается decision gate: suspended skeleton + privileged provisioning AST + atomic dual activation. До ADR/интерфейса Phase B apply заблокирован.
7. Adamas delivery thresholds должны быть явно подтверждены либо явно отклонены владельцем; marketing copy не становится operational policy.
8. Visual review должен принять повторное использование Adamas hero как logo и отсутствие favicon, Open Graph, category и menu-item images.

Machine-readable business/configuration blockers находятся только в `manifest-v1.json#promotionRequirements`; acceptance plan и будущий CLI обязаны использовать тот же полный набор markers.

### Data-quality warnings

- Adamas использует один hero PNG одновременно как logo и hero; semantic AST kinds остаются разными.
- Favicon, Open Graph images, category images и item images отсутствуют.
- Stambul `pizza-four-cheese` содержит повторные пробелы в display name; fixture сохраняет source bytes/meaning, исправление требует content approval.
- Некоторые legacy item names/descriptions выглядят как опечатки или маркетинговые неточности; import не исправляет их молча.
- Adamas benefit обещает бесплатную доставку от 1000 ₽, но это marketing text, а не подтверждённая pricing policy.

### OpenAPI example conflicts

OpenAPI examples являются illustrative, не import source:

- design-token colors отличаются от реально используемых theme CSS;
- example menu содержит только часть items и другие image URLs;
- example `publishedAt` отличается от legacy `2026-07-01`/`2026-07-13`;
- Adamas example `minOrderMinor=50000` не имеет legacy source;
- favicon/OG URLs ссылаются на несуществующие legacy assets;
- несколько intro/SEO strings сокращены;
- example UUID не становятся production UUID.

## Required Phase B interfaces

- Tenancy provisioning API для idempotent suspended skeleton и одной atomic activation transaction обоих tenant; resolver обязан игнорировать suspended.
- Privileged, non-HTTP AST provisioning API для active/suspended tenant; обычные reads/imports остаются active-only.
- Restaurant config write/compare API с 13 feature rows, typed limits/order type config и internal asset IDs.
- Menu snapshot write/compare API, сохраняющий public IDs, version, publishedAt и ordering.
- Notification destination API, принимающий только opaque secret reference.
- Redacted compare/report API, не возвращающий private config values.

## Phase A conclusion

Mapping полностью задан для 2 tenant, 8 categories и 42 items. Structural source defects не мешают staging import. Phase B нельзя начинать как production apply до стабилизации CFG/MENU interfaces, утверждения STAGED lifecycle ADR и закрытия promotion blockers.
