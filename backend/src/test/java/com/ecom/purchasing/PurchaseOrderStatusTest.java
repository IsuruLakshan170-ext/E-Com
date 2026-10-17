package com.ecom.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PurchaseOrderStatusTest {

    @Test
    void receivedOrPartlyReceivedOrdersCannotBeCancelled() {
        assertThat(PurchaseOrderStatus.APPROVED.allowedNext()).contains(PurchaseOrderStatus.CANCELLED);
        assertThat(PurchaseOrderStatus.PARTIALLY_RECEIVED.allowedNext()).doesNotContain(PurchaseOrderStatus.CANCELLED);
        assertThat(PurchaseOrderStatus.RECEIVED.allowedNext()).isEmpty();
    }

    @Test
    void onlyApprovedOrdersAreReceivable() {
        assertThat(PurchaseOrderStatus.SUBMITTED.receivable()).isFalse();
        assertThat(PurchaseOrderStatus.APPROVED.receivable()).isTrue();
        assertThat(PurchaseOrderStatus.PARTIALLY_RECEIVED.receivable()).isTrue();
    }
}
