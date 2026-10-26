package com.salamu.menu.menu.internal.application;

/** Fail-closed corruption signal with no persistence or resource details. */
public final class InvalidPublishedMenuException extends RuntimeException {

    private static final String SAFE_MESSAGE = "Published menu is internally inconsistent";

    public InvalidPublishedMenuException() {
        super(SAFE_MESSAGE);
    }

    public InvalidPublishedMenuException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }
}
