package com.salamu.menu.ordering.internal.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.salamu.menu.menu.api.PublishedMenuQuery;
import com.salamu.menu.menu.api.PublishedMenuSnapshot;
import com.salamu.menu.ordering.api.OrderApiException;
import com.salamu.menu.site.api.AvailableOrderType;
import com.salamu.menu.site.api.SiteConfigurationQuery;
import com.salamu.menu.site.api.SiteNotReadyException;

import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;

@Service
class OrderPreparationService {

    private static final long MAX_TOTAL_MINOR = 1_000_000_000_000L;

    private final SiteConfigurationQuery siteQuery;
    private final PublishedMenuQuery menuQuery;

    OrderPreparationService(SiteConfigurationQuery siteQuery, PublishedMenuQuery menuQuery) {
        this.siteQuery = siteQuery;
        this.menuQuery = menuQuery;
    }

    PreparedOrder prepare(CreateOrderCommand command) {
        try {
            var site = siteQuery.current();
            var menu = menuQuery.current();
            if (!site.currency().equals(menu.currency())) {
                throw OrderApiException.dependencyUnavailable();
            }
            validateSite(command, site);
            if (command.clientMenuVersion() != menu.version()) {
                throw OrderApiException.menuChanged();
            }

            Map<String, PublishedMenuSnapshot.Item> byPublicId = new HashMap<>();
            menu.items().forEach(item -> byPublicId.put(item.publicId(), item));
            var lines = new java.util.ArrayList<PreparedOrder.Line>(command.items().size());
            long total = 0;
            int position = 0;
            for (var requested : command.items()) {
                String itemField = "items[" + position + "]";
                var menuItem = byPublicId.get(requested.menuItemId());
                if (menuItem == null || !menuItem.available()) {
                    throw OrderApiException.itemUnavailable(itemField + ".menuItemId");
                }
                var prepared = prepareLine(menuItem, requested, itemField, position);
                total = add(total, prepared.lineTotalMinor(), "items");
                lines.add(prepared);
                position++;
            }
            return new PreparedOrder(
                    menu.restaurantUuid(),
                    menu.menuVersionId().value(),
                    menu.version(),
                    menu.currency(),
                    command,
                    lines,
                    total,
                    false);
        } catch (OrderApiException exception) {
            throw exception;
        } catch (SiteNotReadyException exception) {
            throw OrderApiException.dependencyUnavailable();
        } catch (DataAccessException exception) {
            throw OrderApiException.dependencyUnavailable();
        }
    }

    private static void validateSite(
            CreateOrderCommand command,
            com.salamu.menu.site.api.SiteConfigurationSnapshot site) {
        if (!site.features().orders()) {
            throw OrderApiException.validation("orderType", "ORDERS_DISABLED");
        }
        var expected = switch (command.orderType()) {
            case DELIVERY -> com.salamu.menu.site.api.OrderType.DELIVERY;
            case PREORDER -> com.salamu.menu.site.api.OrderType.PREORDER;
            case PICKUP -> com.salamu.menu.site.api.OrderType.PICKUP;
        };
        boolean listed = site.orderTypes().stream().map(AvailableOrderType::code).anyMatch(expected::equals);
        boolean feature = switch (command.orderType()) {
            case DELIVERY -> site.features().delivery();
            case PREORDER -> site.features().preorder();
            case PICKUP -> site.features().pickup();
        };
        if (!listed || !feature) {
            throw OrderApiException.validation("orderType", "UNAVAILABLE");
        }

        var limits = site.limits();
        if (command.customer().name().length() > limits.maxNameLength()) {
            throw OrderApiException.validation("customer.name", "INVALID_LENGTH");
        }
        if (command.customer().phone().length() > limits.maxPhoneLength()) {
            throw OrderApiException.validation("customer.phone", "INVALID_LENGTH");
        }
        if (command.fulfillment().address() != null
                && command.fulfillment().address().length() > limits.maxAddressLength()) {
            throw OrderApiException.validation("fulfillment.address", "INVALID_LENGTH");
        }
        if (command.comment() != null && command.comment().length() > limits.maxCommentLength()) {
            throw OrderApiException.validation("comment", "INVALID_LENGTH");
        }
        long itemCount = 0;
        for (int index = 0; index < command.items().size(); index++) {
            var item = command.items().get(index);
            if (item.quantity() > limits.maxItemQuantity()) {
                throw OrderApiException.validation("items[" + index + "].quantity", "OUT_OF_RANGE");
            }
            itemCount += item.quantity();
        }
        if (itemCount > limits.maxTotalItems()) {
            throw OrderApiException.validation("items", "TOTAL_QUANTITY_EXCEEDED");
        }
    }

    private static PreparedOrder.Line prepareLine(
            PublishedMenuSnapshot.Item menuItem,
            CreateOrderCommand.Item requested,
            String itemField,
            int sortOrder) {
        Map<String, PublishedMenuSnapshot.ModifierGroup> groups = new HashMap<>();
        menuItem.modifierGroups().forEach(group -> groups.put(group.publicId(), group));
        Map<String, Integer> selectedByGroup = new HashMap<>();
        var snapshots = new java.util.ArrayList<PreparedOrder.ModifierSnapshot>();
        long unitPrice = menuItem.priceMinor();
        int modifierIndex = 0;
        for (var selection : requested.modifiers()) {
            String field = itemField + ".modifiers[" + modifierIndex + "]";
            var group = groups.get(selection.modifierGroupId());
            if (group == null) {
                throw OrderApiException.modifierUnavailable(field + ".modifierGroupId");
            }
            var option = group.options().stream()
                    .filter(candidate -> candidate.publicId().equals(selection.optionId()))
                    .findFirst()
                    .orElseThrow(() -> OrderApiException.modifierUnavailable(field + ".optionId"));
            if (!option.available()) {
                throw OrderApiException.modifierUnavailable(field + ".optionId");
            }
            selectedByGroup.merge(group.publicId(), selection.quantity(), Integer::sum);
            long delta = multiply(option.priceDeltaMinor(), selection.quantity(), field + ".quantity");
            unitPrice = add(unitPrice, delta, field);
            snapshots.add(new PreparedOrder.ModifierSnapshot(
                    group.publicId(), group.name(), option.publicId(), option.name(),
                    option.priceDeltaMinor(), selection.quantity()));
            modifierIndex++;
        }

        for (var group : menuItem.modifierGroups()) {
            int selected = selectedByGroup.getOrDefault(group.publicId(), 0);
            if (selected < group.minSelections() || selected > group.maxSelections()) {
                throw OrderApiException.validation(itemField + ".modifiers", "SELECTION_COUNT_INVALID");
            }
        }
        if (unitPrice < 0 || unitPrice > MAX_TOTAL_MINOR) {
            throw OrderApiException.validation(itemField + ".modifiers", "INVALID_PRICE_COMBINATION");
        }
        long lineTotal = multiply(unitPrice, requested.quantity(), itemField + ".quantity");
        return new PreparedOrder.Line(
                menuItem.id().value(), menuItem.publicId(), menuItem.name(), unitPrice,
                requested.quantity(), lineTotal, sortOrder, snapshots);
    }

    private static long add(long left, long right, String field) {
        try {
            long result = Math.addExact(left, right);
            if (result < 0 || result > MAX_TOTAL_MINOR) {
                throw OrderApiException.validation(field, "TOTAL_LIMIT_EXCEEDED");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw OrderApiException.validation(field, "TOTAL_LIMIT_EXCEEDED");
        }
    }

    private static long multiply(long left, long right, String field) {
        try {
            long result = Math.multiplyExact(left, right);
            if (result < -MAX_TOTAL_MINOR || result > MAX_TOTAL_MINOR) {
                throw OrderApiException.validation(field, "TOTAL_LIMIT_EXCEEDED");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw OrderApiException.validation(field, "TOTAL_LIMIT_EXCEEDED");
        }
    }
}
