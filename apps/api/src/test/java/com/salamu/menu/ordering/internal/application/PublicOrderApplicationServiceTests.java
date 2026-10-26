package com.salamu.menu.ordering.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salamu.menu.ordering.api.OrderApiException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class PublicOrderApplicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00.123456789Z");
    private static final String KEY = "10000000-0000-4000-8000-000000000001";

    private final OrderStore store = mock(OrderStore.class);
    private final OrderPreparationService preparation = mock(OrderPreparationService.class);
    private final OrderCreationTransaction transaction = mock(OrderCreationTransaction.class);
    private final PublicOrderApplicationService service = new PublicOrderApplicationService(
            store,
            new OrderPayloadHasher(),
            preparation,
            transaction,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void initialIdempotencyLookupFailureBecomesSafeDependencyUnavailable() {
        when(store.findByIdempotencyKey(KEY))
                .thenThrow(new DataAccessResourceFailureException("jdbc:postgresql://secret-host/private"));

        assertSafeDependencyFailure(() -> service.create(KEY, command()));

        verify(preparation, never()).prepare(any());
        verify(transaction, never()).create(anyString(), anyString(), any(), any());
    }

    @Test
    void transactionalDatabaseFailureBecomesSafeDependencyUnavailable() {
        var command = command();
        when(store.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(preparation.prepare(command)).thenReturn(prepared(command));
        when(transaction.create(anyString(), anyString(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("password=private customer=secret"));

        assertSafeDependencyFailure(() -> service.create(KEY, command));

        verify(transaction).create(
                eq(KEY),
                anyString(),
                any(),
                eq(NOW.truncatedTo(ChronoUnit.MICROS)));
    }

    private static void assertSafeDependencyFailure(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(OrderApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(OrderApiException.Code.DEPENDENCY_UNAVAILABLE);
                    assertThat(exception).hasMessage("DEPENDENCY_UNAVAILABLE");
                    assertThat(exception.getMessage()).doesNotContain("secret", "private", "password", "jdbc");
                    assertThat(exception.details()).isEmpty();
                });
    }

    private static CreateOrderCommand command() {
        return new CreateOrderCommand(
                CreateOrderCommand.OrderType.PICKUP,
                new CreateOrderCommand.Customer("Customer", "+79990000000"),
                new CreateOrderCommand.Fulfillment(null, 2),
                List.of(new CreateOrderCommand.Item("pepperoni", 1, List.of())),
                null,
                7);
    }

    private static PreparedOrder prepared(CreateOrderCommand command) {
        return new PreparedOrder(
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("20000000-0000-4000-8000-000000000002"),
                7,
                "RUB",
                command,
                List.of(new PreparedOrder.Line(
                        UUID.fromString("40000000-0000-4000-8000-000000000004"),
                        "pepperoni",
                        "Pepperoni",
                        62_500,
                        1,
                        62_500,
                        0,
                        List.of())),
                62_500,
                false);
    }
}
