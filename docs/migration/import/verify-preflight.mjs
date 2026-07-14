import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { TextDecoder } from "node:util";

const importDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(importDirectory, "../../..");
const legacyRoot = path.resolve(repositoryRoot, "Электронное меню");
const manifestPath = path.join(importDirectory, "manifest-v1.json");
const fixturePath = path.join(importDirectory, "fixtures", "expected-tenants-v1.json");
const requiredPromotionRequirements = Object.freeze([
  "confirm-timezone:Europe/Moscow",
  "confirm-public-contacts:stambul",
  "confirm-public-contacts:adamas",
  "configure-canonical-platform-url:stambul",
  "configure-canonical-platform-url:adamas",
  "verify-before-activate:any-custom-domain",
  "inject-required-secret-references",
  "resolve-stambul-geolocation-feature-without-coordinates",
  "confirm-adamas-delivery-threshold-policy",
  "approve-visual-assets:adamas-logo-hero-and-missing-optional-assets"
]);

const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const assert = (condition, message) => {
  if (!condition) throw new Error(message);
};
const ordinal = (left, right) => (left < right ? -1 : left > right ? 1 : 0);
const sorted = (values) => [...values].sort(ordinal);
const assertSameSet = (actual, expected, message) => {
  assert(new Set(actual).size === actual.length, `${message}: duplicate values`);
  assert(new Set(expected).size === expected.length, `${message}: duplicate expected values`);
  assert(JSON.stringify(sorted(actual)) === JSON.stringify(sorted(expected)), message);
};
const setHash = (entries) =>
  sha256(
    Buffer.from(
      [...entries]
        .sort(([left], [right]) => ordinal(left, right))
        .map(([key, hash]) => `${key}\0${hash}\n`)
        .join(""),
      "utf8"
    )
  );

const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
const fixtureBytes = await readFile(fixturePath);
const fixtureText = new TextDecoder("utf-8", { fatal: true }).decode(fixtureBytes);
const fixture = JSON.parse(fixtureText);

assert(manifest.schemaVersion === 1, "unsupported manifest schemaVersion");
assert(fixture.schemaVersion === 1, "unsupported fixture schemaVersion");
assert(!fixtureText.includes('"managerPhone"'), "private managerPhone key must not exist in fixture");
assert(!/\b7\d{10}\b/.test(fixtureText), "unformatted private phone-like value found in fixture");
assertSameSet(
  manifest.promotionRequirements,
  requiredPromotionRequirements,
  "promotion requirement contract mismatch"
);

const allSources = [];
const validateSource = async (source) => {
  assert(source.path.startsWith("Электронное меню/"), `source outside allowlist: ${source.path}`);
  assert(!source.path.includes("\\"), `backslash is forbidden in source path: ${source.path}`);
  const sourceSegments = source.path.split("/");
  assert(
    sourceSegments.every((segment) => segment.length > 0 && segment !== "." && segment !== ".."),
    `non-canonical source path: ${source.path}`
  );
  const absolute = path.resolve(repositoryRoot, ...source.path.split("/"));
  const relativeToLegacyRoot = path.relative(legacyRoot, absolute);
  assert(
    relativeToLegacyRoot.length > 0 &&
      relativeToLegacyRoot !== ".." &&
      !relativeToLegacyRoot.startsWith(`..${path.sep}`) &&
      !path.isAbsolute(relativeToLegacyRoot),
    `source escapes legacy root: ${source.path}`
  );
  const bytes = await readFile(absolute);
  assert(bytes.length === source.bytes, `byte length mismatch: ${source.path}`);
  assert(sha256(bytes) === source.sha256, `SHA-256 mismatch: ${source.path}`);
  if (source.encoding === "UTF-8") {
    assert(!(bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf), `unexpected UTF-8 BOM: ${source.path}`);
    new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  }
  if (source.kind === "asset") {
    assert(bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])), `asset is not PNG: ${source.path}`);
    assert(bytes.subarray(12, 16).toString("ascii") === "IHDR", `PNG has no IHDR header: ${source.path}`);
    assert(source.contentType === "image/png", `unexpected asset content type: ${source.path}`);
    assert(bytes.readUInt32BE(16) === source.width, `PNG width mismatch: ${source.path}`);
    assert(bytes.readUInt32BE(20) === source.height, `PNG height mismatch: ${source.path}`);
  }
  allSources.push([source.path, source.sha256]);
};

for (const source of manifest.globalSources) await validateSource(source);
assert(
  setHash(manifest.globalSources.map((source) => [source.path, source.sha256])) === manifest.checksumPolicy.globalSourceSetSha256,
  "global source-set hash mismatch"
);

const fixturesBySlug = new Map(fixture.tenants.map((tenant) => [tenant.slug, tenant]));
let totalItems = 0;

for (const tenantManifest of manifest.tenants) {
  for (const source of tenantManifest.sources) await validateSource(source);
  assert(
    setHash(tenantManifest.sources.map((source) => [source.path, source.sha256])) === tenantManifest.sourceSetSha256,
    `source-set hash mismatch: ${tenantManifest.slug}`
  );

  const assetEntries = tenantManifest.sources
    .filter((source) => source.kind === "asset")
    .flatMap((source) => source.roles.map((role) => [role, source.sha256]));
  assert(setHash(assetEntries) === tenantManifest.assetSetSha256, `asset-set hash mismatch: ${tenantManifest.slug}`);

  const tenant = fixturesBySlug.get(tenantManifest.slug);
  assert(tenant, `fixture missing tenant: ${tenantManifest.slug}`);
  assert(tenant.status === tenantManifest.expectedFacts.status, `unexpected final tenant status: ${tenant.slug}`);
  const defaultRestaurantCount = tenant.defaultRestaurant?.isDefault === true ? 1 : 0;
  assert(
    defaultRestaurantCount === tenantManifest.expectedFacts.defaultRestaurantCount,
    `default restaurant count mismatch: ${tenant.slug}`
  );
  assert(tenant.defaultRestaurant?.status === "active", `active default restaurant missing: ${tenant.slug}`);
  assert(tenant.locale === tenantManifest.expectedFacts.locale, `locale mismatch: ${tenant.slug}`);
  assert(tenant.currency === tenantManifest.expectedFacts.currency, `tenant currency mismatch: ${tenant.slug}`);
  assert(tenant.menu.currency === tenantManifest.expectedFacts.currency, `menu currency mismatch: ${tenant.slug}`);
  assert(tenant.menu.version === tenantManifest.expectedFacts.menuVersion, `menu version mismatch: ${tenant.slug}`);
  assert(tenant.menu.categories.length === tenantManifest.expectedFacts.categoryCount, `category count mismatch: ${tenant.slug}`);
  assert(tenant.menu.items.length === tenantManifest.expectedFacts.menuItemCount, `item count mismatch: ${tenant.slug}`);
  assert(tenant.menu.items.filter((item) => item.available).length === tenantManifest.expectedFacts.availableMenuItemCount, `availability count mismatch: ${tenant.slug}`);
  assert(tenant.profile.location === null, `zero coordinates must map to absent: ${tenant.slug}`);

  const categories = new Map();
  tenant.menu.categories.forEach((category, index) => {
    assert(!categories.has(category.categoryId), `duplicate category ID: ${tenant.slug}/${category.categoryId}`);
    assert(category.sourceOrder === index && category.sortOrder === index, `category order mismatch: ${tenant.slug}/${category.categoryId}`);
    categories.set(category.categoryId, category);
  });

  const ids = new Set();
  const perCategoryIndex = new Map();
  tenant.menu.items.forEach((item, index) => {
    assert(item.sourceOrder === index, `item source order mismatch: ${tenant.slug}/${item.menuItemId}`);
    assert(!ids.has(item.menuItemId), `duplicate item ID: ${tenant.slug}/${item.menuItemId}`);
    assert(/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(item.menuItemId), `invalid item ID: ${tenant.slug}/${item.menuItemId}`);
    assert(categories.has(item.categoryId), `unknown category: ${tenant.slug}/${item.menuItemId}`);
    assert(Number.isSafeInteger(item.priceMinor) && item.priceMinor >= 0, `invalid priceMinor: ${tenant.slug}/${item.menuItemId}`);
    assert(item.imageAssetId === null, `unexpected menu image mapping: ${tenant.slug}/${item.menuItemId}`);
    const expectedSortOrder = perCategoryIndex.get(item.categoryId) ?? 0;
    assert(item.sortOrder === expectedSortOrder, `item category order mismatch: ${tenant.slug}/${item.menuItemId}`);
    perCategoryIndex.set(item.categoryId, expectedSortOrder + 1);
    ids.add(item.menuItemId);
  });

  assert(tenant.featureRows.length === 13, `expected 13 feature rows: ${tenant.slug}`);
  assert(new Set(tenant.featureRows.map((row) => row.featureKey)).size === 13, `duplicate feature row: ${tenant.slug}`);
  const orderRows = tenant.featureRows.filter((row) => ["preorder", "delivery", "pickup"].includes(row.featureKey));
  assertSameSet(
    orderRows.filter((row) => row.enabled).map((row) => row.featureKey),
    tenantManifest.expectedFacts.orderTypeCodes,
    `order type codes mismatch: ${tenant.slug}`
  );
  assert(
    orderRows.every((row) => row.enabled || row.config.default !== true),
    `disabled order type cannot be default: ${tenant.slug}`
  );
  assert(orderRows.filter((row) => row.enabled && row.config.default).length === 1, `expected one enabled default order type: ${tenant.slug}`);
  assert(orderRows.find((row) => row.config.default)?.featureKey === tenantManifest.expectedFacts.defaultOrderType, `default order type mismatch: ${tenant.slug}`);
  assert(
    tenant.featureRows.find((row) => row.featureKey === "delivery_blocks")?.enabled === tenantManifest.expectedFacts.deliveryBlocks,
    `delivery_blocks mismatch: ${tenant.slug}`
  );

  const destinationSecretReferences = tenant.notificationDestinations.map((destination) => destination.secretReference);
  for (const secretReference of destinationSecretReferences) {
    assert(
      /^required-secret:[A-Z][A-Z0-9_]*$/.test(secretReference),
      `non-marker secret reference: ${tenant.slug}`
    );
  }
  assertSameSet(
    destinationSecretReferences,
    tenantManifest.requiredSecrets,
    `required secret/destination mismatch: ${tenant.slug}`
  );
  for (const asset of tenant.assets) {
    const source = tenantManifest.sources.find(
      (candidate) => candidate.kind === "asset" && candidate.path === asset.sourcePath && candidate.roles.includes(asset.role)
    );
    assert(source?.sha256 === asset.sha256, `fixture asset does not match manifest: ${tenant.slug}/${asset.role}`);
    assert(source?.bytes === asset.bytes, `fixture asset byte length mismatch: ${tenant.slug}/${asset.role}`);
    assert(source?.contentType === asset.contentType, `fixture asset content type mismatch: ${tenant.slug}/${asset.role}`);
    assert(source?.width === asset.width, `fixture asset width mismatch: ${tenant.slug}/${asset.role}`);
    assert(source?.height === asset.height, `fixture asset height mismatch: ${tenant.slug}/${asset.role}`);
  }

  totalItems += tenant.menu.items.length;
}

assert(fixturesBySlug.size === 2, "expected exactly two fixture tenants");
assert(totalItems === 42, `expected exactly 42 items, got ${totalItems}`);
assert(setHash(allSources) === manifest.checksumPolicy.completeSourceSetSha256, "complete source-set hash mismatch");

console.log("MIG-001 preflight verified: 2 tenants, 8 categories, 42 unique tenant-scoped items, 3 asset roles, 13 feature rows per tenant.");
