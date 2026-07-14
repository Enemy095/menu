# ADR-0001: Public namespace для tenant assets

- Status: superseded by ADR-0003
- Date: 2026-07-14
- Scope: immutable tenant assets

## Context

Tenant assets физически хранятся в private S3-compatible bucket. Canonical object key уже зафиксирован и участвует в idempotency, persistence constraints и import recovery:

```text
tenants/{tenantId}/{assetKind}/{sha256}.{ext}
```

Прямой URL object storage не является публичным API. Bucket listing и anonymous/direct-origin reads закрыты, credentials либо presigned origin URLs нельзя передавать frontend. При этом CDN должен обслуживать immutable assets без database lookup, backend proxy или отдельного edge registry.

Публичный URL не должен буквально копировать private storage prefix `tenants/`. Storage namespace и public delivery namespace должны быть явно разделены, сохраняя детерминированное взаимно-однозначное преобразование.

## Decision

Canonical S3 key остаётся неизменным:

```text
tenants/{tenantUuid}/{assetKind}/{sha256}.{ext}
```

Backend строго разбирает canonical key и заново формирует public CDN URL:

```text
{cdnBase}/assets/{tenantUuid}/{assetKind}/{sha256}.{ext}
```

Допустимы только известные `assetKind`, lowercase UUID, lowercase SHA-256 и утверждённые immutable image extensions. Malformed или noncanonical object key не преобразуется частично и завершается ошибкой.

CDN выполняет deterministic origin-prefix rewrite:

```text
/assets/{tenantUuid}/{assetKind}/{sha256}.{ext}
→
/tenants/{tenantUuid}/{assetKind}/{sha256}.{ext}
```

Для такого rewrite не требуются database query, backend proxy, edge KV или tenant slug lookup.

UUID segment в URL является opaque public storage namespace. Он стабилен при смене slug или custom domain, но не является credential, authorization claim или доказательством tenant ownership. Public JSON DTO по-прежнему не получает поле `tenantId`; UUID присутствует только внутри asset URL.

Изменение prefix, UUID namespace либо формата public asset URL в будущем требует отдельного ADR и migration plan для сохранения старых URL, CDN cache и уже опубликованных menu/config данных.

## Security

- Object storage bucket остаётся private; anonymous access и bucket listing запрещены.
- Direct origin URL не публикуется. Публичная выдача выполняется только как immutable CDN `GET` через ограниченный origin access.
- CDN или его origin identity получает минимальное read-only permission на необходимые object keys; upload/delete/list permissions ему не выдаются.
- Backend не создаёт presigned URLs и не включает access key, secret key, origin signature или bucket credentials в public URL.
- Authorization и tenant ownership проверяются до import/lookup. UUID из URL никогда не используется как authorization boundary.
- Object key и public URL включают tenant scope, поэтому один tenant не может получить alias на object другого tenant через global hash lookup.
- Strict parser не допускает path traversal, произвольный prefix, unknown kind, uppercase/noncanonical hash или неподдерживаемое extension.

Stable UUID в нескольких asset URLs позволяет внешнему наблюдателю коррелировать assets одного tenant. Это осознанный tradeoff ради deterministic stateless CDN mapping. UUID не раскрывает tenant configuration или privileges, а `tenantId` не добавляется в JSON. Если требования privacy потребуют некоррелируемый public namespace, понадобится новый opaque identifier, отдельная migration и новый ADR.

## Consequences

Положительные последствия:

- Slug, hostname и custom domain можно менять без изменения asset URLs.
- Content hash сохраняет immutable cache semantics и позволяет долгий CDN TTL.
- Public URL строится без SQL, storage HEAD или runtime registry.
- Storage layout остаётся tenant-scoped и совместимым с существующей schema/idempotency policy.
- Разделение `assets/` и `tenants/` не позволяет случайно принять private origin key за public contract.

Операционные последствия:

- CDN и local delivery gateway должны иметь проверенный prefix rewrite `assets/` → `tenants/`.
- CDN invalidation для content-addressed object не требуется; новая версия получает новый hash URL.
- Старый URL нельзя переиспользовать для других bytes или metadata.
- Monitoring может агрегировать CDN traffic по opaque UUID только с контролем cardinality; UUID не добавляется как unbounded metric label.

## Rejected alternatives

### Mutable tenant slug в URL

```text
/assets/{tenantSlug}/{kind}/{hash.ext}
```

Отклонено: rename slug или смена canonical domain ломает ранее опубликованные URLs либо требует постоянного alias registry. Slug также создаёт лишнюю связь delivery cache с tenant resolution.

### Backend database proxy или edge KV mapping

Отклонено: каждый cache miss требует дополнительного stateful lookup, добавляет availability boundary и усложняет invalidation/deployment. Для уже tenant-scoped content-addressed key это не даёт достаточной пользы.

### Global hash alias без tenant namespace

```text
/assets/{sha256}.{ext}
```

Отклонено: теряется tenant isolation в storage/cache semantics, усложняются ownership и deletion policies, а одинаковые bytes не должны автоматически создавать cross-tenant shared lifecycle.

### Публикация raw `tenants/...` object key

Отклонено: public contract становился бы копией private storage layout. Отдельный `assets/` namespace делает origin rewrite явным и позволяет в будущем менять delivery topology независимо от S3 key, но изменение самого public namespace всё равно требует ADR и migration.
