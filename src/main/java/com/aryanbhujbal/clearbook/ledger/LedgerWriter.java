package com.aryanbhujbal.clearbook.ledger;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aryanbhujbal.clearbook.domain.AccountCodes;
import com.aryanbhujbal.clearbook.domain.Asset;
import com.aryanbhujbal.clearbook.domain.MarginPolicy;
import com.aryanbhujbal.clearbook.domain.MarginPolicy.MarginSnapshot;
import com.aryanbhujbal.clearbook.domain.Money;
import com.aryanbhujbal.clearbook.domain.Posting;
import com.aryanbhujbal.clearbook.domain.Posting.PostingLine;

/**
 * The transactional half of posting. {@link LedgerService} is the half that
 * retries, and the two are separate beans for a reason given there.
 *
 * <p>The order of operations inside the transaction is the whole design:
 *
 * <ol>
 *   <li><b>Take an optimistic-lock force-increment on every account the
 *   posting touches.</b> Before reading anything. This is what makes the
 *   margin check and the write that follows it a single unit with respect to
 *   other postings on those accounts.</li>
 *   <li>Write the entry and its lines, then flush, so the balances read next
 *   include them.</li>
 *   <li><b>Check margin on the result</b>, and throw if it breaches — which
 *   rolls the whole thing back, so the trade never happened rather than
 *   happening and being unwound.</li>
 * </ol>
 *
 * <p>Getting step 1 wrong is the interesting failure. Without it, two trades
 * on the same account each read the same pre-trade balance, each conclude
 * they are within margin, and both commit. The ledger balances perfectly and
 * the account is below its requirement, with no error anywhere. That is write
 * skew, and no amount of per-trade validation finds it — only making the read
 * and the write conflict does.
 */
@Component
public class LedgerWriter {

    private final EntityManager entityManager;
    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final BalanceService balances;
    private final MarkPriceRepository marks;
    private final MarginPolicy marginPolicy;

    public LedgerWriter(EntityManager entityManager,
                        AccountRepository accounts,
                        JournalEntryRepository entries,
                        BalanceService balances,
                        MarkPriceRepository marks,
                        MarginPolicy marginPolicy) {
        this.entityManager = entityManager;
        this.accounts = accounts;
        this.entries = entries;
        this.balances = balances;
        this.marks = marks;
        this.marginPolicy = marginPolicy;
    }

    /** The idempotency lookup, in its own read transaction. */
    @Transactional(readOnly = true)
    public Optional<Long> findPostedEntryId(String tradeId) {
        return entries.findByTradeId(tradeId).map(JournalEntryEntity::getId);
    }

    /**
     * Posts once. Throws on an optimistic-lock collision so the caller can
     * retry, and on a margin breach so that it does not.
     */
    @Transactional
    public PostingResult postOnce(Posting posting) {
        Map<String, AccountEntity> affected = resolveAndLock(posting);

        JournalEntryEntity entry = new JournalEntryEntity(
                posting.kind().name(),
                posting.tradeId(),
                posting.businessDate(),
                posting.description());

        for (PostingLine line : posting.lines()) {
            entry.addLine(new JournalLineEntity(
                    affected.get(line.accountCode()), line.asset(), line.amount()));
        }
        entries.save(entry);

        // The balances read below must see these lines. The deferred zero-sum
        // trigger still fires at commit, not here — the flush only makes the
        // rows visible to this transaction's own queries.
        entityManager.flush();

        MarginSnapshot worst = checkMargin(posting, affected);
        return PostingResult.posted(entry.getId(), 1, worst);
    }

    /**
     * Loads every account the posting names and bumps its version.
     *
     * <p>{@code OPTIMISTIC_FORCE_INCREMENT} rather than letting a dirty field
     * do it: the account row has nothing to change — it holds no balance — so
     * without an explicit force-increment Hibernate would see a clean entity
     * and write nothing, and two concurrent postings would not conflict at
     * all. The lock is the only thing being taken here, so it has to be taken
     * on purpose.
     */
    private Map<String, AccountEntity> resolveAndLock(Posting posting) {
        Set<String> codes = new LinkedHashSet<>();
        for (PostingLine line : posting.lines()) {
            codes.add(line.accountCode());
        }

        Map<String, AccountEntity> byCode = new HashMap<>();
        for (AccountEntity account : accounts.findByCodeIn(codes)) {
            byCode.put(account.getCode(), account);
        }
        for (String code : codes) {
            AccountEntity account = byCode.get(code);
            if (account == null) {
                throw new UnknownAccountException(code);
            }
            entityManager.lock(account, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        }
        return byCode;
    }

    /**
     * Evaluates margin for each client cash account the posting touched.
     *
     * <p>Only client accounts: house, suspense, income and tax accounts are
     * not margined, and a settlement suspense account is expected to run a
     * large obligation between trade and settlement.
     */
    private MarginSnapshot checkMargin(Posting posting, Map<String, AccountEntity> affected) {
        MarginSnapshot tightest = null;

        for (AccountEntity account : affected.values()) {
            if (!"CLIENT_CASH".equals(account.getKind())) {
                continue;
            }
            String ownerId = account.getOwnerId();
            AccountEntity securities = accounts
                    .findByCode(AccountCodes.clientSecurities(ownerId))
                    .orElseThrow(() ->
                            new UnknownAccountException(AccountCodes.clientSecurities(ownerId)));

            Money cash = balances.balance(account.getId(), Asset.CASH);
            Map<String, Long> positions = balances.positions(securities.getId());
            Map<String, Money> prices = marksFor(positions.keySet(), posting.businessDate());

            MarginSnapshot snapshot = marginPolicy.evaluate(cash, positions, prices);
            if (!snapshot.isSolvent()) {
                throw new MarginBreachException(account.getCode(), snapshot);
            }
            if (tightest == null || snapshot.headroom().isLessThan(tightest.headroom())) {
                tightest = snapshot;
            }
        }
        return tightest;
    }

    private Map<String, Money> marksFor(Set<String> symbols, LocalDate asOf) {
        Map<String, Money> prices = new HashMap<>();
        for (String symbol : symbols) {
            List<MarkPriceEntity> latest = marks.findLatest(symbol, asOf);
            if (!latest.isEmpty()) {
                prices.put(symbol, Money.ofPaise(latest.get(0).getPrice()));
            }
            // A symbol with no mark at all is left absent on purpose:
            // MarginPolicy refuses to value it rather than treating an unknown
            // price as zero, which would quietly make a position free.
        }
        return prices;
    }

    /** Opens accounts for a client, if they do not already exist. */
    @Transactional
    public void openClientAccounts(String clientId) {
        createIfAbsent(AccountCodes.clientCash(clientId), "CLIENT_CASH", clientId);
        createIfAbsent(AccountCodes.clientSecurities(clientId), "CLIENT_SECURITIES", clientId);
    }

    /** Opens the house, suspense and fee accounts the system needs to run. */
    @Transactional
    public void openSystemAccounts() {
        createIfAbsent("HOUSE_CASH", "HOUSE_CASH", null);
        createIfAbsent(AccountCodes.SETTLEMENT_SUSPENSE, "SETTLEMENT_SUSPENSE", null);
        createIfAbsent(AccountCodes.BROKERAGE_INCOME, "BROKERAGE_INCOME", null);
        createIfAbsent(AccountCodes.TAX_PAYABLE, "TAX_PAYABLE", null);
        createIfAbsent(AccountCodes.EXCHANGE_FEES, "EXCHANGE_FEES", null);
        createIfAbsent(AccountCodes.ROUNDING_RESIDUE, "ROUNDING_RESIDUE", null);
    }

    private void createIfAbsent(String code, String kind, String ownerId) {
        if (!accounts.existsByCode(code)) {
            accounts.save(new AccountEntity(code, kind, ownerId));
        }
    }

    /** Every account id a posting would touch, for tests and diagnostics. */
    @Transactional(readOnly = true)
    public List<Long> accountIdsFor(List<String> codes) {
        List<Long> ids = new ArrayList<>();
        for (AccountEntity account : accounts.findByCodeIn(codes)) {
            ids.add(account.getId());
        }
        return ids;
    }
}
