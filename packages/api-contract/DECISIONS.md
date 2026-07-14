# Contract decisions

## OpenAPI как source of truth

- Публичный API использует OpenAPI 3.0.3 и единый namespace `/api/v1/public`.
- Tenant выбирается trusted host и path-параметром `tenantSlug`; внутренний `tenantId` не раскрывается.
- `Idempotency-Key` передаётся только в header и не входит в `CreateOrderRequest`.
- Цена заказа всегда пересчитывается backend; `clientTotalMinor` нужен для обнаружения расхождения.
- Ошибки используют один `ErrorResponse` envelope.

## Client и server

- TypeScript client генерируется OpenAPI Generator 7.22.0 с generator `typescript-fetch`.
- Generated TypeScript сохраняется в `generated/typescript-fetch` и не редактируется вручную.
- Spring MVC controllers и Java DTO реализуются вручную против контракта; Java server stubs не генерируются.
- Generated client является build artifact контракта, а не независимым источником API-модели.

## Compatibility

- Изменения сначала вносятся в OpenAPI и проверяются до изменений consumer/implementation.
- Breaking changes требуют новой API version или отдельной согласованной migration strategy.
- Legacy endpoints `/api/public/orders` и `/api/v1/public/{slug}/orders` не переносятся параллельно: целевой endpoint только `/api/v1/public/{tenantSlug}/orders`.
