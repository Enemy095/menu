package com.salamu.menu.menu.internal.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;

/**
 * Run-bound menu IDs remain stable across deterministic reruns. The migration orchestrator must verify the locked
 * ledger's exact run provenance before invoking a writer; these IDs do not prove tenant-asset provenance.
 */
public final class DeterministicMenuIds {

    private DeterministicMenuIds() {
    }

    static ExpectedMenuIds forSnapshot(
            PublishedMenuSnapshot snapshot,
            ProvisioningRunFingerprint runFingerprint) {
        var menuVersionId = uuid("menu-version", runFingerprint.value(), snapshot.tenantId().value().toString(),
                snapshot.restaurantId().toString(), Long.toString(snapshot.version()));
        Map<String, UUID> categories = new LinkedHashMap<>();
        snapshot.categories().forEach(category -> categories.put(
                category.categoryId(), uuid("menu-category", menuVersionId.toString(), category.categoryId())));
        Map<String, UUID> items = new LinkedHashMap<>();
        snapshot.items().forEach(item -> items.put(
                item.menuItemId(), uuid("menu-item", menuVersionId.toString(), item.menuItemId())));
        return new ExpectedMenuIds(menuVersionId, categories, items);
    }

    private static UUID uuid(String... fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var field : fields) {
                var bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            var bytes = digest.digest();
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x80); // RFC 9562 UUID version 8, application-defined bytes.
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            var buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public record ExpectedMenuIds(
            UUID menuVersionId,
            Map<String, UUID> categoryIds,
            Map<String, UUID> itemIds) {

        public ExpectedMenuIds {
            categoryIds = Map.copyOf(categoryIds);
            itemIds = Map.copyOf(itemIds);
        }
    }
}
