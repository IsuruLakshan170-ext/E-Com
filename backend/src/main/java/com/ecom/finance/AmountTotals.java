package com.ecom.finance;

/** JPQL projection: count + amount + tax over a set of documents. */
public record AmountTotals(Long count, Long amountMinor, Long taxMinor) {
}
