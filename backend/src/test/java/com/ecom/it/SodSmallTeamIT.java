package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.order.OrderStatus;
import com.ecom.returns.ReturnStatus;
import com.ecom.user.Role;
import com.ecom.user.User;

/**
 * R10, small-team mode: one person may do several steps, but only with a reason, and each override is audited.
 * Conflicts of interest (own order's payment, own return) stay blocked even with a reason.
 */
@TestPropertySource(properties = "app.sod.mode=small-team")
class SodSmallTeamIT extends IntegrationTestBase {

    @Test
    void aOnePersonShopCanBuyStockWithRecordedReasons() {
        User owner = user(Role.ADMIN);
        UUID product = product(owner, 0, 0, 100);
        UUID po = submittedPo(owner, product, 4, 25);

        assertThatThrownBy(() -> as(owner, () -> purchasing.approve(po, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES);
                    assertThat(e.details()).containsEntry("overrideAllowed", true);
                });
        as(owner, () -> purchasing.approve(po, "One-person shop: I approve my own orders"));
        receive(owner, po, product, 4, "One-person shop: I also receive deliveries");

        assertThat(onHand(product)).isEqualTo(4);
        assertThat(auditCount("SOD_OVERRIDDEN", po)).isEqualTo(2);
        // V4 dropped chk_po_approver_differs, so the database accepts the same person too
        UUID approvedBy = jdbc.queryForObject("select approved_by from purchase_orders where id = ?", UUID.class, po);
        assertThat(approvedBy).isEqualTo(owner.getId());
    }

    @Test
    void aOnePersonShopCanProcessACustomersReturnWithRecordedReasons() {
        User owner = user(Role.ADMIN);
        User buyer = user(Role.CUSTOMER);
        UUID product = product(owner, 5, 100, 400);
        UUID orderId = deliveredOrder(buyer, owner, owner, product, 1); // owner pays, ships, delivers (not their order)
        UUID rma = requestReturn(buyer, orderId, product, 1);
        as(owner, () -> returns.decide(rma, true, null)); // first step: no conflict yet

        assertThatThrownBy(() -> as(owner, () -> returns.receive(rma, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES));
        as(owner, () -> returns.receive(rma, "Nobody else works here"));
        var refunded = as(owner, () -> returns.refund(rma, "Nobody else works here"));

        assertThat(refunded.status()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(auditCount("SOD_OVERRIDDEN", rma)).isEqualTo(2);
    }

    @Test
    void conflictsOfInterestStayBlockedEvenWithAReason() {
        User owner = user(Role.ADMIN);
        User colleague = user(Role.ADMIN);
        UUID product = product(owner, 5, 100, 400);
        UUID orderId = placeOrder(owner, product, 1).id();

        assertThatThrownBy(() -> pay(owner, orderId)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES));
        pay(colleague, orderId);
        as(colleague, () -> orders.updateStatus(orderId, OrderStatus.SHIPPED));
        as(colleague, () -> orders.updateStatus(orderId, OrderStatus.DELIVERED));
        UUID rma = requestReturn(owner, orderId, product, 1);
        assertThatThrownBy(() -> as(owner, () -> returns.decide(rma, true, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES));
        as(colleague, () -> returns.decide(rma, true, null));
        assertThatThrownBy(() -> as(owner, () -> returns.receive(rma, "It's my own parcel, I'll book it")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES));

        // and the database still refuses a staff member receiving their own return (kept CHECK)
        assertThatThrownBy(() -> jdbc.update("update return_requests set received_by = user_id where id = ?", rma))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
