package com.salamu.menu.tenancy.internal.infrastructure.persistence;

import java.util.UUID;

public record TenantLookupRow(UUID tenantId, String slug) {
}
