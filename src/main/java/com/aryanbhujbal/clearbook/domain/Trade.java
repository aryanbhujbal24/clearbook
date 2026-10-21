package com.aryanbhujbal.clearbook.domain;

import java.time.LocalDate;

/**
 * A fill, as it arrives from the exchange feed.
 *
 * <p>{@code tradeId} is the venue's own identifier and the idempotency key for
 * the whole system: the same fill replayed by Kafka, or resubmitted by a
 * client after a timeout, must post exactly once. Everything downstream keys
 * off it.
 */
public record Trade(
        String tradeId,
        String clientId,
        String symbol,
        Side side,
        long quantity,
        Money price,
        LocalDate businessDate) {

    public Trade {
        if (tradeId == null || tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId is required — it is the idempotency key");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId is required");
        }
        if (symbol == null || !symbol.matches("[A-Z0-9&_-]{1,24}")) {
            throw new IllegalArgumentException("symbol must be a venue code, got: " + symbol);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "quantity must be positive; direction is carried by side, not sign");
        }
        if (price == null || price.paise() <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("businessDate is required");
        }
    }

    /** Quantity times price — the consideration, before any charges. */
    public Money turnover() {
        return price.times(quantity);
    }

    /** The ledger asset code for this trade's instrument. */
    public String asset() {
        return Asset.equity(symbol);
    }

    /** Signed position change: positive on a buy, negative on a sell. */
    public long signedQuantity() {
        return quantity * side.positionSign();
    }
}
