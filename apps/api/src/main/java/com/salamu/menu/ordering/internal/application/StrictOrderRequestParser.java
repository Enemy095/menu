package com.salamu.menu.ordering.internal.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import com.salamu.menu.ordering.api.OrderApiException;
import com.salamu.menu.ordering.internal.application.CreateOrderCommand.Customer;
import com.salamu.menu.ordering.internal.application.CreateOrderCommand.Fulfillment;
import com.salamu.menu.ordering.internal.application.CreateOrderCommand.Item;
import com.salamu.menu.ordering.internal.application.CreateOrderCommand.Modifier;
import com.salamu.menu.ordering.internal.application.CreateOrderCommand.OrderType;

import org.springframework.stereotype.Component;

@Component
public class StrictOrderRequestParser {

    private static final Pattern PUBLIC_ID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()-]{5,32}$");
    private static final Set<String> ROOT_REQUIRED = Set.of(
            "orderType", "customer", "fulfillment", "items", "clientMenuVersion");

    public CreateOrderCommand parse(JsonNode root) {
        requireObject(root, "$root");
        Set<String> rootFields = fields(root);
        var allowed = new HashSet<>(ROOT_REQUIRED);
        allowed.add("comment");
        if (!rootFields.containsAll(ROOT_REQUIRED) || !allowed.containsAll(rootFields)) {
            throw invalid("$root", "INVALID_FIELDS");
        }

        OrderType orderType = orderType(text(root, "orderType", 1, 16));
        Customer customer = customer(object(root, "customer"));
        Fulfillment fulfillment = fulfillment(object(root, "fulfillment"), orderType);
        List<Item> items = items(root.get("items"));
        String comment = optionalText(root, "comment", 500);
        long clientMenuVersion = integer(root, "clientMenuVersion", 1, Long.MAX_VALUE);
        return new CreateOrderCommand(orderType, customer, fulfillment, items, comment, clientMenuVersion);
    }

    private static Customer customer(JsonNode node) {
        requireExact(node, Set.of("name", "phone"), "customer");
        String name = text(node, "name", 1, 80).strip();
        if (name.isBlank()) {
            throw invalid("customer.name", "BLANK");
        }
        String phone = text(node, "phone", 5, 32);
        if (!PHONE.matcher(phone).matches()) {
            throw invalid("customer.phone", "INVALID_FORMAT");
        }
        String normalizedPhone = phone.replaceAll("[ ()-]", "");
        String digits = normalizedPhone.startsWith("+") ? normalizedPhone.substring(1) : normalizedPhone;
        if (digits.length() < 5 || digits.length() > 15 || !digits.chars().allMatch(Character::isDigit)) {
            throw invalid("customer.phone", "INVALID_FORMAT");
        }
        return new Customer(name, normalizedPhone);
    }

    private static Fulfillment fulfillment(JsonNode node, OrderType orderType) {
        if (orderType == OrderType.DELIVERY) {
            requireAllowedAndRequired(node, Set.of("address", "guestCount"), Set.of("address"), "fulfillment");
            String address = text(node, "address", 1, 240).strip();
            if (address.isBlank()) {
                throw invalid("fulfillment.address", "BLANK");
            }
            return new Fulfillment(address, optionalInteger(node, "guestCount", 1, 99));
        }
        requireAllowedAndRequired(node, Set.of("guestCount"), Set.of(), "fulfillment");
        return new Fulfillment(null, optionalInteger(node, "guestCount", 1, 99));
    }

    private static List<Item> items(JsonNode node) {
        if (node == null || !node.isArray() || node.size() < 1 || node.size() > 200) {
            throw invalid("items", "INVALID_SIZE");
        }
        var result = new ArrayList<Item>(node.size());
        var itemIds = new HashSet<String>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            String field = "items[" + index + "]";
            requireExact(item, Set.of("menuItemId", "quantity", "modifiers"), field);
            String menuItemId = publicId(item, "menuItemId", field + ".menuItemId");
            if (!itemIds.add(menuItemId)) {
                throw invalid(field + ".menuItemId", "DUPLICATE");
            }
            int quantity = Math.toIntExact(integer(item, "quantity", 1, 99));
            List<Modifier> modifiers = modifiers(item.get("modifiers"), field + ".modifiers");
            result.add(new Item(menuItemId, quantity, modifiers));
        }
        return List.copyOf(result);
    }

    private static List<Modifier> modifiers(JsonNode node, String field) {
        if (node == null || !node.isArray() || node.size() > 50) {
            throw invalid(field, "INVALID_SIZE");
        }
        var result = new ArrayList<Modifier>(node.size());
        var selections = new HashSet<String>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode modifier = node.get(index);
            String element = field + "[" + index + "]";
            requireExact(modifier, Set.of("modifierGroupId", "optionId", "quantity"), element);
            String groupId = publicId(modifier, "modifierGroupId", element + ".modifierGroupId");
            String optionId = publicId(modifier, "optionId", element + ".optionId");
            if (!selections.add(groupId + "\u0000" + optionId)) {
                throw invalid(element, "DUPLICATE");
            }
            int quantity = Math.toIntExact(integer(modifier, "quantity", 1, 99));
            result.add(new Modifier(groupId, optionId, quantity));
        }
        return List.copyOf(result);
    }

    private static OrderType orderType(String value) {
        return switch (value) {
            case "delivery" -> OrderType.DELIVERY;
            case "preorder" -> OrderType.PREORDER;
            case "pickup" -> OrderType.PICKUP;
            default -> throw invalid("orderType", "UNSUPPORTED_VALUE");
        };
    }

    private static String publicId(JsonNode node, String name, String field) {
        String value = text(node, name, 1, 120);
        if (!PUBLIC_ID.matcher(value).matches()) {
            throw invalid(field, "INVALID_FORMAT");
        }
        return value;
    }

    private static JsonNode object(JsonNode node, String field) {
        JsonNode value = node.get(field);
        requireObject(value, field);
        return value;
    }

    private static void requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw invalid(field, "EXPECTED_OBJECT");
        }
    }

    private static void requireExact(JsonNode node, Set<String> expected, String field) {
        requireObject(node, field);
        if (!fields(node).equals(expected)) {
            throw invalid(field, "INVALID_FIELDS");
        }
    }

    private static void requireAllowedAndRequired(
            JsonNode node, Set<String> allowed, Set<String> required, String field) {
        requireObject(node, field);
        Set<String> actual = fields(node);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw invalid(field, "INVALID_FIELDS");
        }
    }

    private static Set<String> fields(JsonNode node) {
        var result = new LinkedHashSet<String>();
        node.properties().forEach(property -> result.add(property.getKey()));
        return Set.copyOf(result);
    }

    private static String text(JsonNode node, String name, int minimum, int maximum) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw invalid(name, "EXPECTED_STRING");
        }
        String text = value.asString();
        if (text.length() < minimum || text.length() > maximum) {
            throw invalid(name, "INVALID_LENGTH");
        }
        return text;
    }

    private static String optionalText(JsonNode node, String name, int maximum) {
        JsonNode value = node.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isTextual() || value.asString().length() > maximum) {
            throw invalid(name, "INVALID_LENGTH");
        }
        String normalized = value.asString().strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static Integer optionalInteger(JsonNode node, String name, long minimum, long maximum) {
        return node.get(name) == null ? null : Math.toIntExact(integer(node, name, minimum, maximum));
    }

    private static long integer(JsonNode node, String name, long minimum, long maximum) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(name, "EXPECTED_INTEGER");
        }
        long number = value.longValue();
        if (number < minimum || number > maximum) {
            throw invalid(name, "OUT_OF_RANGE");
        }
        return number;
    }

    private static OrderApiException invalid(String field, String reason) {
        return OrderApiException.validation(field, reason);
    }
}
