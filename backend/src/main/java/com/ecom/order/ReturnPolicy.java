package com.ecom.order;

import java.time.Duration;
import java.time.Instant;

public final class ReturnPolicy {

    public static final int WINDOW_DAYS = 30;

    private ReturnPolicy() {
    }

    public static boolean withinWindow(Instant deliveredAt, Instant now) {
        return deliveredAt != null && !now.isAfter(deliveredAt.plus(Duration.ofDays(WINDOW_DAYS)));
    }
}
