package com.salamu.menu.migration.internal.application;

import java.util.Objects;

/**
 * Indicates that PostgreSQL rejected an import-ledger insert with SQLSTATE 23505.
 *
 * <p>The transaction that raised this exception is aborted and must be rolled back. A caller may
 * only inspect the winner from a new transaction after reacquiring the transaction advisory lock.
 */
public final class LegacyImportUniqueConflictException extends RuntimeException {

    public static final String CODE = "LEGACY_IMPORT_UNIQUE_CONFLICT";

    private final LegacyImportConflictScope scope;

    public LegacyImportUniqueConflictException(LegacyImportConflictScope scope) {
        super(CODE);
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    public LegacyImportConflictScope scope() {
        return scope;
    }
}
