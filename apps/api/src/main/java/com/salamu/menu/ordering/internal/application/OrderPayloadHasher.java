package com.salamu.menu.ordering.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
public class OrderPayloadHasher {

    public String hash(CreateOrderCommand command) {
        var canonical = new StringBuilder(1024);
        value(canonical, "sc-order-v1");
        value(canonical, command.orderType().contractValue());
        value(canonical, command.customer().name());
        value(canonical, command.customer().phone());
        nullable(canonical, command.fulfillment().address());
        nullable(canonical, command.fulfillment().guestCount());
        nullable(canonical, command.comment());
        value(canonical, command.clientMenuVersion());
        command.items().stream()
                .sorted(Comparator.comparing(CreateOrderCommand.Item::menuItemId))
                .forEach(item -> {
                    value(canonical, item.menuItemId());
                    value(canonical, item.quantity());
                    item.modifiers().stream()
                            .sorted(Comparator.comparing(CreateOrderCommand.Modifier::modifierGroupId)
                                    .thenComparing(CreateOrderCommand.Modifier::optionId))
                            .forEach(modifier -> {
                                value(canonical, modifier.modifierGroupId());
                                value(canonical, modifier.optionId());
                                value(canonical, modifier.quantity());
                            });
                    value(canonical, "item-end");
                });
        return HexFormat.of().formatHex(sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void value(StringBuilder target, Object value) {
        String text = String.valueOf(value);
        target.append(text.length()).append(':').append(text).append(';');
    }

    private static void nullable(StringBuilder target, Object value) {
        if (value == null) {
            target.append("-;");
        } else {
            value(target, value);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
