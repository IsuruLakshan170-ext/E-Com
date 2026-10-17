package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.PessimisticLockingFailureException;

import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.order.OrderDtos.CheckoutRequest;
import com.ecom.order.OrderDtos.OrderResponse;
import com.ecom.order.OrderService;
import com.ecom.order.OrderStatus;
import com.ecom.user.Role;
import com.ecom.user.User;

/** R1 (lock order / expiry), R2 (lock timeout -> retryable lock error), R6 (cart lock) and the last-unit race. */
class OrderLockingIT extends IntegrationTestBase {

    @Test
    @Timeout(120)
    void theLastUnitIsSoldExactlyOnce() throws Exception {
        User admin = user(Role.ADMIN);
        UUID product = product(admin, 1, 100, 1_000);
        List<User> buyers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            User buyer = user(Role.CUSTOMER);
            addToCart(buyer, product, 1);
            buyers.add(buyer);
        }
        List<Callable<Object>> tasks = new ArrayList<>();
        for (User buyer : buyers) {
            tasks.add(() -> checkout(buyer).id());
        }

        List<Object> results = race(tasks);

        assertThat(results.stream().filter(r -> r instanceof UUID)).hasSize(1);
        assertThat(results).allMatch(r -> r instanceof UUID || isApiError(r, ErrorCode.INSUFFICIENT_STOCK));
        assertThat(reserved(product)).isEqualTo(1);
        assertThat(onHand(product)).isEqualTo(1); // reserved, not shipped
    }

    @Test
    @Timeout(120)
    void concurrentDuplicatesOfOneCheckoutReplayTheSameOrder() throws Exception {
        User admin = user(Role.ADMIN);
        UUID product = product(admin, 10, 100, 500);
        User buyer = user(Role.CUSTOMER);
        addToCart(buyer, product, 2);
        String key = UUID.randomUUID().toString();
        CheckoutRequest request = new CheckoutRequest(address(), quotedTotal(buyer));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> as(buyer, () -> orders.checkout(buyer.getId(), key, request)));
        }

        List<Object> results = race(tasks);

        // R6: the cart lock makes the duplicates wait and then REPLAY, instead of failing with CART_EMPTY or 409
        assertThat(results).allMatch(r -> r instanceof OrderService.CheckoutResult);
        assertThat(results.stream().map(r -> ((OrderService.CheckoutResult) r).order().id()).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> !((OrderService.CheckoutResult) r).replayed())).hasSize(1);
        assertThat(reserved(product)).isEqualTo(2);
    }

    @Test
    @Timeout(120)
    void concurrentCheckoutsWithDifferentKeysCreateOneOrder() throws Exception {
        User admin = user(Role.ADMIN);
        UUID product = product(admin, 10, 100, 500);
        User buyer = user(Role.CUSTOMER);
        addToCart(buyer, product, 1);
        long total = quotedTotal(buyer);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> as(buyer, () -> orders.checkout(buyer.getId(), UUID.randomUUID().toString(),
                    new CheckoutRequest(address(), total))));
        }

        List<Object> results = race(tasks);

        // R6: no stale cart - the winner empties it and the rest see an empty cart
        assertThat(results.stream().filter(r -> r instanceof OrderService.CheckoutResult)).hasSize(1);
        assertThat(results).allMatch(r -> r instanceof OrderService.CheckoutResult || isApiError(r, ErrorCode.CART_EMPTY));
        assertThat(reserved(product)).isEqualTo(1);
    }

    @Test
    void theUnpaidOrderCapHolds() {
        User admin = user(Role.ADMIN);
        UUID product = product(admin, 50, 100, 300);
        User buyer = user(Role.CUSTOMER);
        for (int i = 0; i < 3; i++) {
            placeOrder(buyer, product, 1);
        }
        addToCart(buyer, product, 1);

        assertThatThrownBy(() -> checkout(buyer)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
    }

    @Test
    @Timeout(180)
    void expiryCancellationAndCheckoutNeverDeadlock() throws Exception {
        User admin = user(Role.ADMIN);
        UUID a = product(admin, 200, 100, 200);
        UUID b = product(admin, 200, 100, 300);
        List<UUID> overdue = new ArrayList<>();
        Map<UUID, User> owners = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            User customer = user(Role.CUSTOMER);
            // alternate the line order, so careless locking would take the product rows in different orders
            addToCart(customer, i % 2 == 0 ? a : b, 1);
            addToCart(customer, i % 2 == 0 ? b : a, 1);
            UUID orderId = checkout(customer).id();
            makeOverdue(orderId);
            overdue.add(orderId);
            owners.put(orderId, customer);
        }
        List<User> newBuyers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            User buyer = user(Role.CUSTOMER);
            addToCart(buyer, i % 2 == 0 ? b : a, 1);
            addToCart(buyer, i % 2 == 0 ? a : b, 1);
            newBuyers.add(buyer);
        }
        List<Callable<Object>> tasks = new ArrayList<>();
        for (UUID orderId : overdue) {
            User owner = owners.get(orderId);
            tasks.add(() -> expire(orderId));
            tasks.add(() -> as(owner, () -> orders.cancelForUser(owner.getId(), orderId)));
        }
        for (User buyer : newBuyers) {
            tasks.add(() -> checkout(buyer).id());
        }

        List<Object> results = race(tasks);

        assertThat(results).noneMatch(IntegrationTestBase::isLockFailure); // R1: no deadlock losers, no lock timeouts
        assertThat(results).allMatch(r -> r instanceof Boolean || r instanceof UUID || r instanceof OrderResponse
                || isApiError(r, ErrorCode.INVALID_STATE_TRANSITION));
        for (UUID orderId : overdue) {
            assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }
        assertThat(results.stream().filter(r -> r instanceof UUID)).hasSize(6);
        assertThat(reserved(a)).isEqualTo(6); // released exactly once per cancelled order; only new orders hold stock
        assertThat(reserved(b)).isEqualTo(6);
    }

    @Test
    @Timeout(60)
    void aLockWaitLongerThanTheLockTimeoutFailsAsARetryableLockError() throws Exception {
        User admin = user(Role.ADMIN);
        UUID product = product(admin, 5, 100, 400);
        User buyer = user(Role.CUSTOMER);
        addToCart(buyer, product, 1);
        long total = quotedTotal(buyer);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        Future<?> held = holder.submit(() -> tx.executeWithoutResult(status -> {
            productRepository.lockAllByIds(List.of(product)); // someone else holds the product row
            locked.countDown();
            await(release);
        }));
        try {
            await(locked);
            Throwable thrown = catchThrowable(() -> as(buyer, () -> orders.checkout(buyer.getId(),
                    UUID.randomUUID().toString(), new CheckoutRequest(address(), total))));
            // R2: GlobalExceptionHandler maps this family to 409 CONFLICT, retryable=true
            assertThat(thrown).isInstanceOf(PessimisticLockingFailureException.class);
        } finally {
            release.countDown();
            held.get(30, TimeUnit.SECONDS);
            holder.shutdownNow();
        }
        assertThat(reserved(product)).isZero(); // the failed checkout left nothing half-done
    }
}
