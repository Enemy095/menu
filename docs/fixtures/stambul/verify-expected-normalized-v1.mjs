import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../../../", import.meta.url));
const fixturePath = fileURLToPath(new URL("./expected-normalized-v1.json", import.meta.url));
const importerBundlePath = path.join(
  repositoryRoot,
  "docs/migration/data/stambul-approved-v1.json",
);
const expected = JSON.parse(await readFile(fixturePath, "utf8"));

const sourcePaths = {
  config: "Электронное меню/restaurants/stambul/config.js",
  features: "Электронное меню/restaurants/stambul/features.js",
  orderTypes: "Электронное меню/restaurants/stambul/order-types.js",
  menu: "Электронное меню/restaurants/stambul/menu.js",
  theme: "Электронное меню/themes/stambul/theme.css",
  logo: "Электронное меню/restaurants/stambul/branding/logo.png",
};

const categoryIds = new Map([
  ["Роллы", "rolls"],
  ["Пицца", "pizza"],
  ["Гарниры", "sides"],
  ["Бургеры", "burgers"],
]);

async function legacyObject(relativePath, globalName) {
  const source = await readFile(path.join(repositoryRoot, relativePath), "utf8");
  const sandbox = { window: Object.create(null) };
  vm.runInNewContext(source, sandbox, {
    filename: relativePath,
    timeout: 1_000,
  });
  assert.ok(sandbox.window[globalName], `${relativePath} must define ${globalName}`);
  return JSON.parse(JSON.stringify(sandbox.window[globalName]));
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function canonicalSourceBytes(sourcePath, bytes) {
  if (sourcePath.endsWith(".png")) {
    return bytes;
  }
  const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  return Buffer.from(text.replace(/\r\n?/gu, "\n"), "utf8");
}

function uuidV5(namespace, name) {
  const namespaceBytes = Buffer.from(namespace.replaceAll("-", ""), "hex");
  assert.equal(namespaceBytes.length, 16, "UUID namespace must contain 16 bytes");
  const bytes = createHash("sha1")
    .update(namespaceBytes)
    .update(name, "utf8")
    .digest()
    .subarray(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}

function normalizedItem(sourceItem, sortOrder) {
  return {
    menuItemId: sourceItem.id,
    categoryId: categoryIds.get(sourceItem.category),
    name: sourceItem.name,
    description: sourceItem.description,
    priceMinor: sourceItem.price * 100,
    available: true,
    sortOrder,
    imageAssetRef: null,
    modifierGroups: [],
  };
}

function relativeLuminance(hex) {
  const channel = (offset) => Number.parseInt(hex.slice(offset, offset + 2), 16) / 255;
  const linear = (value) =>
    value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  const [red, green, blue] = [channel(1), channel(3), channel(5)].map(linear);
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

function contrastRatio(foreground, background) {
  const first = relativeLuminance(foreground);
  const second = relativeLuminance(background);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}

test("selected source inventory remains canonical-LF exact and has a stable aggregate digest", async () => {
  for (const source of expected.sourceSet.selectedFiles) {
    const bytes = await readFile(path.join(repositoryRoot, source.path));
    const canonicalBytes = canonicalSourceBytes(source.path, bytes);
    assert.equal(canonicalBytes.length, source.sizeBytes, `${source.path} byte length drifted`);
    assert.equal(sha256(canonicalBytes), source.sha256, `${source.path} SHA-256 drifted`);
  }

  const aggregate = sha256(Buffer.from(JSON.stringify(expected.sourceSet.selectedFiles), "utf8"));
  assert.equal(aggregate, expected.sourceSet.digest);
  assert.equal(expected.selection, "STAMBUL_APPROVED");
});

test("Git attributes and blobs preserve the canonical source bytes across operating systems", async () => {
  for (const source of expected.sourceSet.selectedFiles.filter(({ path: sourcePath }) => !sourcePath.endsWith(".png"))) {
    const blob = spawnSync("git", ["show", `HEAD:${source.path}`], {
      cwd: repositoryRoot,
      encoding: null,
      maxBuffer: 1024 * 1024,
    });
    assert.equal(blob.status, 0, blob.stderr?.toString("utf8"));
    assert.equal(blob.stdout.length, source.sizeBytes, `${source.path} Git blob length drifted`);
    assert.equal(sha256(blob.stdout), source.sha256, `${source.path} Git blob hash drifted`);

    const workingBytes = await readFile(path.join(repositoryRoot, source.path));
    assert.deepEqual(canonicalSourceBytes(source.path, workingBytes), blob.stdout);

    const attributes = spawnSync("git", ["check-attr", "text", "eol", "--", source.path], {
      cwd: repositoryRoot,
      encoding: "utf8",
    });
    assert.equal(attributes.status, 0, attributes.stderr);
    assert.equal(attributes.stderr, "");
    assert.match(attributes.stdout, /: text: set(?:\r?\n)/u);
    assert.match(attributes.stdout, /: eol: lf(?:\r?\n|$)/u);
  }

  const logoAttributes = spawnSync(
    "git",
    ["check-attr", "text", "--", sourcePaths.logo],
    { cwd: repositoryRoot, encoding: "utf8" },
  );
  assert.equal(logoAttributes.status, 0, logoAttributes.stderr);
  assert.equal(logoAttributes.stderr, "");
  assert.match(logoAttributes.stdout, /: text: unset(?:\r?\n|$)/u);

  const bundleAttributes = spawnSync(
    "git",
    ["check-attr", "text", "eol", "--", expected.approvedImporterBundle.path],
    { cwd: repositoryRoot, encoding: "utf8" },
  );
  assert.equal(bundleAttributes.status, 0, bundleAttributes.stderr);
  assert.equal(bundleAttributes.stderr, "");
  assert.match(bundleAttributes.stdout, /: text: set(?:\r?\n)/u);
  assert.match(bundleAttributes.stdout, /: eol: lf(?:\r?\n|$)/u);
});

test("the checked-in importer bundle is the same one-dataset acceptance baseline", async () => {
  const importerBundleBytes = await readFile(importerBundlePath);
  const bundle = JSON.parse(importerBundleBytes.toString("utf8"));
  const legacyConfig = await legacyObject(sourcePaths.config, "RESTAURANT_STAMBUL");
  const graph = expected.normalizedGraph;

  assert.equal(
    path.relative(repositoryRoot, importerBundlePath).replaceAll("\\", "/"),
    expected.approvedImporterBundle.path,
  );
  assert.equal(sha256(importerBundleBytes), expected.approvedImporterBundle.sha256);
  assert.equal(bundle.schemaVersion, expected.schemaVersion);
  assert.equal(bundle.selection, expected.selection);
  assert.equal(bundle.datasetKey, "stambul");
  assert.equal(bundle.uuidNamespace, expected.deterministicIds.namespaceUuid);
  assert.deepEqual(
    bundle.sources.map(({ path: sourcePath, sizeBytes, sha256: digest }) => ({
      path: sourcePath,
      sizeBytes,
      sha256: digest,
    })),
    expected.sourceSet.selectedFiles,
  );
  assert.deepEqual(bundle.localTestBaseline, {
    locale: expected.deploymentBaseline.locale,
    currency: expected.deploymentBaseline.currency,
    timezone: expected.deploymentBaseline.timezone,
    canonicalUrl: expected.deploymentBaseline.canonicalUrl,
    scheduleApproved: true,
    publicContactApproved: true,
    assetRightsApprovedForLocalTest: true,
    feePolicy: "none",
    minimumOrderMinor: null,
    freeDeliveryThresholdMinor: null,
    notificationDestination: null,
    workerEnabled: false,
  });

  assert.deepEqual(bundle.asset, {
    name: "logo",
    kind: "LOGO",
    sourcePath: graph.assets[0].sourcePath,
    contentType: graph.assets[0].contentType,
    extension: "png",
    sizeBytes: graph.assets[0].sizeBytes,
    sha256: graph.assets[0].sha256,
    altText: graph.siteBranding.logoAlt,
  });
  assert.equal(bundle.site.displayName, graph.siteBranding.displayName);
  assert.equal(bundle.site.businessType, graph.siteBranding.businessType);
  assert.deepEqual(bundle.site.designTokens, graph.siteBranding.designTokens);
  assert.deepEqual(bundle.site.localizedTexts, graph.siteBranding.localizedTexts);
  assert.deepEqual(bundle.site.hero, {
    title: graph.siteBranding.hero.title,
    subtitle: graph.siteBranding.hero.subtitle,
    ctaLabel: graph.siteBranding.hero.ctaLabel,
    benefits: [],
  });
  assert.deepEqual(bundle.site.seo, {
    title: graph.siteBranding.seo.title,
    description: graph.siteBranding.seo.description,
  });
  assert.deepEqual(bundle.site.profile, {
    publicPhone: graph.siteProfile.publicPhone,
    publicPhoneLabel: graph.siteProfile.publicPhoneLabel,
    publicEmail: null,
    address: null,
    workingHoursText: graph.siteProfile.workingHoursText,
    workingHours: graph.siteProfile.schedule.map((day) => ({
      day: day.dayOfWeek,
      opensAt: day.intervals[0].opensAt,
      closesAt: day.intervals[0].closesAt,
    })),
    socialLinks: [],
  });
  assert.deepEqual(bundle.restaurant, {
    externalKey: graph.restaurant.externalKey,
    slug: graph.restaurant.slug,
    displayName: graph.restaurant.displayName,
  });

  const sourceFeatureKeyToApi = {
    orders: "orders",
    contacts: "contacts",
    geo_location: "geoLocation",
    social_links: "socialLinks",
    delivery: "delivery",
    pickup: "pickup",
    preorder: "preorder",
    custom_theme: "customTheme",
    whatsapp_fallback: "whatsappFallback",
    promotions: "promotions",
    loyalty: "loyalty",
    reservations: "reservations",
    delivery_blocks: "deliveryBlocks",
  };
  assert.deepEqual(
    Object.fromEntries(
      bundle.site.features.map((feature) => [sourceFeatureKeyToApi[feature.key], feature.enabled]),
    ),
    graph.features,
  );
  assert.deepEqual(bundle.site.features.find((feature) => feature.key === "orders").config.limits, graph.limits);
  const expectedFeatureConfigs = {
    orders: { limits: graph.limits },
    contacts: {},
    geo_location: {},
    social_links: {},
    delivery: { label: "Доставка", default: false },
    pickup: { label: "Самовывоз", default: false },
    preorder: { label: "Предзаказ", default: true },
    custom_theme: {},
    whatsapp_fallback: {},
    promotions: {},
    loyalty: {},
    reservations: {},
    delivery_blocks: {},
  };
  assert.deepEqual(
    Object.fromEntries(bundle.site.features.map((feature) => [feature.key, feature.config])),
    expectedFeatureConfigs,
  );
  assert.deepEqual(
    bundle.site.features
      .filter((feature) => ["preorder", "delivery", "pickup"].includes(feature.key))
      .sort((left, right) => ["preorder", "delivery", "pickup"].indexOf(left.key)
        - ["preorder", "delivery", "pickup"].indexOf(right.key))
      .map((feature) => ({
        code: feature.key,
        label: feature.config.label,
        isDefault: feature.config.default,
      })),
    graph.orderTypes,
  );

  assert.equal(bundle.menu.version, graph.menu.version);
  assert.equal(new Date(bundle.menu.publishedAt).toISOString(), graph.menu.publishedAt);
  assert.equal(bundle.menu.currency, graph.menu.currency);
  assert.deepEqual(
    bundle.menu.categories.map((category, sortOrder) => ({
      categoryId: category.publicId,
      name: category.name,
      sortOrder,
      imageAssetRef: null,
    })),
    graph.menu.categories,
  );
  assert.deepEqual(
    bundle.menu.categories.flatMap((category) =>
      category.items.map((item, sortOrder) => ({
        menuItemId: item.publicId,
        categoryId: category.publicId,
        name: item.name,
        description: item.description,
        priceMinor: item.priceMinor,
        available: true,
        sortOrder,
        imageAssetRef: null,
        modifierGroups: [],
      }))),
    graph.menu.items,
  );

  const serializedBundle = JSON.stringify(bundle);
  assert.doesNotMatch(serializedBundle, /adamas|Адамас|restaurants\/adamas/iu);
  assert.ok(!serializedBundle.includes(expected.sourceSet.unselectedDataset.forbiddenAssetSha256));
  assert.ok(!serializedBundle.includes(legacyConfig.managerPhone));
});

test("UUIDv5 namespace and names produce stable, unique internal identifiers", () => {
  const policy = expected.deterministicIds;
  const names = [
    policy.names.logoAsset,
    policy.names.restaurant,
    policy.names.menuVersion,
    ...expected.normalizedGraph.menu.categories.map(
      (category) => `${policy.names.categoryPrefix}${category.categoryId}`,
    ),
    ...expected.normalizedGraph.menu.items.map(
      (item) => `${policy.names.itemPrefix}${item.menuItemId}`,
    ),
  ];
  const firstPass = names.map((name) => uuidV5(policy.namespaceUuid, name));
  const replay = names.map((name) => uuidV5(policy.namespaceUuid, name));

  assert.deepEqual(replay, firstPass);
  assert.equal(new Set(firstPass).size, 30);
  assert.ok(firstPass.every((id) => id[14] === "5"));
  assert.deepEqual(
    Object.fromEntries(Object.keys(policy.anchorIds).map((name) => [name, uuidV5(policy.namespaceUuid, name)])),
    policy.anchorIds,
  );
});

test("branding, contact, schedule, features, order types and limits match the approved baseline", async () => {
  const [config, sourceFeatures, sourceOrderTypes] = await Promise.all([
    legacyObject(sourcePaths.config, "RESTAURANT_STAMBUL"),
    legacyObject(sourcePaths.features, "RESTAURANT_STAMBUL_FEATURES"),
    legacyObject(sourcePaths.orderTypes, "RESTAURANT_STAMBUL_ORDER_TYPES"),
  ]);
  const graph = expected.normalizedGraph;
  const localized = graph.siteBranding.localizedTexts[0];

  assert.deepEqual(localized, {
    locale: "ru-RU",
    displayName: config.brand.displayName,
    shortName: config.brand.shortName,
    businessTypeLabel: config.brand.businessTypeLabel,
    cuisineText: config.brand.cuisineText,
    slogan: config.brand.slogan,
    introText: config.brand.introText,
    menuSectionTitle: config.brand.menuSection.title,
    cartTitle: config.brand.cartTexts.title,
    orderTypeLegend: config.brand.orderTexts.typeLegend,
  });
  assert.equal(graph.siteBranding.displayName, config.brand.displayName);
  assert.equal(graph.siteBranding.businessType, config.brand.businessTypeLabel);
  assert.equal(graph.siteBranding.logoAlt, config.brand.logoAlt);
  assert.equal(graph.siteBranding.seo.title, config.brand.pageTitle);
  assert.equal(graph.siteBranding.seo.description, config.brand.metaDescription);
  assert.equal(graph.siteBranding.seo.canonicalUrl, "https://stambul.localhost");
  assert.deepEqual(graph.siteBranding.hero, {
    imageAssetRef: null,
    imageAlt: null,
    title: config.brand.slogan,
    subtitle: config.brand.cuisineText,
    ctaLabel: "Смотреть меню",
    benefits: [],
  });

  assert.equal(graph.siteProfile.publicPhone, config.profile.phone);
  assert.equal(graph.siteProfile.publicPhoneLabel, config.profile.publicPhoneLabel);
  assert.equal(graph.siteProfile.workingHoursText, config.profile.workingHoursText);
  assert.equal(graph.siteProfile.schedule.length, 7);
  assert.deepEqual(
    graph.siteProfile.schedule.map((day) => day.dayOfWeek),
    ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"],
  );
  for (const day of graph.siteProfile.schedule) {
    assert.deepEqual(day.intervals, [{ opensAt: "10:00", closesAt: "23:00" }]);
  }

  assert.deepEqual(graph.features, {
    ...sourceFeatures,
    geoLocation: false,
    deliveryBlocks: false,
  });
  assert.deepEqual(
    graph.orderTypes,
    sourceOrderTypes.map(({ code, label, default: isDefault = false }) => ({
      code,
      label,
      isDefault,
    })),
  );
  assert.equal(graph.orderTypes.filter((type) => type.isDefault).length, 1);
  assert.equal(graph.orderTypes[0].code, "preorder");
  assert.deepEqual(graph.limits, {
    maxItemQuantity: config.limits.maxItemQuantity,
    maxTotalItems: config.limits.maxTotalItems,
    maxNameLength: config.limits.maxNameLength,
    maxPhoneLength: config.limits.maxPhoneLength,
    maxAddressLength: config.limits.maxAddressLength,
    maxCommentLength: config.limits.maxCommentLength,
    delayedOrderConfirmationMinutes: config.limits.delayedOrderConfirmationMinutes,
  });
  assert.deepEqual(graph.deliveryConfiguration, {
    enabled: true,
    addressRequired: true,
    feePolicy: "none",
    minimumOrderMinor: null,
    freeDeliveryThresholdMinor: null,
  });
});

test("the two approved design-token deltas are explicit and contract-safe", () => {
  const tokens = expected.normalizedGraph.siteBranding.designTokens;
  const normalizations = Object.fromEntries(
    expected.approvedTechnicalNormalizations.map((entry) => [entry.field, entry]),
  );

  assert.equal(tokens.mutedTextColor, "#7C6A64");
  assert.equal(
    normalizations["siteBranding.designTokens.mutedTextColor"].sourceValue,
    "#7E6C66",
  );
  assert.ok(contrastRatio("#7E6C66", tokens.backgroundColor) < 4.5);
  assert.ok(contrastRatio(tokens.mutedTextColor, tokens.backgroundColor) >= 4.5);

  assert.equal(tokens.fontFamily, "Georgia, Times New Roman, serif");
  assert.equal(
    normalizations["siteBranding.designTokens.fontFamily"].sourceValue,
    "Georgia, \"Times New Roman\", serif",
  );
  assert.match(
    tokens.fontFamily,
    /^[A-Za-z_-][A-Za-z0-9 _-]*(?:,\s*[A-Za-z_-][A-Za-z0-9 _-]*)*$/u,
  );
});

test("all 23 items, four categories, source order, exact text and minor-unit prices are frozen", async () => {
  const sourceMenu = await legacyObject(sourcePaths.menu, "RESTAURANT_STAMBUL_MENU");
  const expectedMenu = expected.normalizedGraph.menu;
  const categoryCounters = new Map();
  const normalizedItems = sourceMenu.items.map((item) => {
    const sortOrder = categoryCounters.get(item.category) ?? 0;
    categoryCounters.set(item.category, sortOrder + 1);
    return normalizedItem(item, sortOrder);
  });

  assert.equal(sourceMenu.items.length, 23);
  assert.equal(expectedMenu.items.length, 23);
  assert.deepEqual(
    expectedMenu.categories,
    sourceMenu.categories.map((name, sortOrder) => ({
      categoryId: categoryIds.get(name),
      name,
      sortOrder,
      imageAssetRef: null,
    })),
  );
  assert.deepEqual(expectedMenu.items, normalizedItems);
  assert.equal(expectedMenu.version, sourceMenu.version);
  assert.equal(expectedMenu.publishedAt, sourceMenu.publishedAt);
  assert.equal(expectedMenu.currency, "RUB");
  assert.equal(
    expectedMenu.items.find((item) => item.menuItemId === "pizza-four-cheese").name,
    "Пицца    4 сыра",
  );
  assert.ok(expectedMenu.items.every((item) => item.imageAssetRef === null));
  assert.ok(expectedMenu.items.every((item) => item.modifierGroups.length === 0));
});

test("only the Stambul logo is selected and all unsupported assets/data are explicitly absent", async () => {
  const graph = expected.normalizedGraph;
  assert.equal(graph.assets.length, 1);
  const asset = graph.assets[0];
  const bytes = await readFile(path.join(repositoryRoot, asset.sourcePath));

  assert.equal(asset.assetType, "logo");
  assert.equal(asset.sha256, sha256(bytes));
  assert.equal(asset.sizeBytes, bytes.length);
  assert.equal(bytes.subarray(1, 4).toString("ascii"), "PNG");
  assert.equal(bytes.readUInt32BE(16), 197);
  assert.equal(bytes.readUInt32BE(20), 179);
  assert.equal(
    asset.canonicalObjectKey,
    `assets/logo/${asset.sha256}.png`,
  );

  assert.ok(Object.values(graph.explicitlyAbsent).every(Boolean));
  assert.equal(graph.siteBranding.faviconAssetRef, null);
  assert.equal(graph.siteBranding.hero.imageAssetRef, null);
  assert.equal(graph.siteBranding.seo.openGraphAssetRef, null);
  assert.equal(graph.siteProfile.location, null);
  assert.deepEqual(graph.siteProfile.socialLinks, []);
  assert.equal(graph.notification.workerEnabled, false);
  assert.equal(graph.notification.destination, null);
});

test("normalized content contains no Adamas bytes, identifiers or private legacy destination", async () => {
  const config = await legacyObject(sourcePaths.config, "RESTAURANT_STAMBUL");
  const { explicitlyAbsent: _absenceProof, ...publishableGraph } = expected.normalizedGraph;
  const serialized = JSON.stringify(publishableGraph);

  assert.doesNotMatch(serialized, /adamas|Адамас|restaurants\/adamas/iu);
  assert.ok(!serialized.includes(expected.sourceSet.unselectedDataset.forbiddenAssetSha256));
  assert.ok(!serialized.includes(config.managerPhone));
  assert.equal(expected.normalizedGraph.assets[0].sourcePath, sourcePaths.logo);
});

test("publication policy is fail-closed, transactionally atomic and idempotent", () => {
  const policy = expected.publicationSafety;
  assert.equal(policy.dryRunExternalWrites, 0);
  assert.equal(policy.applyPreflightReadTransactionCount, 1);
  assert.equal(policy.applyWriteTransactionCount, 1);
  assert.match(policy.identicalBundleReplay, /^UNCHANGED\b/u);
  assert.match(policy.differentBundleAgainstExistingGraph, /^CONFLICT\b/u);
  assert.match(policy.concurrentApply, /serialized/u);
  assert.match(policy.databaseFailureAfterAssetUpload, /no public database graph/u);
  assert.deepEqual(expected.normalizedGraph.runtimeTablesExpectedEmptyAfterImport, [
    "orders",
    "order_items",
    "order_status_history",
    "outbox_events",
    "notification_deliveries",
    "notification_destinations",
  ]);
});
