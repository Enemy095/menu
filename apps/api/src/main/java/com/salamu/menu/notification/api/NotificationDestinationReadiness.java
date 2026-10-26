package com.salamu.menu.notification.api;

import java.util.Objects;
import java.util.regex.Pattern;

public record NotificationDestinationReadiness(
        NotificationDestinationId id,
        NotificationChannel channel,
        String displayName,
        NotificationDestinationStatus status,
        String referenceFingerprint,
        boolean ready) {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public NotificationDestinationReadiness {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(referenceFingerprint, "referenceFingerprint");
        if (displayName.isBlank() || displayName.length() > 160) {
            throw new IllegalArgumentException("displayName is invalid");
        }
        if (!SHA_256.matcher(referenceFingerprint).matches()) {
            throw new IllegalArgumentException("referenceFingerprint is invalid");
        }
    }
}
