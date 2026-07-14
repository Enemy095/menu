package com.salamu.menu.notification.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.salamu.menu.notification.api.NotificationChannel;
import com.salamu.menu.notification.api.NotificationDestinationCleanupCandidate;
import com.salamu.menu.notification.api.NotificationDestinationSemanticHash;
import com.salamu.menu.notification.api.NotificationDestinationSpec;
import com.salamu.menu.notification.api.NotificationDestinationStatus;
import com.salamu.menu.notification.api.NotificationProvisioningException;
import com.salamu.menu.notification.api.NotificationProvisioningService;
import com.salamu.menu.notification.api.OpaqueSecretReference;
import com.salamu.menu.notification.api.SecretReferenceReadiness;
import com.salamu.menu.tenancy.api.ProvisioningRunFingerprint;
import com.salamu.menu.tenancy.api.SuspendedTenantSpec;
import com.salamu.menu.tenancy.api.TenantProvisioningService;
import com.salamu.menu.tenancy.api.TenantProvisioningState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles({"test", "legacy-import"})
@SpringBootTest
class NotificationProvisioningPostgreSqlIntegrationTests {

    private static final ProvisioningRunFingerprint RUN =
            new ProvisioningRunFingerprint("a".repeat(64));
    private static final UUID RUN_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine3.21")
            .withDatabaseName("notification_provisioning")
            .withUsername("notification_provisioning")
            .withPassword("notification_provisioning");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private NotificationProvisioningService notificationProvisioning;

    @Autowired
    private TenantProvisioningService tenantProvisioning;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE tenants CASCADE");
    }

    @Test
    void writerAndVerifierAreMandatoryIdempotentRedactedAndRejectReferenceMismatch() {
        var tenant = skeleton("tenant-a");
        var expected = spec(tenant, "secret://staging/tenants/tenant-a/whatsapp");

        assertThatThrownBy(() -> notificationProvisioning.writeDestination(expected, RUN))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> notificationProvisioning.verifyPreparedDestination(expected, RUN))
                .isInstanceOf(IllegalTransactionStateException.class);

        var first = transactions.execute(status -> {
            var written = notificationProvisioning.writeDestination(expected, RUN);
            assertThat(notificationProvisioning.verifyPreparedDestination(expected, RUN))
                    .isEqualTo(written.semanticHash());
            return written;
        });
        var rerun = transactions.execute(status -> notificationProvisioning.writeDestination(expected, RUN));

        assertThat(rerun).isEqualTo(first);
        assertThat(first.toString()).doesNotContain(expected.secretReference().value());
        assertThat(notificationProvisioning.inspectDestinations(tenant.tenantId())).containsExactly(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_destinations WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId().value())).isEqualTo(1);

        var mismatch = spec(tenant, "secret://staging/tenants/tenant-a/whatsapp-v2");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                notificationProvisioning.writeDestination(mismatch, RUN)))
                .isInstanceOf(NotificationProvisioningException.class)
                .hasMessageNotContaining(expected.secretReference().value())
                .hasMessageNotContaining(mismatch.secretReference().value())
                .extracting(error -> ((NotificationProvisioningException) error).code())
                .isEqualTo(NotificationProvisioningException.Code.DESTINATION_CONFLICT);
        assertThat(notificationProvisioning.inspectDestinations(tenant.tenantId())).containsExactly(first);
    }

    @Test
    void identicalNaturalKeysAreTenantScoped() {
        var tenantA = skeleton("tenant-a");
        var tenantB = skeleton("tenant-b");
        var destinationA = transactions.execute(status -> notificationProvisioning.writeDestination(
                spec(tenantA, "secret://staging/tenants/tenant-a/whatsapp"), RUN));
        var destinationB = transactions.execute(status -> notificationProvisioning.writeDestination(
                spec(tenantB, "secret://staging/tenants/tenant-b/whatsapp"), RUN));

        assertThat(destinationA.destinationId()).isNotEqualTo(destinationB.destinationId());
        assertThat(notificationProvisioning.inspectDestinations(tenantA.tenantId()))
                .containsExactly(destinationA)
                .doesNotContain(destinationB);
        assertThat(notificationProvisioning.inspectDestinations(tenantB.tenantId()))
                .containsExactly(destinationB)
                .doesNotContain(destinationA);
    }

    @Test
    void cleanupIsIdempotentExactSuspendedAndProtectedByDeliveryReferences() {
        var tenant = skeleton("tenant-a");
        var snapshot = transactions.execute(status -> notificationProvisioning.writeDestination(
                spec(tenant, "secret://staging/tenants/tenant-a/whatsapp"), RUN));
        var wrongHash = new NotificationDestinationCleanupCandidate(
                tenant.tenantId(),
                tenant.identity().slug(),
                snapshot.destinationId(),
                snapshot.channel(),
                snapshot.displayName(),
                new NotificationDestinationSemanticHash(
                        NotificationDestinationSemanticHash.CURRENT_SCHEMA_VERSION,
                        "b".repeat(64)));

        assertThatThrownBy(() -> notificationProvisioning.cleanupDestination(RUN_ID, RUN, wrongHash))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                notificationProvisioning.cleanupDestination(RUN_ID, RUN, wrongHash)))
                .isInstanceOf(NotificationProvisioningException.class);

        var candidate = cleanup(tenant, snapshot);
        var eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (
                    id, tenant_id, aggregate_type, aggregate_id, event_type,
                    schema_version, payload, processing_state, attempt_count, available_at, created_at
                ) VALUES (?, ?, 'order', ?, 'order.created', 1, '{}'::jsonb, 'pending', 0, now(), now())
                """, eventId, tenant.tenantId().value(), UUID.randomUUID());
        jdbcTemplate.update("""
                INSERT INTO notification_deliveries (
                    id, tenant_id, outbox_event_id, destination_id, attempt_number, status, started_at
                ) VALUES (?, ?, ?, ?, 1, 'started', now())
                """, UUID.randomUUID(), tenant.tenantId().value(), eventId, snapshot.destinationId());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                notificationProvisioning.cleanupDestination(RUN_ID, RUN, candidate)))
                .isInstanceOf(NotificationProvisioningException.class)
                .extracting(error -> ((NotificationProvisioningException) error).code())
                .isEqualTo(NotificationProvisioningException.Code.CLEANUP_CONFLICT);

        jdbcTemplate.update("DELETE FROM notification_deliveries WHERE tenant_id = ?", tenant.tenantId().value());
        jdbcTemplate.update("DELETE FROM outbox_events WHERE tenant_id = ?", tenant.tenantId().value());
        transactions.executeWithoutResult(status -> {
            notificationProvisioning.cleanupDestination(RUN_ID, RUN, candidate);
            notificationProvisioning.cleanupDestination(RUN_ID, RUN, candidate);
        });
        assertThat(notificationProvisioning.inspectDestinations(tenant.tenantId())).isEmpty();
    }

    @Test
    void inspectionSupportsActiveTenantButCleanupRejectsIt() {
        var tenant = skeleton("tenant-a");
        var snapshot = transactions.execute(status -> notificationProvisioning.writeDestination(
                spec(tenant, "secret://staging/tenants/tenant-a/whatsapp"), RUN));
        jdbcTemplate.update("UPDATE tenants SET status = 'active' WHERE id = ?", tenant.tenantId().value());

        assertThat(notificationProvisioning.inspectDestinations(tenant.tenantId())).containsExactly(snapshot);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                notificationProvisioning.cleanupDestination(RUN_ID, RUN, cleanup(tenant, snapshot))))
                .isInstanceOf(NotificationProvisioningException.class)
                .extracting(error -> ((NotificationProvisioningException) error).code())
                .isEqualTo(NotificationProvisioningException.Code.TENANT_STATE_CONFLICT);
    }

    private TenantProvisioningState skeleton(String slug) {
        return transactions.execute(status -> tenantProvisioning.provisionSuspendedSkeleton(
                new SuspendedTenantSpec(slug, "ru-RU", "RUB", "Europe/Moscow"), RUN));
    }

    private static NotificationDestinationSpec spec(TenantProvisioningState tenant, String reference) {
        return new NotificationDestinationSpec(
                tenant.tenantId(),
                tenant.identity().slug(),
                NotificationChannel.WHATSAPP,
                "manager",
                NotificationDestinationStatus.ACTIVE,
                new OpaqueSecretReference(reference),
                SecretReferenceReadiness.READY);
    }

    private static NotificationDestinationCleanupCandidate cleanup(
            TenantProvisioningState tenant,
            com.salamu.menu.notification.api.NotificationDestinationSnapshot snapshot) {
        return new NotificationDestinationCleanupCandidate(
                tenant.tenantId(),
                tenant.identity().slug(),
                snapshot.destinationId(),
                snapshot.channel(),
                snapshot.displayName(),
                snapshot.semanticHash());
    }
}
