package com.aryanbhujbal.clearbook.ledger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aryanbhujbal.clearbook.domain.AccountCodes;
import com.aryanbhujbal.clearbook.domain.Asset;
import com.aryanbhujbal.clearbook.domain.MarginPolicy;
import com.aryanbhujbal.clearbook.domain.MarginPolicy.MarginSnapshot;
import com.aryanbhujbal.clearbook.domain.Money;

/**
 * Read-side queries: what happened on an account, and where it stands.
 *
 * <p>Separate from {@link LedgerWriter} because the two have genuinely
 * different shapes — one is a short transaction guarding an invariant, the
 * other is a set of reporting queries that must never block a posting. Keeping
 * them apart also keeps the read path honest: everything here folds the
 * journal, so a statement and the balance it closes on cannot disagree.
 */
@Service
public class StatementService {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final BalanceService balances;
    private final MarkPriceRepository marks;
    private final MarginPolicy marginPolicy;

    public StatementService(AccountRepository accounts,
                            JournalEntryRepository entries,
                            BalanceService balances,
                            MarkPriceRepository marks,
                            MarginPolicy marginPolicy) {
        this.accounts = accounts;
        this.entries = entries;
        this.balances = balances;
        this.marks = marks;
        this.marginPolicy = marginPolicy;
    }

    /**
     * Every line that has touched a client's cash and securities accounts,
     * newest first, with the balance each one folds into.
     */
    @Transactional(readOnly = true)
    public Statement statementFor(String clientId) {
        AccountEntity cash = require(AccountCodes.clientCash(clientId));
        AccountEntity securities = require(AccountCodes.clientSecurities(clientId));

        List<StatementLine> lines = new ArrayList<>();
        for (AccountEntity account : List.of(cash, securities)) {
            for (JournalEntryEntity entry : entries.findEntriesTouching(account.getId())) {
                for (JournalLineEntity line : entry.getLines()) {
                    if (line.getAccount().getId().equals(account.getId())) {
                        lines.add(new StatementLine(
                                entry.getId(),
                                entry.getBusinessDate(),
                                entry.getKind(),
                                entry.getTradeId(),
                                entry.getDescription(),
                                account.getCode(),
                                line.getAsset(),
                                line.getAmount()));
                    }
                }
            }
        }

        return new Statement(
                clientId,
                balances.balance(cash.getId(), Asset.CASH),
                balances.positions(securities.getId()),
                lines);
    }

    /** Where the account stands against its maintenance requirement. */
    @Transactional(readOnly = true)
    public MarginSnapshot marginFor(String clientId, LocalDate asOf) {
        AccountEntity cash = require(AccountCodes.clientCash(clientId));
        AccountEntity securities = require(AccountCodes.clientSecurities(clientId));

        Map<String, Long> positions = balances.positions(securities.getId());
        Map<String, Money> prices = new HashMap<>();
        for (String symbol : positions.keySet()) {
            List<MarkPriceEntity> latest = marks.findLatest(symbol, asOf);
            if (!latest.isEmpty()) {
                prices.put(symbol, Money.ofPaise(latest.get(0).getPrice()));
            }
        }

        return marginPolicy.evaluate(balances.balance(cash.getId(), Asset.CASH), positions, prices);
    }

    private AccountEntity require(String code) {
        return accounts.findByCode(code).orElseThrow(() -> new UnknownAccountException(code));
    }

    /** A client's position and the history behind it. */
    public record Statement(
            String clientId,
            Money cash,
            Map<String, Long> positions,
            List<StatementLine> lines) {
    }

    /** One movement on one account. */
    public record StatementLine(
            Long entryId,
            LocalDate businessDate,
            String kind,
            String tradeId,
            String description,
            String accountCode,
            String asset,
            long amount) {
    }
}
