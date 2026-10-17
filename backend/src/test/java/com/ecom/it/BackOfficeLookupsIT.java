package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ecom.finance.FinanceDtos.FinanceOrderResponse;
import com.ecom.finance.FinanceOrderQueries;
import com.ecom.inventory.InventoryDtos.StockMovementResponse;
import com.ecom.order.OrderDtos.OrderResponse;
import com.ecom.order.OrderStatus;
import com.ecom.purchasing.PurchasingDtos.GoodsReceiptLineResponse;
import com.ecom.purchasing.PurchasingDtos.GoodsReceiptResponse;
import com.ecom.purchasing.PurchasingDtos.PurchaseOrderResponse;
import com.ecom.purchasing.PurchasingDtos.ReorderPolicyRequest;
import com.ecom.purchasing.PurchasingDtos.ReorderSuggestion;
import com.ecom.purchasing.ReplenishmentService;
import com.ecom.user.Role;
import com.ecom.user.User;

/** BE-1..BE-3: the lookups the back office needs (payment queue, order-number search, names on documents). */
class BackOfficeLookupsIT extends IntegrationTestBase {

    @Autowired FinanceOrderQueries financeOrders;
    @Autowired ReplenishmentService replenishment;

    @Test
    void financeSeesUnpaidOrdersWithTheirCustomerAndDeadline() {
        User admin = user(Role.ADMIN);
        User buyer = user(Role.CUSTOMER);
        User financeUser = user(Role.FINANCE_MANAGER);
        OrderResponse order = placeOrder(buyer, product(admin, 5, 300, 1_000), 2);

        List<FinanceOrderResponse> queue = as(financeUser, () -> financeOrders.list(null, null, 0, 200)).items();

        assertThat(queue).allMatch(o -> o.status() == OrderStatus.PENDING_PAYMENT);
        FinanceOrderResponse mine = queue.stream().filter(o -> o.id().equals(order.id())).findFirst().orElseThrow();
        assertThat(mine.orderNumber()).isEqualTo(order.orderNumber());
        assertThat(mine.customerId()).isEqualTo(buyer.getId());
        assertThat(mine.customerEmail()).isEqualTo(buyer.getEmail());
        assertThat(mine.customerName()).isEqualTo(buyer.getFullName());
        assertThat(mine.total()).isEqualTo(order.total());
        assertThat(mine.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void anOrderNumberFindsTheOrderInAnyStatusWhateverTheCase() {
        User admin = user(Role.ADMIN);
        User buyer = user(Role.CUSTOMER);
        User financeUser = user(Role.FINANCE_MANAGER);
        OrderResponse order = placeOrder(buyer, product(admin, 5, 300, 1_000), 1);
        pay(financeUser, order.id());
        String typed = order.orderNumber().toLowerCase(Locale.ROOT);

        // BE-1: a number overrides the PENDING_PAYMENT default (late payments, "did my payment arrive?")
        assertThat(as(financeUser, () -> financeOrders.list(null, typed, 0, 10)).items())
                .singleElement().satisfies(o -> {
                    assertThat(o.id()).isEqualTo(order.id());
                    assertThat(o.status()).isEqualTo(OrderStatus.PAID);
                    assertThat(o.paidAt()).isNotNull();
                });
        assertThat(as(financeUser, () -> financeOrders.list(OrderStatus.PENDING_PAYMENT, typed, 0, 10)).items()).isEmpty();

        // BE-2: the same search on the admin order list
        assertThat(as(admin, () -> orders.listAll(null, typed, 0, 20)).items())
                .extracting(OrderResponse::id).containsExactly(order.id());
        assertThat(as(admin, () -> orders.listAll(OrderStatus.SHIPPED, typed, 0, 20)).items()).isEmpty();
    }

    @Test
    void purchaseOrdersReceiptsAndMovementsCarryNamesEvenForHiddenProducts() {
        User admin = user(Role.ADMIN);
        User purchaser = user(Role.PURCHASING_MANAGER);
        User approver = user(Role.PURCHASING_MANAGER);
        User receiver = user(Role.INVENTORY_MANAGER);
        UUID productId = product(admin, 0, 0, 1_000);
        String sku = productRepository.findById(productId).orElseThrow().getSku();
        UUID poId = buyStock(purchaser, approver, receiver, productId, 4, 250);
        as(admin, () -> {
            catalog.deactivateProduct(productId); // BE-3 also covers the old "inactive product name" gap
            return null;
        });

        PurchaseOrderResponse po = as(purchaser, () -> purchasing.get(poId));
        assertThat(po.supplierName()).isEqualTo("IT supplier");
        assertThat(po.lines()).singleElement().satisfies(l -> {
            assertThat(l.sku()).isEqualTo(sku);
            assertThat(l.productName()).isEqualTo("IT product");
        });

        List<GoodsReceiptResponse> receipts = as(receiver, () -> purchasing.listReceipts(poId));
        assertThat(receipts).singleElement().satisfies(r -> assertThat(r.lines())
                .extracting(GoodsReceiptLineResponse::sku, GoodsReceiptLineResponse::productName)
                .containsExactly(tuple(sku, "IT product")));

        List<StockMovementResponse> movements = as(receiver, () -> inventory.listMovements(productId, null, 0, 10)).items();
        assertThat(movements).isNotEmpty().allSatisfy(m -> {
            assertThat(m.sku()).isEqualTo(sku);
            assertThat(m.productName()).isEqualTo("IT product");
        });
    }

    @Test
    void reorderSuggestionsNameThePreferredSupplier() {
        User admin = user(Role.ADMIN);
        User purchaser = user(Role.PURCHASING_MANAGER);
        UUID supplierId = supplier(purchaser);
        UUID productId = product(admin, 1, 100, 1_000);
        as(admin, () -> replenishment.setReorderPolicy(productId, new ReorderPolicyRequest(5, 10, supplierId)));

        ReorderSuggestion suggestion = as(admin, () -> replenishment.allSuggestions()).stream()
                .filter(s -> s.productId().equals(productId)).findFirst().orElseThrow();

        assertThat(suggestion.preferredSupplierId()).isEqualTo(supplierId);
        assertThat(suggestion.preferredSupplierName()).isEqualTo("IT supplier");
    }
}
