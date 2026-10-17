package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.finance.FinanceDtos.PaymentResponse;
import com.ecom.finance.FinanceDtos.SalesSummaryResponse;
import com.ecom.finance.PaymentOutcome;
import com.ecom.order.CancelReason;
import com.ecom.order.CustomerOrder;
import com.ecom.order.OrderStatus;
import com.ecom.user.Role;
import com.ecom.user.User;

/** R3: payment vs expiry, and the late-payment rule (re-reserve, else record + auto-refund). R11 cases: CancelledOrderPaymentIT. */
class LatePaymentIT extends IntegrationTestBase {

    @Test
    @Timeout(60)
    void anOrderWhosePaymentIsBeingRecordedIsSkippedByExpiry() throws Exception {
        User admin = user(Role.ADMIN);
        UUID product = product(admin, 5, 100, 400);
        User buyer = user(Role.CUSTOMER);
        UUID orderId = placeOrder(buyer, product, 1).id();
        makeOverdue(orderId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        // Stands in for recordPayment mid-flight: it locks the order row first (R3).
        Future<?> payment = pool.submit(() -> tx.executeWithoutResult(status -> {
            orderRepository.lockById(orderId);
            locked.countDown();
            await(release);
        }));
        try {
            await(locked);
            long started = System.nanoTime();
            assertThat(expire(orderId)).isFalse(); // SKIP LOCKED: skipped, not blocked
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(LOCK_TIMEOUT);
        } finally {
            release.countDown();
            payment.get(30, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(expire(orderId)).isTrue(); // once the row is free, the job takes it
    }

    @Test
    void aLatePaymentReinstatesTheOrderWhenTheStockIsStillThere() {
        User admin = user(Role.ADMIN);
        User financeUser = user(Role.FINANCE_MANAGER);
        UUID product = product(admin, 5, 100, 400);
        User buyer = user(Role.CUSTOMER);
        UUID orderId = placeOrder(buyer, product, 2).id();
        makeOverdue(orderId);
        assertThat(expire(orderId)).isTrue();
        assertThat(orderRepository.findById(orderId).orElseThrow().getCancelReason()).isEqualTo(CancelReason.EXPIRED);
        assertThat(reserved(product)).isZero();

        PaymentResponse payment = pay(financeUser, orderId);

        assertThat(payment.outcome()).isEqualTo(PaymentOutcome.APPLIED_AFTER_REINSTATEMENT);
        assertThat(payment.invoiceNumber()).startsWith("INV-");
        CustomerOrder order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getCancelReason()).isNull();
        assertThat(reserved(product)).isEqualTo(2);
        assertThat(auditCount("ORDER_REINSTATED_AFTER_LATE_PAYMENT", orderId)).isEqualTo(1);
    }

    @Test
    void aLatePaymentIsRecordedAndRefundedWhenTheStockIsGone() {
        User admin = user(Role.ADMIN);
        User financeUser = user(Role.FINANCE_MANAGER);
        UUID product = product(admin, 1, 100, 400);
        User first = user(Role.CUSTOMER);
        UUID late = placeOrder(first, product, 1).id();
        makeOverdue(late);
        expire(late);
        placeOrder(user(Role.CUSTOMER), product, 1); // someone else now holds the only unit

        PaymentResponse payment = pay(financeUser, late);

        assertThat(payment.outcome()).isEqualTo(PaymentOutcome.REFUNDED_ORDER_EXPIRED);
        assertThat(payment.refundId()).isNotNull();
        assertThat(payment.creditNoteNumber()).startsWith("CN-");
        assertThat(payment.invoiceId()).isNull();
        CustomerOrder order = orderRepository.findById(late).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getRefundedMinor()).isEqualTo(order.getTotalMinor());
        assertThat(reserved(product)).isEqualTo(1); // the other customer's reservation is untouched
        assertThat(auditCount("ORDER_EXPIRED_PAYMENT_REFUNDED", late)).isEqualTo(1);
        Long cash = jdbc.queryForObject("select coalesce(sum(l.debit_minor - l.credit_minor), 0) from journal_lines l "
                + "join journal_entries e on e.id = l.entry_id where l.account_code = '1000' and e.source_id in (?, ?)",
                Long.class, payment.id(), payment.refundId());
        assertThat(cash).isZero(); // the money came in and went straight back out
    }

    @Test
    void aNormalPaymentIsAppliedOnceAndADuplicateIsRejected() {
        User admin = user(Role.ADMIN);
        User financeUser = user(Role.FINANCE_MANAGER);
        UUID product = product(admin, 5, 100, 400);
        UUID orderId = placeOrder(user(Role.CUSTOMER), product, 1).id();

        assertThat(pay(financeUser, orderId).outcome()).isEqualTo(PaymentOutcome.APPLIED);
        assertThatThrownBy(() -> pay(financeUser, orderId)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
    }


    @Test
    void theSalesSummaryQueriesRun() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        SalesSummaryResponse summary = finance.salesSummary(today.minusDays(1), today);
        assertThat(summary.invoicesIssued()).isGreaterThanOrEqualTo(0);
        assertThat(summary.refundedTotal().amountMinor()).isGreaterThanOrEqualTo(0);
    }
}
