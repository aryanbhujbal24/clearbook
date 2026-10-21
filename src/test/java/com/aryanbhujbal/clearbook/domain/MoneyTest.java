package com.aryanbhujbal.clearbook.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    @Test
    void rupeesBecomePaise() {
        assertThat(Money.ofRupees(1234).paise()).isEqualTo(123_400);
        assertThat(Money.ofRupees("1234.56").paise()).isEqualTo(123_456);
        assertThat(Money.ofRupees("1000.00").paise()).isEqualTo(100_000);
        assertThat(Money.ofRupees("-50.75").paise()).isEqualTo(-5_075);
    }

    @Test
    @DisplayName("sub-paise precision is refused rather than silently rounded")
    void subPaisePrecisionIsRefused() {
        // A caller passing 10.005 has a unit confusion. Rounding it away would
        // hide the bug and put a wrong number in the ledger.
        assertThatThrownBy(() -> Money.ofRupees("10.005"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sub-paise");
    }

    @Test
    void formatsForStatements() {
        assertThat(Money.ofPaise(123_456).toPlainString()).isEqualTo("1234.56");
        assertThat(Money.ofPaise(-5).toPlainString()).isEqualTo("-0.05");
        assertThat(Money.ZERO.toPlainString()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("overflow throws instead of wrapping into a negative balance")
    void overflowThrows() {
        assertThatThrownBy(() -> Money.ofPaise(Long.MAX_VALUE).plus(Money.ofPaise(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    /**
     * Half-even is not a detail. Half-up rounds every terminal 5 upwards, so
     * across a day of fee calculations the broker systematically collects a
     * fraction of a paisa more than the stated rate. Half-even breaks ties to
     * the even neighbour, so the bias cancels.
     */
    @ParameterizedTest(name = "{0} x {1} = {2} paise")
    @CsvSource({
            "1025, 0.18,      184",  // 184.50 -> 184, the even neighbour
            "7,    0.5,       4",    // 3.50   -> 4,   the other direction
            "5,    0.5,       2",    // 2.50   -> 2
            "12000000, 0.0000297, 356",  // 356.40 -> 356
            "3100000,  0.000001,  3",    // 3.10   -> 3
    })
    void roundsHalfEven(long paise, String rate, long expected) {
        assertThat(Money.ofPaise(paise).timesRounded(new BigDecimal(rate)).paise())
                .isEqualTo(expected);
    }

    @Test
    void comparesAndNegates() {
        assertThat(Money.ofPaise(100).isLessThan(Money.ofPaise(101))).isTrue();
        assertThat(Money.ofPaise(-1).isNegative()).isTrue();
        assertThat(Money.ofPaise(-250).abs()).isEqualTo(Money.ofPaise(250));
        assertThat(Money.ofPaise(5).negated()).isEqualTo(Money.ofPaise(-5));
        assertThat(Money.ZERO.isZero()).isTrue();
    }
}
