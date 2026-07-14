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
  ["GET /api/v1/public/{tenantSlug}/restaurant", "getPublicRestaurant"],
  ["GET /api/v1/public/{tenantSlug}/menu", "getPublicMenu"],
  ["POST /api/v1/public/{tenantSlug}/orders", "createPublicOrder"],
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

test("contract exposes exactly the fixed public operations with operation IDs", () => {
  const actual = new Map();
  for (const [route, pathItem] of Object.entries(spec.paths)) {
    for (const method of ["get", "post", "put", "patch", "delete"]) {
      if (pathItem[method]) actual.set(`${method.toUpperCase()} ${route}`, pathItem[method].operationId);
    }
  }
  assert.deepEqual(actual, expectedOperations);
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
      const response = resolve(responseRef);
      const mediaType = response.content?.["application/json"];
      assert.ok(mediaType?.example || mediaType?.examples, `${operationKey} response needs examples`);
    }
  }
});

test("create order supplies examples for every order type", () => {
  const operation = spec.paths["/api/v1/public/{tenantSlug}/orders"].post;
  const examples = operation.requestBody.content["application/json"].examples;
  assert.deepEqual(new Set(Object.keys(examples)), new Set(["preorder", "delivery", "pickup"]));
  assert.deepEqual(
    new Set(Object.values(examples).map((example) => resolve(example).value.orderType)),
    new Set(["preorder", "delivery", "pickup"]),
  );
});

test("all required error codes have examples", () => {
  const required = new Set([
    "TENANT_NOT_FOUND",
    "VALIDATION_ERROR",
    "MENU_CHANGED",
    "ITEM_UNAVAILABLE",
    "TOTAL_MISMATCH",
    "IDEMPOTENCY_CONFLICT",
    "RATE_LIMITED",
    "INTERNAL_ERROR",
  ]);
  const actual = new Set(
    Object.values(spec.components.examples)
      .map(resolve)
      .map((example) => example.value?.code)
      .filter(Boolean),
  );
  assert.deepEqual(actual, required);
});

test("public schemas do not expose internal or secret fields", () => {
  const forbidden = new Set([
    "tenantId",
    "managerPhone",
    "notificationDestination",
    "notificationDestinations",
    "destinationConfig",
    "secret",
    "secrets",
    "storageKey",
    "objectStorageKey",
  ]);
  const properties = new Set();
  for (const schema of Object.values(spec.components.schemas)) collectPropertyNames(schema, properties);
  assert.deepEqual([...properties].filter((name) => forbidden.has(name)), []);
});

test("order body excludes routing and idempotency fields", () => {
  const request = spec.components.schemas.CreateOrderRequest;
  const properties = new Set(Object.keys(request.properties));
  assert.equal(properties.has("tenantId"), false);
  assert.equal(properties.has("tenantSlug"), false);
  assert.equal(properties.has("businessSlug"), false);
  assert.equal(properties.has("idempotencyKey"), false);

  const operation = spec.paths["/api/v1/public/{tenantSlug}/orders"].post;
  assert.ok(operation.parameters.some((parameter) => resolve(parameter).name === "Idempotency-Key"));
});

test("menu keys preserve legacy-compatible stable string identifiers", () => {
  for (const [schemaName, propertyName] of [
    ["MenuCategory", "categoryId"],
    ["MenuItem", "menuItemId"],
    ["CreateOrderItem", "menuItemId"],
  ]) {
    const property = spec.components.schemas[schemaName].properties[propertyName];
    assert.equal(property.type, "string");
    assert.equal(property.format, undefined);
    assert.ok(property.pattern);
  }
  assert.equal(spec.components.examples.AdamasMenu.value.items[0].menuItemId, "pizza-pepperoni");
  assert.equal(spec.components.examples.StambulMenu.value.items[0].menuItemId, "cezar-roll-chicken");
});

test("all order business conflicts use HTTP 409", () => {
  const responses = spec.paths["/api/v1/public/{tenantSlug}/orders"].post.responses;
  assert.equal(responses["422"], undefined);
  const conflict = resolve(responses["409"]);
  assert.deepEqual(
    new Set(Object.keys(conflict.content["application/json"].examples)),
    new Set(["menuChanged", "itemUnavailable", "totalMismatch", "idempotencyConflict"]),
  );
});

test("tenant slug is capped at the database limit", () => {
  assert.equal(spec.components.parameters.TenantSlug.schema.maxLength, 63);
  assert.equal(spec.components.schemas.RestaurantResponse.properties.slug.maxLength, 63);
});

test("Stambul and Adamas restaurant and menu examples exist", () => {
  for (const name of ["StambulRestaurant", "AdamasRestaurant", "StambulMenu", "AdamasMenu"]) {
    assert.ok(spec.components.examples[name], `missing ${name}`);
  }
});
