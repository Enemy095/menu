# CHECKOUT-001 — checkout form and order submission UI

Status: `planned`  
Wave: 6/7  
Dependencies: `CART-001`, `API-001`, `ORD-001`

## Objective

Реализовать RHF/Zod checkout и generated-client order submission с корректной idempotency/retry state machine.

## Deliverables

- RHF form: name, phone, optional guest count, conditional address, comment, order type.
- Zod schema generated/composed from tenant limits/features.
- Unicode/control/bidi/zero-width normalization equivalent по intent legacy hardening.
- Phone normalization to accepted E.164 form.
- Address required only for delivery.
- Disabled order type cannot be selected or submitted.
- Payload builder isolated from rendering.
- `Idempotency-Key` created once per semantic draft and preserved for retry/reload until draft changes or succeeds.
- Generated API client adapter and stable error-code mapping.
- Submit lock always released on success/error; one UI action creates one request.
- Success UI displays original order number/total/confirmation state and clears cart/draft at defined point.
- `MENU_CHANGED`, unavailable item, total mismatch, validation, rate limit, network and internal error UX.

## Privacy

- Customer PII is not persisted by default.
- Pending draft persistence contains request identity/cart-derived fields only unless explicit product decision allows PII.
- Logs/errors do not include phone/address/comment.

## Tests and acceptance

- Legacy phone corpus and all text limits.
- Delivery/preorder/pickup conditional validation.
- Optional guestCount sent correctly.
- Double click and network retry reuse one key.
- Semantic edit invalidates previous pending key.
- Identical replay shows original order.
- Error paths unlock submit and preserve recoverable cart/form.
- Success clears state once and cannot resubmit accidentally.
- Generated request matches OpenAPI exactly.
- Unit/component/Playwright accessibility/E2E pass.
