package com.salamu.menu.menu.internal.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PersistedMenuValidatorTests {

    private static final UUID MENU_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID RESTAURANT_ID = UUID.fromString("11000000-0000-4000-8000-000000000001");
    private static final PublicMenuReadRepository.MenuHeader HEADER =
            new PublicMenuReadRepository.MenuHeader(
                    MENU_ID,
                    RESTAURANT_ID,
                    1,
                    Instant.parse("2026-07-14T09:00:00Z"),
                    "RUB");
    private static final PublicMenuReadRepository.MenuCategoryRow CATEGORY =
            new PublicMenuReadRepository.MenuCategoryRow("pizza", "Пицца", null, null, 10);
    private static final PublicMenuReadRepository.MenuItemRow ITEM =
            new PublicMenuReadRepository.MenuItemRow(
                    "pizza-pepperoni", "pizza", "Пепперони", null, 59_000, "RUB", null, true, 10);

    @Test
    void acceptsAContractCompatibleProjection() {
        assertThatCode(() -> PersistedMenuValidator.validate(HEADER, List.of(CATEGORY), List.of(ITEM)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidProjections")
    void rejectsCorruptPersistedOutputWithOneGenericFailure(
            String scenario,
            Projection projection) {
        assertThatThrownBy(() -> PersistedMenuValidator.validate(
                        projection.header(), projection.categories(), projection.items()))
                .isExactlyInstanceOf(InvalidPublishedMenuException.class)
                .hasMessage("Published menu data is invalid");
    }

    private static Stream<Arguments> invalidProjections() {
        return Stream.of(
                invalid("null header", null, List.of(CATEGORY), List.of(ITEM)),
                invalid("null menu id", header(null, RESTAURANT_ID, 1, instant(), "RUB"),
                        List.of(CATEGORY), List.of(ITEM)),
                invalid("null restaurant id", header(MENU_ID, null, 1, instant(), "RUB"),
                        List.of(CATEGORY), List.of(ITEM)),
                invalid("non-positive version", header(MENU_ID, RESTAURANT_ID, 0, instant(), "RUB"),
                        List.of(CATEGORY), List.of(ITEM)),
                invalid("null publishedAt", header(MENU_ID, RESTAURANT_ID, 1, null, "RUB"),
                        List.of(CATEGORY), List.of(ITEM)),
                invalid("invalid header currency", header(MENU_ID, RESTAURANT_ID, 1, instant(), "rub"),
                        List.of(CATEGORY), List.of(ITEM)),
                invalid("non-ISO header currency", header(MENU_ID, RESTAURANT_ID, 1, instant(), "ZZZ"),
                        List.of(CATEGORY), List.of(ITEM)),
                invalid("null categories", HEADER, null, List.of(ITEM)),
                invalid("null items", HEADER, List.of(CATEGORY), null),
                invalid("invalid category id", HEADER,
                        List.of(category("Pizza!", "Пицца", null, 10)), List.of()),
                invalid("oversized category id", HEADER,
                        List.of(category("a".repeat(121), "Пицца", null, 10)), List.of()),
                invalid("blank category name", HEADER,
                        List.of(category("pizza", "  ", null, 10)), List.of()),
                invalid("oversized category name", HEADER,
                        List.of(category("pizza", "a".repeat(121), null, 10)), List.of()),
                invalid("oversized category description", HEADER,
                        List.of(category("pizza", "Пицца", "a".repeat(501), 10)), List.of()),
                invalid("negative category sort", HEADER,
                        List.of(category("pizza", "Пицца", null, -1)), List.of()),
                invalid("duplicate category public id", HEADER,
                        List.of(CATEGORY, category("pizza", "Другая", null, 20)), List.of(ITEM)),
                invalid("invalid item id", HEADER, List.of(CATEGORY),
                        List.of(item("Pizza!", "pizza", "Пицца", null, 1, "RUB", 0))),
                invalid("oversized item id", HEADER, List.of(CATEGORY),
                        List.of(item("a".repeat(121), "pizza", "Пицца", null, 1, "RUB", 0))),
                invalid("missing item category", HEADER, List.of(CATEGORY),
                        List.of(item("item", "missing", "Item", null, 1, "RUB", 0))),
                invalid("blank item name", HEADER, List.of(CATEGORY),
                        List.of(item("item", "pizza", "  ", null, 1, "RUB", 0))),
                invalid("oversized item name", HEADER, List.of(CATEGORY),
                        List.of(item("item", "pizza", "a".repeat(161), null, 1, "RUB", 0))),
                invalid("oversized item description", HEADER, List.of(CATEGORY),
                        List.of(item("item", "pizza", "Item", "a".repeat(1001), 1, "RUB", 0))),
                invalid("negative item price", HEADER, List.of(CATEGORY),
                        List.of(item("item", "pizza", "Item", null, -1, "RUB", 0))),
                invalid("invalid item currency", HEADER, List.of(CATEGORY),
                        List.of(item("item", "pizza", "Item", null, 1, "ZZZ", 0))),
                invalid("mixed item currency", HEADER, List.of(CATEGORY),
                        List.of(item("item", "pizza", "Item", null, 1, "USD", 0))),
                invalid("negative item sort", HEADER, List.of(CATEGORY),
                        List.of(item("item", "pizza", "Item", null, 1, "RUB", -1))),
                invalid("duplicate item public id", HEADER, List.of(CATEGORY),
                        List.of(ITEM, item("pizza-pepperoni", "pizza", "Другая", null, 1, "RUB", 20))));
    }

    private static Arguments invalid(
            String scenario,
            PublicMenuReadRepository.MenuHeader header,
            List<PublicMenuReadRepository.MenuCategoryRow> categories,
            List<PublicMenuReadRepository.MenuItemRow> items) {
        return Arguments.of(scenario, new Projection(header, categories, items));
    }

    private static PublicMenuReadRepository.MenuHeader header(
            UUID menuId,
            UUID restaurantId,
            long version,
            Instant publishedAt,
            String currency) {
        return new PublicMenuReadRepository.MenuHeader(menuId, restaurantId, version, publishedAt, currency);
    }

    private static PublicMenuReadRepository.MenuCategoryRow category(
            String id,
            String name,
            String description,
            int sortOrder) {
        return new PublicMenuReadRepository.MenuCategoryRow(id, name, description, null, sortOrder);
    }

    private static PublicMenuReadRepository.MenuItemRow item(
            String id,
            String categoryId,
            String name,
            String description,
            long priceMinor,
            String currency,
            int sortOrder) {
        return new PublicMenuReadRepository.MenuItemRow(
                id, categoryId, name, description, priceMinor, currency, null, true, sortOrder);
    }

    private static Instant instant() {
        return Instant.parse("2026-07-14T09:00:00Z");
    }

    private record Projection(
            PublicMenuReadRepository.MenuHeader header,
            List<PublicMenuReadRepository.MenuCategoryRow> categories,
            List<PublicMenuReadRepository.MenuItemRow> items) {
    }
}
