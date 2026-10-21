package com.aryanbhujbal.clearbook.domain;

import java.math.BigDecimal;

/**
 * What a trade costs on top of its consideration.
 *
 * <p><b>The rates below are illustrative defaults, not a statement of current
 * statutory rates.</b> Indian charges — STT, stamp duty, exchange transaction
 * charges, the SEBI turnover fee and GST — are set by regulation and change;
 * anyone running this against real money configures them from the current
 * circulars. They live here as a record precisely so they are configuration
 * rather than constants buried in a calculation.
 *
 * <p>Two properties matter more than the numbers:
 *
 * <ol>
 *   <li><b>Every component is rounded to whole paise independently, and the
 *   total is their sum.</b> Not the other way round. Rounding the total and
 *   then apportioning it leaves the components disagreeing with the figure
 *   charged, which is how a ledger ends up a paisa out and a reconciliation
 *   ends up a day long.</li>
 *   <li><b>GST is charged on already-rounded components</b>, because that is
 *   what it is levied on — a figure actually charged, not an exact real
 *   number that nobody paid.</li>
 * </ol>
 *
 * <p>Because the total is defined as the sum of the parts, a trade entry
 * balances exactly by construction: the client is debited precisely what the
 * income and tax accounts are credited, and there is no rounding residue left
 * over needing a home.
 */
public record FeeSchedule(
        BigDecimal brokerageRate,
        Money brokerageCapPerOrder,
        BigDecimal securitiesTransactionTaxRate,
        BigDecimal exchangeTransactionRate,
        BigDecimal regulatorTurnoverRate,
        BigDecimal stampDutyRate,
        BigDecimal goodsAndServicesTaxRate) {

    /**
     * Illustrative defaults in the shape of Indian equity delivery charges.
     * Verify every one against the current circulars before trusting it.
     */
    public static FeeSchedule defaults() {
        return new FeeSchedule(
                new BigDecimal("0.0003"),    // brokerage, 0.03% of turnover
                Money.ofRupees(20),          // ... capped per order
                new BigDecimal("0.001"),     // STT, 0.1%, both sides on delivery
                new BigDecimal("0.0000297"), // exchange transaction charge
                new BigDecimal("0.000001"),  // regulator turnover fee
                new BigDecimal("0.00015"),   // stamp duty, buy side only
                new BigDecimal("0.18"));     // GST on brokerage + exchange + regulator
    }

    /** A schedule that charges nothing — useful for isolating ledger tests. */
    public static FeeSchedule free() {
        return new FeeSchedule(
                BigDecimal.ZERO, Money.ofRupees(0), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Charges on one order.
     *
     * @param side     stamp duty applies to purchases only
     * @param turnover quantity times price, as consideration
     */
    public FeeBreakdown compute(Side side, Money turnover) {
        if (turnover.isNegative()) {
            throw new IllegalArgumentException("turnover cannot be negative: " + turnover);
        }

        Money brokerage = turnover.timesRounded(brokerageRate);
        if (brokerageCapPerOrder.paise() > 0 && brokerage.paise() > brokerageCapPerOrder.paise()) {
            brokerage = brokerageCapPerOrder;
        }

        Money stt = turnover.timesRounded(securitiesTransactionTaxRate);
        Money exchange = turnover.timesRounded(exchangeTransactionRate);
        Money regulator = turnover.timesRounded(regulatorTurnoverRate);
        Money stamp = side == Side.BUY
                ? turnover.timesRounded(stampDutyRate)
                : Money.ZERO;

        // Levied on what was charged, not on an unrounded ideal.
        Money gstBase = brokerage.plus(exchange).plus(regulator);
        Money gst = gstBase.timesRounded(goodsAndServicesTaxRate);

        return new FeeBreakdown(brokerage, stt, exchange, regulator, stamp, gst);
    }
}
