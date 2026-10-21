package com.aryanbhujbal.clearbook.support;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aryanbhujbal.clearbook.domain.AccountCodes;
import com.aryanbhujbal.clearbook.domain.Asset;
import com.aryanbhujbal.clearbook.domain.Money;
import com.aryanbhujbal.clearbook.domain.Posting;
import com.aryanbhujbal.clearbook.domain.Posting.EntryKind;
import com.aryanbhujbal.clearbook.domain.Posting.PostingLine;
import com.aryanbhujbal.clearbook.ledger.LedgerService;
import com.aryanbhujbal.clearbook.ledger.LedgerWriter;
import com.aryanbhujbal.clearbook.ledger.MarkPriceEntity;
import com.aryanbhujbal.clearbook.ledger.MarkPriceRepository;

/**
 * Opens accounts, funds them and sets marks, so tests can start from a known
 * state.
 *
 * <p>A plain {@code @Component} under the application's package, so the test
 * context picks it up by ordinary component scanning. Deliberately not
 * {@code @TestComponent}, which Boot's type-exclude filter removes from
 * scanning and which would therefore fail to inject.
 */
@Component
public class LedgerFixture {

    private final LedgerWriter writer;
    private final LedgerService ledger;
    private final MarkPriceRepository marks;

    public LedgerFixture(LedgerWriter writer, LedgerService ledger, MarkPriceRepository marks) {
        this.writer = writer;
        this.ledger = ledger;
        this.marks = marks;
    }

    /** Opens the system accounts and one client, funded with the given cash. */
    public void openAndFund(String clientId, Money cash, LocalDate businessDate) {
        writer.openSystemAccounts();
        writer.openClientAccounts(clientId);
        fund(clientId, cash, businessDate);
    }

    /**
     * Credits a client with cash against the house account.
     *
     * <p>Against the house rather than out of nowhere: there is no such thing
     * as a single-sided entry here, and modelling a deposit as one would
     * breach the conservation rule the whole ledger rests on.
     */
    public void fund(String clientId, Money cash, LocalDate businessDate) {
        if (cash.isZero()) {
            return;
        }
        ledger.post(new Posting(
                EntryKind.OPENING_BALANCE,
                null,
                businessDate,
                "opening balance for " + clientId,
                List.of(
                        PostingLine.cash(AccountCodes.clientCash(clientId), cash),
                        PostingLine.cash("HOUSE_CASH", cash.negated()))));
    }

    /** Gives a client a position without a trade, for isolating margin tests. */
    public void seedPosition(String clientId, String symbol, long quantity,
                             LocalDate businessDate) {
        ledger.post(new Posting(
                EntryKind.ADJUSTMENT,
                null,
                businessDate,
                "seeded position",
                List.of(
                        PostingLine.shares(AccountCodes.clientSecurities(clientId), symbol, quantity),
                        PostingLine.shares(AccountCodes.SETTLEMENT_SUSPENSE, symbol, -quantity))));
    }

    @Transactional
    public void mark(String symbol, LocalDate businessDate, Money price) {
        marks.save(new MarkPriceEntity(symbol, businessDate, price.paise()));
    }

    public String cashAccount(String clientId) {
        return AccountCodes.clientCash(clientId);
    }

    public String securitiesAccount(String clientId) {
        return AccountCodes.clientSecurities(clientId);
    }

    public String cashAsset() {
        return Asset.CASH;
    }
}
