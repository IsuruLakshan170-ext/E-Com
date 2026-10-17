package com.ecom.finance;

/** Chart of accounts codes (seeded by V2 migration). */
public final class LedgerAccounts {

    public static final String CASH = "1000";
    public static final String INVENTORY = "1200";
    public static final String ACCOUNTS_PAYABLE = "2000";
    public static final String SALES_TAX_PAYABLE = "2100";
    /** Deferred revenue: money received for orders not yet shipped (M2). */
    public static final String CUSTOMER_DEPOSITS = "2200";
    public static final String OPENING_EQUITY = "3000";
    public static final String SALES_REVENUE = "4000";
    public static final String SALES_RETURNS = "4100";
    public static final String COST_OF_GOODS_SOLD = "5000";
    public static final String INVENTORY_ADJUSTMENTS = "5100";

    private LedgerAccounts() {
    }
}
