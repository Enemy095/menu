package com.salamu.menu.menu.internal.application;

/** Generic fail-closed signal for persisted menu data that violates the public contract. */
final class InvalidPublishedMenuException extends IllegalStateException {

    InvalidPublishedMenuException() {
        super("Published menu data is invalid");
    }
}
