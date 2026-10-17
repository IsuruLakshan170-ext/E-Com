package com.ecom.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reservation model (H2/M2) and the exact inventory value pool (R4). */
class ProductStockTest {

    private Product product;

    @BeforeEach
    void setUp() {
        product = new Product("SKU-1", "Widget", null, null, 1_000, "USD", null, true);
        product.initCost(400);
        product.receiveAtCost(10, 400); // value 4000
    }

    @Test
    void reservingReducesAvailableButNotOnHandOrValue() {
        product.reserve(4);
        assertThat(product.getStock()).isEqualTo(10);
        assertThat(product.getReserved()).isEqualTo(4);
        assertThat(product.getAvailable()).isEqualTo(6);
        assertThat(product.getStockValueMinor()).isEqualTo(4_000);
    }

    @Test
    void cannotReserveMoreThanAvailable() {
        product.reserve(8);
        assertThatThrownBy(() -> product.reserve(3)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releaseGivesStockBack() {
        product.reserve(5);
        product.releaseReservation(5);
        assertThat(product.getAvailable()).isEqualTo(10);
    }

    @Test
    void shippingConsumesReservationAndReturnsTheValueThatLeft() {
        product.reserve(3);
        long cogs = product.consumeReservation(3);
        assertThat(cogs).isEqualTo(1_200);
        assertThat(product.getStock()).isEqualTo(7);
        assertThat(product.getReserved()).isZero();
        assertThat(product.getStockValueMinor()).isEqualTo(2_800);
    }

    @Test
    void adjustmentsCanNeverEatReservedUnits() {
        product.reserve(9);
        assertThatThrownBy(() -> product.decreaseStock(2)).isInstanceOf(IllegalStateException.class);
        assertThat(product.decreaseStock(1)).isEqualTo(400);
        assertThat(product.getStock()).isEqualTo(9);
    }

    @Test
    void averageCostIsDerivedFromTheValuePool() {
        // 10 @ 4.00 + 5 @ 5.00 = 65.00 / 15 = 4.3333 -> 433 (display), value stays exact
        assertThat(product.receiveAtCost(5, 500)).isEqualTo(2_500);
        assertThat(product.getCostMinor()).isEqualTo(433);
        assertThat(product.getStockValueMinor()).isEqualTo(6_500);
    }

    @Test
    void valuePoolIsExactAndEmptiesWithTheLastUnit() {
        product.receiveAtCost(5, 500);                 // 15 units, value 6500
        product.reserve(7);
        long first = product.consumeReservation(7);    // 6500 * 7 / 15 = 3033.3 -> 3033
        long rest = product.decreaseStock(8);          // the last units take the exact remainder
        assertThat(first).isEqualTo(3_033);
        assertThat(rest).isEqualTo(3_467);
        assertThat(first + rest).isEqualTo(6_500);
        assertThat(product.getStock()).isZero();
        assertThat(product.getStockValueMinor()).isZero();
    }

    @Test
    void countGainIsValuedAtTheCurrentAverage() {
        assertThat(product.increaseStock(2)).isEqualTo(800);
        assertThat(product.getStockValueMinor()).isEqualTo(4_800);
    }

    @Test
    void returnsComeBackAtTheirGivenValue() {
        assertThat(product.receiveValue(1, 455)).isEqualTo(455);
        assertThat(product.getStockValueMinor()).isEqualTo(4_455);
        assertThat(product.getStock()).isEqualTo(11);
    }
}
