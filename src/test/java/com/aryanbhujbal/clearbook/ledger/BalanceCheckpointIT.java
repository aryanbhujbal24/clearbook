package com.aryanbhujbal.clearbook.ledger;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.aryanbhujbal.clearbook.domain.Side;
import com.aryanbhujbal.clearbook.domain.Trade;
import com.aryanbhujbal.clearbook.support.LedgerFixture;
import com.aryanbhujbal.clearbook.support.PostgresIT;
import com.aryanbhujbal.clearbook.trade.TradeService;

/**
 * The property that keeps "balances are derived" honest.
 *
 * <p>A checkpoint is an optimisation: {@code balance = checkpoint + Σ(lines
 * after it)}. The moment a checkpoint can give a different answer from a full
 * fold of the journal, it has stopped being a cache and become a second source
 * of truth — and the system has quietly acquired the reconciliation problem it
 * was designed not to have.
 *
 * <p>So the test is blunt: compute every balance with checkpoints in place,
 * delete every checkpoint, compute them again, and require the two to be
 * identical.
 */
class BalanceCheckpointIT extends PostgresIT {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

    @Autowired
    private LedgerFixture fixture;
    @Autowired
    private TradeService trades;
    @Autowired
    private BalanceService balances;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private BalanceCheckpointRepository checkpoints;

    private String client;
    private Long cashId;
    private Long securitiesId;

    @BeforeEach
    void tradeAHistory() {
        client = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        fixture.openAndFund(client, Money.ofRupees(1_000_000), DAY);
        fixture.mark("TCS", DAY, Money.ofRupees("3050.00"));

        // A deliberately mixed history, so the fold has both signs in it and a
        // checkpoint cannot pass by accidentally summing one direction.
        record Leg(Side side, long quantity) {
        }
        List<Leg> history = List.of(
                new Leg(Side.BUY, 3), new Leg(Side.BUY, 4), new Leg(Side.BUY, 5),
                new Leg(Side.SELL, 6), new Leg(Side.BUY, 7), new Leg(Side.BUY, 8),
                new Leg(Side.SELL, 9), new Leg(Side.BUY, 10));

        for (int i = 0; i < history.size(); i++) {
            Leg leg = history.get(i);
            trades.settle(new Trade("T-" + client + "-" + i, client, "TCS", leg.side(),
                    leg.quantity(), Money.ofRupees("3000.00"), DAY));
        }

        cashId = accounts.findByCode(AccountCodes.clientCash(client)).orElseThrow().getId();
        securitiesId = accounts.findByCode(AccountCodes.clientSecurities(client))
                .orElseThrow().getId();
    }

    @Test
    @DisplayName("deleting every checkpoint changes no answer the system gives")
    void checkpointsAreOnlyACache() {
        balances.checkpoint(cashId, Asset.CASH);
        balances.checkpoint(securitiesId, Asset.equity("TCS"));

        Money cashWithCheckpoint = balances.balance(cashId, Asset.CASH);
        long positionWithCheckpoint = balances.position(securitiesId, "TCS");

        assertThat(checkpoints.count())
                .as("the checkpoints should actually have been written")
                .isPositive();

        checkpoints.deleteAllInBatch();

        assertThat(balances.balance(cashId, Asset.CASH)).isEqualTo(cashWithCheckpoint);
        assertThat(balances.position(securitiesId, "TCS")).isEqualTo(positionWithCheckpoint);
    }

    @Test
    @DisplayName("a checkpoint taken part-way through still lands on the same balance")
    void checkpointMidHistoryIsStillCorrect() {
        Money beforeMore = balances.balance(cashId, Asset.CASH);
        balances.checkpoint(cashId, Asset.CASH);

        trades.settle(new Trade("T-" + client + "-late", client, "TCS", Side.BUY, 4,
                Money.ofRupees("3000.00"), DAY));

        Money viaCheckpoint = balances.balance(cashId, Asset.CASH);
        checkpoints.deleteAllInBatch();
        Money viaFullFold = balances.balance(cashId, Asset.CASH);

        assertThat(viaCheckpoint)
                .as("the lines after the checkpoint must be added, not ignored")
                .isEqualTo(viaFullFold)
                .isNotEqualTo(beforeMore);
    }

    @Test
    @DisplayName("checkpointing twice is harmless")
    void checkpointingIsIdempotent() {
        balances.checkpoint(cashId, Asset.CASH);
        Money once = balances.balance(cashId, Asset.CASH);

        balances.checkpoint(cashId, Asset.CASH);
        balances.checkpoint(cashId, Asset.CASH);

        assertThat(balances.balance(cashId, Asset.CASH)).isEqualTo(once);
        assertThat(balances.assetsOutOfBalance()).isEmpty();
    }
}
