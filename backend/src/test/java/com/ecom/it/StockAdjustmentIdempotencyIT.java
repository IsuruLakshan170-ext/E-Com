package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.inventory.InventoryDtos.StockAdjustmentRequest;
import com.ecom.inventory.InventoryService;
import com.ecom.user.Role;
import com.ecom.user.User;

/** R7: a retried stock adjustment (e.g. an agent that timed out) is applied exactly once. */
class StockAdjustmentIdempotencyIT extends IntegrationTestBase {

    @Test
    void aRetriedAdjustmentIsAppliedOnce() {
        User admin = user(Role.ADMIN);
        User clerk = user(Role.INVENTORY_MANAGER);
        UUID product = product(admin, 10, 100, 300);
        String key = UUID.randomUUID().toString();
        StockAdjustmentRequest request = new StockAdjustmentRequest(-2, "Damaged in storage");

        InventoryService.AdjustResult first = as(clerk, () -> inventory.adjust(product, key, request));
        InventoryService.AdjustResult retry = as(clerk, () -> inventory.adjust(product, key, request));

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(onHand(product)).isEqualTo(8);
    }

    @Test
    @Timeout(60)
    void concurrentRetriesAreAppliedOnce() throws Exception {
        User admin = user(Role.ADMIN);
        User clerk = user(Role.INVENTORY_MANAGER);
        UUID product = product(admin, 10, 100, 300);
        String key = UUID.randomUUID().toString();
        StockAdjustmentRequest request = new StockAdjustmentRequest(-3, "Cycle count");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> as(clerk, () -> inventory.adjust(product, key, request)));
        }

        List<Object> results = race(tasks);

        // the product row is locked before the replay check, so duplicates wait and then replay
        assertThat(results).allMatch(r -> r instanceof InventoryService.AdjustResult);
        assertThat(results.stream().filter(r -> !((InventoryService.AdjustResult) r).replayed())).hasSize(1);
        assertThat(onHand(product)).isEqualTo(7);
    }

    @Test
    void reusingAKeyForADifferentAdjustmentIsRejected() {
        User admin = user(Role.ADMIN);
        User clerk = user(Role.INVENTORY_MANAGER);
        UUID product = product(admin, 10, 100, 300);
        String key = UUID.randomUUID().toString();
        as(clerk, () -> inventory.adjust(product, key, new StockAdjustmentRequest(1, "Found one")));

        assertThatThrownBy(() -> as(clerk, () -> inventory.adjust(product, key, new StockAdjustmentRequest(5, "Found five"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(onHand(product)).isEqualTo(11);
    }
}
