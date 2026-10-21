package com.aryanbhujbal.clearbook.domain;

import java.util.List;

/**
 * The charges on one order, itemised.
 *
 * <p>Itemised rather than totalled because each component is credited to a
 * different account — brokerage to income, STT and stamp duty to tax payable,
 * the exchange and regulator fees to their own — and because a client who
 * asks "why did this trade cost ₹47.18?" deserves the six numbers rather than
 * the one.
 */
public record FeeBreakdown(
        Money brokerage,
        Money securitiesTransactionTax,
        Money exchangeTransaction,
        Money regulatorTurnover,
        Money stampDuty,
        Money goodsAndServicesTax) {

    /**
     * What the client is actually charged: the sum of the rounded components.
     *
     * <p>Defined this way round so that the debit to the client always equals
     * the sum of the credits to the fee accounts, with no residue to find a
     * home for.
     */
    public Money total() {
        return brokerage
                .plus(securitiesTransactionTax)
                .plus(exchangeTransaction)
                .plus(regulatorTurnover)
                .plus(stampDuty)
                .plus(goodsAndServicesTax);
    }

    /** Everything owed to the taxman, for the tax-payable leg. */
    public Money taxes() {
        return securitiesTransactionTax.plus(stampDuty).plus(goodsAndServicesTax);
    }

    /** Everything owed to the exchange and the regulator. */
    public Money venueCharges() {
        return exchangeTransaction.plus(regulatorTurnover);
    }

    public List<Component> components() {
        return List.of(
                new Component("brokerage", brokerage),
                new Component("securities transaction tax", securitiesTransactionTax),
                new Component("exchange transaction", exchangeTransaction),
                new Component("regulator turnover", regulatorTurnover),
                new Component("stamp duty", stampDuty),
                new Component("GST", goodsAndServicesTax));
    }

    public record Component(String name, Money amount) {}
}
