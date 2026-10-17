package com.ecom.order;

import java.util.Locale;

/**
 * BE-2: staff search by order number ("what's your order number?"). Numbers are ORD-yyyyMMdd-XXXXXXXX in upper
 * case, so a typed number is trimmed and upper-cased and then matched exactly (unique index, no LIKE).
 */
public final class OrderNumbers {

    /** Bean-validation pattern for an orderNumber query parameter. */
    public static final String PARAM_PATTERN = "^[A-Za-z0-9-]{1,32}$";

    private OrderNumbers() {
    }

    /** @return the stored form, or null when nothing was typed. */
    public static String normalize(String typed) {
        return typed == null || typed.isBlank() ? null : typed.strip().toUpperCase(Locale.ROOT);
    }
}
