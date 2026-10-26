package com.salamu.menu.notification.internal.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Notification-internal secret-manager locator whose textual form is always redacted. */
public final class OpaqueSecretReference {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("secret", "vault");
    private static final String REDACTED = "OpaqueSecretReference[redacted]";

    private final String value;
    private final boolean valid;

    public OpaqueSecretReference(String value) {
        this.value = value == null ? "" : value;
        this.valid = validate(this.value);
    }

    public boolean valid() {
        return valid;
    }

    public String fingerprint() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Persistence/provider boundary only. This value must never be logged or returned. */
    public String persistenceValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OpaqueSecretReference reference && value.equals(reference.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return REDACTED;
    }

    private static boolean validate(String value) {
        if (value.isBlank()
                || value.length() > 512
                || !value.equals(value.strip())
                || value.startsWith("required-secret:")) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            return scheme != null
                    && ALLOWED_SCHEMES.contains(scheme)
                    && !uri.isOpaque()
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getPath() != null
                    && uri.getPath().length() >= 2
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
