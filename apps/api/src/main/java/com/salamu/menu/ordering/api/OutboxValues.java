package com.salamu.menu.ordering.api;

import java.util.regex.Pattern;

final class OutboxValues {

    private static final Pattern WORKER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$");
    private static final Pattern ERROR = Pattern.compile("^[A-Z][A-Z0-9_]{0,119}$");

    private OutboxValues() {
    }

    static String workerId(String value) {
        if (value == null || !WORKER.matcher(value).matches()) {
            throw new IllegalArgumentException("outbox worker ID is invalid");
        }
        return value;
    }

    static String errorCode(String value) {
        if (value == null || !ERROR.matcher(value).matches()) {
            throw new IllegalArgumentException("outbox error code is invalid");
        }
        return value;
    }
}
