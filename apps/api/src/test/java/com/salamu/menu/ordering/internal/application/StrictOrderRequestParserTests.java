package com.salamu.menu.ordering.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import com.salamu.menu.ordering.api.OrderApiException;

import org.junit.jupiter.api.Test;

class StrictOrderRequestParserTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StrictOrderRequestParser parser = new StrictOrderRequestParser();

    @Test
    void parsesTheFrozenDeliveryShapeAndNormalizesCustomerBoundaries() throws Exception {
        var command = parser.parse(mapper.readTree("""
                {
                  "orderType":"delivery",
                  "customer":{"name":"  Rezvan  ","phone":"+7 (999) 000-00-00"},
                  "fulfillment":{"address":"  Main street  ","guestCount":2},
                  "items":[{"menuItemId":"pizza","quantity":2,"modifiers":[
                    {"modifierGroupId":"size","optionId":"large","quantity":1}
                  ]}],
                  "comment":"  no onions  ",
                  "clientMenuVersion":7
                }
                """));

        assertThat(command.orderType()).isEqualTo(CreateOrderCommand.OrderType.DELIVERY);
        assertThat(command.customer().name()).isEqualTo("Rezvan");
        assertThat(command.customer().phone()).isEqualTo("+79990000000");
        assertThat(command.fulfillment().address()).isEqualTo("Main street");
        assertThat(command.comment()).isEqualTo("no onions");
        assertThat(command.items()).singleElement().satisfies(item -> {
            assertThat(item.menuItemId()).isEqualTo("pizza");
            assertThat(item.quantity()).isEqualTo(2);
        });
    }

    @Test
    void rejectsUnknownFieldsWrongFulfillmentAndDuplicateSelections() throws Exception {
        assertValidation("""
                {"orderType":"pickup","customer":{"name":"A","phone":"12345"},
                 "fulfillment":{"address":"not allowed"},
                 "items":[{"menuItemId":"pizza","quantity":1,"modifiers":[]}],
                 "clientMenuVersion":1}
                """);
        assertValidation("""
                {"orderType":"pickup","customer":{"name":"A","phone":"12345"},
                 "fulfillment":{},"unexpected":true,
                 "items":[{"menuItemId":"pizza","quantity":1,"modifiers":[]}],
                 "clientMenuVersion":1}
                """);
        assertValidation("""
                {"orderType":"pickup","customer":{"name":"A","phone":"12345"},
                 "fulfillment":{},
                 "items":[{"menuItemId":"pizza","quantity":1,"modifiers":[
                   {"modifierGroupId":"size","optionId":"large","quantity":1},
                   {"modifierGroupId":"size","optionId":"large","quantity":1}]}],
                 "clientMenuVersion":1}
                """);
    }

    @Test
    void canonicalHashIgnoresItemAndModifierArrayOrderButCoversSemanticChanges() throws Exception {
        var hasher = new OrderPayloadHasher();
        var left = parser.parse(mapper.readTree(request(
                """
                [{"menuItemId":"pizza","quantity":1,"modifiers":[
                      {"modifierGroupId":"size","optionId":"large","quantity":1},
                      {"modifierGroupId":"sauce","optionId":"hot","quantity":1}]},
                     {"menuItemId":"tea","quantity":2,"modifiers":[]}]
                """)));
        var reordered = parser.parse(mapper.readTree(request(
                """
                [{"menuItemId":"tea","quantity":2,"modifiers":[]},
                     {"menuItemId":"pizza","quantity":1,"modifiers":[
                      {"modifierGroupId":"sauce","optionId":"hot","quantity":1},
                      {"modifierGroupId":"size","optionId":"large","quantity":1}]}]
                """)));
        var changed = parser.parse(mapper.readTree(request(
                """
                [{"menuItemId":"tea","quantity":3,"modifiers":[]},
                     {"menuItemId":"pizza","quantity":1,"modifiers":[
                      {"modifierGroupId":"sauce","optionId":"hot","quantity":1},
                      {"modifierGroupId":"size","optionId":"large","quantity":1}]}]
                """)));

        assertThat(hasher.hash(left)).isEqualTo(hasher.hash(reordered));
        assertThat(hasher.hash(changed)).isNotEqualTo(hasher.hash(left));
        assertThat(hasher.hash(left)).matches("^[0-9a-f]{64}$");
    }

    @Test
    void blankOptionalCommentNormalizesToAbsentAndPhoneRequiresFiveToFifteenDigits() throws Exception {
        String withoutComment = request("""
                [{"menuItemId":"pizza","quantity":1,"modifiers":[]}]
                """);
        String blankComment = withoutComment.replace(
                "\"clientMenuVersion\":7",
                "\"comment\":\"   \",\"clientMenuVersion\":7");
        var absent = parser.parse(mapper.readTree(withoutComment));
        var blank = parser.parse(mapper.readTree(blankComment));

        assertThat(blank.comment()).isNull();
        assertThat(new OrderPayloadHasher().hash(blank)).isEqualTo(new OrderPayloadHasher().hash(absent));

        assertValidation(withoutComment.replace("\"phone\":\"12345\"", "\"phone\":\"( )--\""));
        assertValidation(withoutComment.replace(
                "\"phone\":\"12345\"",
                "\"phone\":\"1234567890123456\""));
    }

    private void assertValidation(String json) throws Exception {
        assertThatThrownBy(() -> parser.parse(mapper.readTree(json)))
                .isInstanceOfSatisfying(OrderApiException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(OrderApiException.Code.VALIDATION_ERROR));
    }

    private static String request(String items) {
        return """
                {"orderType":"pickup","customer":{"name":"A","phone":"12345"},
                 "fulfillment":{},"items":%s,"clientMenuVersion":7}
                """.formatted(items);
    }
}
