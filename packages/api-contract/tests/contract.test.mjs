import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import path from "node:path";
import YAML from "yaml";

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = await readFile(path.join(packageRoot, "openapi/openapi.yaml"), "utf8");
const spec = YAML.parse(source);

const expectedOperations = new Map([
  ["GET /api/v1/public/availability", "getPublicAvailability"],
  ["GET /api/v1/public/config", "getPublicConfiguration"],
  ["GET /api/v1/public/menu", "getPublicMenu"],
  ["POST /api/v1/public/orders", "createPublicOrder"],
]);

function dereference(ref) {
  assert.match(ref, /^#\/components\//);
  return ref
    .slice(2)
    .split("/")
    .reduce((value, segment) => value[segment], spec);
}

function resolve(value) {
  return value?.$ref ? dereference(value.$ref) : value;
}

function collectPropertyNames(schema, result = new Set(), visited = new Set()) {
  const resolved = resolve(schema);
  if (!resolved || typeof resolved !== "object" || visited.has(resolved)) return result;
  visited.add(resolved);

  for (const [name, property] of Object.entries(resolved.properties ?? {})) {
    result.add(name);
    collectPropertyNames(property, result, visited);
  }
  for (const child of resolved.allOf ?? []) collectPropertyNames(child, result, visited);
  for (const child of resolved.oneOf ?? []) collectPropertyNames(child, result, visited);
  for (const child of resolved.anyOf ?? []) collectPropertyNames(child, result, visited);
  if (resolved.items) collectPropertyNames(resolved.items, result, visited);
  if (typeof resolved.additionalProperties === "object") {
    collectPropertyNames(resolved.additionalProperties, result, visited);
  }
  return result;
}

function accepts(schema, value) {
  const resolved = resolve(schema);
  if (!resolved || typeof resolved !== "object") return true;

  if (resolved.oneOf) {
    return resolved.oneOf.filter((candidate) => accepts(candidate, value)).length === 1;
  }
  if (resolved.allOf && !resolved.allOf.every((candidate) => accepts(candidate, value))) return false;
  if (resolved.enum && !resolved.enum.some((candidate) => Object.is(candidate, value))) return false;

  if (resolved.type === "object") {
    if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
    if ((resolved.required ?? []).some((name) => !Object.hasOwn(value, name))) return false;
    if (resolved.additionalProperties === false) {
      const allowed = new Set(Object.keys(resolved.properties ?? {}));
      if (Object.keys(value).some((name) => !allowed.has(name))) return false;
    }
    return Object.entries(value).every(([name, child]) => {
      const property = resolved.properties?.[name];
      return property ? accepts(property, child) : true;
    });
  }

  if (resolved.type === "array") {
    if (!Array.isArray(value)) return false;
    if (resolved.minItems !== undefined && value.length < resolved.minItems) return false;
    if (resolved.maxItems !== undefined && value.length > resolved.maxItems) return false;
    return value.every((item) => accepts(resolved.items, item));
  }

  if (resolved.type === "string") {
    if (typeof value !== "string") return false;
    if (resolved.minLength !== undefined && value.length < resolved.minLength) return false;
    if (resolved.maxLength !== undefined && value.length > resolved.maxLength) return false;
    if (resolved.pattern && !new RegExp(resolved.pattern).test(value)) return false;
    return true;
  }

  if (resolved.type === "integer") {
    if (!Number.isInteger(value)) return false;
  } else if (resolved.type === "number") {
    if (typeof value !== "number" || !Number.isFinite(value)) return false;
  } else if (resolved.type === "boolean") {
    return typeof value === "boolean";
  } else {
    return true;
  }

  if (resolved.minimum !== undefined && value < resolved.minimum) return false;
  if (resolved.maximum !== undefined && value > resolved.maximum) return false;
  return true;
}

function exampleValue(name) {
  return resolve(spec.components.examples[name]).value;
}

test("contract exposes canonical reads, fresh availability and order creation", () => {
  const actual = new Map();
  for (const [route, pathItem] of Object.entries(spec.paths)) {
    assert.equal(route.includes("{"), false, `${route} must not contain a selector`);
    for (const method of ["get", "post", "put", "patch", "delete"]) {
      if (pathItem[method]) actual.set(`${method.toUpperCase()} ${route}`, pathItem[method].operationId);
    }
  }
  assert.deepEqual(actual, expectedOperations);
});

test("canonical source has no legacy scope vocabulary", () => {
  const legacyScopeWord = ["ten", "ant"].join("");
  assert.equal(source.toLowerCase().includes(legacyScopeWord), false);
  assert.equal(source.includes("X-" + legacyScopeWord), false);
});

test("every operation has success and error examples", () => {
  for (const [operationKey] of expectedOperations) {
    const [method, route] = operationKey.split(" ");
    const operation = spec.paths[route][method.toLowerCase()];
    const responses = Object.entries(operation.responses);
    const success = responses.filter(([status]) => status.startsWith("2"));
    const errors = responses.filter(([status]) => status.startsWith("4") || status.startsWith("5"));
    assert.ok(success.length > 0, `${operationKey} needs a success response`);
    assert.ok(errors.length > 0, `${operationKey} needs an error response`);
    for (const [, responseRef] of [...success, ...errors]) {
      const mediaType = resolve(responseRef).content?.["application/json"];
      assert.ok(mediaType?.example || mediaType?.examples, `${operationKey} response needs examples`);
    }
  }
});

test("order request uses explicit discriminated structural variants", () => {
  const request = spec.components.schemas.CreateOrderRequest;
  assert.deepEqual(
    request.oneOf.map((candidate) => candidate.$ref),
    [
      "#/components/schemas/DeliveryOrderRequest",
      "#/components/schemas/PreorderOrderRequest",
      "#/components/schemas/PickupOrderRequest",
    ],
  );
  assert.equal(request.discriminator.propertyName, "orderType");
  assert.deepEqual(request.discriminator.mapping, {
    delivery: "#/components/schemas/DeliveryOrderRequest",
    preorder: "#/components/schemas/PreorderOrderRequest",
    pickup: "#/components/schemas/PickupOrderRequest",
  });

  for (const [name, literal, fulfillment] of [
    ["DeliveryOrderRequest", "delivery", "DeliveryFulfillment"],
    ["PreorderOrderRequest", "preorder", "NonDeliveryFulfillment"],
    ["PickupOrderRequest", "pickup", "NonDeliveryFulfillment"],
  ]) {
    const variant = spec.components.schemas[name];
    assert.equal(variant.additionalProperties, false);
    assert.deepEqual(
      new Set(variant.required),
      new Set(["orderType", "customer", "fulfillment", "items", "clientMenuVersion"]),
    );
    assert.deepEqual(variant.properties.orderType.enum, [literal]);
    assert.equal(variant.properties.fulfillment.$ref, `#/components/schemas/${fulfillment}`);
  }
});

test("confirmed order examples satisfy exactly one structural variant", () => {
  const request = spec.components.schemas.CreateOrderRequest;
  const operationExamples = spec.paths["/api/v1/public/orders"].post.requestBody.content["application/json"].examples;
  assert.deepEqual(new Set(Object.keys(operationExamples)), new Set(["preorder", "delivery", "pickup"]));
  for (const name of ["PreorderRequest", "DeliveryRequest", "PickupRequest"]) {
    assert.equal(accepts(request, exampleValue(name)), true, name);
  }
});

test("invalid cross-order fulfillment shapes are structurally rejected", () => {
  const request = spec.components.schemas.CreateOrderRequest;
  const delivery = structuredClone(exampleValue("DeliveryRequest"));
  const preorder = structuredClone(exampleValue("PreorderRequest"));
  const pickup = structuredClone(exampleValue("PickupRequest"));

  delete delivery.fulfillment.address;
  assert.equal(accepts(request, delivery), false, "delivery without address");

  preorder.fulfillment.address = "Москва, Центральная улица, 10";
  assert.equal(accepts(request, preorder), false, "preorder with delivery-only address");

  pickup.fulfillment.requestedFor = "2026-07-14T17:45:00+03:00";
  assert.equal(accepts(request, pickup), false, "unsupported requestedFor");

  const wrongDiscriminator = structuredClone(exampleValue("PreorderRequest"));
  wrongDiscriminator.orderType = "delivery";
  assert.equal(accepts(request, wrongDiscriminator), false, "wrong discriminator literal");

  const lowGuestCount = structuredClone(exampleValue("PreorderRequest"));
  lowGuestCount.fulfillment.guestCount = 0;
  assert.equal(accepts(request, lowGuestCount), false, "guestCount below minimum");

  const highGuestCount = structuredClone(exampleValue("DeliveryRequest"));
  highGuestCount.fulfillment.guestCount = 100;
  assert.equal(accepts(request, highGuestCount), false, "guestCount above maximum");
});

test("order input has modifiers but no client price authority, routing or unconfirmed time", () => {
  const properties = collectPropertyNames(spec.components.schemas.CreateOrderRequest);
  for (const forbidden of [
    "clientTotalMinor",
    "serverTotalMinor",
    "destination",
    "destinationReference",
    "secret",
    "clientId",
    "businessSlug",
    "idempotencyKey",
    "requestedFor",
  ]) {
    assert.equal(properties.has(forbidden), false, forbidden);
  }

  const item = spec.components.schemas.CreateOrderItem;
  assert.ok(item.required.includes("modifiers"));
  assert.equal(resolve(item.properties.modifiers.items), spec.components.schemas.CreateOrderModifier);
  assert.equal(spec.components.schemas.DeliveryFulfillment.properties.address.maxLength, 240);

  const operation = spec.paths["/api/v1/public/orders"].post;
  assert.ok(operation.parameters.some((parameter) => resolve(parameter).name === "Idempotency-Key"));
  assert.equal(resolve(operation.responses["201"].content["application/json"].schema), spec.components.schemas.CreateOrderResponse);
  assert.ok(spec.components.schemas.CreateOrderResponse.required.includes("serverTotalMinor"));
});

test("schedule is structured and matches confirmed daily 10:00-23:00 fixtures", () => {
  const configSchema = spec.components.schemas.PublicConfigurationResponse;
  assert.ok(configSchema.required.includes("schedule"));
  assert.equal(configSchema.properties.schedule.$ref, "#/components/schemas/WeeklySchedule");
  assert.equal(Object.hasOwn(spec.components.schemas.PublicProfile.properties, "workingHoursText"), false);

  const scheduleSchema = spec.components.schemas.WeeklySchedule;
  assert.deepEqual(new Set(scheduleSchema.required), new Set(["timezone", "displayText", "days"]));
  assert.equal(scheduleSchema.properties.days.minItems, 7);
  assert.equal(scheduleSchema.properties.days.maxItems, 7);

  const schedule = exampleValue("PublicConfiguration").schedule;
  assert.equal(schedule.timezone, "Europe/Moscow");
  assert.equal(schedule.displayText, "Ежедневно с 10:00 до 23:00");
  assert.deepEqual(
    schedule.days.map((day) => day.dayOfWeek),
    ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"],
  );
  for (const day of schedule.days) {
    assert.deepEqual(day.intervals, [{ opensAt: "10:00", closesAt: "23:00" }]);
  }
  assert.equal(accepts(scheduleSchema, schedule), true);
});

test("delivery configuration exposes only confirmed operational policy", () => {
  const configSchema = spec.components.schemas.PublicConfigurationResponse;
  assert.ok(configSchema.required.includes("deliveryConfiguration"));
  assert.equal(configSchema.properties.deliveryConfiguration.$ref, "#/components/schemas/DeliveryConfiguration");
  assert.deepEqual(spec.components.schemas.DeliveryConfiguration.properties.feePolicy.enum, ["none"]);
  assert.deepEqual(exampleValue("PublicConfiguration").deliveryConfiguration, {
    enabled: true,
    addressRequired: true,
    feePolicy: "none",
  });

  const limitProperties = spec.components.schemas.OrderLimits.properties;
  assert.equal(Object.hasOwn(limitProperties, "minOrderMinor"), false);
  assert.equal(Object.hasOwn(limitProperties, "freeDeliveryThresholdMinor"), false);
});

test("config and request limits exactly match confirmed legacy bounds", () => {
  const expected = {
    maxItemQuantity: 99,
    maxTotalItems: 200,
    maxNameLength: 80,
    maxPhoneLength: 32,
    maxAddressLength: 240,
    maxCommentLength: 500,
  };
  const limits = spec.components.schemas.OrderLimits.properties;
  const example = exampleValue("PublicConfiguration").limits;
  for (const [name, maximum] of Object.entries(expected)) {
    assert.equal(limits[name].maximum, maximum, `${name} schema maximum`);
    assert.equal(example[name], maximum, `${name} config value`);
  }

  assert.equal(spec.components.schemas.CreateOrderItem.properties.quantity.maximum, expected.maxItemQuantity);
  assert.equal(spec.components.schemas.OrderCustomer.properties.name.maxLength, expected.maxNameLength);
  assert.equal(spec.components.schemas.OrderCustomer.properties.phone.maxLength, expected.maxPhoneLength);
  const phonePattern = new RegExp(spec.components.schemas.OrderCustomer.properties.phone.pattern);
  for (const accepted of ["12345", "+7 (999) 000-00-00", "123456789012345"]) {
    assert.equal(phonePattern.test(accepted), true, `accepted phone ${accepted}`);
  }
  for (const rejected of ["( )--", "1234", "1234567890123456"]) {
    assert.equal(phonePattern.test(rejected), false, `rejected phone ${rejected}`);
  }
  assert.equal(spec.components.schemas.DeliveryFulfillment.properties.address.maxLength, expected.maxAddressLength);
  for (const name of ["DeliveryOrderRequest", "PreorderOrderRequest", "PickupOrderRequest"]) {
    const properties = spec.components.schemas[name].properties;
    assert.equal(properties.items.maxItems, expected.maxTotalItems);
    assert.equal(properties.comment.maxLength, expected.maxCommentLength);
  }
});

test("order examples remain within limits and response totals match menu prices", () => {
  const config = exampleValue("PublicConfiguration");
  const menu = exampleValue("PublicMenu");
  const itemById = new Map(menu.items.map((item) => [item.menuItemId, item]));

  for (const [requestName, responseName] of [
    ["PreorderRequest", "PreorderResponse"],
    ["DeliveryRequest", "DeliveryResponse"],
    ["PickupRequest", "PickupResponse"],
  ]) {
    const request = exampleValue(requestName);
    const response = exampleValue(responseName);
    const totalQuantity = request.items.reduce((sum, item) => sum + item.quantity, 0);
    assert.ok(totalQuantity <= config.limits.maxTotalItems);
    assert.ok(request.items.every((item) => item.quantity <= config.limits.maxItemQuantity));

    const calculated = request.items.reduce((orderTotal, requestedItem) => {
      const menuItem = itemById.get(requestedItem.menuItemId);
      assert.ok(menuItem, requestedItem.menuItemId);
      const optionById = new Map(
        menuItem.modifierGroups.flatMap((group) => group.options.map((option) => [option.optionId, option])),
      );
      const modifiers = requestedItem.modifiers.reduce((sum, selected) => {
        const option = optionById.get(selected.optionId);
        assert.ok(option, selected.optionId);
        return sum + option.priceDeltaMinor * selected.quantity;
      }, 0);
      return orderTotal + (menuItem.priceMinor + modifiers) * requestedItem.quantity;
    }, 0);
    assert.equal(response.serverTotalMinor, calculated, `${responseName} total`);
  }
});

test("menu publishes modifier choices that order requests can reference", () => {
  const item = spec.components.schemas.MenuItem;
  assert.ok(item.required.includes("modifierGroups"));
  assert.equal(resolve(item.properties.modifierGroups.items), spec.components.schemas.MenuModifierGroup);
  const exampleItem = exampleValue("PublicMenu").items[0];
  assert.ok(exampleItem.modifierGroups.length > 0);
  const selected = exampleValue("PreorderRequest").items[0].modifiers[0];
  assert.equal(selected.modifierGroupId, exampleItem.modifierGroups[0].modifierGroupId);
  assert.ok(exampleItem.modifierGroups[0].options.some((option) => option.optionId === selected.optionId));
});

test("all stable public error codes have examples", () => {
  const required = new Set([
    "VALIDATION_ERROR",
    "MENU_NOT_FOUND",
    "MENU_CHANGED",
    "ITEM_UNAVAILABLE",
    "MODIFIER_UNAVAILABLE",
    "IDEMPOTENCY_CONFLICT",
    "RATE_LIMITED",
    "SITE_NOT_READY",
    "DEPENDENCY_UNAVAILABLE",
    "INTERNAL_ERROR",
  ]);
  assert.deepEqual(new Set(spec.components.schemas.ErrorCode.enum), required);
  const actual = new Set(
    Object.values(spec.components.examples)
      .map(resolve)
      .map((example) => example.value?.code)
      .filter(Boolean),
  );
  assert.deepEqual(actual, required);
});

test("public schemas exclude internal, routing and secret fields", () => {
  const hiddenScopeId = ["ten", "antId"].join("");
  const forbidden = new Set([
    hiddenScopeId,
    "managerPhone",
    "notificationDestination",
    "notificationDestinations",
    "destinationConfig",
    "destinationReference",
    "secret",
    "secrets",
    "storageKey",
    "objectStorageKey",
  ]);
  const properties = new Set();
  for (const schema of Object.values(spec.components.schemas)) collectPropertyNames(schema, properties);
  assert.deepEqual([...properties].filter((name) => forbidden.has(name)), []);
});

test("all order business conflicts use HTTP 409", () => {
  const responses = spec.paths["/api/v1/public/orders"].post.responses;
  assert.equal(responses["422"], undefined);
  const conflict = resolve(responses["409"]);
  assert.deepEqual(
    new Set(Object.keys(conflict.content["application/json"].examples)),
    new Set(["menuChanged", "itemUnavailable", "modifierUnavailable", "idempotencyConflict"]),
  );
});

test("MVP reads expose cache metadata without unsupported conditional requests", () => {
  const config = spec.paths["/api/v1/public/config"].get;
  const menu = spec.paths["/api/v1/public/menu"].get;
  for (const operation of [config, menu]) {
    assert.equal(operation.parameters, undefined);
    assert.equal(operation.responses["304"], undefined);
    assert.deepEqual(
      new Set(Object.keys(operation.responses["200"].headers)),
      new Set(["ETag", "Cache-Control", "Cache-Tag", "Surrogate-Key"]),
    );
  }

  assert.equal(resolve(config.responses["200"].headers.ETag).example, '"config-20b4851e"');
  assert.equal(resolve(config.responses["200"].headers["Cache-Control"]).example, "public, max-age=60, s-maxage=300, stale-while-revalidate=60");
  assert.equal(resolve(config.responses["200"].headers["Cache-Tag"]).example, "site-config");
  assert.equal(resolve(config.responses["200"].headers["Surrogate-Key"]).example, "site-config");
  assert.equal(resolve(menu.responses["200"].headers.ETag).example, '"menu-v7-90d5f358"');
  assert.equal(resolve(menu.responses["200"].headers["Cache-Control"]).example, "public, max-age=30, s-maxage=300, stale-while-revalidate=60");
  assert.equal(resolve(menu.responses["200"].headers["Cache-Tag"]).example, "menu");
  assert.equal(resolve(menu.responses["200"].headers["Surrogate-Key"]).example, "menu menu-v7");
});

test("every public error response is explicitly no-store", () => {
  for (const [operationKey] of expectedOperations) {
    const [method, route] = operationKey.split(" ");
    const responses = spec.paths[route][method.toLowerCase()].responses;
    for (const [status, responseRef] of Object.entries(responses)) {
      if (!status.startsWith("4") && !status.startsWith("5")) continue;
      const response = resolve(responseRef);
      const cacheControl = resolve(response.headers?.["Cache-Control"]);
      assert.deepEqual(cacheControl?.schema?.enum, ["no-store"], `${operationKey} ${status}`);
      assert.equal(cacheControl?.example, "no-store", `${operationKey} ${status}`);
    }
  }
});

test("all public minor-unit money fields have realistic JavaScript-safe bounds", () => {
  const moneyFields = [];
  for (const [schemaName, schema] of Object.entries(spec.components.schemas)) {
    for (const [propertyName, property] of Object.entries(resolve(schema).properties ?? {})) {
      if (propertyName.endsWith("Minor")) moneyFields.push([`${schemaName}.${propertyName}`, resolve(property)]);
    }
  }
  assert.deepEqual(
    moneyFields.map(([name]) => name).sort(),
    ["CreateOrderResponse.serverTotalMinor", "MenuItem.priceMinor", "MenuModifierOption.priceDeltaMinor"],
  );
  for (const [name, schema] of moneyFields) {
    assert.equal(Number.isSafeInteger(schema.maximum), true, `${name} maximum must be safe`);
    assert.ok(schema.maximum <= 1_000_000_000_000, `${name} maximum must remain realistic`);
    assert.ok(schema.maximum < Number.MAX_SAFE_INTEGER, `${name} maximum must be below MAX_SAFE_INTEGER`);
    assert.equal(Number.isSafeInteger(schema.minimum), true, `${name} minimum must be explicit and safe`);
  }
});

test("public asset URLs are immutable and content-addressed", () => {
  const assetProperties = [
    spec.components.schemas.Branding.properties.logoUrl,
    spec.components.schemas.Branding.properties.faviconUrl,
    spec.components.schemas.Hero.properties.imageUrl,
    spec.components.schemas.SeoMetadata.properties.openGraphImageUrl,
    spec.components.schemas.MenuCategory.properties.imageUrl,
    spec.components.schemas.MenuItem.properties.imageUrl,
  ];
  for (const property of assetProperties) {
    assert.match(property.description, /Immutable content-addressed public asset URL/);
  }
});

test("examples contain one configuration and menu without external destination data", () => {
  assert.ok(spec.components.examples.PublicConfiguration);
  assert.ok(spec.components.examples.PublicMenu);
  assert.equal(Object.keys(spec.components.examples).some((name) => /Stambul|Adamas/.test(name)), false);
  for (const requestName of ["PreorderRequest", "DeliveryRequest", "PickupRequest"]) {
    const rendered = JSON.stringify(exampleValue(requestName));
    assert.equal(/destination|secret|clientTotalMinor/.test(rendered), false);
  }
});
