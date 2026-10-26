# MIG-001 Phase B acceptance plan

Команды ниже являются целевыми после реализации importer. Сейчас из них реально существует только preflight verifier; остальные должны быть добавлены вместе с Phase B production code/tests и не считаются проверенными заранее.

## 1. Preflight

```powershell
node docs/migration/import/verify-preflight.mjs
git diff --check -- docs/migration/import
```

Ожидание: `2 tenants`, `8 categories`, `42 items`, `3 asset roles`, `13 feature rows per tenant`; checksums совпадают с raw bytes.

## 2. Planned automated integration suite

```powershell
./gradlew :apps:api:test --tests "*LegacyImportManifestTests"
./gradlew :apps:api:test --tests "*LegacyImportIntegrationTests"
./gradlew :apps:api:test --tests "*LegacyImportFailureRecoveryTests"
./gradlew :apps:api:test --tests "*LegacyImportPublicApiTests"
./gradlew :apps:api:check
```

Testcontainers suite должна запускать PostgreSQL 17 и MinIO с clean volumes, читать checked-in fixture и проверять:

1. checksum mismatch, duplicate ID, invalid price, missing category/asset и missing secret marker падают до write;
2. dry-run не создаёт skeleton, asset или DB row;
3. apply создаёт оба skeleton как suspended; resolver для обоих возвращает controlled 404;
4. fault после asset stage оставляет только non-public suspended provisioning state;
5. recovery rerun использует staged content hash и не дублирует objects/assets;
6. одна final transaction записывает оба snapshots и переводит оба tenant в active;
7. concurrent public reader видит либо 0, либо 2 active tenant, никогда один;
8. final state: 2 tenant, 2 default restaurant, 8 categories, 42 items;
9. second apply возвращает `UNCHANGED` для обоих и выполняет zero writes;
10. public API полностью совпадает с fixture за исключением generated internal/public restaurant UUID и resolved CDN/canonical URLs;
11. private destinations не появляются в response, logs, traces или error reports.

## 3. Planned local apply

```powershell
docker compose --profile assets up -d --wait
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:LEGACY_IMPORT_STAMBUL_WHATSAPP_DESTINATION_REF = '<injected-by-secret-manager>'
$env:LEGACY_IMPORT_ADAMAS_WHATSAPP_DESTINATION_REF = '<injected-by-secret-manager>'
./gradlew :apps:api:bootRun --args="legacy-import --manifest docs/migration/import/manifest-v1.json --dry-run"
./gradlew :apps:api:bootRun --args="legacy-import --manifest docs/migration/import/manifest-v1.json --apply --environment staging"
```

Dry-run report должен быть redacted. Apply должен fail before writes, если canonical URL/config confirmations или secret references отсутствуют.

## 4. SQL acceptance

Все queries tenant-scoped через imported slugs. Они не предполагают, что БД содержит только эти два tenant.

### Final tenant/default restaurant counts

```powershell
docker compose exec -T postgres psql -U emenu -d emenu -v ON_ERROR_STOP=1 -c @'
SELECT t.slug,
       t.status,
       count(DISTINCT r.id) FILTER (WHERE r.is_default AND r.status = 'active') AS active_default_restaurants
FROM tenants t
LEFT JOIN restaurants r ON r.tenant_id = t.id
WHERE t.slug IN ('stambul', 'adamas')
GROUP BY t.slug, t.status
ORDER BY t.slug;
'@
```

Expected:

```text
adamas   | active | 1
stambul  | active | 1
```

### Exact versions/categories/items

```powershell
docker compose exec -T postgres psql -U emenu -d emenu -v ON_ERROR_STOP=1 -c @'
SELECT t.slug,
       mv.version,
       mv.status,
       count(DISTINCT mc.id) AS categories,
       count(DISTINCT mi.id) AS items,
       count(DISTINCT mi.id) FILTER (WHERE mi.available) AS available_items
FROM tenants t
JOIN restaurants r ON r.tenant_id = t.id AND r.is_default
JOIN menu_versions mv ON mv.tenant_id = t.id AND mv.restaurant_id = r.id AND mv.status = 'published'
LEFT JOIN menu_categories mc ON mc.tenant_id = t.id AND mc.menu_version_id = mv.id
LEFT JOIN menu_items mi ON mi.tenant_id = t.id AND mi.menu_version_id = mv.id
WHERE t.slug IN ('stambul', 'adamas')
GROUP BY t.slug, mv.version, mv.status
ORDER BY t.slug;
'@
```

Expected:

```text
adamas   | 1 | published | 4 | 19 | 19
stambul  | 1 | published | 4 | 23 | 23
```

### Ordered IDs and prices

```powershell
docker compose exec -T postgres psql -U emenu -d emenu -v ON_ERROR_STOP=1 -P pager=off -c @'
SELECT t.slug,
       mc.sort_order AS category_order,
       mc.public_id AS category_id,
       mi.sort_order AS item_order,
       mi.public_id AS item_id,
       mi.price_minor,
       mi.available,
       mi.image_asset_id
FROM tenants t
JOIN restaurants r ON r.tenant_id = t.id AND r.is_default
JOIN menu_versions mv ON mv.tenant_id = t.id AND mv.restaurant_id = r.id AND mv.status = 'published'
JOIN menu_categories mc ON mc.tenant_id = t.id AND mc.menu_version_id = mv.id
JOIN menu_items mi ON mi.tenant_id = t.id AND mi.menu_version_id = mv.id AND mi.category_id = mc.id
WHERE t.slug IN ('stambul', 'adamas')
ORDER BY t.slug, mc.sort_order, mi.sort_order;
'@
```

Integration test сравнивает все 42 rows с `fixtures/expected-tenants-v1.json`, включая names/descriptions. `image_asset_id` ожидается null для всех items.

### 13 feature rows and one default order type

```powershell
docker compose exec -T postgres psql -U emenu -d emenu -v ON_ERROR_STOP=1 -c @'
SELECT t.slug,
       count(*) AS feature_rows,
       count(*) FILTER (
         WHERE tf.feature_key IN ('preorder','delivery','pickup')
           AND tf.enabled
           AND coalesce((tf.config->>'default')::boolean, false)
       ) AS enabled_defaults
FROM tenants t
JOIN tenant_features tf ON tf.tenant_id = t.id
WHERE t.slug IN ('stambul', 'adamas')
GROUP BY t.slug
ORDER BY t.slug;
'@
```

Expected для каждого tenant: `13 | 1`.

### Secret references, not secret material

```powershell
docker compose exec -T postgres psql -U emenu -d emenu -v ON_ERROR_STOP=1 -c @'
SELECT t.slug,
       nd.channel,
       nd.status,
       nd.secret_reference LIKE 'secret://%' AS opaque_reference
FROM tenants t
JOIN notification_destinations nd ON nd.tenant_id = t.id
WHERE t.slug IN ('stambul', 'adamas')
ORDER BY t.slug, nd.channel;
'@
```

Expected: one active WhatsApp destination per tenant, `opaque_reference=true`. SQL/report никогда не печатает само значение `secret_reference`.

## 5. Rerun zero diff

Planned CLI contract:

```powershell
$before = docker compose exec -T postgres psql -U emenu -d emenu -Atc "SELECT coalesce(max(updated_at)::text,'') FROM tenants WHERE slug IN ('stambul','adamas')"
./gradlew :apps:api:bootRun --args="legacy-import --manifest docs/migration/import/manifest-v1.json --apply --environment staging"
$after = docker compose exec -T postgres psql -U emenu -d emenu -Atc "SELECT coalesce(max(updated_at)::text,'') FROM tenants WHERE slug IN ('stambul','adamas')"
if ($before -ne $after) { throw 'rerun changed tenant state' }
```

Кроме timestamp guard, integration test снимает canonical semantic snapshot всех relevant rows до/после rerun. Ожидаемый report:

```text
stambul UNCHANGED created=0 updated=0 deleted=0 uploaded=0
adamas  UNCHANGED created=0 updated=0 deleted=0 uploaded=0
```

## 6. Public API acceptance

При запущенном backend и local platform mapping:

```powershell
$stambulRestaurant = curl.exe -fsS -H 'Host: platform.localhost' http://localhost:8080/api/v1/public/stambul/restaurant | ConvertFrom-Json
$stambulMenu = curl.exe -fsS -H 'Host: platform.localhost' http://localhost:8080/api/v1/public/stambul/menu | ConvertFrom-Json
$adamasRestaurant = curl.exe -fsS -H 'Host: platform.localhost' http://localhost:8080/api/v1/public/adamas/restaurant | ConvertFrom-Json
$adamasMenu = curl.exe -fsS -H 'Host: platform.localhost' http://localhost:8080/api/v1/public/adamas/menu | ConvertFrom-Json
if ($stambulMenu.items.Count -ne 23 -or $adamasMenu.items.Count -ne 19) { throw 'menu count mismatch' }
if ($stambulRestaurant.features.deliveryBlocks -or -not $adamasRestaurant.features.deliveryBlocks) { throw 'deliveryBlocks mismatch' }
```

Additional assertions:

- unknown slug/host returns controlled 404 and never Adamas fallback;
- suspended tenant returns the same controlled 404;
- custom domain resolves only after active+verified registry row;
- response raw JSON contains no keys matching `managerPhone|secretReference|notificationDestination`;
- logo/hero URLs point to tenant-scoped immutable AST/CDN objects;
- internal `imageAssetId`/`openGraphAssetId` never appear in response;
- categories/items match source ordering and every `priceMinor` from fixture.

## 7. Promotion gate

Production promotion remains blocked until:

- owner confirms timezone and both public contact records;
- canonical platform URLs are configured;
- any custom domain ownership is verified before activation;
- secret references exist in the production secret manager;
- Stambul `geo_location=true` with absent location is resolved;
- `confirm-adamas-delivery-threshold-policy`: optional Adamas delivery thresholds are explicitly accepted or explicitly omitted;
- `approve-visual-assets:adamas-logo-hero-and-missing-optional-assets`: visual review accepts Adamas hero reused as logo and absence of favicon/OG/category/menu-item images.

Все условия представлены точными markers в `manifest-v1.json#promotionRequirements`; production CLI требует полный набор, а не интерпретирует prose или warnings.
