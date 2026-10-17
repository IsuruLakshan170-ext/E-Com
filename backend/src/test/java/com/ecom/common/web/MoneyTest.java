package com.ecom.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L7: decimal places follow the ISO currency. */
class MoneyTest {

    @Test
    void formatsByCurrencyFractionDigits() {
        assertThat(Money.of(1_999, "USD").formatted()).isEqualTo("19.99");
        assertThat(Money.of(1_999, "JPY").formatted()).isEqualTo("1999");
        assertThat(Money.of(1_999, "KWD").formatted()).isEqualTo("1.999");
        assertThat(Money.of(0, "USD").formatted()).isEqualTo("0.00");
    }
}
