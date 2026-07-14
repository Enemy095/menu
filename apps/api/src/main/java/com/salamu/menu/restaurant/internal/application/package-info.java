/**
 * Public restaurant configuration use cases.
 *
 * <p>MVP persisted feature convention shared with migration and ordering:
 *
 * <ul>
 *   <li>13 public flags use snake_case rows in {@code tenant_features}; an absent row reads as disabled.
 *   <li>{@code preorder}, {@code delivery} and {@code pickup} configs contain {@code label} and
 *       {@code default}; enabled rows become the public ordered list and exactly one is default.
 *   <li>{@code orders.config.limits} contains the OpenAPI restaurant limit fields.
 *   <li>unknown feature keys are ignored for forward compatibility.
 * </ul>
 *
 * <p>Branding JSON stores internal {@code hero.imageAssetId} and
 * {@code seo_metadata.openGraphAssetId}; they are removed at the persistence boundary and resolved
 * through the tenant-scoped asset batch API.
 */
package com.salamu.menu.restaurant.internal.application;
