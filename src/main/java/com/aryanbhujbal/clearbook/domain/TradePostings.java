package com.aryanbhujbal.clearbook.domain;

import java.util.ArrayList;
import java.util.List;

import com.aryanbhujbal.clearbook.domain.Posting.PostingLine;

/**
 * Turns a fill plus its charges into a balanced set of journal lines.
 *
 * <p>A purchase of {@code q} shares at {@code p}, with charges {@code f}:
 *
 * <pre>
 *   client cash          − (q·p + f)      the client pays consideration and charges
 *   settlement suspense  + (q·p)          owed to the clearing house until settlement
 *   brokerage income     + brokerage
 *   tax payable          + STT + stamp + GST
 *   exchange fees        + exchange + regulator
 *   client securities    + q              shares the client now owns
 *   settlement suspense  − q              and which the clearing house owes
 * </pre>
 *
 * <p>Rupees net to zero because the charges debited from the client are
 * exactly the charges credited to the fee accounts — a consequence of
 * {@link FeeBreakdown#total()} being defined as the sum of its components
 * rather than an independently rounded figure. Shares net to zero because
 * every share the client gains is one the suspense account gives up.
 *
 * <p>A sale is the same entry with the cash and share signs reversed. Charges
 * are <em>always</em> a debit to the client: they are paid on a sale as well
 * as a purchase, which is why they are added on a buy and subtracted from
 * proceeds on a sell rather than simply following the side's sign.
 *
 * <p>Suspense is the counterparty for both legs until settlement moves them.
 * Nothing here posts directly between two client accounts: a clearing system
 * never has one client's cash become another's without the clearing house in
 * between, and modelling it that way makes an unsettled obligation visible as
 * a balance rather than implicit in a missing entry.
 */
public final class TradePostings {

    private TradePostings() {
    }

    public static Posting forTrade(Trade trade, FeeBreakdown fees) {
        if (trade == null) {
            throw new IllegalArgumentException("trade is required");
        }
        if (fees == null) {
            throw new IllegalArgumentException("fees are required; use FeeSchedule.free() for none");
        }

        Money turnover = trade.turnover();
        Money charges = fees.total();
        String clientCash = AccountCodes.clientCash(trade.clientId());
        String clientSecurities = AccountCodes.clientSecurities(trade.clientId());

        // What leaves (buy) or reaches (sell) the client's cash account, with
        // charges always working against them.
        Money clientCashDelta = trade.side() == Side.BUY
                ? turnover.plus(charges).negated()
                : turnover.minus(charges);

        List<PostingLine> lines = new ArrayList<>(8);
        lines.add(PostingLine.cash(clientCash, clientCashDelta));
        lines.add(PostingLine.cash(AccountCodes.SETTLEMENT_SUSPENSE,
                turnover.times(trade.side().cashSign()).negated()));

        addIfNonZero(lines, AccountCodes.BROKERAGE_INCOME, fees.brokerage());
        addIfNonZero(lines, AccountCodes.TAX_PAYABLE, fees.taxes());
        addIfNonZero(lines, AccountCodes.EXCHANGE_FEES, fees.venueCharges());

        long signedQuantity = trade.signedQuantity();
        lines.add(PostingLine.shares(clientSecurities, trade.symbol(), signedQuantity));
        lines.add(PostingLine.shares(AccountCodes.SETTLEMENT_SUSPENSE, trade.symbol(),
                -signedQuantity));

        return new Posting(
                Posting.EntryKind.TRADE,
                trade.tradeId(),
                trade.businessDate(),
                describe(trade),
                lines);
    }

    /** A zero fee is not a leg; posting one would be noise on every statement. */
    private static void addIfNonZero(List<PostingLine> lines, String accountCode, Money amount) {
        if (!amount.isZero()) {
            lines.add(PostingLine.cash(accountCode, amount));
        }
    }

    private static String describe(Trade trade) {
        return "%s %d %s @ %s".formatted(
                trade.side(), trade.quantity(), trade.symbol(), trade.price().toPlainString());
    }
}
