package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.user.Role;
import com.ecom.user.User;

/** R10, strict mode (the default): every step needs a different person; reasons are ignored. */
class SodStrictIT extends IntegrationTestBase {

    @Test
    void aPurchaseOrderCreatorCannotApproveItEvenWithAReason() {
        User admin = user(Role.ADMIN);
        User purchaser = user(Role.PURCHASING_MANAGER);
        UUID product = product(admin, 0, 0, 100);
        UUID po = submittedPo(purchaser, product, 5, 50);

        assertThatThrownBy(() -> as(purchaser, () -> purchasing.approve(po, "I am in a hurry")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SELF_APPROVAL_NOT_ALLOWED));
        assertThat(auditCount("SOD_OVERRIDDEN", po)).isZero();
    }

    @Test
    void theApproverCannotAlsoReceiveTheGoods() {
        User admin = user(Role.ADMIN);
        User purchaser = user(Role.PURCHASING_MANAGER);
        User approver = user(Role.ADMIN);
        UUID product = product(admin, 0, 0, 100);
        UUID po = submittedPo(purchaser, product, 5, 50);
        as(approver, () -> purchasing.approve(po, null));

        assertThatThrownBy(() -> receive(approver, po, product, 5, "short-staffed"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES));
        assertThat(onHand(product)).isZero();
    }

    @Test
    void returnStepsNeedThreeDifferentPeople() {
        User admin = user(Role.ADMIN);
        User financeUser = user(Role.FINANCE_MANAGER);
        User service = user(Role.ADMIN);   // every scope - the rule is per PERSON, not per role
        User desk = user(Role.INVENTORY_MANAGER);
        User buyer = user(Role.CUSTOMER);
        UUID product = product(admin, 5, 100, 400);
        UUID orderId = deliveredOrder(buyer, financeUser, service, product, 1);
        UUID rma = requestReturn(buyer, orderId, product, 1);
        as(service, () -> returns.decide(rma, true, null));

        assertThatThrownBy(() -> as(service, () -> returns.receive(rma, "I can do it")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES));
        as(desk, () -> returns.receive(rma, null));
        assertThatThrownBy(() -> as(desk, () -> returns.refund(rma, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES));
        assertThatThrownBy(() -> as(service, () -> returns.refund(rma, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES));
        as(financeUser, () -> returns.refund(rma, null));
    }

    @Test
    void staffNeverPayTheirOwnOrderOrProcessTheirOwnReturn() {
        User owner = user(Role.ADMIN); // shops as a customer AND works as staff
        User financeUser = user(Role.FINANCE_MANAGER);
        User service = user(Role.CUSTOMER_SERVICE);
        UUID product = product(owner, 5, 100, 400);
        UUID orderId = placeOrder(owner, product, 1).id();

        assertThatThrownBy(() -> pay(owner, orderId)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES));
        pay(financeUser, orderId);
        as(service, () -> orders.updateStatus(orderId, com.ecom.order.OrderStatus.SHIPPED));
        as(service, () -> orders.updateStatus(orderId, com.ecom.order.OrderStatus.DELIVERED));
        UUID rma = requestReturn(owner, orderId, product, 1);
        assertThatThrownBy(() -> as(owner, () -> returns.decide(rma, true, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SEGREGATION_OF_DUTIES));
    }
}
