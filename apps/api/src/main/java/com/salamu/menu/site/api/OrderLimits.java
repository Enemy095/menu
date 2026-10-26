package com.salamu.menu.site.api;

public record OrderLimits(
        int maxItemQuantity,
        int maxTotalItems,
        int maxNameLength,
        int maxPhoneLength,
        int maxAddressLength,
        int maxCommentLength,
        int delayedOrderConfirmationMinutes) {

    public OrderLimits {
        bounded(maxItemQuantity, 1, 99, "maxItemQuantity");
        bounded(maxTotalItems, 1, 200, "maxTotalItems");
        bounded(maxNameLength, 1, 80, "maxNameLength");
        bounded(maxPhoneLength, 5, 32, "maxPhoneLength");
        bounded(maxAddressLength, 1, 240, "maxAddressLength");
        bounded(maxCommentLength, 1, 500, "maxCommentLength");
        bounded(delayedOrderConfirmationMinutes, 0, 1440, "delayedOrderConfirmationMinutes");
    }

    private static void bounded(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is outside the accepted contract bound");
        }
    }
}
