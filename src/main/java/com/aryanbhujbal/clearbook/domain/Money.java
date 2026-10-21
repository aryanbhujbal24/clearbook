package com.aryanbhujbal.clearbook.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * An exact rupee amount, held as a signed count of paise.
 *
 * <p>Money is never a {@code double}. The usual demonstration is that
 * {@code 0.1 + 0.2 != 0.3}, but the failure that actually bites a ledger is
 * quieter: a binary fraction cannot represent most decimal amounts, so a
 * balance accumulated over a few hundred thousand postings drifts away from
 * the sum of its own entries. A ledger whose balance disagrees with its
 * journal has lost the one property it exists to provide.
 *
 * <p>So the representation is an integer count of the smallest unit, and every
 * operation that could produce a fraction — a percentage fee, a pro-rata
 * split — has to say explicitly how it rounds and where the remainder goes.
 * See {@link FeeSchedule}.
 *
 * <p>{@code long} paise covers roughly ±92,233,720,368,547.75 crore, which is
 * several orders of magnitude beyond any balance this system will hold, and
 * {@link Math#addExact} is used so that an overflow throws rather than
 * silently wrapping into a negative balance.
 */
public record Money(long paise) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static Money ofPaise(long paise) {
        return new Money(paise);
    }

    public static Money ofRupees(long rupees) {
        return new Money(Math.multiplyExact(rupees, 100L));
    }

    /**
     * Parses an exact decimal rupee amount such as {@code "1234.56"}.
     *
     * <p>Rejects anything with sub-paise precision rather than rounding it
     * away: a caller passing {@code "10.005"} has a unit confusion, and
     * silently resolving it would hide the bug.
     */
    public static Money ofRupees(String rupees) {
        BigDecimal value = new BigDecimal(rupees);
        if (value.scale() > 2) {
            BigDecimal stripped = value.stripTrailingZeros();
            if (stripped.scale() > 2) {
                throw new IllegalArgumentException(
                        "amount has sub-paise precision: " + rupees);
            }
            value = stripped;
        }
        return new Money(value.movePointRight(2).longValueExact());
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(paise, other.paise));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(paise, other.paise));
    }

    public Money negated() {
        return new Money(Math.negateExact(paise));
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(paise, factor));
    }

    public Money abs() {
        return paise < 0 ? negated() : this;
    }

    /**
     * Multiplies by a fraction, rounding half-even to whole paise.
     *
     * <p>Half-even rather than half-up because fees are computed thousands of
     * times a day and half-up is biased: it rounds a terminal 5 upwards every
     * time, so the broker systematically collects a fraction of a paisa more
     * than the stated rate. Half-even splits those cases evenly and the bias
     * cancels.
     *
     * <p>The rounding error itself is returned by
     * {@link FeeSchedule#compute}, never discarded — a discarded remainder is
     * money that exists on one side of the ledger and not the other.
     */
    public Money timesRounded(BigDecimal fraction) {
        BigDecimal exact = BigDecimal.valueOf(paise).multiply(fraction, MathContext.DECIMAL128);
        return new Money(exact.setScale(0, RoundingMode.HALF_EVEN).longValueExact());
    }

    /** The exact, unrounded product — used to measure what rounding discarded. */
    public BigDecimal timesExact(BigDecimal fraction) {
        return BigDecimal.valueOf(paise).multiply(fraction, MathContext.DECIMAL128);
    }

    public boolean isZero() {
        return paise == 0;
    }

    public boolean isNegative() {
        return paise < 0;
    }

    public boolean isLessThan(Money other) {
        return paise < other.paise;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(paise, other.paise);
    }

    /** Rupees and paise, for statements and error messages: {@code 1234.56}. */
    public String toPlainString() {
        return BigDecimal.valueOf(paise).divide(HUNDRED).setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    @Override
    public String toString() {
        return "INR " + toPlainString();
    }
}
