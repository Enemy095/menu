# Production dataset decision checklist

Status: `decision-required`
Task: [SC-DATA-001](../tasks/SC-DATA-001.md)

Complete this checklist before any content import. An answer must be explicit and
traceable to the product/data owner; absence of an answer means `blocked`.

## 1. Selection

- [ ] Select exactly one outcome:
  - [ ] `STAMBUL_APPROVED`
  - [ ] `ADAMAS_APPROVED`
  - [ ] `NEW_BUNDLE_REQUIRED`
  - [ ] `REJECTED / DO NOT IMPORT`
- Decision owner: `<name/role>`
- Decision date/time: `<ISO-8601>`
- Decision artifact/link: `<id>`
- Approved raw source digest: `<sha256>`
- [ ] Confirm the other legacy dataset must be absent from this deployment.

## 2. Business identity and contacts

- [ ] Legal/public display name approved.
- [ ] Business type, short name, cuisine text, slogan and intro approved.
- [ ] Public phone is current and may be published.
- [ ] Public phone is not confused with notification/provider destination.
- [ ] Public address: `<value or explicitly absent>`.
- [ ] Public email: `<value or explicitly absent>`.
- [ ] Social links: `<approved HTTPS list or explicitly absent>`.
- [ ] Coordinates + public label: `<values or explicitly absent>`.

## 3. Locale, currency and time

- [ ] Locale approved: `<BCP-47; proposed ru-RU>`.
- [ ] Currency approved: `<ISO-4217; proposed RUB>`.
- [ ] Business timezone approved: `<IANA identifier>`.
- [ ] Seven-day structured schedule approved.
- [ ] Human schedule text approved.
- [ ] Holiday/exception schedule is explicitly out of scope or separately specified.

Proposed schedule requiring confirmation:

```text
Monday–Sunday: 10:00–23:00 in the approved business timezone
```

## 4. Menu content

- [ ] Category names and order approved.
- [ ] Every item ID/name/description approved.
- [ ] Every integer-RUB price approved and conversion to minor units acknowledged.
- [ ] All source items are currently available, or exceptions are listed.
- [ ] No modifier/add-on data is required, or a new bundle is supplied.
- [ ] No item/category images are expected, or approved assets are supplied.
- [ ] Source menu version/published timestamp may be retained.
- [ ] Stable public IDs and deterministic UUID policy approved.

Stambul only:

- [ ] Keep exact `Пицца    4 сыра` spacing, or approve normalized `Пицца 4 сыра`.
- [ ] Confirm `Турецкая кухня` branding with the actual menu composition.

Adamas only:

- [ ] Confirm delivery-block widgets are required.
- [ ] Confirm derived popular list or provide explicit editorial popular IDs.
- [ ] Approve/reject the visible `От 40 минут` claim.
- [ ] Approve/reject the visible `Доставка от 1000 ₽ бесплатно` claim.

## 5. Order capabilities and delivery policy

- [ ] `preorder` is supported and is the default.
- [ ] `delivery` is supported; address is required.
- [ ] `pickup` is supported.
- [ ] Limits 99/item, 200 total, name 80, phone 32, address 240, comment 500 approved.
- [ ] Delayed confirmation setting `15 minutes` approved or replaced.
- [ ] Delivery fee policy selected:
  - [ ] no fee (`feePolicy=none`);
  - [ ] new contract/policy work required before publication.
- [ ] Minimum order selected: `<none/value/new contract>`.
- [ ] Free-delivery threshold selected: `<none/value/new contract>`.

Current OpenAPI cannot enforce threshold pricing. Marketing copy must not imply an
unsupported operational rule.

## 6. Branding/assets/SEO

- [ ] Source asset usage rights approved.
- [ ] Source bytes and SHA-256 match [inventory](source-inventory.md).
- [ ] Logo alt text approved.
- [ ] Hero/image behavior approved.
- [ ] Canonical HTTPS URL supplied.
- [ ] SEO title and description approved.
- [ ] Favicon supplied or explicitly absent.
- [ ] Open Graph image supplied or explicitly absent.

Adamas only:

- [ ] Asset owner resolves one PNG used for both logo and hero: approved two semantic
  asset kinds/keys or an approved safe shared-kind model.

## 7. Notification destination

- [ ] Provider/channel selected.
- [ ] Opaque reference supplied as `secret://...` or `vault://...`.
- [ ] Reference resolves in the deployment environment.
- [ ] Import bundle contains no raw phone/token/credential.
- [ ] Only readiness/fingerprint appears in logs and handoff evidence.
- [ ] Test delivery destination and production destination are not confused.

## 8. Publication readiness

- [ ] Target database contains no conflicting content graph.
- [ ] Selected normalized bundle reviewed and checksum approved.
- [ ] Unselected dataset absence assertion prepared.
- [ ] Object upload/verification and DB transaction rollback procedure approved.
- [ ] Idempotent rerun behavior demonstrated in staging.
- [ ] `/config` and `/menu` expected bodies/hashes approved.
- [ ] No orders/outbox are created by data publication.
- [ ] Backup/restore and audit-retention requirements accepted.

## Decision record

```text
Outcome:
Owner:
Artifact:
Raw source digest:
Normalized bundle digest:
Timezone/schedule:
Canonical URL:
Delivery policy:
Asset decision:
Opaque destination fingerprint:
Approved exceptions:
```

Until every required field for the selected outcome is resolved, registry status stays
`blocked` and no production import is authorized.
