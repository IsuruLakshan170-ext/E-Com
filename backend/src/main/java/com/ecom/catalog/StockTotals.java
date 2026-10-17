package com.ecom.catalog;

/** JPQL projection: units on hand and their value at moving-average cost. */
public record StockTotals(Long units, Long valueMinor) {
}
