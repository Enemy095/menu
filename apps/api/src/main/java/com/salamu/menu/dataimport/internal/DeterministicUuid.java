package com.salamu.menu.dataimport.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

final class DeterministicUuid {

    private DeterministicUuid() {
    }

    static UUID versionFive(UUID namespace, String name) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        try {
            var namespaceBytes = ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits())
                    .array();
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(namespaceBytes);
            byte[] digest = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(digest, 0, 16);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is required for UUIDv5", exception);
        }
    }
}
