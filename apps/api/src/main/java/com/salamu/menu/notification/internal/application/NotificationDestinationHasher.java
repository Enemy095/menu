package com.salamu.menu.notification.internal.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.salamu.menu.notification.api.NotificationDestinationSemanticHash;
import com.salamu.menu.notification.api.NotificationDestinationSpec;
import com.salamu.menu.notification.internal.application.NotificationProvisioningRepository.StoredDestination;

final class NotificationDestinationHasher {

    private NotificationDestinationHasher() {
    }

    static NotificationDestinationSemanticHash hash(NotificationDestinationSpec spec) {
        return hash(
                spec.tenantId().value().toString(),
                spec.channel().databaseValue(),
                spec.displayName(),
                spec.status().databaseValue(),
                spec.secretReference().fingerprint().value(),
                "ready");
    }

    static NotificationDestinationSemanticHash hash(StoredDestination destination) {
        return hash(
                destination.tenantId().value().toString(),
                destination.channel().databaseValue(),
                destination.displayName(),
                destination.status().databaseValue(),
                destination.secretReference().fingerprint().value(),
                "ready");
    }

    private static NotificationDestinationSemanticHash hash(String... fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "notification-destination:v1");
            for (var field : fields) {
                add(digest, field);
            }
            return new NotificationDestinationSemanticHash(
                    NotificationDestinationSemanticHash.CURRENT_SCHEMA_VERSION,
                    java.util.HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void add(MessageDigest digest, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
