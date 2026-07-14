package com.salamu.menu.notification.internal.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.salamu.menu.notification.api.NotificationDestinationQuery;
import com.salamu.menu.notification.internal.application.OpaqueSecretReference;

import jakarta.persistence.Table;

class NotificationPersistenceMappingTests {

    @Test
    void mapsOnlyFinalNotificationTablesWithoutOwnerColumnsOrOutboxOwnership() throws Exception {
        assertThat(NotificationDestinationEntity.class.getAnnotation(Table.class).name())
                .isEqualTo("notification_destinations");
        assertThat(NotificationDeliveryEntity.class.getAnnotation(Table.class).name())
                .isEqualTo("notification_deliveries");
        assertThat(fields(NotificationDestinationEntity.class)).containsExactlyInAnyOrder(
                "id", "channel", "status", "displayName", "secretReference", "metadata", "createdAt", "updatedAt");
        assertThat(fields(NotificationDeliveryEntity.class)).containsExactlyInAnyOrder(
                "id", "outboxEventId", "destinationId", "attemptNumber", "status",
                "providerMessageId", "errorCode", "errorMessage", "startedAt", "completedAt");
        assertThat(NotificationDestinationQuery.class.getDeclaredMethod("configured").getParameterCount()).isZero();
        assertThat(Arrays.stream(NotificationPersistenceMappingTests.class.getPackage().getName().split("\\.")))
                .noneMatch("tenancy"::equals);
    }

    @Test
    void persistenceConverterKeepsTheRawReferenceInternalAndRedacted() {
        String raw = "secret://application/providers/email-primary";
        var converter = new OpaqueSecretReferenceConverter();
        OpaqueSecretReference reference = converter.convertToEntityAttribute(raw);

        assertThat(reference.valid()).isTrue();
        assertThat(reference.toString()).isEqualTo("OpaqueSecretReference[redacted]");
        assertThat(reference.toString()).doesNotContain(raw);
        assertThat(converter.convertToDatabaseColumn(reference)).isEqualTo(raw);
    }

    private static String[] fields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(java.lang.reflect.Field::getName).toArray(String[]::new);
    }
}
