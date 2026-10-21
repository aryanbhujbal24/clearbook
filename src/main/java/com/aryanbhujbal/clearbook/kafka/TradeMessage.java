package com.aryanbhujbal.clearbook.kafka;

import java.time.LocalDate;

import com.aryanbhujbal.clearbook.domain.Money;
import com.aryanbhujbal.clearbook.domain.Side;
import com.aryanbhujbal.clearbook.domain.Trade;

/**
 * A fill as it arrives on the exchange topic.
 *
 * <p>Its own type rather than the domain {@link Trade}, for the same reason
 * the HTTP request is: a wire format belongs to whoever is on the other end of
 * the wire, and it has to be able to change — or to arrive malformed — without
 * that reaching the ledger.
 *
 * <p>{@code price} is a string for the reason it always is here: a JSON number
 * is binary floating point, and a price that does not survive the trip is a
 * consideration that is wrong by the time it is multiplied by a quantity.
 */
public record TradeMessage(
        String tradeId,
        String clientId,
        String symbol,
        String side,
        long quantity,
        String price,
        LocalDate businessDate) {

    /**
     * Converts to the domain record.
     *
     * <p>Every failure here is permanent — a malformed message will be exactly
     * as malformed on the fourth attempt — which is why the consumer's error
     * handler routes these straight to the dead-letter topic instead of
     * retrying them.
     */
    public Trade toTrade() {
        if (side == null) {
            throw new IllegalArgumentException("side is required (BUY or SELL)");
        }
        Side parsed;
        try {
            parsed = Side.valueOf(side.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("side must be BUY or SELL, got: " + side);
        }
        if (price == null) {
            throw new IllegalArgumentException("price is required");
        }
        return new Trade(tradeId, clientId, symbol, parsed, quantity,
                Money.ofRupees(price), businessDate);
    }
}
