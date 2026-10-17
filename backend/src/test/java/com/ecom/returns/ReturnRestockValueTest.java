package com.ecom.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.ecom.order.OrderItem;

/** R4: returns restock exactly their share of the shipped cost; the last return takes the remainder. */
class ReturnRestockValueTest {

    private OrderItem shipped(long shippedCost, int quantity) {
        OrderItem item = mock(OrderItem.class);
        when(item.getShippedCostMinor()).thenReturn(shippedCost);
        when(item.getQuantity()).thenReturn(quantity);
        return item;
    }

    @Test
    void partialReturnsSumToTheShippedCost() {
        OrderItem item = shipped(200, 3);
        long first = ReturnService.restockValue(item, 1, 0, 0);          // 66.7 -> 67
        long second = ReturnService.restockValue(item, 1, 1, first);     // 67
        long last = ReturnService.restockValue(item, 1, 2, first + second); // remainder 66
        assertThat(first + second + last).isEqualTo(200);
    }

    @Test
    void fullReturnTakesEverything() {
        assertThat(ReturnService.restockValue(shipped(201, 2), 2, 0, 0)).isEqualTo(201);
    }

    @Test
    void legacyLinesFallBackToTheCheckoutCost() {
        OrderItem item = mock(OrderItem.class);
        when(item.getShippedCostMinor()).thenReturn(null);
        when(item.getUnitCostMinor()).thenReturn(150L);
        when(item.getQuantity()).thenReturn(2);
        assertThat(ReturnService.restockValue(item, 2, 0, 0)).isEqualTo(300);
    }
}
