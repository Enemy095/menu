package com.salamu.menu.notification.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * Validated secret-manager locator. It is not secret material, but its textual value is still
 * deliberately redacted from {@link #toString()} and all provisioning result types.
 */
public final class OpaqueSecretReference {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("secret", "vault");
    private static final String REDACTED = "OpaqueSecretReference[redacted]";

    private final String value;
    private final String scheme;

    public OpaqueSecretReference(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > 512
                || value.isBlank()
                || !value.equals(value.strip())
                || value.startsWith("required-secret:")) {
            throw invalid();
        }
        try {
            var uri = new URI(value);
            var canonicalScheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (canonicalScheme == null
                    || !ALLOWED_SCHEMES.contains(canonicalScheme)
                    || uri.isOpaque()
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getPath() == null
                    || uri.getPath().length() < 2
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw invalid();
            }
            this.value = value;
            this.scheme = canonicalScheme;
        } catch (URISyntaxException exception) {
            throw invalid();
        }
    }

    /** For notification-owned persistence and provider adapters only. Never log this value. */
    public String value() {
        return value;
    }

    public String scheme() {
        return scheme;
    }

    public SecretReferenceFingerprint fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return new SecretReferenceFingerprint(
                    HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OpaqueSecretReference reference && value.equals(reference.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return REDACTED;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Opaque secret reference has an invalid or unsupported form");
    }
}
