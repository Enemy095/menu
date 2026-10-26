package com.salamu.menu.notification.internal.infrastructure.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.salamu.menu.notification.internal.application.OpaqueEventPayload;

import org.springframework.stereotype.Component;

@Component
final class TelegramOrderMessageFactory {

    private static final int TELEGRAM_TEXT_LIMIT = 4096;
    private static final int SAFE_TEXT_LIMIT = 4000;

    private final ObjectMapper objectMapper;
    private final com.salamu.menu.site.api.SiteConfigurationQuery site;

    TelegramOrderMessageFactory(ObjectMapper objectMapper, com.salamu.menu.site.api.SiteConfigurationQuery site) {
        this.objectMapper = objectMapper;
        this.site = site;
    }

    String create(OpaqueEventPayload payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload.providerValue());
        } catch (JacksonException exception) {
            throw new InvalidOrderEventException();
        }
        requireObject(root);
        String orderNumber = requiredText(root, "orderNumber");
        String orderType = requiredText(root, "orderType");
        String customerName = requiredText(root, "customerName");
        String customerPhone = requiredText(root, "customerPhone");
        long totalMinor = requiredNonNegativeLong(root, "serverTotalMinor");
        String currency = requiredText(root, "currency");
        String receivedAt = requiredText(root, "serverReceivedAt");
        try {
            receivedAt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss", Locale.ROOT)
                    .withZone(java.time.ZoneId.of(site.current().timezone()))
                    .format(java.time.Instant.parse(receivedAt));
        } catch (java.time.DateTimeException exception) {
            throw new InvalidOrderEventException();
        }
        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new InvalidOrderEventException();
        }

        var message = new StringBuilder(1024);
        message.append("🍽 <b>НОВЫЙ ЗАКАЗ №")
                .append(html(orderNumber))
                .append("</b>\n\n")
                .append("<b>Получение:</b> <b>").append(html(orderType(orderType))).append("</b>\n\n")
                .append("<b>Клиент:</b> ").append(html(customerName)).append('\n')
                .append("<b>Телефон:</b> <code>").append(html(customerPhone)).append("</code>\n");
        optionalText(root, "customerAddress")
                .ifPresent(value -> message.append("<b>Адрес:</b> ").append(html(value)).append('\n'));

        message.append("\n<b>СОСТАВ ЗАКАЗА</b>\n");
        List<String> itemLines = new ArrayList<>();
        for (JsonNode item : items) {
            String name = requiredText(item, "name");
            long quantity = requiredPositiveLong(item, "quantity");
            long lineTotal = requiredNonNegativeLong(item, "lineTotalMinor");
            StringBuilder line = new StringBuilder()
                    .append("• ").append(html(name))
                    .append(" × ").append(quantity)
                    .append(" — ").append(html(money(lineTotal, currency)));
            JsonNode modifiers = item.path("modifiers");
            if (modifiers.isArray()) {
                List<String> modifierNames = new ArrayList<>();
                for (JsonNode modifier : modifiers) {
                    String optionName = requiredText(modifier, "optionName");
                    long modifierQuantity = requiredPositiveLong(modifier, "quantity");
                    modifierNames.add(html(optionName)
                            + (modifierQuantity == 1 ? "" : " × " + modifierQuantity));
                }
                if (!modifierNames.isEmpty()) {
                    line.append("\n  <i>").append(String.join(", ", modifierNames)).append("</i>");
                }
            }
            itemLines.add(line.toString());
        }
        appendItemsWithinLimit(message, itemLines);

        optionalPositiveLong(root, "guestCount")
                .ifPresent(value -> message.append("\n<b>Приборов:</b> ").append(value));
        optionalText(root, "comment")
                .ifPresent(value -> message.append("\n<b>Комментарий:</b> ").append(html(value)));
        message.append("\n\n<b>ИТОГО: ")
                .append(html(money(totalMinor, currency)))
                .append("</b>\n")
                .append("<b>Получен:</b> ").append(html(receivedAt));

        if (message.length() > TELEGRAM_TEXT_LIMIT) {
            throw new InvalidOrderEventException();
        }
        return message.toString();
    }

    private static void appendItemsWithinLimit(StringBuilder message, List<String> itemLines) {
        int omitted = 0;
        for (String line : itemLines) {
            if (message.length() + line.length() + 2 > SAFE_TEXT_LIMIT) {
                omitted++;
            } else {
                message.append(line).append('\n');
            }
        }
        if (omitted > 0) {
            message.append("<i>Ещё позиций: ").append(omitted).append("</i>\n");
        }
    }

    private static String orderType(String value) {
        return switch (value) {
            case "delivery" -> "Доставка";
            case "preorder" -> "Предзаказ";
            case "pickup" -> "Самовывоз";
            default -> throw new InvalidOrderEventException();
        };
    }

    private static String money(long minor, String currency) {
        long major = minor / 100;
        long fraction = minor % 100;
        String grouped = String.format(Locale.ROOT, "%,d", major).replace(',', ' ');
        String symbol = "RUB".equals(currency) ? "₽" : currency;
        return grouped + "," + String.format(Locale.ROOT, "%02d", fraction) + " " + symbol;
    }

    private static String html(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new InvalidOrderEventException();
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new InvalidOrderEventException();
        }
        return value.textValue();
    }

    private static java.util.Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return java.util.Optional.empty();
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new InvalidOrderEventException();
        }
        return java.util.Optional.of(value.textValue());
    }

    private static long requiredNonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new InvalidOrderEventException();
        }
        return value.longValue();
    }

    private static long requiredPositiveLong(JsonNode node, String field) {
        long value = requiredNonNegativeLong(node, field);
        if (value < 1) {
            throw new InvalidOrderEventException();
        }
        return value;
    }

    private static java.util.OptionalLong optionalPositiveLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return java.util.OptionalLong.empty();
        }
        long parsed = requiredPositiveLong(node, field);
        return java.util.OptionalLong.of(parsed);
    }

    static final class InvalidOrderEventException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidOrderEventException() {
            super("Telegram order payload is invalid", null, false, false);
        }
    }
}
