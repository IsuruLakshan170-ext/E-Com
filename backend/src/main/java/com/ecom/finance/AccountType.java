package com.ecom.finance;

public enum AccountType {
    ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE;

    /** Assets and expenses grow with debits; the rest grow with credits. */
    public boolean debitNormal() {
        return this == ASSET || this == EXPENSE;
    }
}
