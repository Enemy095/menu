package com.salamu.menu.menu.api;

import java.util.Objects;
import java.util.UUID;

/** Typed result of a create-or-compare menu write. */
public record MenuProvisioningResult(
        UUID menuVersionId,
        MenuSemanticHash semanticHash,
        MenuProvisioningDisposition disposition) {

    public MenuProvisioningResult {
        Objects.requireNonNull(menuVersionId, "menuVersionId");
        Objects.requireNonNull(semanticHash, "semanticHash");
        Objects.requireNonNull(disposition, "disposition");
    }
}
