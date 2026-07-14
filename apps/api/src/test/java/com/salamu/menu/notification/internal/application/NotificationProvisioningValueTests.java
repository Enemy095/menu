package com.salamu.menu.notification.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationSpec;
import com.salamu.menu.notification.api.NotificationDestinationStatus;
import com.salamu.menu.notification.api.OpaqueSecretReference;
import com.salamu.menu.notification.api.SecretReferenceReadiness;
import com.salamu.menu.tenancy.api.TenantId;

import org.junit.jupiter.api.Test;

class NotificationProvisioningValueTests {

    private static final String REFERENCE = "secret://staging/tenants/tenant-a/whatsapp";

    @Test
    void opaqueReferenceAcceptsAllowlistedManagersAndNeverRendersItsValue() {
        var reference = new OpaqueSecretReference(REFERENCE);

        assertThat(reference.scheme()).isEqualTo("secret");
        assertThat(reference.toString()).isEqualTo("OpaqueSecretReference[redacted]");
        assertThat(reference.toString()).doesNotContain("tenant-a", "whatsapp");
        assertThat(reference.fingerprint().value()).hasSize(64).doesNotContain(REFERENCE);
        assertThat(new OpaqueSecretReference("vault://tenant/manager").scheme()).isEqualTo("vault");
    }

    @Test
    void markerRawPhoneAndUnsupportedSchemesAreRejectedWithoutEchoingInput() {
        assertRejected("required-secret:LEGACY_IMPORT_DESTINATION");
        assertRejected("+79991234567");
        assertRejected("https://secrets.example/tenant-a");
        assertRejected("env://TENANT_A_DESTINATION");
        assertRejected("secret://tenant");
    }

    @Test
    void destinationRequiresMetadataReadyReferenceAndCanonicalNaturalKey() {
        var tenantId = new TenantId(UUID.randomUUID());
        var reference = new OpaqueSecretReference(REFERENCE);

        assertThatThrownBy(() -> new NotificationDestinationSpec(
                tenantId,
                "tenant-a",
                NotificationChannel.WHATSAPP,
                "manager",
                NotificationDestinationStatus.ACTIVE,
                reference,
                SecretReferenceReadiness.MISSING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(REFERENCE);
        assertThatThrownBy(() -> new NotificationDestinationSpec(
                tenantId,
                "tenant-a",
                NotificationChannel.WHATSAPP,
                " manager",
                NotificationDestinationStatus.ACTIVE,
                reference,
                SecretReferenceReadiness.READY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void semanticHashChangesWithReferenceButNeverContainsIt() {
        var tenantId = new TenantId(UUID.randomUUID());
        var first = spec(tenantId, REFERENCE);
        var second = spec(tenantId, "secret://staging/tenants/tenant-a/whatsapp-v2");

        var firstHash = NotificationDestinationHasher.hash(first);
        assertThat(firstHash).isNotEqualTo(NotificationDestinationHasher.hash(second));
        assertThat(firstHash.toString()).doesNotContain(REFERENCE);
        assertThat(first.toString()).doesNotContain(REFERENCE);
    }

    private static NotificationDestinationSpec spec(TenantId tenantId, String reference) {
        return new NotificationDestinationSpec(
                tenantId,
                "tenant-a",
                NotificationChannel.WHATSAPP,
                "manager",
                NotificationDestinationStatus.ACTIVE,
                new OpaqueSecretReference(reference),
                SecretReferenceReadiness.READY);
    }

    private static void assertRejected(String value) {
        assertThatThrownBy(() -> new OpaqueSecretReference(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(value);
    }
}
