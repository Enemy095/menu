package com.salamu.menu.ordering.internal.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record CreateOrderCommand(
        OrderType orderType,
        Customer customer,
        Fulfillment fulfillment,
        List<Item> items,
        String comment,
        long clientMenuVersion) {

    public CreateOrderCommand {
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(fulfillment, "fulfillment");
        items = immutable(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }

    private static <T> List<T> immutable(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        return List.copyOf(new ArrayList<>(values));
    }

    public enum OrderType {
        DELIVERY("delivery"),
        PREORDER("preorder"),
        PICKUP("pickup");

        private final String contractValue;

        OrderType(String contractValue) {
            this.contractValue = contractValue;
        }

        public String contractValue() {
            return contractValue;
        }
    }

    public record Customer(String name, String phone) {

        public Customer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(phone, "phone");
        }
    }

    public record Fulfillment(String address, Integer guestCount) {
    }

    public record Item(String menuItemId, int quantity, List<Modifier> modifiers) {

        public Item {
            Objects.requireNonNull(menuItemId, "menuItemId");
            modifiers = immutable(modifiers, "modifiers");
        }
    }

    public record Modifier(String modifierGroupId, String optionId, int quantity) {

        public Modifier {
            Objects.requireNonNull(modifierGroupId, "modifierGroupId");
            Objects.requireNonNull(optionId, "optionId");
        }
    }
}
