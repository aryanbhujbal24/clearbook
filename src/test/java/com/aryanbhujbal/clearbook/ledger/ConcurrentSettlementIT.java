package com.aryanbhujbal.clearbook.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.aryanbhujbal.clearbook.domain.AccountCodes;
import com.aryanbhujbal.clearbook.domain.Asset;
import com.aryanbhujbal.clearbook.domain.FeeBreakdown;
import com.aryanbhujbal.clearbook.domain.FeeSchedule;
import com.aryanbhujbal.clearbook.domain.Money;
import com.aryanbhujbal.clearbook.domain.Side;
import com.aryanbhujbal.clearbook.domain.Trade;
import com.aryanbhujbal.clearbook.support.LedgerFixture;
import com.aryanbhujbal.clearbook.support.PostgresIT;
import com.aryanbhujbal.clearbook.trade.TradeService;

/**
 * What happens when settlements for one account arrive at the same instant.
 *
 * <p>This is the test the ledger exists to pass. Every posting is individually
 * correct in a way that is easy to verify and easy to be fooled by: run them
 * one at a time and nothing here can fail. The failures only appear when two
 * transactions read the same state and both act on it, and they appear as a
 * ledger that still balances perfectly while holding the wrong number — which
 * is the kind of bug that is found in a month-end reconciliation rather than
 * in a log.
 *
 * <p>Three distinct things are being checked:
 *
 * <ol>
 *   <li><b>No lost updates.</b> Twenty concurrent buys must debit the account
 *   twenty times, not nineteen. Because balances are derived from the journal
 *   rather than read-modify-written, this is almost free — there is no stored
 *   balance for a late writer to clobber.</li>
 *   <li><b>No duplicate postings under concurrent redelivery.</b> The same
 *   trade id submitted by many threads at once must post exactly once. The
 *   application-level "have I seen this?" check cannot deliver that on its
 *   own — every thread can pass it before any of them inserts — so the UNIQUE
 *   constraint is what actually closes the window.</li>
 *   <li><b>No margin breach by write skew.</b> Two trades that each leave the
 *   account solvent, and together do not, must not both commit.</li>
 * </ol>
 */
class ConcurrentSettlementIT extends PostgresIT {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);
    private static final int THREADS = 20;

    @Autowired
    private LedgerFixture fixture;
    @Autowired
    private TradeService trades;
    @Autowired
    private BalanceService balances;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private FeeSchedule feeSchedule;

    private String client;

    @BeforeEach
    void openAccounts() {
        client = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        fixture.openAndFund(client, Money.ofRupees(10_000_000), DAY);
        fixture.mark("TCS", DAY, Money.ofRupees("3050.00"));
    }

    @Test
    @DisplayName("twenty concurrent settlements all land, and the ledger still balances")
    void concurrentSettlementsDoNotLoseUpdates() throws Exception {
        Money price = Money.ofRupees("3000.00");
        long quantity = 1;

        List<Trade> batch = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            batch.add(new Trade("T-" + client + "-" + i, client, "TCS", Side.BUY,
                    quantity, price, DAY));
        }

        List<Throwable> failures = runAllAtOnce(
                batch.stream().<Callable<Void>>map(trade -> () -> {
                    trades.settle(trade);
                    return null;
                }).toList());

        assertThat(failures)
                .as("every settlement should have won its lock within the retry budget")
                .isEmpty();

        // Each trade costs its consideration plus its charges. Computed from
        // the schedule rather than hardcoded: the arithmetic is pinned by
        // GoldenScenarioTest, and what this test is about is that it happened
        // exactly twenty times.
        Money turnover = price.times(quantity);
        FeeBreakdown fees = feeSchedule.compute(Side.BUY, turnover);
        Money perTrade = turnover.plus(fees.total());
        Money expected = Money.ofRupees(10_000_000).minus(perTrade.times(THREADS));

        Long cashId = accountId(AccountCodes.clientCash(client));
        Long securitiesId = accountId(AccountCodes.clientSecurities(client));

        assertThat(balances.balance(cashId, Asset.CASH))
                .as("a lost update would show up here as too much money left")
                .isEqualTo(expected);
        assertThat(balances.position(securitiesId, "TCS")).isEqualTo(THREADS * quantity);
        assertThat(balances.assetsOutOfBalance()).isEmpty();
    }

    @Test
    @DisplayName("the same trade submitted by twenty threads at once posts exactly once")
    void concurrentRedeliveryPostsOnce() throws Exception {
        Trade trade = new Trade("T-" + client + "-race", client, "TCS", Side.BUY, 5,
                Money.ofRupees("3000.00"), DAY);

        AtomicInteger freshPostings = new AtomicInteger();
        List<Callable<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            attempts.add(() -> {
                var outcome = trades.settle(trade);
                if (!outcome.result().alreadyPosted()) {
                    freshPostings.incrementAndGet();
                }
                return null;
            });
        }

        List<Throwable> failures = runAllAtOnce(attempts);

        assertThat(failures)
                .as("a duplicate submission is a normal outcome, never an error")
                .isEmpty();
        assertThat(freshPostings.get())
                .as("exactly one thread should have created the entry")
                .isEqualTo(1);

        Money turnover = Money.ofRupees("3000.00").times(5);
        Money expected = Money.ofRupees(10_000_000)
                .minus(turnover.plus(feeSchedule.compute(Side.BUY, turnover).total()));

        assertThat(balances.balance(accountId(AccountCodes.clientCash(client)), Asset.CASH))
                .as("the client must be charged once, not twenty times")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("two trades that are each affordable and jointly are not cannot both commit")
    void writeSkewCannotBreachMargin() throws Exception {
        // Funding chosen so that one trade is comfortably within margin and
        // two are not. The figure is not arbitrary — buying stock adds its
        // marked value back as equity, so an account does not breach merely by
        // spending its cash. With k lots of 100 shares bought at 3,000.00 and
        // marked at 3,050.00, all in paise:
        //
        //     equity      = funding + k x   500,000   (100 shares x the 50.00 mark-up)
        //     requirement = 0.20 x k x 30,500,000  =  k x 6,100,000
        //
        // so it breaches when funding < k x 5,600,000. Funding of 8,000,000
        // paise clears one lot (8,000,000 > 5,600,000) and fails two
        // (8,000,000 < 11,200,000) — before charges, which only widen the gap.
        String thin = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        fixture.openAndFund(thin, Money.ofRupees(80_000), DAY);

        Money price = Money.ofRupees("3000.00");
        Trade first = new Trade("T-" + thin + "-a", thin, "TCS", Side.BUY, 100, price, DAY);
        Trade second = new Trade("T-" + thin + "-b", thin, "TCS", Side.BUY, 100, price, DAY);

        List<Throwable> failures = runAllAtOnce(List.of(
                () -> {
                    trades.settle(first);
                    return null;
                },
                () -> {
                    trades.settle(second);
                    return null;
                }));

        // One of them must have been refused. Which one is a race, and does
        // not matter; that exactly one got through is the whole point.
        assertThat(failures)
                .as("one of the two trades must be refused")
                .hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(MarginBreachException.class);

        Long cashId = accountId(AccountCodes.clientCash(thin));
        Long securitiesId = accountId(AccountCodes.clientSecurities(thin));

        assertThat(balances.position(securitiesId, "TCS"))
                .as("only one trade's worth of stock should have been delivered")
                .isEqualTo(100);
        assertThat(balances.balance(cashId, Asset.CASH).isNegative()).isFalse();
        assertThat(balances.assetsOutOfBalance()).isEmpty();
    }

    /**
     * Runs every task on its own thread, released simultaneously.
     *
     * <p>The latch matters. Submitting tasks to a pool and hoping they overlap
     * produces a test that passes whether or not the locking works, because
     * the tasks mostly run one after another. Holding every thread at a
     * starting gate and releasing them together is what makes the contention
     * reliable enough to assert on.
     */
    private List<Throwable> runAllAtOnce(List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();

        try {
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("every worker should have reached the starting gate")
                    .isTrue();
            go.countDown();

            List<Throwable> failures = new ArrayList<>();
            for (Future<Void> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException failed) {
                    failures.add(failed.getCause());
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private Long accountId(String code) {
        return accounts.findByCode(code).orElseThrow().getId();
    }
}
