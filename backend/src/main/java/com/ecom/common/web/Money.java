package com.ecom.common.web;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Money as exposed by the API: exact integer minor units plus a pre-formatted string, so neither UIs
 * nor LLM agents ever do float maths. Decimal places come from the ISO currency (JPY 0, USD 2, KWD 3).
 */
public record Money(long amountMinor, String currency, String formatted) {

    public static Money of(long amountMinor, String currency) {
        int digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        return new Money(amountMinor, currency, BigDecimal.valueOf(amountMinor).movePointLeft(digits).toPlainString());
    }
}
