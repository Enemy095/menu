package com.salamu.menu.restaurant.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.salamu.menu.restaurant.api.DefaultRestaurantUnavailableException;
import com.salamu.menu.restaurant.api.RestaurantId;
import com.salamu.menu.restaurant.api.RestaurantStatus;

class DefaultRestaurantQueryServiceTests {

    private final DefaultRestaurantRepository repository = mock(DefaultRestaurantRepository.class);
    private final DefaultRestaurantQueryService query = new DefaultRestaurantQueryService(repository);

    @Test
    void returnsTheOnlyActiveDefaultWithoutASelector() {
        var stored = new DefaultRestaurantRepository.StoredRestaurant(
                new RestaurantId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                "main",
                "Main restaurant",
                RestaurantStatus.ACTIVE);
        when(repository.findActiveDefaults()).thenReturn(List.of(stored));

        var result = query.requireActiveDefault();

        assertThat(result.id()).isEqualTo(stored.id());
        assertThat(result.externalKey()).isEqualTo("main");
        assertThat(result.displayName()).isEqualTo("Main restaurant");
        assertThat(result.status()).isEqualTo(RestaurantStatus.ACTIVE);
        verify(repository).findActiveDefaults();
    }

    @Test
    void zeroOrMultipleDefaultsFailClosedWithTheSameRedactedSignal() {
        when(repository.findActiveDefaults()).thenReturn(List.of());
        assertUnavailable();

        var first = stored(1);
        var second = stored(2);
        when(repository.findActiveDefaults()).thenReturn(List.of(first, second));
        assertUnavailable();
    }

    @Test
    void malformedRepositoryDataFailsClosedWithoutIdentifierLeakage() {
        var identifier = UUID.fromString("10000000-0000-0000-0000-000000000099");
        when(repository.findActiveDefaults()).thenReturn(List.of(
                new DefaultRestaurantRepository.StoredRestaurant(
                        new RestaurantId(identifier), "INVALID KEY", "Restaurant", RestaurantStatus.ACTIVE)));

        assertThatThrownBy(query::requireActiveDefault)
                .isInstanceOf(DefaultRestaurantUnavailableException.class)
                .hasMessage("Default restaurant is unavailable")
                .hasMessageNotContaining(identifier.toString())
                .hasNoCause();
    }

    private void assertUnavailable() {
        assertThatThrownBy(query::requireActiveDefault)
                .isInstanceOf(DefaultRestaurantUnavailableException.class)
                .hasMessage("Default restaurant is unavailable")
                .hasNoCause();
    }

    private static DefaultRestaurantRepository.StoredRestaurant stored(long suffix) {
        return new DefaultRestaurantRepository.StoredRestaurant(
                new RestaurantId(new UUID(0, suffix)),
                "restaurant-" + suffix,
                "Restaurant " + suffix,
                RestaurantStatus.ACTIVE);
    }
}
