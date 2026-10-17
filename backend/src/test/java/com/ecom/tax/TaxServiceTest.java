package com.ecom.tax;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaxServiceTest {

    @Test
    void roundsHalfUpToTheMinorUnit() {
        assertThat(TaxService.taxFor(1_999, 1_000)).isEqualTo(200);   // 199.9 -> 200
        assertThat(TaxService.taxFor(1_005, 1_000)).isEqualTo(101);   // 100.5 -> 101
        assertThat(TaxService.taxFor(1_004, 1_000)).isEqualTo(100);   // 100.4 -> 100
    }

    @Test
    void zeroRateOrZeroAmountIsZero() {
        assertThat(TaxService.taxFor(12_345, 0)).isZero();
        assertThat(TaxService.taxFor(0, 2_000)).isZero();
    }
}
