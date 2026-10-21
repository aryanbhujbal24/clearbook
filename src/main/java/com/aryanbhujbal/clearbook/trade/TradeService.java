package com.aryanbhujbal.clearbook.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aryanbhujbal.clearbook.domain.FeeBreakdown;
import com.aryanbhujbal.clearbook.domain.FeeSchedule;
import com.aryanbhujbal.clearbook.domain.Posting;
import com.aryanbhujbal.clearbook.domain.Trade;
import com.aryanbhujbal.clearbook.domain.TradePostings;
import com.aryanbhujbal.clearbook.ledger.LedgerService;
import com.aryanbhujbal.clearbook.ledger.PostingResult;

/**
 * Prices a fill and hands it to the ledger.
 *
 * <p>Thin on purpose. Charges are {@link FeeSchedule}'s problem, turning a
 * trade into balanced lines is {@link TradePostings}' problem, and
 * idempotency, locking and margin are {@link LedgerService}'s. What is left
 * here is the sequence, which is the only part that belongs to "settling a
 * trade" rather than to one of those.
 *
 * <p>Both entry points — the REST endpoint and the Kafka consumer — come
 * through this method, so a trade submitted twice by different routes still
 * posts once.
 */
@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    private final FeeSchedule feeSchedule;
    private final LedgerService ledger;

    public TradeService(FeeSchedule feeSchedule, LedgerService ledger) {
        this.feeSchedule = feeSchedule;
        this.ledger = ledger;
    }

    public SettlementOutcome settle(Trade trade) {
        FeeBreakdown fees = feeSchedule.compute(trade.side(), trade.turnover());
        Posting posting = TradePostings.forTrade(trade, fees);

        PostingResult result = ledger.post(posting);

        if (result.alreadyPosted()) {
            log.info("trade {} was already settled as entry {}", trade.tradeId(), result.entryId());
        } else {
            log.info("settled trade {} as entry {} in {} attempt(s)",
                    trade.tradeId(), result.entryId(), result.attempts());
        }
        return new SettlementOutcome(trade, fees, result);
    }

    /** What settling a trade produced: the charges applied and the entry written. */
    public record SettlementOutcome(Trade trade, FeeBreakdown fees, PostingResult result) {
    }
}
