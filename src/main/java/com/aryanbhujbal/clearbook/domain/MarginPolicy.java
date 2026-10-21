package com.aryanbhujbal.clearbook.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Whether an account is adequately margined.
 *
 * <p>A flat maintenance rate against the marked value of each position:
 *
 * <pre>
 *   equity      = cash + Σ (position × mark)
 *   requirement = Σ |position × mark| × maintenanceRate
 *   solvent     ⇔ equity ≥ requirement
 * </pre>
 *
 * <p>Deliberately not SPAN or any portfolio-margin model. Those net offsetting
 * risk across a portfolio and are the right answer for a real clearing member,
 * but they would be the bulk of this project and they are not where its
 * interesting failure lives. The interesting failure is the race: two trades
 * that each pass this check and together breach it. Making the formula
 * cleverer does nothing about that; making the check and the posting atomic
 * does. See {@code LedgerService}.
 *
 * <p>Absolute value in the requirement is what makes a short position cost
 * margin rather than refund it.
 */
public record MarginPolicy(BigDecimal maintenanceRate) {

    public static MarginPolicy of(String rate) {
        return new MarginPolicy(new BigDecimal(rate));
    }

    /** A typical maintenance level; configure it per instrument in practice. */
    public static MarginPolicy defaults() {
        return of("0.20");
    }

    /**
     * @param cash      settled plus pending cash
     * @param positions signed share counts by symbol
     * @param marks     price per share, in paise, by symbol
     */
    public MarginSnapshot evaluate(Money cash,
                                   Map<String, Long> positions,
                                   Map<String, Money> marks) {
        Money marketValue = Money.ZERO;
        Money requirement = Money.ZERO;

        for (Map.Entry<String, Long> position : positions.entrySet()) {
            long quantity = position.getValue();
            if (quantity == 0) {
                continue;
            }
            Money mark = marks.get(position.getKey());
            if (mark == null) {
                throw new IllegalStateException(
                        "no mark price for " + position.getKey()
                                + " — refusing to margin a position that cannot be valued");
            }
            Money value = mark.times(quantity);
            marketValue = marketValue.plus(value);
            requirement = requirement.plus(value.abs().timesRounded(maintenanceRate));
        }

        return new MarginSnapshot(cash, marketValue, cash.plus(marketValue), requirement);
    }

    /**
     * An account's margin position at a moment.
     *
     * @param equity      cash plus the marked value of positions
     * @param requirement what must be maintained against those positions
     */
    public record MarginSnapshot(Money cash, Money marketValue, Money equity, Money requirement) {

        public boolean isSolvent() {
            return !equity.isLessThan(requirement);
        }

        /** How much equity could be lost before the account breaches. */
        public Money headroom() {
            return equity.minus(requirement);
        }
    }
}
