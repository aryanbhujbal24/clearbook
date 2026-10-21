package com.aryanbhujbal.clearbook.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.aryanbhujbal.clearbook.domain.AccountCodes;
import com.aryanbhujbal.clearbook.domain.Asset;
import com.aryanbhujbal.clearbook.domain.Money;
import com.aryanbhujbal.clearbook.domain.Posting;
import com.aryanbhujbal.clearbook.domain.Posting.EntryKind;
import com.aryanbhujbal.clearbook.domain.Posting.PostingLine;
import com.aryanbhujbal.clearbook.domain.Side;
import com.aryanbhujbal.clearbook.domain.Trade;
import com.aryanbhujbal.clearbook.support.LedgerFixture;
import com.aryanbhujbal.clearbook.support.PostgresIT;
import com.aryanbhujbal.clearbook.trade.TradeService;

/**
 * The golden scenario, end to end against a real PostgreSQL.
 *
 * <p>The same two trades as {@code GoldenScenarioTest}, which checks the
 * arithmetic in isolation. This one checks that persisting it changes nothing:
 * the figures that come back out of the database are the figures that were
 * worked out by hand.
 */
class LedgerPostingIT extends PostgresIT {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

    @Autowired
    private LedgerFixture fixture;
    @Autowired
    private TradeService trades;
    @Autowired
    private LedgerService ledger;
    @Autowired
    private BalanceService balances;
    @Autowired
    private StatementService statements;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

    private String client;

    @BeforeEach
    void openAccounts() {
        // A fresh client per test: the container is shared across classes, so
        // tests that assume an empty ledger would interfere with each other.
        client = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        fixture.openAndFund(client, Money.ofRupees(500_000), DAY);
        fixture.mark("TCS", DAY, Money.ofRupees("3050.00"));
    }

    @Test
    @DisplayName("the hand-computed scenario survives a round trip through the database")
    void goldenScenarioPersists() {
        trades.settle(new Trade("T-" + client + "-1", client, "TCS", Side.BUY, 40,
                Money.ofRupees("3000.00"), DAY));
        trades.settle(new Trade("T-" + client + "-2", client, "TCS", Side.SELL, 10,
                Money.ofRupees("3100.00"), DAY));

        Long cashId = accountId(AccountCodes.clientCash(client));
        Long securitiesId = accountId(AccountCodes.clientSecurities(client));

        // 50,000,000 - 12,016,594 + 3,095,691
        assertThat(balances.balance(cashId, Asset.CASH)).isEqualTo(Money.ofPaise(41_079_097));
        assertThat(balances.position(securitiesId, "TCS")).isEqualTo(30);
    }

    @Test
    @DisplayName("the same trade id posts once, however many times it is submitted")
    void postingIsIdempotent() {
        Trade trade = new Trade("T-" + client + "-dup", client, "TCS", Side.BUY, 10,
                Money.ofRupees("3000.00"), DAY);

        var first = trades.settle(trade);
        var second = trades.settle(trade);
        var third = trades.settle(trade);

        assertThat(first.result().alreadyPosted()).isFalse();
        assertThat(second.result().alreadyPosted()).isTrue();
        assertThat(third.result().alreadyPosted()).isTrue();

        // Same entry every time, and the cash moved exactly once.
        assertThat(second.result().entryId()).isEqualTo(first.result().entryId());
        assertThat(third.result().entryId()).isEqualTo(first.result().entryId());

        // 50,000,000 funded, less 3,000,000 consideration and 4,621 of charges.
        Long cashId = accountId(AccountCodes.clientCash(client));
        Money afterOne = Money.ofPaise(50_000_000 - 3_000_000 - 4_621);
        assertThat(balances.balance(cashId, Asset.CASH)).isEqualTo(afterOne);
    }

    @Test
    @DisplayName("the database refuses an unbalanced entry even with the service bypassed")
    void databaseEnforcesZeroSumIndependently() {
        // Going through LedgerService would prove nothing about the database:
        // the Posting constructor rejects an unbalanced set before a row is
        // ever written, so the exception would come from the domain. To test
        // that PostgreSQL enforces the rule on its own, the only honest route
        // is raw SQL that skips the domain entirely — which is also exactly
        // what a future bug, a migration script or a careless console session
        // would do.
        Long cashId = accountId(AccountCodes.clientCash(client));
        Long houseId = accountId("HOUSE_CASH");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            Long entryId = jdbc.queryForObject("""
                    INSERT INTO journal_entry (kind, business_date, description)
                    VALUES ('ADJUSTMENT', ?, 'bypassing the domain on purpose')
                    RETURNING id
                    """, Long.class, DAY);

            jdbc.update("""
                    INSERT INTO journal_line (entry_id, account_id, asset, amount)
                    VALUES (?, ?, 'INR', 10000)
                    """, entryId, cashId);
            // One paisa short. The rows insert happily; the constraint trigger
            // is DEFERRED, so the refusal lands at COMMIT.
            jdbc.update("""
                    INSERT INTO journal_line (entry_id, account_id, asset, amount)
                    VALUES (?, ?, 'INR', -9999)
                    """, entryId, houseId);
        })).hasMessageContaining("does not balance");

        // And the rollback left nothing behind.
        assertThat(balances.assetsOutOfBalance()).isEmpty();
    }

    @Test
    @DisplayName("the domain refuses an unbalanced entry before the database is troubled")
    void domainRefusesUnbalancedPostingsToo() {
        // Defence in depth: the database check is the one that cannot be
        // bypassed, this one is the one that gives a diagnosable message at
        // the point the lines were built.
        assertThatThrownBy(this::unbalancedPosting)
                .isInstanceOf(Posting.UnbalancedPostingException.class)
                .hasMessageContaining("INR nets to");
    }

    @Test
    @DisplayName("an entry naming an account that does not exist is refused")
    void unknownAccountsAreRefused() {
        assertThatThrownBy(() -> ledger.post(new Posting(
                EntryKind.ADJUSTMENT, null, DAY, "to nowhere",
                List.of(
                        PostingLine.cash("CLIENT:NOBODY:CASH", Money.ofRupees(1)),
                        PostingLine.cash("HOUSE_CASH", Money.ofRupees(1).negated())))))
                .isInstanceOf(UnknownAccountException.class);
    }

    @Test
    @DisplayName("nothing in the ledger creates or destroys an asset")
    void everythingConserves() {
        trades.settle(new Trade("T-" + client + "-c1", client, "TCS", Side.BUY, 25,
                Money.ofRupees("3000.00"), DAY));
        trades.settle(new Trade("T-" + client + "-c2", client, "TCS", Side.SELL, 5,
                Money.ofRupees("3100.00"), DAY));

        assertThat(balances.assetsOutOfBalance()).isEmpty();
    }

    @Test
    @DisplayName("a statement adds up to the balance it closes on")
    void statementReconciles() {
        trades.settle(new Trade("T-" + client + "-s1", client, "TCS", Side.BUY, 12,
                Money.ofRupees("3000.00"), DAY));

        var statement = statements.statementFor(client);

        long cashFromLines = statement.lines().stream()
                .filter(line -> Asset.isCash(line.asset()))
                .filter(line -> line.accountCode().equals(AccountCodes.clientCash(client)))
                .mapToLong(StatementService.StatementLine::amount)
                .sum();

        // The whole point of deriving balances: the movements a client can see
        // are the movements the balance is made of.
        assertThat(Money.ofPaise(cashFromLines)).isEqualTo(statement.cash());
    }

    private Posting unbalancedPosting() {
        return new Posting(EntryKind.ADJUSTMENT, null, DAY, "deliberately broken",
                List.of(
                        PostingLine.cash(AccountCodes.clientCash(client), Money.ofRupees(100)),
                        PostingLine.cash("HOUSE_CASH", Money.ofRupees(99).negated())));
    }

    private Long accountId(String code) {
        return accounts.findByCode(code).orElseThrow().getId();
    }
}
