package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ecom.order.OrderStatus;
import com.ecom.user.Role;
import com.ecom.user.User;

/**
 * R4: GL 1200 Inventory always equals the stock valuation (sum of the products' exact value pools), through
 * receipts at different costs, a sale, shipment and a customer return. Checked globally, so it also catches
 * any other flow in this test run that breaks the invariant.
 */
class InventoryValuationIT extends IntegrationTestBase {

    @Test
    void ledgerInventoryEqualsTheStockValuationAtEveryStep() {
        User admin = user(Role.ADMIN);
        User purchaser = user(Role.PURCHASING_MANAGER);
        User approver = user(Role.PURCHASING_MANAGER);
        User receiver = user(Role.INVENTORY_MANAGER);
        User financeUser = user(Role.FINANCE_MANAGER);
        User service = user(Role.CUSTOMER_SERVICE);
        User returnsDesk = user(Role.INVENTORY_MANAGER);
        User buyer = user(Role.CUSTOMER);
        UUID product = product(admin, 0, 0, 500);
        assertInvariant();

        buyStock(purchaser, approver, receiver, product, 3, 100);    // 3 @ 1.00 -> value 300
        assertInvariant();
        UUID orderId = placeOrder(buyer, product, 2).id();          // reserving moves no value
        assertInvariant();
        buyStock(purchaser, approver, receiver, product, 2, 101);    // + 2 @ 1.01 -> value 502 for 5 units
        assertInvariant();

        pay(financeUser, orderId);
        as(service, () -> orders.updateStatus(orderId, OrderStatus.SHIPPED));
        assertInvariant();
        long cogs = tx.execute(s -> orderRepository.findById(orderId).orElseThrow().getItems().get(0).getShippedCostMinor());
        assertThat(cogs).isEqualTo(201);                            // 502 * 2 / 5 = 200.8 -> taken at SHIPMENT
        assertThat(stockValue(product)).isEqualTo(301);

        as(service, () -> orders.updateStatus(orderId, OrderStatus.DELIVERED));
        UUID rma = requestReturn(buyer, orderId, product, 1);
        as(service, () -> returns.decide(rma, true, "approved"));
        as(returnsDesk, () -> returns.receive(rma, null));          // back at its share of the shipped cost
        assertInvariant();
        assertThat(stockValue(product)).isEqualTo(301 + 101);       // 201 / 2 = 100.5 -> 101

        as(financeUser, () -> returns.refund(rma, null));
        assertInvariant();
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    private void assertInvariant() {
        assertThat(glBalance("1200")).isEqualTo(inventoryValuation());
    }

    private long stockValue(UUID productId) {
        return productRepository.findById(productId).orElseThrow().getStockValueMinor();
    }
}
