package com.aryanbhujbal.clearbook.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

import com.aryanbhujbal.clearbook.domain.Money;
import com.aryanbhujbal.clearbook.domain.Side;
import com.aryanbhujbal.clearbook.domain.Trade;

/**
 * A trade as it arrives over HTTP.
 *
 * <p>Separate from {@link Trade} rather than binding straight onto it, for two
 * reasons that both matter. The wire format can change independently of the
 * domain — a field renamed for a client should not ripple into the ledger. And
 * the domain record validates in its constructor, so binding onto it directly
 * turns a bad request into a 500 from deep inside Jackson instead of a 400
 * with a list of what was wrong.
 *
 * <p>Price arrives as a decimal string rather than a {@code double}. A JSON
 * number is parsed as binary floating point, so {@code 3000.10} does not
 * survive the trip intact, and a price that is a hair out multiplied by a
 * quantity is a consideration that is wrong.
 */
public record SubmitTradeRequest(

        @NotBlank(message = "tradeId is required — it is the idempotency key")
        String tradeId,

        @NotBlank(message = "clientId is required")
        @Pattern(regexp = "[A-Z0-9_-]{1,32}", message = "clientId must be 1-32 characters of A-Z, 0-9, _ or -")
        String clientId,

        @NotBlank(message = "symbol is required")
        @Pattern(regexp = "[A-Z0-9&_-]{1,24}", message = "symbol must be a venue code")
        String symbol,

        @NotNull(message = "side must be BUY or SELL")
        Side side,

        @Positive(message = "quantity must be positive; direction is carried by side")
        long quantity,

        @NotBlank(message = "price is required, as a decimal string such as \"3000.50\"")
        @Pattern(regexp = "\\d{1,15}(\\.\\d{1,2})?", message = "price must be a positive decimal with at most two places")
        String price,

        @NotNull(message = "businessDate is required")
        LocalDate businessDate) {

    /** Converts to the domain record, which applies its own invariants. */
    public Trade toTrade() {
        return new Trade(tradeId, clientId, symbol, side, quantity,
                Money.ofRupees(price), businessDate);
    }
}
