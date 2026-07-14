package com.salamu.menu.menu.internal.application;

import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates database projections before they cross the public application boundary. */
final class PersistedMenuValidator {

    private static final Pattern PUBLIC_ID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern ISO_CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private PersistedMenuValidator() {
    }

    static void validate(
            PublicMenuReadRepository.MenuHeader header,
            List<PublicMenuReadRepository.MenuCategoryRow> categories,
            List<PublicMenuReadRepository.MenuItemRow> items) {
        validateHeader(header);
        if (categories == null || items == null) {
            throw new InvalidPublishedMenuException();
        }

        Set<String> categoryIds = new HashSet<>();
        for (var category : categories) {
            if (category == null
                    || !validPublicId(category.categoryId(), 120)
                    || !validRequiredText(category.name(), 120)
                    || !validOptionalText(category.description(), 500)
                    || category.sortOrder() < 0
                    || !categoryIds.add(category.categoryId())) {
                throw new InvalidPublishedMenuException();
            }
        }

        Set<String> itemIds = new HashSet<>();
        for (var item : items) {
            if (item == null
                    || !validPublicId(item.menuItemId(), 120)
                    || !validPublicId(item.categoryId(), 120)
                    || !categoryIds.contains(item.categoryId())
                    || !validRequiredText(item.name(), 160)
                    || !validOptionalText(item.description(), 1000)
                    || item.priceMinor() < 0
                    || !validCurrency(item.currency())
                    || !header.currency().equals(item.currency())
                    || item.sortOrder() < 0
                    || !itemIds.add(item.menuItemId())) {
                throw new InvalidPublishedMenuException();
            }
        }
    }

    static void validateHeader(PublicMenuReadRepository.MenuHeader header) {
        if (header == null
                || header.menuVersionId() == null
                || header.restaurantId() == null
                || header.version() < 1
                || header.publishedAt() == null
                || !validCurrency(header.currency())) {
            throw new InvalidPublishedMenuException();
        }
    }

    private static boolean validPublicId(String value, int maxLength) {
        return value != null
                && value.length() <= maxLength
                && PUBLIC_ID.matcher(value).matches();
    }

    private static boolean validRequiredText(String value, int maxLength) {
        return value != null
                && !value.isBlank()
                && value.length() <= maxLength;
    }

    private static boolean validOptionalText(String value, int maxLength) {
        return value == null || value.length() <= maxLength;
    }

    private static boolean validCurrency(String value) {
        if (value == null || !ISO_CURRENCY.matcher(value).matches()) {
            return false;
        }
        try {
            Currency.getInstance(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
