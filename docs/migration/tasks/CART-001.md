# CART-001 — tenant-scoped persisted cart

Status: `planned`  
Wave: 6  
Dependencies: `WEB-002`

## Objective

Создать единственного owner состояния корзины в Zustand и безопасную versioned persistence, изолированную по tenant/restaurant.

## Persisted contract

```ts
type PersistedCart = {
  schemaVersion: number;
  tenantId: string;
  restaurantId: string;
  menuVersion: number;
  orderType: OrderType;
  items: Array<{ menuItemId: string; quantity: number }>;
};
```

## Deliverables

- Store actions/selectors: add, remove, set, clear, order type, count, lines и total.
- Storage adapter with tenant/restaurant-scoped key.
- Runtime schema validation and migrations.
- Reconciliation against current menu/version/features.
- Max 99 per item and 200 total, driven by tenant limits.
- Mobile cart bar, accessible modal/drawer и desktop persistent panel.
- Empty/line/total/clear UI.
- Focus trap/return, Escape/backdrop close and body scroll handling.
- User notification when unavailable/removed items are discarded.

## Trust rules

- Persist only IDs, quantities, order type and versions.
- Do not persist name, price, total, customer PII or destination.
- Recompute lines/totals from current menu.
- Corrupt/unknown future schema clears safely.
- Tenant switch never merges carts.

## Tests and acceptance

- Add/remove/clear and 99/200 limits.
- Reload restores cart/order type only.
- Tenant switch isolation.
- Corrupt JSON/unknown schema handling.
- Removed/unavailable item reconciliation.
- Stale price/name ignored.
- Mobile/desktop totals identical.
- Keyboard/focus/accessibility flows pass.
- No second cart state exists outside store.
