package com.aryanbhujbal.clearbook.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.aryanbhujbal.clearbook.domain.FeeBreakdown;
import com.aryanbhujbal.clearbook.trade.TradeService.SettlementOutcome;

/**
 * What the caller gets back after submitting a trade.
 *
 * <p>Carries the itemised charges rather than just a total, because a client
 * asking why a trade cost what it did deserves the six numbers. Carries
 * {@code alreadyPosted} because a retry is a legitimate outcome and the caller
 * should be able to tell the difference without inferring it.
 *
 * <p>All amounts are decimal strings, not JSON numbers: a consumer parsing
 * {@code 1234.56} into a binary float has reintroduced exactly the error the
 * ledger is built to avoid.
 */
public record TradeAcceptedResponse(
        String tradeId,
        Long entryId,
        boolean alreadyPosted,
        int attempts,
        Map<String, String> charges,
        String totalCharges,
        MarginSnapshotResponse margin) {

    public static TradeAcceptedResponse from(SettlementOutcome outcome) {
        FeeBreakdown fees = outcome.fees();

        Map<String, String> charges = new LinkedHashMap<>();
        for (FeeBreakdown.Component component : fees.components()) {
            charges.put(component.name(), component.amount().toPlainString());
        }

        return new TradeAcceptedResponse(
                outcome.trade().tradeId(),
                outcome.result().entryId(),
                outcome.result().alreadyPosted(),
                outcome.result().attempts(),
                charges,
                fees.total().toPlainString(),
                outcome.result().margin() == null
                        ? null
                        : MarginSnapshotResponse.from(outcome.result().margin()));
    }
}
