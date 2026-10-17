package com.ecom.finance;

/** JPQL projection: summed debits/credits for one account. */
public record AccountTotal(String accountCode, Long debitMinor, Long creditMinor) {
}
