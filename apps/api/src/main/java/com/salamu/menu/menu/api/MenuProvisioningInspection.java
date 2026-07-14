package com.salamu.menu.menu.api;

import java.util.Objects;
import java.util.UUID;

/** Sanitized non-HTTP inspection result without persistence entities or storage object keys. */
public record MenuProvisioningInspection(
        UUID menuVersionId,
        PublishedMenuSnapshot snapshot,
        MenuSemanticHash semanticHash,
        int categoryCount,
        int itemCount) {

    public MenuProvisioningInspection {
        Objects.requireNonNull(menuVersionId, "menuVersionId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(semanticHash, "semanticHash");
        if (categoryCount != snapshot.categories().size() || itemCount != snapshot.items().size()) {
            throw new IllegalArgumentException("Inspection counts do not match snapshot contents");
        }
    }
}
