package com.salamu.menu.ordering.internal.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PreparedOrder(
        UUID restaurantId,
        UUID menuVersionId,
        long menuVersion,
        String currency,
        CreateOrderCommand command,
        List<Line> lines,
        long totalMinor,
        boolean needsConfirmation) {

    public PreparedOrder {
        Objects.requireNonNull(restaurantId, "restaurantId");
        Objects.requireNonNull(menuVersionId, "menuVersionId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(command, "command");
        lines = List.copyOf(new ArrayList<>(lines));
        if (menuVersion < 1 || totalMinor < 0 || totalMinor > 1_000_000_000_000L) {
            throw new IllegalArgumentException("prepared order is invalid");
        }
    }

    public record Line(
            UUID menuItemId,
            String menuItemPublicId,
            String itemName,
            long unitPriceMinor,
            int quantity,
            long lineTotalMinor,
            int sortOrder,
            List<ModifierSnapshot> modifiers) {

        public Line {
            modifiers = List.copyOf(new ArrayList<>(modifiers));
        }
    }

    public record ModifierSnapshot(
            String groupPublicId,
            String groupName,
            String optionPublicId,
            String optionName,
            long priceDeltaMinor,
            int quantity) {
    }
}
